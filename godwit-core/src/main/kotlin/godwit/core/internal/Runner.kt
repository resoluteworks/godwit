package godwit.core.internal

import com.mongodb.MongoException
import com.mongodb.kotlin.client.ClientSession
import com.mongodb.kotlin.client.MongoCluster
import com.mongodb.kotlin.client.MongoDatabase
import godwit.core.GodwitConfig
import godwit.core.HistoryEntry
import godwit.core.HistoryState
import godwit.core.LockLostException
import godwit.core.Migration
import godwit.core.MigrationFailedException
import godwit.core.MigrationKind
import godwit.core.MigrationOutcome
import godwit.core.MigrationReport
import godwit.core.MigrationStatus
import godwit.core.Origin
import godwit.core.OutsideTransactionScope
import godwit.core.PlanConflictException
import godwit.core.StepKind
import godwit.core.Target
import godwit.core.TransactionScope
import godwit.core.UntrackedDatabase
import godwit.core.UntrackedDatabaseException
import org.bson.Document
import java.time.Instant
import java.util.UUID
import kotlin.random.Random
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

/** The runner's timings and its source of chance. Every default is the production value; tests shorten them. */
internal data class Tuning(
    /**
     * How long a transaction attempt runs before a NoSuchTransaction (251) or TransactionExceededLifetimeLimitSeconds
     * (290) counts as the server's transaction lifetime ending (60 s by default), which earns the lifetime guidance.
     */
    val lifetimeGuidanceAfter: Duration = 60.seconds,
    /** After the first "Retrying transaction" line of a transaction, the least time before the next one. */
    val retryLogInterval: Duration = 10.seconds,
    /** Waits out the pause between two runs of a transaction body. */
    val sleep: (Duration) -> Unit = { Thread.sleep(it.toJavaDuration()) },
    /** The jitter of those pauses. */
    val random: Random = Random.Default
)

/**
 * `migrate`, `status` and `history` of one `Godwit`: the sequence of architecture.md's "One migrate call" and
 * "Running one migration". Decisions come from the planner; this class carries them out with the history store, the
 * lock and the topology check, and runs the steps through the scopes and [Transaction].
 *
 * Every kind runs through the same [MigrationRun]: once-only migrations first, then repeatable and every-start ones,
 * in the plan's order. Only the history writes differ by kind. The marker ([HistoryStore.markRunning]) of a repeatable
 * or every-start migration takes an APPLIED document back to RUNNING, so it never reports one as applied by another
 * run. The APPLIED record ([HistoryStore.recordApplied]) stores a repeatable's `revision` and removes it for every
 * other kind, and stores `lastRunAt` and increments `runCount` for a repeatable or every-start migration.
 *
 * Under the lock, before the plan that runs is made, [Adoption] calls the adoption hook while history holds nothing
 * but ADOPTED documents; the plan then records its superseded squashes before it runs anything. `markApplied` takes
 * the same lock for its one write.
 */
