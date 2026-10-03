package godwit.core

import com.mongodb.kotlin.client.MongoCluster
import godwit.core.internal.Runner
import godwit.core.internal.Tuning
import godwit.core.internal.validateCall

/**
 * Runs an app's migrations against one database.
 *
 * Build it from the same [cluster] (usually the app's `MongoClient`) whose databases the app's services use: the
 * session godwit opens for a transactional step is valid only with that client, and a service built on another client
 * fails with "ClientSession from same MongoClient".
 *
 * godwit owns two collections in the database, [GodwitConfig.historyCollection] (one document per migration) and
 * [GodwitConfig.lockCollection] (one lock document), and keeps no state between calls: every call reads history again.
 * Instances are cheap; a process usually builds one at startup.
 *
 * Logging goes through slf4j-api to the logger named `godwit`, as key-value pairs (slf4j 2 fluent API):
 *
 * | Level | Message                         | Keys                                                                   |
 * |-------|---------------------------------|------------------------------------------------------------------------|
 * | INFO  | Migrations up to date           | runId, checked, durationMs                                             |
 * | INFO  | Waiting for migration lock      | holder, holderRunId, expiresAt, waitedMs (every 10 s while waiting)    |
 * | INFO  | Acquired migration lock         | runId, lockWaitMs                                                      |
 * | WARN  | Lock renewal failed             | runId, holder, error (the lock is held until the local deadline)       |
 * | WARN  | Lost migration lock             | runId, holder, reason (once per run)                                   |
 * | WARN  | Lock release failed             | runId, holder, error (the lease ends on its own)                       |
 * | INFO  | Adopted applied migrations      | adopted, ignored (on every call of the hook)                           |
 * | INFO  | Recorded superseded migration   | id, supersedes                                                         |
 * | WARN  | Resuming interrupted migration  | id, attempts                                                           |
 * | WARN  | Running out-of-order migration  | id, appliedAfter                                                       |
 * | INFO  | Running migration               | id, kind, steps, attempt                                               |
 * | WARN  | Retrying transaction            | id, attempt, error (the first retry, then at most every 10 s)          |
 * | WARN  | Slow transaction                | id, attempt, durationMs                                                |
 * | DEBUG | Committed batch                 | id, batch, lastId                                                      |
 * | INFO  | Applied migration               | id, kind, steps, attempts, txRetries, batches, durationMs, then counts |
 * | WARN  | Unknown applied migrations      | ids                                                                    |
 * | ERROR | Migration failed                | id, step, attempts, error                                              |
 * | INFO  | Migrations complete             | runId, ran, recorded, upToDate, lockWaitMs, durationMs                 |
 * | WARN  | Marked migration applied        | id, reason, holder                                                     |
 *
 * On "Retrying transaction", `error` is what made the driver run the body again: the code name and code of the error
 * the previous attempt's body threw, such as `WriteConflict (112)`, or for an error without a code name (a network
 * error) its exception class and code, such as `MongoSocketReadException (-2)`, or `commit` when the body returned and
 * the commit failed with a transient error. Every retry counts in `transactionRetries`; the line is rate-limited.
 *
 * On "Migration failed", `error` is the class and message of the exception the step threw. On "Lock renewal failed"
 * and "Lock release failed", it is the class and message of the exception the lock operation threw. On "Lost migration
 * lock", `reason` is `NOT_OWNER` (a renewal matched no lock document with this run's owner token: another run holds the
 * lock, or the document was deleted) or `DEADLINE_PASSED` (no renewal succeeded within `lease - safetyMargin` of the
 * last successful one, or of the acquire before the first); the heartbeat thread or [StepScope.checkLock], whichever
 * notices first, logs it.
 */
