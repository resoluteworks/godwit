package godwit.core.internal

import com.mongodb.MongoCommandException
import com.mongodb.kotlin.client.MongoCollection
import godwit.core.SearchIndexNotReadyException
import org.bson.Document
import org.bson.conversions.Bson
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

/** How often `ensureSearchIndex` asks whether a search index is queryable while it waits for one. */
internal val SEARCH_INDEX_POLL = 1.seconds

/**
 * IndexAlreadyExists: the server refuses a search index whose name exists with another definition, which is how a
 * create that raced another create of the same name ends.
 */
private const val INDEX_ALREADY_EXISTS = 68

/**
 * Creates the search index [name] on [collection] with [definition] unless a search index with that name exists, and
 * with [awaitReady], polls it every [poll] until it is queryable, calling [checkLock] between polls; throws
 * [SearchIndexNotReadyException] when [awaitReady] passes first. Returns true when this call created it.
 */
internal fun ensureSearchIndex(
    collection: MongoCollection<*>,
    name: String,
    definition: Bson,
    awaitReady: Duration?,
    poll: Duration,
    checkLock: () -> Unit
): Boolean {
    val created = searchIndexes(collection, name).isEmpty() && createSearchIndex(collection, name, definition)
    if (awaitReady != null) awaitQueryable(collection, name, awaitReady, poll, checkLock)
    return created
}

/** The search indexes named [name], as `$listSearchIndexes` reports them: one, or none. */
private fun searchIndexes(collection: MongoCollection<*>, name: String): List<Document> =
    collection.listSearchIndexes().name(name).toList()

/** False when a search index of that name appeared since it was looked for: a concurrent create. */
private fun createSearchIndex(collection: MongoCollection<*>, name: String, definition: Bson): Boolean = try {
    collection.createSearchIndex(name, definition)
    true
} catch (e: MongoCommandException) {
    if (e.code != INDEX_ALREADY_EXISTS) throw e
    false
}

private fun awaitQueryable(
    collection: MongoCollection<*>,
    name: String,
    awaitReady: Duration,
    poll: Duration,
    checkLock: () -> Unit
) {
    val deadline = TimeSource.Monotonic.markNow() + awaitReady
    while (searchIndexes(collection, name).none { it.getBoolean("queryable") == true }) {
        val left = -deadline.elapsedNow()
        if (!left.isPositive()) {
            throw SearchIndexNotReadyException(collection.namespace.collectionName, name, awaitReady)
        }
        checkLock()
        Thread.sleep(minOf(poll, left).toJavaDuration())
    }
}
