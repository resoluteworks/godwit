package godwit.core.internal

import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.UpdateOptions
import com.mongodb.kotlin.client.ClientSession
import godwit.core.HistoryState
import godwit.core.LockLostException
import godwit.core.Migration
import godwit.core.MigrationKind
import godwit.core.Origin
import godwit.core.StepKind
import org.bson.Document
import org.bson.codecs.configuration.CodecRegistry
import org.bson.conversions.Bson
import java.time.Instant
import java.util.Date

/** The most of a stack trace that `lastError.stack` keeps, in UTF-8 bytes. */
internal const val STACK_CAP_BYTES = 8 * 1024

/** The markers' options: insert the document when it is missing, and return it as it is after the write. */
private val UPSERT_AND_RETURN = FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER)

/** The run that writes: its lock [owner] token, the process's [holder] and the call's [runId]. */
internal data class Writer(val owner: String, val holder: String, val runId: String)

/** What a run that applied a migration records with it. [finishedAt] is the client's clock. */
internal data class AppliedRun(
    val counts: Map<String, Long>,
    val transactionRetries: Int,
    val durationMs: Long,
    val finishedAt: Instant,
    /** It ran under `OutOfOrder.RUN` behind an applied migration listed after it. */
    val outOfOrder: Boolean = false
)

/** Why a run failed: the step's [error], the [step] it failed in (null between steps), and when. */
internal data class FailedRun(val error: Throwable, val step: StepKind?, val durationMs: Long, val at: Instant)

/**
 * The history collection: every read and write godwit makes to it, one document per migration with the migration id
 * as `_id`. The handle (from [Bookkeeping]) reads with majority read concern on the primary and writes with majority
 * write concern; the collection is created by its first upsert, and godwit creates no index on it.
 *
 * Writes that change `state` carry the writing run's owner token. The writes of a run that holds the lock are fenced
 * on it (`{_id, owner, state: RUNNING}`), so a run that lost the lock and does not know it yet writes nothing over the
 * run that took over. Driver exceptions propagate unchanged, except the duplicate keys that answer a conditional
 * upsert ([unlessApplied]).
 */
internal class HistoryStore(private val bookkeeping: Bookkeeping) {
    private val collection = bookkeeping.history

    /** The codecs that decode the documents this store returns; encoding a value with them restores its BSON type. */
    val codecs: CodecRegistry get() = collection.codecRegistry

    /**
     * Every document, sorted by `_id`: one `find`. Without a batch size the server's first batch stops at 101
     * documents, and a longer history would take a `getMore` as well; with the largest one, the first batch holds
     * every document up to the 16 MiB a reply can carry.
     */
    fun readAll(): List<Document> = collection.find().sort(Sorts.ascending("_id")).batchSize(Int.MAX_VALUE).toList()

    /** The document of [id], or null. */
    fun read(id: String): Document? = collection.find(Document("_id", id)).firstOrNull()

