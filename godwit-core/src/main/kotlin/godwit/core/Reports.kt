package godwit.core

import org.bson.BsonValue
import java.time.Instant
import kotlin.time.Duration

/** How a history document came to be APPLIED. Stored as `origin`. */
enum class Origin {
    /** godwit ran it. */
    RAN,

    /** [GodwitConfig.adoptApplied] reported it applied before godwit tracked the database. */
    ADOPTED,

    /** Recorded without running, because every id it supersedes was already applied. */
    SUPERSEDED,

    /** Recorded by [Godwit.markApplied]. */
    MARKED
}

/** The state of a history document. Stored as `state`. */
enum class HistoryState {
    /** A run started it and has not finished: it is running now, or the run was interrupted and its lease expired. */
    RUNNING,

    /** The last run failed; [HistoryEntry.lastError] says why. The next [Godwit.migrate] retries it. */
    FAILED,

    /** Done. Final for a once-only migration; a repeatable or every-start one goes back to RUNNING on its next run. */
    APPLIED
}

/** One migration that a [Godwit.migrate] call ran or recorded. */
class MigrationOutcome internal constructor(
    val id: String,
    val kind: MigrationKind,
    val origin: Origin,
    /** The steps that ran; empty for an adopted or superseded migration. */
    val steps: List<StepKind>,
    /**
     * Runs started since the migration was last APPLIED, this one included: 1 unless earlier runs failed. 0 for an
     * adopted or superseded migration, which did not run.
     */
    val attempts: Int,
    /** Driver retries of transaction bodies in this call, over every transaction of the migration. */
    val transactionRetries: Int,
    /**
     * Pages committed by an `inBatches` step, over every attempt; a last read that finds nothing is not a page. 0 for
     * other migrations.
     */
    val batches: Int,
    /** Every counter the steps set with `count`. */
    val counts: Map<String, Long>,
    /** True when it ran under [OutOfOrder.RUN] before an applied migration listed after it. */
    val outOfOrder: Boolean,
    /** This call's run of the migration; zero for an adopted or superseded migration. */
    val duration: Duration
) {
    /** The counter [name]; 0 when the steps never counted it. */
    fun count(name: String): Long = counts[name] ?: 0
}

/** What one [Godwit.migrate] call did. The "Migrations complete" log line carries the same numbers. */
class MigrationReport internal constructor(
    /**
     * A UUID per call: in every history document it writes, in its lock lines, and in its "Migrations up to date" or
     * "Migrations complete" line. The lines about one migration carry its `id` instead.
     */
    val runId: String,
    /** Migrations that ran, in run order. */
    val ran: List<MigrationOutcome>,
    /** Migrations recorded without running: adopted or superseded. */
    val recorded: List<MigrationOutcome>,
    /** Ids found already applied, and repeatables found at their current revision. */
    val upToDate: List<String>,
    /**
     * Once-only ids still due because the [Target] stopped before them. A target never runs repeatable or every-start
     * migrations, and they are not listed here. Empty under [Target.Latest].
     */
    val pending: List<String>,
    /** APPLIED history ids that the list does not know (see [UnknownApplied]). */
    val unknownApplied: List<String>,
    /** Time spent waiting for the lock; null when nothing was due and the lock was never taken. */
    val lockWait: Duration?,
    val duration: Duration
) {
    /** The outcome for [id] in [ran] or [recorded]. Throws [NoSuchElementException] when this call did neither. */
    operator fun get(id: String): MigrationOutcome =
        (ran + recorded).firstOrNull { it.id == id } ?: throw NoSuchElementException("$id neither ran nor was recorded")
}

/** What [Godwit.status] found. */
class MigrationStatus internal constructor(
    /** Ids [Godwit.migrate] with [Target.Latest] would run, in run order. Never includes every-start migrations. */
    val pending: List<String>,
    /**
     * Why [Godwit.migrate] would throw: out of order, partially superseded, untracked database, unknown applied ids
     * under [UnknownApplied.FAIL]. The first three are not reported while [GodwitConfig.adoptApplied] can still run,
     * because the hook, which only [Godwit.migrate] calls, may resolve them.
     */
    val problems: List<String>,
    /** APPLIED history ids that the list does not know. */
    val unknownApplied: List<String>
) {
    val isUpToDate: Boolean get() = pending.isEmpty() && problems.isEmpty()
}

