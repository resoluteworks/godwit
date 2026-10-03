package godwit.core.internal

import com.mongodb.MongoClientSettings
import com.mongodb.ReadConcern
import com.mongodb.kotlin.client.ClientSession
import com.mongodb.kotlin.client.MongoCollection
import com.mongodb.kotlin.client.MongoDatabase
import godwit.core.Migration
import godwit.core.TransactionScope
import org.bson.BsonArray
import org.bson.BsonDocument
import org.bson.BsonDouble
import org.bson.BsonInt32
import org.bson.BsonString
import org.bson.BsonType
import org.bson.BsonValue
import org.bson.Document
import org.bson.RawBsonDocument
import org.bson.codecs.Codec
import org.bson.codecs.configuration.CodecRegistry
import org.bson.json.JsonMode
import org.bson.json.JsonWriterSettings

/** A BSON type an `_id` can have: the `$type` alias that selects it, and its class in `_id` paging. */
private class IdType(val alias: String, val typeClass: String)

/**
 * Every BSON type an `_id` can have (MongoDB refuses an array, a regular expression and undefined as `_id`), with the
 * `$type` alias that selects it and its class in `_id` paging. `$gt` on `_id` and the `_id` sort compare the values of
 * one class with each other and never with those of another: int, long, double and decimal are the class `number`,
 * which the alias `number` selects as a whole, and a symbol compares as a string, so string and symbol are the class
 * `string`. Every other type is a class of its own, named by its alias.
 */
private val ID_TYPES: Map<BsonType, IdType> = mapOf(
    BsonType.DOUBLE to IdType("number", "number"),
    BsonType.INT32 to IdType("number", "number"),
    BsonType.INT64 to IdType("number", "number"),
    BsonType.DECIMAL128 to IdType("number", "number"),
    BsonType.STRING to IdType("string", "string"),
    BsonType.SYMBOL to IdType("symbol", "string"),
    BsonType.DOCUMENT to IdType("object", "object"),
    BsonType.BINARY to IdType("binData", "binData"),
    BsonType.OBJECT_ID to IdType("objectId", "objectId"),
    BsonType.BOOLEAN to IdType("bool", "bool"),
    BsonType.DATE_TIME to IdType("date", "date"),
    BsonType.NULL to IdType("null", "null"),
    BsonType.DB_POINTER to IdType("dbPointer", "dbPointer"),
    BsonType.JAVASCRIPT to IdType("javascript", "javascript"),
    BsonType.JAVASCRIPT_WITH_SCOPE to IdType("javascriptWithScope", "javascriptWithScope"),
    BsonType.TIMESTAMP to IdType("timestamp", "timestamp"),
    BsonType.MIN_KEY to IdType("minKey", "minKey"),
    BsonType.MAX_KEY to IdType("maxKey", "maxKey")
)

/** The classes an `_id` can have. */
internal val ID_TYPE_CLASSES: Set<String> = ID_TYPES.values.map { it.typeClass }.toSet()

/** The class of [value] in `_id` paging: `objectId`, `string` for a string or a symbol, `number` for any number. */
internal fun typeClass(value: BsonValue): String = ID_TYPES.getValue(value.bsonType).typeClass

/**
 * `{_id: {$type: [...]}}`: the `_id`s whose class is not [type], by the aliases of every type an `_id` can have outside
 * that class. The `_id` index answers it from bounds on those types alone, which hold no key of [type]'s class, so it
 * reads no document of that class. A symbol's bounds are those of every string, which is why string and symbol are one
 * class and leave the list together.
 */
internal fun otherIdTypes(type: String): BsonDocument {
    val aliases = ID_TYPES.values.filter { it.typeClass != type }.map { it.alias }.distinct()
    return BsonDocument("_id", BsonDocument("\$type", BsonArray(aliases.map(::BsonString))))
}

/** Whether [value] is a NaN, double or decimal: the number that sorts before every other and that `$gt` never passes. */
internal fun isNaN(value: BsonValue): Boolean = when (value.bsonType) {
    BsonType.DOUBLE -> value.asDouble().value.isNaN()
    BsonType.DECIMAL128 -> value.asDecimal128().value.isNaN
    else -> false
}