    /**
     * Writes the RUNNING marker of [migration] for [writer], outside any transaction, and returns the document as it
     * is afterwards: `attempts`, the `checkpoint` an `inBatches` step resumes from, and the `lastError` of an earlier
     * run, which stays until the migration applies. `description` is the migration's own, removed when it declares
     * none. With [session], the causally consistent session that later carries the step's transaction, the
     * transaction's snapshot sees the marker.
     *
     * A once-only migration's marker is an upsert conditional on `state != APPLIED` that increments `attempts`, sent
     * once more after a duplicate key ([unlessApplied]). The result is null only when the document is APPLIED: another
     * run applied the migration, and the caller skips it. A repeatable or every-start migration's marker is an
     * unconditional pipeline upsert that restarts `attempts` at 1 after an APPLIED run and never returns null.
     */
    fun markRunning(
        migration: Migration,
        writer: Writer,
        startedAt: Instant,
        session: ClientSession? = null
    ): Document? {
        val steps = migration.steps.map { it.name }
        return if (migration.kind == MigrationKind.Once) {
            val set = Document("kind", StoredKind.ONCE.name)
                .append("steps", steps)
                .append("state", HistoryState.RUNNING.name)
                .append("origin", Origin.RAN.name)
                .append("owner", writer.owner)
                .append("holder", writer.holder)
                .append("runId", writer.runId)
                .append("startedAt", Date.from(startedAt))
                .append("godwitVersion", GODWIT_VERSION)
                .append("v", DOCUMENT_FORMAT)
            val update = Document("\$set", set).append("\$inc", Document("attempts", 1))
            val description = migration.description
            if (description == null) {
                update.append("\$unset", Document("description", ""))
            } else {
                set.append("description", description)
            }
            unlessApplied {
                if (session == null) {
                    collection.findOneAndUpdate(notApplied(migration.id), update, UPSERT_AND_RETURN)
                } else {
                    collection.findOneAndUpdate(session, notApplied(migration.id), update, UPSERT_AND_RETURN)
                }
            }
        } else {
            val attempts = Document(
                "\$cond",
                listOf(
                    Document("\$eq", listOf("\$state", HistoryState.APPLIED.name)),
                    1,
                    Document("\$add", listOf(Document("\$ifNull", listOf("\$attempts", 0)), 1))
                )
            )
            val set = Document("kind", migration.kind.stored.name)
                .append("steps", steps)
                .append("state", HistoryState.RUNNING.name)
                .append("origin", Origin.RAN.name)
                .append("attempts", attempts)
                .append("owner", literal(writer.owner))
                .append("holder", literal(writer.holder))
                .append("runId", literal(writer.runId))
                .append("startedAt", Date.from(startedAt))
                .append("godwitVersion", GODWIT_VERSION)
                .append("v", DOCUMENT_FORMAT)
                .append("description", migration.description?.let(::literal) ?: "\$\$REMOVE")
            val pipeline = listOf(Document("\$set", set))
            if (session == null) {
                collection.findOneAndUpdate(Document("_id", migration.id), pipeline, UPSERT_AND_RETURN)
            } else {
                collection.findOneAndUpdate(session, Document("_id", migration.id), pipeline, UPSERT_AND_RETURN)
            }
        }
    }

    /**
     * Records [migration] APPLIED with the facts of [run], fenced on [owner] and `state: RUNNING`: on [session] inside
     * the step's transaction, or on its own after an outside-only step when [session] is null. Removes `lastError` and
     * `checkpoint`. A repeatable also records its `revision`, and every other kind removes it, so that `revision` names
     * the revision whose data is in the database even when the id changed kind; a repeatable or every-start migration
     * sets `lastRunAt` and increments `runCount`; a superseding migration stores its `supersedes` list.
     *
     * @throws LockLostException when the fence matches nothing: another run's marker carries another owner token.
     *   Inside a transaction, the exception aborts it.
     */
    fun recordApplied(migration: Migration, owner: String, run: AppliedRun, session: ClientSession? = null) {
        val finishedAt = Date.from(run.finishedAt)
        val set = Document("state", HistoryState.APPLIED.name)
            .append("counts", Document(run.counts))
            .append("transactionRetries", run.transactionRetries)
            .append("durationMs", run.durationMs)
            .append("finishedAt", finishedAt)
        val unset = Document("lastError", "").append("checkpoint", "")
        val update = Document("\$set", set).append("\$unset", unset)
        val kind = migration.kind
        if (kind is MigrationKind.Repeatable) set.append("revision", kind.revision) else unset.append("revision", "")
        if (kind != MigrationKind.Once) {
            set.append("lastRunAt", finishedAt)
            update.append("\$inc", Document("runCount", 1L))
        }
        if (run.outOfOrder) set.append("outOfOrder", true)
        if (migration.supersedes.isNotEmpty()) set.append("supersedes", migration.supersedes)
        val fence = ownedAndRunning(migration.id, owner)
        val result = if (session == null) {
            collection.updateOne(fence, update)
        } else {
            collection.updateOne(session, fence, update)
        }
        if (result.matchedCount == 0L) throw LockLostException(migration.id)
    }

