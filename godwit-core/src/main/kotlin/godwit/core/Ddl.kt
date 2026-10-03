package godwit.core

import com.mongodb.MongoCommandException
import com.mongodb.client.model.CreateCollectionOptions
import com.mongodb.client.model.Filters
import com.mongodb.kotlin.client.MongoCollection
import com.mongodb.kotlin.client.MongoDatabase
import godwit.core.internal.SEARCH_INDEX_POLL
import godwit.core.internal.ensureSearchIndex
import org.bson.conversions.Bson
import kotlin.time.Duration

// Idempotent DDL. Inside a migration, the OutsideTransactionScope members of the same names call these. Outside
// godwit, a library's own setup function calls them directly (fun MongoDatabase.ensureXSchema()).

/**
 * Creates collection [name] with [options] unless a collection with that name exists. Returns true when this call
 * created it. The options of an existing collection are neither compared nor changed (`collMod` changes them). A
 * concurrent create that the server refuses (NamespaceExists, code 48: before MongoDB 7.0, or from 7.0 when the options
 * differ) counts as existing; from 7.0 the server accepts a create with the same options, so two concurrent calls can
 * both return true. Not allowed in a transaction.
 */
fun MongoDatabase.ensureCollection(
    name: String,
    options: CreateCollectionOptions = CreateCollectionOptions()
): Boolean {
    if (listCollectionNames().filter(Filters.eq("name", name)).firstOrNull() != null) return false
    return try {
        createCollection(name, options)
        true
    } catch (e: MongoCommandException) {
        if (e.code != NAMESPACE_EXISTS) throw e
        false
    }
}

/**
 * Creates the Atlas Search index [name] on this collection with [definition] unless a search index with that name
 * exists. Returns true when this call created it. The definition of an existing index is neither compared nor changed.
 * A concurrent create of an index with the same name counts as existing (IndexAlreadyExists, code 68, when its
 * definition differs).
 *
 * Search indexes build in the background. With [awaitReady] null it returns once the index is requested; otherwise it
 * polls the index until it is queryable and throws [SearchIndexNotReadyException] when [awaitReady] passes first.
 * Needs a deployment that serves Atlas Search (Atlas, or the Atlas local image: `testGodwit(atlasSearch = true)`).
 */
fun MongoCollection<*>.ensureSearchIndex(name: String, definition: Bson, awaitReady: Duration? = null): Boolean =
    ensureSearchIndex(this, name, definition, awaitReady, SEARCH_INDEX_POLL) {}

/**
 * Drops index [indexName] from this collection when `listIndexes` shows it. Returns true when this call dropped it,
 * false when no such index exists, on every server version (from MongoDB 8.3 `dropIndexes` itself succeeds for a
 * missing index). A concurrent drop that the server refuses (IndexNotFound, code 27: before MongoDB 8.3) counts as
 * gone; from 8.3 the server accepts the drop of a missing index, so two concurrent calls can both return true. Not
 * allowed in a transaction.
 */
fun MongoCollection<*>.dropIndexIfExists(indexName: String): Boolean {
    if (listIndexes().toList().none { it.getString("name") == indexName }) return false
    return try {
        dropIndex(indexName)
        true
    } catch (e: MongoCommandException) {
        if (e.code != INDEX_NOT_FOUND) throw e
        false
    }
}

/** NamespaceExists: a collection of that name exists (from MongoDB 7.0, with other options). */
private const val NAMESPACE_EXISTS = 48

/** IndexNotFound: no index of that name, before MongoDB 8.3. */
private const val INDEX_NOT_FOUND = 27
