package godwit.core

import kotlin.time.Duration

/**
 * The base of every exception godwit throws. Driver exceptions from godwit's own history and lock writes propagate
 * unchanged.
 */
abstract class GodwitException internal constructor(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** The migration list or the [Target] is invalid. Thrown before any I/O; [problems] lists every rule broken. */
class InvalidMigrationsException internal constructor(val problems: List<String>) :
    GodwitException("Invalid migrations:\n" + problems.joinToString("\n") { "- $it" })

/**
 * The list and the database's history disagree: an out-of-order migration under [OutOfOrder.FAIL], a partially
 * superseded squash, an adoption gap, or unknown applied ids under [UnknownApplied.FAIL]. Nothing ran.
 *
 * [problems] holds one line per conflict:
 *
 * | Conflict | Problem line |
 * |---|---|
 * | Out of order, or an adoption gap | `<id> is pending, but <other>, listed after it, is applied (out of order; OutOfOrder.RUN runs it)` |
 * | Partially superseded squash | `<id> supersedes <n> migrations, but only <applied ids> are applied. Deploy the previous release first.` |
 * | Unknown applied id | `<id> is applied, but the list does not declare it (UnknownApplied.FAIL)` |
 */
class PlanConflictException internal constructor(val problems: List<String>) :
    GodwitException("Migrations cannot run against this database:\n" + problems.joinToString("\n") { "- $it" })

/**
 * The database has [collections] but no godwit history, and [GodwitConfig.adoptApplied] adopted nothing. Running every
 * migration against existing data could damage it. Nothing ran.
 */
class UntrackedDatabaseException internal constructor(val collections: List<String>) :
    GodwitException(
        "The database has collections $collections but no godwit history. Configure GodwitConfig.adoptApplied to " +
            "adopt the migrations already applied, or set UntrackedDatabase.RUN_ALL to run every migration."
    )

/**
 * Migration [id] failed in [step]. It is recorded FAILED with the error; the next [Godwit.migrate] retries it, outside
 * step first. [report] covers what this call did before the failure. When the FAILED write fails too, this exception
 * carries that write's exception as suppressed and the document stays as the step left it: RUNNING, which the next
 * start resumes, or APPLIED when a commit applied although the driver threw and the majority was still behind when
 * the FAILED write gave up, which the next start finds applied.
 *
 * The message adds one line of guidance for the causes godwit recognises, in the step's exception or in its causes up
 * to eight levels deep, so a service that wraps the driver's error is recognised too:
 *
 * | Cause | Guidance line |
 * |---|---|
 * | Transaction past its lifetime | `The transaction ran past the server's transaction lifetime (transactionLifetimeLimitSeconds, 60 s by default). Process the documents with inBatches, or move work that needs no atomicity to outsideTransaction.` |
 * | Transaction too large | `The transaction was too large for the storage engine's cache. Process the documents with inBatches, or move work that needs no atomicity to outsideTransaction.` |
 * | An `inBatches` page past its lifetime | `The page's transaction ran past the server's transaction lifetime (transactionLifetimeLimitSeconds, 60 s by default). Lower batchSize, or, when few documents match pending, create an index that serves pending.` |
 * | An `inBatches` page too large | `The page's transaction was too large for the storage engine's cache. Lower batchSize.` |
 * | DDL in a transaction | `DDL cannot run in a transaction: index builds on existing collections, drop, dropIndexes, renameCollection and collMod belong in outsideTransaction.` |
 * | Session from another client | `The step passed godwit's session to an operation on another MongoClient. Build Godwit and the services the migrations call from the same MongoClient.` |
 */
class MigrationFailedException internal constructor(
    val id: String,
    val step: StepKind,
    val report: MigrationReport,
    cause: Throwable,
    guidance: String?
) : GodwitException(
    "Migration $id failed in $step: ${cause.message}" + (guidance?.let { "\n$it" } ?: ""),
    cause
)

/**
 * Another process held the lock for longer than [LockConfig.waitTimeout]. [holder] is null when the lock was released
 * as the wait ended, or when the lock document lacks one of the fields godwit writes (one written by hand). Nothing
 * ran.
 */
class LockTimeoutException internal constructor(val holder: LockHolder?, val waited: Duration) :
    GodwitException("Waited $waited for the migration lock, held by ${holder?.holder ?: "nobody"}")

/**
 * This run lost the migration lock (a renewal found another owner, or the lease deadline passed without one) while
 * running [id]. Its uncommitted transaction rolled back; the process now holding the lock continues the work. A commit
 * that applied although the driver threw, once the lock was lost too (commit retries that ran for the driver's 120 s
 * outlast the default lease), stays applied, with the commit's error as the [cause]: the next start finds the
 * migration APPLIED.
 *
 * When a step failed and the lock was lost at the same moment, the step's exception is the [cause], and godwit records
 * it as the migration's `lastError` with the FAILED write, which is fenced on this run's owner token and on RUNNING:
 * it matches only while no other run has taken the migration over. When the lock loss alone stopped the run, [cause]
 * is null.
 */
class LockLostException internal constructor(val id: String?, cause: Throwable? = null) :
    GodwitException("Lost the migration lock" + (id?.let { " while running $it" } ?: ""), cause)

/**
 * Migrations with a transactional step are due ([due]), and the server is a standalone `mongod`, which has no
 * transactions. Thrown before any migration runs: before taking the lock, or under it when the plan made there has a
 * transactional step due that the plan made before the lock did not. DDL-only migrations run on a standalone server.
 */
class TransactionsUnsupportedException internal constructor(val due: List<String>) :
    GodwitException(
        "Migrations $due need transactions, which a standalone mongod does not support. " +
            "Run a single-node replica set: start mongod with --replSet rs0, then run rs.initiate() once."
    )

/** [Godwit.requireUpToDate] found [pending] migrations or [problems]. */
class PendingMigrationsException internal constructor(val pending: List<String>, val problems: List<String>) :
    GodwitException("The database is not up to date. Pending: $pending. Problems: $problems")

/** [ensureSearchIndex] waited [waited] and search index [name] on [collection] was still not queryable. */
class SearchIndexNotReadyException internal constructor(
    val collection: String,
    val name: String,
    val waited: Duration
) : GodwitException("Search index $name on $collection was not queryable after $waited")