    /**
     * Writes [checkpoint] as the document's `checkpoint` (`lastId` with its BSON type, `batches`, `counts`) on
     * [session], inside the transaction of the page it follows, fenced on [owner] and `state: RUNNING` as the APPLIED
     * record is. The page's writes and its checkpoint commit together.
     *
     * @throws LockLostException when the fence matches nothing: another run's marker carries another owner token. The
     *   exception aborts the page's transaction.
     */
    fun writeCheckpoint(id: String, owner: String, checkpoint: Checkpoint, session: ClientSession) {
        val stored = Document("lastId", checkpoint.lastId)
            .append("batches", checkpoint.batches)
            .append("counts", Document(checkpoint.counts))
        val update = Document("\$set", Document("checkpoint", stored))
        val result = collection.updateOne(session, ownedAndRunning(id, owner), update)
        if (result.matchedCount == 0L) throw LockLostException(id)
    }

    /**
     * Records [id] FAILED with [run]'s error as `lastError` (`type`, `message`, `stack` capped at 8 KB, `step`, `at`),
     * outside any transaction, fenced on [owner] and `state: RUNNING`. Returns whether it matched: false when another
     * run took the document over, or when the document is APPLIED (a commit that applied although the driver threw).
     */
    fun markFailed(id: String, owner: String, run: FailedRun): Boolean {
        val at = Date.from(run.at)
        val error = Document("type", run.error.javaClass.name)
        run.error.message?.let { error.append("message", it) }
        error.append("stack", capUtf8(run.error.stackTraceToString(), STACK_CAP_BYTES))
        run.step?.let { error.append("step", it.name) }
        error.append("at", at)
        val set = Document("state", HistoryState.FAILED.name)
            .append("durationMs", run.durationMs)
            .append("finishedAt", at)
            .append("lastError", error)
        return collection.updateOne(ownedAndRunning(id, owner), Document("\$set", set)).matchedCount == 1L
    }

    /**
     * Records each of [ids] APPLIED with origin ADOPTED, with upserts that only insert: every field is in
     * `$setOnInsert`, so a document that exists, in any state, is left unchanged. Passes each id this call inserted to
     * [recorded] once its write is durable, so that a caller whose call throws still knows what it recorded.
     *
     * [ids] are in list order: the once-only order, each migration preceded by the ids its `supersedes` list names, in
     * that list's order. With [transactions] (a replica set or `mongos`) they are written in one transaction through
     * the driver's `withTransaction`, with [checkLock] before the commit: a transient error is retried in the same
     * call, and any other error, a lost lock included, leaves nothing recorded and propagates; the inserted ids go to
     * [recorded] after the commit, in list order. Without (a standalone server) they are written one at a time,
     * last-listed first, with [checkLock] before each, and each inserted id goes to [recorded] after its write, so an
     * error part-way leaves the ids [recorded] has seen and propagates.
     */
    fun recordAdopted(
        ids: List<String>,
        writer: Writer,
        finishedAt: Instant,
        transactions: Boolean,
        checkLock: () -> Unit,
        recorded: (String) -> Unit
    ) {
        val insert = Document(
            "\$setOnInsert",
            onceOnlyRecord(Origin.ADOPTED, writer, finishedAt)
                .append("kind", StoredKind.ONCE.name)
                .append("steps", emptyList<String>())
                .append("attempts", 0)
        )
        val options = UpdateOptions().upsert(true)
        if (!transactions) {
            for (id in ids.asReversed()) {
                checkLock()
                if (collection.updateOne(Document("_id", id), insert, options).upsertedId != null) recorded(id)
            }
            return
        }
        val inserted = bookkeeping.startSession().use { session ->
            session.withTransaction(
                {
                    val inserted = ids.filter { id ->
                        collection.updateOne(session, Document("_id", id), insert, options).upsertedId != null
                    }
                    checkLock()
                    inserted
                },
                TRANSACTION_OPTIONS
            )
        }
        inserted.forEach(recorded)
    }

    /**
     * Records the superseding [migration] APPLIED with origin SUPERSEDED and its `supersedes` list, without running
     * it, removing `lastError` and `checkpoint`: an upsert conditional on `state != APPLIED`, sent once more after a
     * duplicate key ([unlessApplied]); a document it inserts also gets `kind: ONCE`, `steps: []` and `attempts: 0`,
     * and an existing one (an earlier run of the migration that failed or was interrupted) keeps its other fields.
     * False when the document is already APPLIED, which leaves it unchanged.
     */
    fun recordSuperseded(migration: Migration, writer: Writer, finishedAt: Instant): Boolean {
        val set = onceOnlyRecord(Origin.SUPERSEDED, writer, finishedAt).append("supersedes", migration.supersedes)
        return recordOnceOnly(migration.id, set)
    }