private val RELAXED_JSON: JsonWriterSettings = JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).build()

/**
 * How "Committed batch" prints a `lastId`: an ObjectId as its hex string, a string as it is, any other value as its
 * relaxed Extended JSON, in which an int, a long or a finite double prints as the number (`499`, `2.5`) and a decimal
 * as a document (`{"$numberDecimal": "3"}`).
 */
internal fun loggable(lastId: BsonValue): Any = when (lastId.bsonType) {
    BsonType.OBJECT_ID -> lastId.asObjectId().value.toHexString()
    BsonType.STRING -> lastId.asString().value
    else -> BsonDocument("v", lastId).toJson(RELAXED_JSON).removePrefix("{\"v\": ").removeSuffix("}")
}

/**
 * How far an `inBatches` step got: the last `_id` of the last committed page, with its BSON type, the pages committed
 * and the counters of those pages. The outside step's counters are not in it: the outside step runs again on every
 * attempt and counts again.
 */
internal data class Checkpoint(val lastId: BsonValue, val batches: Int, val counts: Map<String, Long>)

/**
 * The `checkpoint` of a history document that [codecs] decoded; null when it has none. An absent `counts` reads as
 * empty.
 *
 * `lastId` is read back as BSON by encoding it with [codecs] again, which restores its BSON type: the history
 * collection decodes with the driver's default codecs, but the driver applies the client's `uuidRepresentation` to
 * them, so a binary `_id` of a UUID subtype comes back as a `java.util.UUID` that only the same representation encodes
 * to the same bytes.
 */
internal fun Document.checkpoint(codecs: CodecRegistry): Checkpoint? {
    val stored = get("checkpoint", Document::class.java) ?: return null
    val bson = stored.toBsonDocument(BsonDocument::class.java, codecs)
    return Checkpoint(bson.getValue("lastId"), bson.getNumber("batches").intValue(), stored.counts())
}

/** `{_id: 1}`: the page's sort and the projection of the other-type check. */
private val BY_ID = BsonDocument("_id", BsonInt32(1))

/** What one page's transaction committed. */
private sealed interface PageEnd

/** A page of `batchSize` documents, which committed [checkpoint]. [type] is the run's `_id` class. */
private class Next(val checkpoint: Checkpoint, val type: String?) : PageEnd

/**
 * The last page, read before the check for other `_id` classes: its transaction committed nothing, and the page runs
 * again once the check has passed. [type] is the run's `_id` class.
 */
private class Unchecked(val type: String) : PageEnd

/**
 * The last page, which committed the APPLIED record with [counts]. [batches] counts every committed page; [lastId] is
 * the page's last `_id`, null when the page found nothing.
 */
private class Last(val counts: Map<String, Long>, val batches: Int, val lastId: BsonValue?) : PageEnd

/** Where a page starts: after [from], with the run's `_id` class [runType] when it is known. */
private class PageStart(
    val from: Checkpoint?,
    val runType: String?,
    /** Whether the check for other `_id` classes passed just before this page's transaction. */
    val otherTypesChecked: Boolean,
    /** The retries of the transactions this run ran before this page's. */
    val earlierRetries: Int
)

/**
 * One run of an `inBatches` step: the pages of architecture.md's "Checkpoint writes". Each page is one transaction
 * ([Transaction], from [newTransaction]) on the run's session that, after `checkLock()`, reads up to `batchSize`
 * documents of the step's collection that match `pending` and whose `_id` is greater than the checkpoint's `lastId`,
 * in `_id` order; calls the step with them unless there are none; calls `checkLock()`; and writes the next checkpoint,
 * fenced on the run's owner token. The page with fewer than `batchSize` documents, an empty one included, is the last:
 * its transaction writes the APPLIED record instead, which removes the checkpoint.
 *
 * The loop's checkpoint and `_id` class advance only after `withTransaction` returns: the body only reads them, so a
 * body the driver runs again after a transient error on the commit reads the same page again instead of the next one.
 *
 * `$gt` on `_id` matches values of the class of the checkpoint's `lastId` only ([typeClass]), so a collection whose
 * `pending` documents have `_id`s of two classes would be partly skipped. Every page's `_id`s must have the run's class
 * (the checkpoint's on a resumed run, otherwise the first page's), and before the last page commits, no document
 * matching `pending` may have an `_id` of another class: either check throws [IllegalStateException] naming both
 * classes, which fails the migration in IN_BATCHES with the committed pages and their checkpoint kept. The second
 * check runs outside any transaction, with majority read concern, so its scan of the documents of other classes that do
 * not match `pending` has no transaction lifetime to outlast: the last page's first transaction reads the page and
 * commits without calling the step ([Unchecked]), the check runs, and the page runs again in a new transaction, which
 * calls the step and records APPLIED.
 *
 * Pages are read as raw BSON, so the `_id` classes and the checkpoint's `lastId` keep their BSON types whatever codecs
 * the app's client has; `pending` is rendered and the step's documents are decoded with the app's codec registry, as
 * `collection(...)` in the step would.
 */
