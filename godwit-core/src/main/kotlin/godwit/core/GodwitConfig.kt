package godwit.core

import com.mongodb.kotlin.client.MongoDatabase
import godwit.core.internal.defaultHolder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Settings for one [Godwit]. Every default is the recommended production value. */
data class GodwitConfig(
    /** One document per migration; `_id` is the migration id. */
    val historyCollection: String = "godwit-history",

    /** One lock document; its `_id` is [historyCollection]. */
    val lockCollection: String = "godwit-lock",

    val lock: LockConfig = LockConfig(),

    /**
     * What happens to a pending once-only migration listed before an applied once-only migration, or before an applied
     * id that the `supersedes` list of a later migration not yet applied names. Repeatable and every-start history
     * documents and unknown ids never make a once-only migration out of order.
     */
    val outOfOrder: OutOfOrder = OutOfOrder.FAIL,

    /** What happens when history holds an APPLIED id that the list does not know. */
    val unknownApplied: UnknownApplied = UnknownApplied.WARN,

    /** What happens when the database has collections but no godwit history, and nothing was adopted. */
    val untrackedDatabase: UntrackedDatabase = UntrackedDatabase.REFUSE,

    /**
     * For adopting a database migrated by another tool, or by hand: returns the ids of the migrations already applied
     * to the database it is given. The app writes it; godwit knows nothing of any other tool's records.
     *
     * godwit calls it under the lock, on a start that has work due, while every document in its history collection
     * has origin [Origin.ADOPTED] (an empty collection included). Each returned id that the list declares as once-only,
     * or names in a `supersedes` list, and that history does not hold yet, is recorded APPLIED with origin
     * [Origin.ADOPTED]. Adoption only adds: an id recorded by an earlier call stays recorded whatever the hook returns
     * now. Other returned ids are logged and ignored. A history document of another origin (a migration that ran, a
     * recorded squash, a [Godwit.markApplied]) ends adoption: the hook is not called while one exists. godwit decides
     * from the history each start reads, so deleting every such document by hand reopens adoption. The hook can stay
     * configured until every database is adopted; because godwit can call it more than once, it must only read.
     *
     * Until adoption ends, [Godwit.markApplied] on a [Godwit] built with this hook throws [IllegalStateException] and
     * writes nothing: a MARKED document would end adoption before the hook has recorded every applied id, and the ids
     * it has not recorded would run on the next start. A deliberate repair marks from a [Godwit] built from the same
     * configuration without it, `copy(adoptApplied = null)`, so that the marks land in this [historyCollection] and
     * [lockCollection].
     *
     * While the hook can still run, the out-of-order and partial-squash checks wait until it has run under the lock
     * and its ids are recorded. The adopted once-only ids must then form a prefix of the once-only list: a declared id
     * missing before an adopted one is out of order and follows [outOfOrder]. When history is still empty after the
     * hook, [untrackedDatabase] applies.
     *
     * godwit checks the lock after the hook returns, then records the new ids with idempotent upserts that only insert,
     * so a document that exists stays unchanged: in one transaction on a replica set, which the driver retries in the
     * same call after a transient error; on a standalone server, which has no transactions, one at a time and
     * last-listed first (the reverse of the once-only order, each migration preceded by the ids its `supersedes` list
     * names). An interrupted adoption completes on the next start, which calls the hook again and records what is
     * missing.
     */
    val adoptApplied: ((MongoDatabase) -> Set<String>)? = null,

    /** A transaction attempt slower than this logs a WARN. The server aborts transactions after 60 s by default. */
    val slowTransactionWarning: Duration = 20.seconds,

    /** Names this process in the lock document, history documents and log lines. Defaults to `<hostname>/<pid>`. */
    val holder: String = defaultHolder()
)

/**
 * The migration lock: one document in [GodwitConfig.lockCollection], leased with server time (`$$NOW`) and renewed by
 * a heartbeat thread while a run holds it. A crashed holder's lease expires within [lease]; no TTL index is involved.
 */
data class LockConfig(
    /** How long the lock stays held without a renewal. */
    val lease: Duration = 60.seconds,

    /** How often the holder renews the lease. Less than [lease] minus [safetyMargin]. */
    val heartbeat: Duration = 20.seconds,

    /** The holder treats the lock as lost this long before its lease would end without a renewal. */
    val safetyMargin: Duration = 10.seconds,

    /**
     * How long [Godwit.migrate] and [Godwit.markApplied] wait while another process holds the lock. They poll every
     * 250 ms to 5 s with jitter, log the holder every 10 s, and throw [LockTimeoutException] when it passes.
     * [Duration.ZERO] fails at once. Set it above the longest migration.
     */
    val waitTimeout: Duration = 10.minutes
) {
    init {
        require(lease.isPositive()) { "lease must be positive" }
        require(!safetyMargin.isNegative() && safetyMargin < lease) {
            "safetyMargin must be at least 0 and below lease"
        }
        require(heartbeat.isPositive() && heartbeat < lease - safetyMargin) {
            "heartbeat must be positive and below lease minus safetyMargin"
        }
        require(!waitTimeout.isNegative()) { "waitTimeout must not be negative" }
    }
}

/**
 * The policy for a pending once-only migration listed before an applied once-only migration (typically a merge of two
 * branches). Only once-only history counts: repeatable and every-start documents and unknown ids never make a once-only
 * migration out of order. While a superseding migration is not APPLIED, the applied ids its `supersedes` list names
 * count in its place, so a squash or a rename about to be recorded hides no gap before it.
 */
enum class OutOfOrder {
    /**
     * [Godwit.migrate] throws [PlanConflictException] with the problem
     * `<id> is pending, but <other>, listed after it, is applied (out of order; OutOfOrder.RUN runs it)`, where
     * `<other>` is the first applied once-only id listed after `<id>`, counting those ids in their migration's place.
     */
    FAIL,

    /**
     * The migration runs in list order with the other due ones; its history records `outOfOrder: true`, and the
     * "Running out-of-order migration" line lists the applied once-only ids listed after it as `appliedAfter`.
     */
    RUN
}

/**
 * The policy for an APPLIED history id that the list does not know. An id is known when the list declares it, when a
 * `supersedes` list in the list names it, or when a superseding migration recorded in history names it in its stored
 * `supersedes` list.
 */
enum class UnknownApplied {
    /**
     * Logged and returned in [MigrationReport.unknownApplied]: the normal state while older code runs after a
     * rollback.
     */
    WARN,

    /**
     * [Godwit.migrate] throws [PlanConflictException], with one problem per id:
     * `<id> is applied, but the list does not declare it (UnknownApplied.FAIL)`.
     */
    FAIL
}

/** The policy for a database that has collections (other than godwit's) but no godwit history, after adoption. */
enum class UntrackedDatabase {
    /** [Godwit.migrate] throws [UntrackedDatabaseException] naming the collections found. */
    REFUSE,

    /** Every migration runs, as on an empty database. */
    RUN_ALL
}
