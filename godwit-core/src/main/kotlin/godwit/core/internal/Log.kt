package godwit.core.internal

import godwit.core.StepKind
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder

/**
 * godwit's log lines, one function per event of the catalogue in the `Godwit` KDoc, with the level, message and keys
 * it lists. Every line goes to the logger named `godwit` through the slf4j 2 fluent API, with its facts as key-value
 * pairs: lists print in brackets (`[a, b]`), times as ISO-8601 instants with milliseconds, and an `error` that is an
 * exception as its class and message.
 */
internal object Log {
    private val logger: Logger = LoggerFactory.getLogger("godwit")

    private val instants: DateTimeFormatter = DateTimeFormatterBuilder().appendInstant(3).toFormatter()

    fun migrationsUpToDate(runId: String, checked: Int, durationMs: Long) =
        logger.atInfo().setMessage("Migrations up to date")
            .addKeyValue("runId", runId)
            .addKeyValue("checked", checked)
            .addKeyValue("durationMs", durationMs)
            .log()

    /** [holder] and [holderRunId] name the run that holds the lock, not this one. */
    fun waitingForMigrationLock(holder: String, holderRunId: String, expiresAt: Instant, waitedMs: Long) =
        logger.atInfo().setMessage("Waiting for migration lock")
            .addKeyValue("holder", holder)
            .addKeyValue("holderRunId", holderRunId)
            .addKeyValue("expiresAt", instants.format(expiresAt))
            .addKeyValue("waitedMs", waitedMs)
            .log()

    fun acquiredMigrationLock(runId: String, lockWaitMs: Long) = logger.atInfo().setMessage("Acquired migration lock")
        .addKeyValue("runId", runId)
        .addKeyValue("lockWaitMs", lockWaitMs)
        .log()

    fun lockRenewalFailed(runId: String, holder: String, error: Throwable) =
        logger.atWarn().setMessage("Lock renewal failed")
            .addKeyValue("runId", runId)
            .addKeyValue("holder", holder)
            .addKeyValue("error", error.toString())
            .log()

    fun lostMigrationLock(runId: String, holder: String, reason: LossReason) =
        logger.atWarn().setMessage("Lost migration lock")
            .addKeyValue("runId", runId)
            .addKeyValue("holder", holder)
            .addKeyValue("reason", reason.name)
            .log()

    fun lockReleaseFailed(runId: String, holder: String, error: Throwable) =
        logger.atWarn().setMessage("Lock release failed")
            .addKeyValue("runId", runId)
            .addKeyValue("holder", holder)
            .addKeyValue("error", error.toString())
            .log()

    /**
     * [adopted] lists the ids this call of the hook recorded, in list order; [ignored] the ids it returned that the list
     * neither declares as once-only nor names in a `supersedes` list, sorted.
     */
    fun adoptedAppliedMigrations(adopted: List<String>, ignored: List<String>) =
        logger.atInfo().setMessage("Adopted applied migrations")
            .addKeyValue("adopted", adopted)
            .addKeyValue("ignored", ignored)
            .log()

    fun recordedSupersededMigration(id: String, supersedes: List<String>) =
        logger.atInfo().setMessage("Recorded superseded migration")
            .addKeyValue("id", id)
            .addKeyValue("supersedes", supersedes)
            .log()

    fun resumingInterruptedMigration(id: String, attempts: Int) =
        logger.atWarn().setMessage("Resuming interrupted migration")
            .addKeyValue("id", id)
            .addKeyValue("attempts", attempts)
            .log()

    fun runningOutOfOrderMigration(id: String, appliedAfter: List<String>) =
        logger.atWarn().setMessage("Running out-of-order migration")
            .addKeyValue("id", id)
            .addKeyValue("appliedAfter", appliedAfter)
            .log()

    /** [attempt] is the migration's run count since it last applied, its `attempts` field. */
    fun runningMigration(id: String, kind: StoredKind, steps: List<StepKind>, attempt: Int) =
        logger.atInfo().setMessage("Running migration")
            .addKeyValue("id", id)
            .addKeyValue("kind", kind.name)
            .addKeyValue("steps", steps)
            .addKeyValue("attempt", attempt)
            .log()

    /**
     * [attempt] is the driver's attempt at one transaction body. [error] is the code name and code of the error the
     * previous attempt's body threw, such as `WriteConflict (112)`, or `commit`.
     */
    fun retryingTransaction(id: String, attempt: Int, error: String) =
        logger.atWarn().setMessage("Retrying transaction")
            .addKeyValue("id", id)
            .addKeyValue("attempt", attempt)
            .addKeyValue("error", error)
            .log()

    fun slowTransaction(id: String, attempt: Int, durationMs: Long) = logger.atWarn().setMessage("Slow transaction")
        .addKeyValue("id", id)
        .addKeyValue("attempt", attempt)
        .addKeyValue("durationMs", durationMs)
        .log()

    /** [lastId] prints as its value: an ObjectId as its hex string, a string or a number as it is. */
    fun committedBatch(id: String, batch: Int, lastId: Any) = logger.atDebug().setMessage("Committed batch")
        .addKeyValue("id", id)
        .addKeyValue("batch", batch)
        .addKeyValue("lastId", lastId)
        .log()

    /** Each counter in [counts] follows the fixed keys, in the map's order. */
    fun appliedMigration(
        id: String,
        kind: StoredKind,
        steps: List<StepKind>,
        attempts: Int,
        txRetries: Int,
        batches: Int,
        durationMs: Long,
        counts: Map<String, Long>
    ) {
        val event = logger.atInfo().setMessage("Applied migration")
            .addKeyValue("id", id)
            .addKeyValue("kind", kind.name)
            .addKeyValue("steps", steps)
            .addKeyValue("attempts", attempts)
            .addKeyValue("txRetries", txRetries)
            .addKeyValue("batches", batches)
            .addKeyValue("durationMs", durationMs)
        counts.forEach { (name, value) -> event.addKeyValue(name, value) }
        event.log()
    }

    fun unknownAppliedMigrations(ids: List<String>) = logger.atWarn().setMessage("Unknown applied migrations")
        .addKeyValue("ids", ids)
        .log()

    /** Carries [error] as the event's cause too, so the whole stack trace reaches the log. */
    fun migrationFailed(id: String, step: StepKind, attempts: Int, error: Throwable) =
        logger.atError().setMessage("Migration failed")
            .addKeyValue("id", id)
            .addKeyValue("step", step.name)
            .addKeyValue("attempts", attempts)
            .addKeyValue("error", error.toString())
            .setCause(error)
            .log()

    fun migrationsComplete(runId: String, ran: Int, recorded: Int, upToDate: Int, lockWaitMs: Long, durationMs: Long) =
        logger.atInfo().setMessage("Migrations complete")
            .addKeyValue("runId", runId)
            .addKeyValue("ran", ran)
            .addKeyValue("recorded", recorded)
            .addKeyValue("upToDate", upToDate)
            .addKeyValue("lockWaitMs", lockWaitMs)
            .addKeyValue("durationMs", durationMs)
            .log()

    fun markedMigrationApplied(id: String, reason: String, holder: String) =
        logger.atWarn().setMessage("Marked migration applied")
            .addKeyValue("id", id)
            .addKeyValue("reason", reason)
            .addKeyValue("holder", holder)
            .log()
}