internal class Pages(
    private val migration: Migration,
    private val step: InBatchesStep,
    private val database: MongoDatabase,
    private val store: HistoryStore,
    private val lock: HeldLock,
    /** A new transaction for each page. */
    private val newTransaction: () -> Transaction,
    /** The facts the APPLIED record of the last page records, from the counters and this run's transaction retries. */
    private val appliedRun: (counts: Map<String, Long>, transactionRetries: Int) -> AppliedRun
) {
    private val id = migration.id

    /** `pending` as the app's client renders it. */
    private val pending: BsonDocument = step.pending.toBsonDocument(BsonDocument::class.java, database.codecRegistry)

    private val raw: MongoCollection<RawBsonDocument> =
        database.getCollection(step.collection, RawBsonDocument::class.java)
            .withCodecRegistry(MongoClientSettings.getDefaultCodecRegistry())

    /** [raw] for the check for other `_id` classes, which runs outside any transaction. */
    private val majority: MongoCollection<RawBsonDocument> = raw.withReadConcern(ReadConcern.MAJORITY)

    private val documents: Codec<Document> = database.codecRegistry.get(Document::class.java)

    /** What the last run of a page body that returned the last page returned. */
    private lateinit var lastPage: Last

    /**
     * Runs the pages on [session], after [resumeFrom] (the checkpoint of the marker's document, null on a first run),
     * and returns what the APPLIED record committed: [outsideCounts] added to the counters of every page, the driver's
     * retries over every page's transaction in this run, and the pages committed over every attempt. Logs "Committed
     * batch" after each page that held documents.
     */
    fun run(session: ClientSession, resumeFrom: Checkpoint?, outsideCounts: Map<String, Long>): Applied {
        var checkpoint = resumeFrom
        var type = resumeFrom?.let { typeClass(it.lastId) }
        var checked = false
        var retries = 0
        while (true) {
            val transaction = newTransaction()
            val start = PageStart(checkpoint, type, checked, retries)
            val end = transaction.run(session) { attempt -> page(session, attempt, start, outsideCounts) }
            retries += transaction.retries
            checked = false
            when (end) {
                is Next -> {
                    Log.committedBatch(id, end.checkpoint.batches, loggable(end.checkpoint.lastId))
                    checkpoint = end.checkpoint
                    type = end.type
                }

                is Unchecked -> {
                    requireNoOtherType(session, end.type)
                    type = end.type
                    checked = true
                }

                is Last -> return committed(end, end.counts, retries)
            }
        }
    }

    /**
     * What the run reports when the commit of its last page applied although the driver threw, and the APPLIED
     * record it then finds is its own: the record's [counts] and [transactionRetries], and the pages the last run of
     * the last page's body counted. That run's transaction is the one that committed: the driver runs a body again only
     * after an error that means its transaction did not commit.
     */
    fun appliedDespiteError(counts: Map<String, Long>, transactionRetries: Int): Applied =
        committed(lastPage, counts, transactionRetries)

    /** Logs "Committed batch" for the last page when it held documents, and reports the step applied. */
    private fun committed(last: Last, counts: Map<String, Long>, transactionRetries: Int): Applied {
        last.lastId?.let { Log.committedBatch(id, last.batches, loggable(it)) }
        return Applied(counts, transactionRetries, last.batches)
    }

    /**
     * One run of a page body: run [attempt] of the page at [start]. A last page whose `_id` class is known returns
     * [Unchecked] before the step unless the check for other classes has just passed.
     */
    private fun page(
        session: ClientSession,
        attempt: Int,
        start: PageStart,
        outsideCounts: Map<String, Long>
    ): PageEnd {
        val from = start.from
        val context = StepContext { lock.checkLock(id) }
        lock.checkLock(id)
        val page = read(session, from)
        val type = pageType(page, start.runType)
        val last = page.size < step.batchSize
        if (last && type != null && !start.otherTypesChecked) return Unchecked(type)
        if (page.isNotEmpty()) {
            step.body(TransactionScope(id, database, session, attempt, context), page.map { it.decode(documents) })
        }
        lock.checkLock(id)
        val committed = if (from == null) 0 else from.batches
        val batches = if (page.isEmpty()) committed else committed + 1
        val counts = addCounts(from?.counts.orEmpty(), context.counts)
        if (last) {
            val total = addCounts(outsideCounts, counts)
            store.recordApplied(migration, lock.owner, appliedRun(total, start.earlierRetries + attempt - 1), session)
            return Last(total, batches, page.lastOrNull()?.id).also { lastPage = it }
        }
        val next = Checkpoint(page.last().id, batches, counts)
        store.writeCheckpoint(id, lock.owner, next, session)
        return Next(next, type)
    }

    /** Up to `batchSize` documents that match `pending` with an `_id` greater than [after]'s `lastId`, by `_id`. */
    private fun read(session: ClientSession, after: Checkpoint?): List<RawBsonDocument> {
        val filter = if (after == null) pending else and(pending, idAbove(after.lastId))
        return raw.find(session, filter).sort(BY_ID).limit(step.batchSize).batchSize(step.batchSize).toList()
    }

    /**
     * The run's `_id` class once [page] is read: [runType] when it is known, otherwise the class of the page's first
     * `_id`, or null for an empty page. Throws when an `_id` of the page has another class.
     */
    private fun pageType(page: List<RawBsonDocument>, runType: String?): String? {
        if (page.isEmpty()) return runType
        val type = runType ?: typeClass(page.first().id)
        page.firstOrNull { typeClass(it.id) != type }?.let { throw mixedTypes(type, typeClass(it.id)) }
        return type
    }

    /**
     * Throws when a document matching `pending` has an `_id` whose class is not [type]: one `find` on [session], outside
     * any transaction, whose `_id` filter ([otherIdTypes]) reads no document of the run's class.
     */
    private fun requireNoOtherType(session: ClientSession, type: String) {
        val other = majority.find(session, and(pending, otherIdTypes(type))).projection(BY_ID).limit(1).firstOrNull()
            ?: return
        throw mixedTypes(type, typeClass(other.id))
    }

    private fun mixedTypes(runType: String, other: String) = IllegalStateException(
        "The documents of ${step.collection} that match pending have _ids of two types, $runType and $other. " +
            "inBatches pages by _id with \$gt, which compares values of one BSON type (all numeric types count as " +
            "one), so it would skip the documents of the other type. Select one _id type per migration with " +
            "Filters.type(\"_id\", ...) in pending; a migration that has a checkpoint keeps the type of its lastId."
    )
}

/**
 * The `_id`s after [lastId] in `_id` order, of its class only: `{_id: {$gt: lastId}}`. NaN sorts before every other
 * number, but `$gt: NaN` matches nothing, so after a NaN it is `{_id: {$gte: -Infinity}}`, every number but NaN; a
 * collection holds one NaN `_id` at most, because the unique `_id` index counts every NaN as equal.
 */
private fun idAbove(lastId: BsonValue): BsonDocument = if (isNaN(lastId)) {
    BsonDocument("_id", BsonDocument("\$gte", BsonDouble(Double.NEGATIVE_INFINITY)))
} else {
    BsonDocument("_id", BsonDocument("\$gt", lastId))
}

/** `{$and: [first, second]}`. */
private fun and(first: BsonDocument, second: BsonDocument) = BsonDocument("\$and", BsonArray(listOf(first, second)))

private val RawBsonDocument.id: BsonValue get() = getValue("_id")