/**
 * One history document, as [Godwit.history] reads it.
 *
 * The stored document, in [GodwitConfig.historyCollection]:
 *
 * | Field                     | Type      | Meaning                                                                |
 * |---------------------------|-----------|------------------------------------------------------------------------|
 * | `_id`                     | String    | The migration id                                                       |
 * | `kind`                    | String    | `ONCE`, `EVERY_START` or `REPEATABLE`                                  |
 * | `revision`                | String?   | A repeatable's revision                                                |
 * | `description`             | String?   | As of the last run                                                     |
 * | `steps`                   | [String]  | `OUTSIDE_TRANSACTION`, `IN_TRANSACTION`, `IN_BATCHES`                  |
 * | `state`                   | String    | `RUNNING`, `FAILED` or `APPLIED`                                       |
 * | `origin`                  | String    | `RAN`, `ADOPTED`, `SUPERSEDED` or `MARKED`                             |
 * | `attempts`                | Int       | Runs started since the last APPLIED                                    |
 * | `transactionRetries`      | Int       | Driver retries of transaction bodies in the successful run             |
 * | `counts`                  | Document  | The steps' counters, `{ordersUpdated: 1200}`                           |
 * | `durationMs`              | Long      | Duration of the last run                                               |
 * | `startedAt`, `finishedAt` | Date      | Client clock, informational                                            |
 * | `lastError`               | Document? | `{type, message, stack, step, at}`; removed when APPLIED               |
 * | `checkpoint`              | Document? | `{lastId, batches, counts}` while an `inBatches` step is unfinished    |
 * | `runCount`, `lastRunAt`   | Long, Date| Successful runs of a repeatable or every-start migration               |
 * | `supersedes`              | [String]? | The ids a superseding migration replaces                               |
 * | `outOfOrder`              | Boolean?  | Ran under [OutOfOrder.RUN]                                             |
 * | `reason`                  | String?   | The [Godwit.markApplied] reason                                        |
 * | `holder`                  | String    | [GodwitConfig.holder] of the process that last wrote it                |
 * | `owner`                   | String    | Lock token of the run that last wrote `state`; fences a stale run      |
 * | `runId`                   | String    | [MigrationReport.runId] of the call that last wrote it                 |
 * | `godwitVersion`, `v`      | String, Int | Writer version and document format version (1)                       |
 */
class HistoryEntry internal constructor(
    val id: String,
    /** For a repeatable, carries the stored revision: empty until a run of the repeatable applies. */
    val kind: MigrationKind,
    val state: HistoryState,
    val origin: Origin,
    val description: String?,
    val steps: List<StepKind>,
    val attempts: Int,
    val transactionRetries: Int,
    val counts: Map<String, Long>,
    val duration: Duration?,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val lastError: LastError?,
    val checkpoint: BatchCheckpoint?,
    /** Successful runs of a repeatable or every-start migration; null for a once-only one. */
    val runCount: Long?,
    val lastRunAt: Instant?,
    val supersedes: List<String>,
    val outOfOrder: Boolean,
    val reason: String?,
    val holder: String?,
    val runId: String?,
    val godwitVersion: String?
)

/** The error of a FAILED migration's last run, written outside any transaction after it failed. */
class LastError internal constructor(
    /** The exception class name. */
    val type: String,
    val message: String?,
    /** The stack trace, capped at 8 KB. */
    val stack: String?,
    /** The step that failed; null when the failure happened between steps. */
    val step: StepKind?,
    val at: Instant
)

/** How far an unfinished `inBatches` step got. Committed with each page. */
class BatchCheckpoint internal constructor(
    /** The `_id` of the last document of the last committed page. */
    val lastId: BsonValue,
    /** Pages committed so far. */
    val batches: Int
)

/**
 * The process holding the migration lock, as a waiting process sees it.
 *
 * The stored document, in [GodwitConfig.lockCollection]: `_id` ([GodwitConfig.historyCollection]), `owner` (a random
 * token per acquisition), `holder`, `runId`, and the server times `acquiredAt`, `refreshedAt`, `expiresAt` and
 * `releasedAt`.
 */
class LockHolder internal constructor(
    /** [GodwitConfig.holder] of the holding process. */
    val holder: String,
    /** [MigrationReport.runId] of the holding call. */
    val runId: String,
    val acquiredAt: Instant,
    /** When the lease ends unless the holder renews it. */
    val expiresAt: Instant
)