class Godwit internal constructor(
    /** The cluster that owns [databaseName]. godwit opens its sessions on it. */
    val cluster: MongoCluster,
    val databaseName: String,
    val config: GodwitConfig,
    private val tuning: Tuning
) {
    constructor(cluster: MongoCluster, databaseName: String, config: GodwitConfig = GodwitConfig()) :
        this(cluster, databaseName, config, Tuning())

    /** A runner per call: a `Godwit` holds only its constructor arguments. */
    private val runner: Runner get() = Runner(cluster, databaseName, config, tuning)

    /**
     * Brings the database up to [target] and returns what happened. Synchronous; safe to call from several processes
     * at once (the lock serialises them).
     *
     * In order:
     * 1. [validateMigrations], before any I/O.
     * 2. Reads history (majority read concern, primary) and checks the plan against it: out-of-order migrations
     *    ([OutOfOrder]), partially superseded squashes, unknown applied ids ([UnknownApplied]). While
     *    [GodwitConfig.adoptApplied] can still run (it is set and every history document is ADOPTED), the out-of-order
     *    and squash checks wait for step 6.
     * 3. When nothing is due, returns without taking the lock; [MigrationReport.lockWait] is then null.
     * 4. When a transactional step is due, checks the server supports transactions, or throws
     *    [TransactionsUnsupportedException].
     * 5. Takes the lock, waiting up to [LockConfig.waitTimeout], or throws [LockTimeoutException].
     * 6. Under the lock: reads history again, calls [GodwitConfig.adoptApplied] while every history document is
     *    ADOPTED and records the ids it adds, plans and checks again (another process may have done the work while this
     *    one waited), checks the server again when this plan has a transactional step due that the first one did not,
     *    and applies the untracked-database guard ([UntrackedDatabase]).
     * 7. Records superseded squashes, runs every due once-only migration in list order, then every due [repeatable]
     *    and [everyStart] migration in list order.
     * 8. Releases the lock.
     *
     * A migration is due when its history document is missing or not APPLIED; a repeatable also when its stored
     * revision differs or a run of another kind wrote the document; an every-start one always. Stops at the first
     * failure with [MigrationFailedException]: the migration is recorded FAILED with its error, and the next call
     * retries it, outside step first. When the driver
     * throws after a commit that did apply (the reply was lost or timed out) while this run still holds the lock,
     * godwit's fenced FAILED write matches nothing; once the server acknowledges it with majority write concern, godwit
     * finds the document APPLIED by this run, reports the migration as applied and continues. A migration with only an
     * outside step does the same with its APPLIED record, which godwit sends once more after a driver exception.
     */
    fun migrate(migrations: List<Migration>, target: Target = Target.Latest): MigrationReport {
        validateCall(migrations, target)
        return runner.migrate(migrations, target)
    }

    /** [migrate] for migrations written inline. */
    fun migrate(vararg migrations: Migration, target: Target = Target.Latest): MigrationReport =
        migrate(migrations.toList(), target)

    /**
     * What [migrate] with [Target.Latest] would do, without taking the lock or writing anything, except while
     * [GodwitConfig.adoptApplied] can still run (below). [everyStart] migrations are never pending. On a database
     * without godwit history every migration is pending: adoption runs only in [migrate]. While
     * [GodwitConfig.adoptApplied] can still run, the ids adoption has not recorded are pending, and neither an
     * untracked database nor an out-of-order or partial-squash conflict is reported, because the hook may resolve them.
     * Throws [InvalidMigrationsException] for an invalid list.
     */
    fun status(migrations: List<Migration>): MigrationStatus {
        validateMigrations(migrations)
        return runner.status(migrations)
    }

    /**
     * For a process that must not migrate (a worker deployed next to the app): throws [PendingMigrationsException]
     * unless [status] is up to date.
     */
    fun requireUpToDate(migrations: List<Migration>) {
        val status = status(migrations)
        if (!status.isUpToDate) throw PendingMigrationsException(status.pending, status.problems)
    }

    /** Every history document, sorted by id. Reads without the lock. */
    fun history(): List<HistoryEntry> = runner.history()

    /**
     * Records the once-only migration [id] as APPLIED with origin [Origin.MARKED] and [reason], without running
     * anything: the audited escape hatch for a change applied by hand or a migration that must never run on this
     * database. godwit rolls forward only; this is the one way to skip a once-only migration.
     *
     * Waits for the lock like [migrate], then reads history under it. A once-only document that is RUNNING or FAILED,
     * or a missing one, becomes APPLIED (a missing one is recorded as once-only); an APPLIED once-only document is left
     * unchanged. The id is not checked against any list: a typo shows up as an unknown applied id on the next
     * [migrate], and marking an id while once-only migrations listed before it are pending makes those out of order.
     * To record several applied ids, stop every instance and mark the last-listed first: a start between two marks
     * then finds the ids not yet marked pending before a marked one, which [OutOfOrder.FAIL] refuses; marked
     * first-listed first, it would find a valid prefix and run the rest.
     *
     * A MARKED document ends adoption and turns off the untracked-database guard: [GodwitConfig.adoptApplied] is not
     * called while it exists. A mark made before the hook has recorded every applied id would let the ids it has not
     * recorded run on the next [migrate], over the live data, so this refuses while adoption has not ended. A manual
     * repair on such a database, such as recording what was applied after its history was lost while the old record
     * remains, stops every instance and marks from a [Godwit] built from the same configuration without the hook,
     * `config.copy(adoptApplied = null)`, so that the marks land in the history and lock collections the app uses.
     *
     * @throws IllegalArgumentException when [reason] is blank (before taking the lock), or when the document of [id]
     *   belongs to a repeatable or every-start migration, in any state, APPLIED included: a mark never changes whether
     *   such a migration runs, so the way past it is code. Nothing is written.
     * @throws IllegalStateException when [GodwitConfig.adoptApplied] is set and history holds no document whose origin
     *   is other than [Origin.ADOPTED] (it is empty or holds only ADOPTED documents): adoption has not ended, and the
     *   hook still runs on every start that takes the lock. The check runs under the lock, on the same history read.
     *   Nothing is written. The message is "adoption has not ended on this database; run migrate() first so the
     *   adoptApplied hook adopts, or call markApplied from a Godwit built without adoptApplied".
     */
    fun markApplied(id: String, reason: String): Unit = throw NotImplementedError("P6")

    /** The two collections godwit owns in the database. Skip them when clearing collections between tests. */
    val bookkeepingCollections: Set<String> get() = setOf(config.historyCollection, config.lockCollection)
}

/** How far [Godwit.migrate] goes. */
sealed interface Target {
    /** Every due migration: once-only migrations in list order, then repeatable and every-start ones. */
    data object Latest : Target

    /**
     * Every once-only migration listed before [id], none from [id] on, and no [repeatable] or [everyStart] migration.
     * For tests that insert data in the shape a migration expects before running it. [id] must be a once-only id in
     * the list ([InvalidMigrationsException] otherwise).
     */
    data class Before(val id: String) : Target

    /** As [Before], plus [id] itself. */
    data class Through(val id: String) : Target
}