internal class Runner(
    private val cluster: MongoCluster,
    private val databaseName: String,
    private val config: GodwitConfig,
    private val tuning: Tuning
) {
    private val bookkeeping = Bookkeeping(cluster, databaseName, config)

    private val store = HistoryStore(bookkeeping)

    /** The database as the app's client sees it: what the steps work on. */
    private val database: MongoDatabase get() = cluster.getDatabase(databaseName)

    /**
     * Plans from one history read and, when something is due, takes the lock, plans again from a second read and runs
     * what is due. The list and the target are valid: the caller has checked them.
     */
    fun migrate(migrations: List<Migration>, target: Target): MigrationReport {
        val started = TimeSource.Monotonic.markNow()
        val runId = UUID.randomUUID().toString()
        val history = readHistory()
        val first = plan(migrations, history, target, config, adoptionCanRun(config, history))
        if (first.conflicts.isNotEmpty()) throw PlanConflictException(first.conflicts)
        if (first.unknownApplied.isNotEmpty()) Log.unknownAppliedMigrations(first.unknownApplied)
        if (first.nothingDue) {
            val duration = started.elapsedNow()
            Log.migrationsUpToDate(runId, migrations.size, duration.inWholeMilliseconds)
            return MigrationReport(
                runId,
                emptyList(),
                emptyList(),
                first.upToDate,
                first.pending,
                first.unknownApplied,
                null,
                duration
            )
        }
        val topology = Topology(database)
        topology.requireTransactions(first)
        val lock = MongoLock(bookkeeping, config.lock, config.holder).acquire(runId)
        try {
            return Call(runId, lock, started).run(migrations, target, topology)
        } finally {
            lock.release()
        }
    }

    /** What [migrate] with [Target.Latest] would do, from one history read: no lock, no writes. */
    fun status(migrations: List<Migration>): MigrationStatus {
        val history = readHistory()
        val plan = plan(migrations, history, Target.Latest, config, adoptionCanRun(config, history))
        val problems = plan.conflicts + listOfNotNull(untrackedRefusal(plan)?.message)
        return MigrationStatus(plan.statusPending, problems, plan.unknownApplied)
    }

    /** Every history document, sorted by id. */
    fun history(): List<HistoryEntry> = store.readAll().map { it.toHistoryEntry(store.codecs) }

    /**
     * Records the once-only migration [id] APPLIED with origin MARKED and [reason], under the lock, from the history
     * read there: refused while adoption can still run ([IllegalStateException]) and for a repeatable or every-start
     * document in any state ([IllegalArgumentException]), with nothing written. A document that is APPLIED already
     * stays as it is, and nothing is logged. [reason] is not blank: the caller has checked it.
     */
    fun markApplied(id: String, reason: String) {
        val runId = UUID.randomUUID().toString()
        val lock = MongoLock(bookkeeping, config.lock, config.holder).acquire(runId)
        try {
            val history = readHistory()
            check(!adoptionCanRun(config, history)) { ADOPTION_NOT_ENDED }
            val kind = history.firstOrNull { it.id == id }?.kind
            require(kind == null || kind == StoredKind.ONCE) {
                "$id is $kind in history; markApplied records once-only migrations only. A repeatable or " +
                    "every-start migration is due whatever its history says: fix it in code, or remove it from the list"
            }
            lock.checkLock(null)
            val writer = Writer(lock.owner, config.holder, runId)
            if (store.recordMarked(id, reason, writer, Instant.now())) {
                Log.markedMigrationApplied(id, reason, config.holder)
            }
        } finally {
            lock.release()
        }
    }

    private fun readHistory(): List<HistoryRecord> = store.readAll().map { it.toHistoryRecord() }

    /**
     * The untracked-database guard: under [UntrackedDatabase.REFUSE], a database whose history is empty while adoption
     * cannot run ([Plan.untracked]) and that holds collections other than godwit's two and `system.*` is refused.
     * Null when the call may run.
     */
    private fun untrackedRefusal(plan: Plan): UntrackedDatabaseException? {
        if (!plan.untracked || config.untrackedDatabase == UntrackedDatabase.RUN_ALL) return null
        val bookkeepingCollections = setOf(config.historyCollection, config.lockCollection)
        val collections = database.listCollectionNames().toList()
            .filter { it !in bookkeepingCollections && !it.startsWith("system.") }
            .sorted()
        return if (collections.isEmpty()) null else UntrackedDatabaseException(collections)
    }

    /** One `migrate` call from the moment it holds the lock: its run id, its lock, and what it has run so far. */
    private inner class Call(val runId: String, val lock: HeldLock, val started: ComparableTimeMark) {
        val writer = Writer(lock.owner, config.holder, runId)

        val ran = mutableListOf<MigrationOutcome>()

        /** Adopted ids in list order, then recorded squashes in list order. */
        val recorded = mutableListOf<MigrationOutcome>()

        val upToDate = mutableListOf<String>()

        /**
         * Reads history again under the lock, adopts while adoption can run and reads history once more, plans from
         * it, records the superseded squashes and runs what is due, in run order.
         */
        fun run(migrations: List<Migration>, target: Target, topology: Topology): MigrationReport {
            var history = readHistory()
            val hook = config.adoptApplied
            val adopted = if (hook != null && adoptionCanRun(config, history)) {
                Adoption(hook, database, store, topology).adopt(migrations, history, lock, writer)
                    .also { history = readHistory() }
            } else {
                emptyList()
            }
            val plan = plan(migrations, history, target, config, adopting = false)
            if (plan.conflicts.isNotEmpty()) throw PlanConflictException(plan.conflicts)
            topology.requireTransactions(plan)
            untrackedRefusal(plan)?.let { throw it }
            recorded += adopted.map { recordedOutcome(it, MigrationKind.Once, Origin.ADOPTED) }
            upToDate += plan.upToDate - adopted.toSet()
            plan.superseded.forEach(::recordSuperseded)
            val previous = history.associateBy { it.id }
            for (due in plan.due) {
                val outcome = MigrationRun(this, plan, due, previous[due.migration.id]).run()
                if (outcome == null) upToDate += due.migration.id else ran += outcome
            }
            val report = report(plan)
            Log.migrationsComplete(
                runId,
                ran.size,
                report.recorded.size,
                upToDate.size,
                lock.lockWait.inWholeMilliseconds,
                report.duration.inWholeMilliseconds
            )
            return report
        }

        /**
         * Records the superseding [migration] SUPERSEDED without running it, after a lock check. A document another
         * run has applied meanwhile is left as it is, and the migration is up to date.
         */
        private fun recordSuperseded(migration: Migration) {
            lock.checkLock(migration.id)
            if (store.recordSuperseded(migration, writer, Instant.now())) {
                Log.recordedSupersededMigration(migration.id, migration.supersedes)
                recorded += recordedOutcome(migration.id, migration.kind, Origin.SUPERSEDED)
            } else {
                upToDate += migration.id
            }
        }

        /** What this call has done so far, for the result or for the exception that ends it. */
        fun report(plan: Plan) = MigrationReport(
            runId,
            ran.toList(),
            recorded.toList(),
            upToDate.toList(),
            plan.pending,
            plan.unknownApplied,
            lock.lockWait,
            started.elapsedNow()
        )
    }

    /**
     * One run of one due migration, from its marker to its APPLIED record or its failure. [previous] is its history
     * document as the read under the lock found it.
     */
    private inner class MigrationRun(
        private val call: Call,
        private val plan: Plan,
        private val due: DueMigration,
        private val previous: HistoryRecord?
    ) {
        private val migration = due.migration

        private val id = migration.id

        private val lock = call.lock

        private val started = TimeSource.Monotonic.markNow()

        /** The step running now: the one a failure is recorded against. */
        private var step: StepKind = migration.steps.first()

        /** `attempts` of the history document once the marker is written. */
        private var attempts = 0

        /** The outside step's counters, once it has returned. */
        private var outsideCounts: Map<String, Long> = emptyMap()

        /** The transactional step's transaction, once it has started; for an `inBatches` step, the current page's. */
        private var transaction: Transaction? = null

        /** Where an `inBatches` step resumes: the checkpoint of the marker's document, null on a first run. */
        private var checkpoint: Checkpoint? = null

        /** The pages of an `inBatches` step, once they have started. */
        private var pages: Pages? = null

        /**
         * The outcome; null when a once-only migration's marker finds it APPLIED by another run, which reports it up
         * to date. A repeatable or every-start migration's marker never does: it runs again.
         */
        fun run(): MigrationOutcome? {
            lock.checkLock(id)
            return bookkeeping.startSession().use { session ->
                val marker = store.markRunning(migration, call.writer, Instant.now(), session) ?: return@use null
                // A marker that landed after another run acquired the lock (this run paused past its lease) has taken
                // that run's document over; the check stops this run before any step.
                lock.checkLock(id)
                attempts = marker.getInteger("attempts")
                checkpoint = marker.checkpoint(store.codecs)
                if (previous?.state == HistoryState.RUNNING) Log.resumingInterruptedMigration(id, attempts)
                if (due.outOfOrder) Log.runningOutOfOrderMigration(id, due.appliedAfter)
                Log.runningMigration(id, migration.kind.stored, migration.steps, attempts)
                val applied = execute(session)
                val duration = started.elapsedNow()
                Log.appliedMigration(
                    id,
                    migration.kind.stored,
                    migration.steps,
                    attempts,
                    applied.transactionRetries,
                    applied.batches,
                    duration.inWholeMilliseconds,
                    applied.counts
                )
                MigrationOutcome(
                    id,
                    migration.kind,
                    Origin.RAN,
                    migration.steps,
                    attempts,
                    applied.transactionRetries,
                    applied.batches,
                    applied.counts,
                    due.outOfOrder,
                    duration
                )
            }
        }

        /** Runs the steps; a migration without a transactional step is recorded APPLIED after them. */
        private fun execute(session: ClientSession): Applied {
            val committed = try {
                steps(session)
            } catch (e: Throwable) {
                return failed(e)
            }
            return committed ?: recordOutsideOnly()
        }

        /** What the transaction committed, or null for a migration with only an outside step. */
        private fun steps(session: ClientSession): Applied? = when (val bodies = migration.bodies) {
            is OutsideFirst<*> -> outsideFirst(bodies, session)
            is TransactionalOnly -> transactional(bodies.transactional, Unit, session)
        }

        private fun <T> outsideFirst(bodies: OutsideFirst<T>, session: ClientSession): Applied? {
            val context = StepContext { lock.checkLock(id) }
            val prepared = bodies.outside.invoke(OutsideTransactionScope(id, database, context))
            outsideCounts = context.counts
            lock.checkLock(id)
            val next = bodies.transactional ?: return null
            return transactional(next, prepared, session)
        }

        /** Runs the transactional step: one transaction for `inTransaction`, one per page for `inBatches`. */
        private fun <T> transactional(step: TransactionalStep<T>, prepared: T, session: ClientSession): Applied {
            this.step = step.kind
            return when (step) {
                is InTransactionStep<T> -> inTransaction(step.body, prepared, session)

                is InBatchesStep -> Pages(migration, step, database, store, lock, ::newTransaction, ::appliedRun)
                    .also { pages = it }
                    .run(session, checkpoint, outsideCounts)
            }
        }

        /** A transaction of the transactional step, which a failure's guidance looks at once it is the latest. */
        private fun newTransaction() = Transaction(id, config.slowTransactionWarning, tuning).also { transaction = it }

        /**
         * Runs an `inTransaction` step in one transaction that godwit's own read opens
         * ([HistoryStore.openStepTransaction]) and whose last write is the fenced APPLIED record, sent only while that
         * transaction is still open ([requireStepTransaction]). Every run of the body gets a new scope and new
         * counters, and the same [prepared] instance.
         */
        private fun <T> inTransaction(
            body: TransactionScope.(prepared: T) -> Unit,
            prepared: T,
            session: ClientSession
        ): Applied {
            val transaction = newTransaction()
            val counts = transaction.run(session) { attempt ->
                val context = StepContext { lock.checkLock(id) }
                val scope = TransactionScope(id, database, session, attempt, context)
                lock.checkLock(id)
                store.openStepTransaction(id, session)
                val opened = transactionNumber(session)
                scope.body(prepared)
                lock.checkLock(id)
                requireStepTransaction(session, opened)
                val counts = addCounts(outsideCounts, context.counts)
                store.recordApplied(migration, lock.owner, appliedRun(counts, attempt - 1), session)
                counts
            }
            return Applied(counts, transaction.retries)
        }

        /**
         * The APPLIED record of a migration with only an outside step: godwit's own write, outside any transaction.
         * A driver exception from it is not the step's failure: [confirmApplied] decides whether the migration is
         * applied, or propagates that exception.
         */
        private fun recordOutsideOnly(): Applied {
            val run = appliedRun(outsideCounts, 0)
            try {
                store.recordApplied(migration, lock.owner, run)
            } catch (e: LockLostException) {
                Log.migrationFailed(id, step, attempts, e)
                throw e
            } catch (e: MongoException) {
                confirmApplied(run, e)
            }
            return Applied(outsideCounts, 0)
        }

        /**
         * Settles an APPLIED record of a migration with only an outside step that threw [error]. It sends the same
         * fenced record once more. When the first one never applied, the second records the migration, and it is
         * applied. When the first one applied and only its reply failed (a lost reply, a client-side timeout during
         * its majority wait), the second matches nothing, and the server acknowledges that no-op only once the first
         * is majority-committed, so the majority read that follows finds the document APPLIED by this run: applied
         * too. A majority read alone, right after the timeout, could not see a write that is still waiting for its
         * majority. Anything else propagates [error] unchanged: the second write failed as well (it is attached as
         * suppressed), the read failed (attached likewise), or another run owns the document. The next start then
         * finds the migration APPLIED or resumes it.
         */
        private fun confirmApplied(run: AppliedRun, error: MongoException) {
            val recorded = try {
                store.recordApplied(migration, lock.owner, run)
                true
            } catch (fenced: LockLostException) {
                false
            } catch (again: Exception) {
                error.addSuppressed(again)
                throw error
            }
            if (recorded) return
            val document = try {
                store.read(id)
            } catch (read: Exception) {
                error.addSuppressed(read)
                throw error
            }
            document.appliedBy(lock.owner) ?: throw error
        }

        /**
         * Handles a step's failure [error]. A lost lock (from `checkLock()` or a fence) writes nothing more. A step
         * error at the moment the lock is lost is recorded with the fenced FAILED write, which matches only while no
         * other run has taken the document over, and becomes the cause of [LockLostException], also when it is a
         * commit that applied although the driver threw. Any other error is recorded FAILED, fenced on this run's owner
         * and RUNNING; when that write matches nothing, the server has acknowledged the no-op once everything before it
         * is majority-committed, and the document decides: APPLIED by this run means the commit applied and only its
         * reply failed, so the migration is applied ([Pages.appliedDespiteError] for an `inBatches` step); anything
         * else means another run owns it. When the FAILED write throws, the document is left as the step left it
         * (RUNNING, or APPLIED by a commit whose majority wait outlasts that write's timeout too), and
         * [MigrationFailedException] carries the write's exception as suppressed.
         */
        private fun failed(error: Throwable): Applied {
            if (error is LockLostException) {
                Log.migrationFailed(id, step, attempts, error)
                throw error
            }
            val failure = FailedRun(error, step, started.elapsedNow().inWholeMilliseconds, Instant.now())
            if (lock.isLost()) {
                val lost = LockLostException(id, error)
                try {
                    store.markFailed(id, lock.owner, failure)
                } catch (write: Exception) {
                    lost.addSuppressed(write)
                }
                Log.migrationFailed(id, step, attempts, error)
                throw lost
            }
            val matched = try {
                store.markFailed(id, lock.owner, failure)
            } catch (write: Exception) {
                Log.migrationFailed(id, step, attempts, error)
                throw migrationFailed(error).apply { addSuppressed(write) }
            }
            if (!matched) {
                val document = try {
                    store.read(id)
                } catch (read: Exception) {
                    read.addSuppressed(error)
                    throw read
                }
                document.appliedBy(lock.owner)?.let {
                    val counts = it.counts()
                    val retries = it.getInteger("transactionRetries", 0)
                    return pages?.appliedDespiteError(counts, retries) ?: Applied(counts, retries)
                }
                Log.migrationFailed(id, step, attempts, error)
                throw LockLostException(id, error)
            }
            Log.migrationFailed(id, step, attempts, error)
            throw migrationFailed(error)
        }

        private fun migrationFailed(error: Throwable) = MigrationFailedException(
            id,
            step,
            call.report(plan),
            error,
            guidance(error, step, transaction?.longestAttempt ?: Duration.ZERO, tuning.lifetimeGuidanceAfter)
        )

        private fun appliedRun(counts: Map<String, Long>, transactionRetries: Int) = AppliedRun(
            counts,
            transactionRetries,
            started.elapsedNow().inWholeMilliseconds,
            Instant.now(),
            due.outOfOrder
        )
    }
}

/**
 * What a migration's committed run left in history: its counters, its transactions' retries, and for an `inBatches`
 * step the pages committed over every attempt.
 */
internal class Applied(val counts: Map<String, Long>, val transactionRetries: Int, val batches: Int = 0)

/** The message of the [IllegalStateException] `markApplied` throws while adoption can still run. */
internal const val ADOPTION_NOT_ENDED = "adoption has not ended on this database; run migrate() first so the " +
    "adoptApplied hook adopts, or call markApplied from a Godwit built without adoptApplied"

/** A migration this call recorded without running it: no steps, no attempts, no counts, no duration. */
private fun recordedOutcome(id: String, kind: MigrationKind, origin: Origin) =
    MigrationOutcome(id, kind, origin, emptyList(), 0, 0, 0, emptyMap(), false, Duration.ZERO)

/** The document when it is APPLIED and the run whose lock token is [owner] wrote it; null otherwise. */
private fun Document?.appliedBy(owner: String): Document? =
    this?.takeIf { it.getString("state") == HistoryState.APPLIED.name && it.getString("owner") == owner }