    /**
     * Records [id] APPLIED with origin MARKED and [reason], removing `lastError` and `checkpoint`: an upsert
     * conditional on `state != APPLIED`, sent once more after a duplicate key ([unlessApplied]); a document it inserts
     * also gets `kind: ONCE`, `steps: []` and `attempts: 0`, and an existing one keeps its other fields. False when the
     * document is already APPLIED, which leaves it unchanged.
     */
    fun recordMarked(id: String, reason: String, writer: Writer, finishedAt: Instant): Boolean {
        val set = onceOnlyRecord(Origin.MARKED, writer, finishedAt).append("reason", reason)
        return recordOnceOnly(id, set)
    }

    /**
     * The conditional upsert of a once-only record that did not run: [set], with `lastError` and `checkpoint` removed
     * (the migration is APPLIED, so nothing is left to retry or resume) and the fields a new document needs.
     */
    private fun recordOnceOnly(id: String, set: Document): Boolean {
        val update = Document("\$set", set)
            .append("\$unset", Document("lastError", "").append("checkpoint", ""))
            .append(
                "\$setOnInsert",
                Document("kind", StoredKind.ONCE.name).append("steps", emptyList<String>()).append("attempts", 0)
            )
        return unlessApplied { collection.updateOne(notApplied(id), update, UpdateOptions().upsert(true)) } != null
    }

    /**
     * Sends [write], an upsert filtered on [notApplied], and returns its result, or null when the document is APPLIED.
     *
     * Such an upsert hits a duplicate key in two cases. The document is APPLIED: the filter matches nothing and the
     * insert collides with it. Or the document was missing and another write inserted it first, in any state: the
     * server retries a duplicate-key upsert as an update only for a filter of equalities on `_id`, and `$ne` is not
     * one. The error does not tell the two apart, so [write] is sent once more. The document exists by then, so the
     * filter matches it unless it is APPLIED, and a second duplicate key means it is.
     */
    private fun <T> unlessApplied(write: () -> T?): T? = nullOnDuplicateKey(write) ?: nullOnDuplicateKey(write)

    /** The fields every record of a migration that did not run sets: APPLIED, [origin], the writer, the time. */
    private fun onceOnlyRecord(origin: Origin, writer: Writer, finishedAt: Instant) =
        Document("state", HistoryState.APPLIED.name)
            .append("origin", origin.name)
            .append("owner", writer.owner)
            .append("holder", writer.holder)
            .append("runId", writer.runId)
            .append("finishedAt", Date.from(finishedAt))
            .append("godwitVersion", GODWIT_VERSION)
            .append("v", DOCUMENT_FORMAT)

    /** `{_id, state: {$ne: APPLIED}}`: the conditional upserts' filter. */
    private fun notApplied(id: String): Bson =
        Document("_id", id).append("state", Document("\$ne", HistoryState.APPLIED.name))

    /** `{_id, owner, state: RUNNING}`: the fence of a run's own writes. */
    private fun ownedAndRunning(id: String, owner: String): Bson =
        Document("_id", id).append("owner", owner).append("state", HistoryState.RUNNING.name)
}

/** A history document as the planner reads it. An absent `supersedes` list reads as empty. */
internal fun Document.toHistoryRecord(): HistoryRecord = HistoryRecord(
    id = getString("_id"),
    kind = StoredKind.valueOf(getString("kind")),
    state = HistoryState.valueOf(getString("state")),
    origin = Origin.valueOf(getString("origin")),
    revision = getString("revision"),
    supersedes = getList("supersedes", String::class.java) ?: emptyList()
)

/**
 * The longest prefix of [text] whose UTF-8 encoding fits in [maxBytes], never splitting a character: a character
 * whose bytes cross the limit is left out whole.
 */
internal fun capUtf8(text: String, maxBytes: Int): String {
    val bytes = text.encodeToByteArray()
    if (bytes.size <= maxBytes) return text
    var end = maxBytes
    // A continuation byte (10xxxxxx) at the cut belongs to a character that starts before it.
    while ((bytes[end].toInt() and 0xC0) == 0x80) end--
    return bytes.decodeToString(0, end)
}
