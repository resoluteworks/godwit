package godwit.core

import godwit.core.internal.InBatchesStep
import godwit.core.internal.InTransactionStep
import godwit.core.internal.OutsideFirst
import godwit.core.internal.StepBodies
import godwit.core.internal.TransactionalOnly
import org.bson.Document
import org.bson.conversions.Bson

/**
 * How often a migration runs. The factory that starts the declaration names it ([migration], [everyStart] or
 * [repeatable]), so the kind is visible where the migration is written. Stored in history as `kind`.
 */
sealed interface MigrationKind {
    /** Runs once per database, in list order. Stored as `ONCE`. */
    data object Once : MigrationKind

    /**
     * Runs on every [Godwit.migrate] call with [Target.Latest], after every pending once-only migration, under the
     * lock. Stored as `EVERY_START`.
     */
    data object EveryStart : MigrationKind

    /**
     * Runs when [revision] differs from the revision stored in its history document, after every pending once-only
     * migration. Stored as `REPEATABLE` with `revision`.
     */
    data class Repeatable(val revision: String) : MigrationKind
}

/**
 * The steps a migration can have. A migration has an optional [OUTSIDE_TRANSACTION] step followed by an optional
 * [IN_TRANSACTION] or [IN_BATCHES] step, and at least one step.
 */
enum class StepKind {
    /** No session and no transaction. Runs at least once: a retry runs it again, so it must be idempotent. */
    OUTSIDE_TRANSACTION,

    /** One transaction that also commits the APPLIED history record. Commits exactly once. */
    IN_TRANSACTION,

    /** One transaction per page of documents, each committing a checkpoint. Each page commits exactly once. */
    IN_BATCHES
}

/**
 * A complete migration: a plain value with an id, a kind and one or two steps.
 *
 * Only the step functions of [MigrationDraft] and [OutsideTransactionMigration] create one, so the compiler checks
 * the shape: a draft without a step is not a [Migration], and nothing can follow the transactional step.
 *
 * An app lists its migrations in a plain `List<Migration>`, usually built by a function whose parameters are the
 * services the migrations need. List position is the run order; nothing is discovered, scanned or registered.
 */
sealed class Migration {
    /**
     * Flat and global within the database, and the `_id` of the migration's history document. Matches
     * `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`. A numeric prefix (`001-initial-setup`) is checked for order by
     * [validateMigrations].
     */
    abstract val id: String

    /** Free text stored in history and shown in logs. */
    abstract val description: String?

    abstract val kind: MigrationKind

    /** The ids this migration replaces (a squash). Empty unless declared with `migration(id, supersedes = ...)`. */
    abstract val supersedes: List<String>

    /** The steps in run order: one or two entries. */
    abstract val steps: List<StepKind>

    /** The bodies of [steps], which the runner calls. */
    internal abstract val bodies: StepBodies

    final override fun toString(): String = id
}

/**
 * Starts a once-only migration: it runs once per database, in list order.
 *
 * [supersedes] declares a squash: the ids this migration replaces, which are no longer in the list. While this
 * migration has no APPLIED history document: on a database where every one of them is APPLIED, it is recorded with
 * origin [Origin.SUPERSEDED] without running; where none is, it runs; where only some are, [Godwit.migrate] throws
 * [PlanConflictException]. Once it is APPLIED, the list is not evaluated again. The stored `supersedes` list keeps the
 * old ids known after the list stops naming them (see [UnknownApplied]).
 */
fun migration(id: String, description: String? = null, supersedes: List<String> = emptyList()): MigrationDraft =
    MigrationDraft(id, description, MigrationKind.Once, supersedes.toList())

/**
 * Starts a migration that runs on every [Godwit.migrate] call with [Target.Latest], after every pending once-only
 * migration, under the lock. A list that contains one takes the lock on every start; prefer [repeatable] unless the
 * work must happen on every start. Listed after every once-only migration.
 */
fun everyStart(id: String, description: String? = null): MigrationDraft =
    MigrationDraft(id, description, MigrationKind.EveryStart, emptyList())

/**
 * Starts a migration that runs again whenever [revision] differs from the revision stored in its history document,
 * after every pending once-only migration. The app owns [revision] (a date or a counter) and changes it in the same
 * commit as the code. When the stored revision matches, a start skips it without taking the lock. Listed after every
 * once-only migration.
 */
fun repeatable(id: String, revision: String, description: String? = null): MigrationDraft =
    MigrationDraft(id, description, MigrationKind.Repeatable(revision), emptyList())

/**
 * A migration without a step. Not a [Migration]: it cannot be put in a `List<Migration>` or passed to
 * [Godwit.migrate] until one of these functions gives it a step.
 */
class MigrationDraft internal constructor(
    internal val id: String,
    internal val description: String?,
    internal val kind: MigrationKind,
    internal val supersedes: List<String>
) {
    /**
     * Adds the step that runs without a session or a transaction. Every write in it commits on its own.
     *
     * It runs at least once: when the migration fails or the process dies, the next [Godwit.migrate] runs it again
     * from the start, so it must be idempotent. DDL (collections, indexes, search indexes), server-side updates that
     * are safe to repeat, and calls to external services belong here.
     *
     * The value [step] returns is handed to the transactional step that follows, if any. The result is a complete
     * [Migration] on its own.
     */
    fun <T> outsideTransaction(step: OutsideTransactionScope.() -> T): OutsideTransactionMigration<T> =
        OutsideTransactionMigration(id, description, kind, supersedes, step)

    /**
     * Adds the step that runs in one transaction. The same transaction commits the APPLIED history record, so the
     * step's writes and the record commit together or not at all: the step's effect commits exactly once.
     *
     * Pass [TransactionScope.session] to every driver call and every app service call. The driver runs [step] again
     * on a transient transaction error, so it must not call external services. The transaction must commit within
     * the server's transaction lifetime (60 s by default); larger work belongs in [inBatches].
     */
    fun inTransaction(step: TransactionScope.() -> Unit): Migration =
        TransactionalMigration(id, description, kind, supersedes, TransactionalOnly(InTransactionStep { step() }))

    /**
     * Adds the step that processes [collection] page by page, one transaction per page.
     *
     * For each page, in one transaction, godwit reads up to [batchSize] documents that match [pending] and have an
     * `_id` greater than the checkpoint, in `_id` order, calls [step] with them, and commits the step's writes
     * together with the new checkpoint (the page's last `_id`, the page count and the counts so far). The page that
     * finds fewer than [batchSize] documents is the last: its transaction commits the APPLIED history record instead,
     * which removes the checkpoint. [step] is never called with an empty page.
     *
     * After a failure, the next [Godwit.migrate] resumes after the checkpoint, so no page commits twice. [pending] is
     * evaluated on every page: a document that stops matching it is skipped.
     *
     * Paging compares `_id` with `$gt`, which matches values of the same BSON type only (all numeric types count as
     * one), so the `_id`s of the documents that match [pending] must share one type. godwit checks every page and,
     * before the last page commits, that no document matching [pending] has an `_id` of another type; either check
     * fails the migration with [MigrationFailedException] in IN_BATCHES, naming both types. [pending] can select one
     * type with `Filters.type("_id", ...)`. Documents inserted during the run with an `_id` below the checkpoint are not
     * visited; the app writes new documents in the new shape.
     *
     * [batchSize] is between 1 and 10000, and a page must commit well within the server's transaction lifetime.
     * Not available to [everyStart] and [repeatable] migrations ([validateMigrations] reports it).
     */
    fun inBatches(
        collection: String,
        pending: Bson,
        batchSize: Int = 500,
        step: TransactionScope.(batch: List<Document>) -> Unit
    ): Migration = TransactionalMigration(
        id,
        description,
        kind,
        supersedes,
        TransactionalOnly(InBatchesStep(collection, pending, batchSize, step))
    )
}

/**
 * A migration whose first step runs outside any transaction. Complete on its own (a DDL-only migration), or followed
 * by one transactional step.
 *
 * [T] is the type of the value the outside step returns. [inTransaction] receives it, which is how work done outside
 * the transaction (external calls, slow reads) reaches the transaction without repeating on a driver retry. Every
 * driver retry of the body receives the same instance, so the value must be fully materialised (a `List` or `Map`, not
 * a cursor or a `Sequence`) and the body must not mutate it. When the migration is retried after a failure, the outside
 * step runs again and produces a fresh value.
 */
class OutsideTransactionMigration<T> internal constructor(
    override val id: String,
    override val description: String?,
    override val kind: MigrationKind,
    override val supersedes: List<String>,
    private val outside: OutsideTransactionScope.() -> T
) : Migration() {
    override val steps: List<StepKind> get() = listOf(StepKind.OUTSIDE_TRANSACTION)

    override val bodies: StepBodies = OutsideFirst(outside, null)

    /** As [MigrationDraft.inTransaction], with the value the outside step returned as [step]'s parameter. */
    fun inTransaction(step: TransactionScope.(prepared: T) -> Unit): Migration =
        TransactionalMigration(id, description, kind, supersedes, OutsideFirst(outside, InTransactionStep(step)))

    /** As [MigrationDraft.inBatches], run after the outside step. */
    fun inBatches(
        collection: String,
        pending: Bson,
        batchSize: Int = 500,
        step: TransactionScope.(batch: List<Document>) -> Unit
    ): Migration = TransactionalMigration(
        id,
        description,
        kind,
        supersedes,
        OutsideFirst(outside, InBatchesStep(collection, pending, batchSize, step))
    )
}

/**
 * A migration that ends with a transactional step: what [MigrationDraft.inTransaction], [MigrationDraft.inBatches] and
 * the same functions of [OutsideTransactionMigration] return. Nothing can follow it.
 */
internal class TransactionalMigration(
    override val id: String,
    override val description: String?,
    override val kind: MigrationKind,
    override val supersedes: List<String>,
    override val bodies: StepBodies
) : Migration() {
    override val steps: List<StepKind> get() = bodies.kinds
}
