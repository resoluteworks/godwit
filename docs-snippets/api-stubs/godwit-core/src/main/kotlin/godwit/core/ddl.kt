package godwit.core

import com.mongodb.client.model.CreateCollectionOptions
import com.mongodb.kotlin.client.MongoCollection
import com.mongodb.kotlin.client.MongoDatabase
import org.bson.conversions.Bson
import kotlin.time.Duration

// Idempotent DDL. Inside a migration, the OutsideTransactionScope members of the same names call these. Outside
// godwit, a library's own setup function calls them directly (fun MongoDatabase.ensureXSchema()).

/**
 * Creates collection [name] with [options] unless a collection with that name exists. Returns true when this call
 * created it. The options of an existing collection are neither compared nor changed (`collMod` changes them). A
 * concurrent create of the same collection (NamespaceExists, code 48) counts as existing. Not allowed in a transaction.
 */
fun MongoDatabase.ensureCollection(
    name: String,
    options: CreateCollectionOptions = CreateCollectionOptions()
): Boolean = TODO()

/**
 * Creates the Atlas Search index [name] on this collection with [definition] unless a search index with that name
 * exists. Returns true when this call created it. The definition of an existing index is neither compared nor changed.
 * A concurrent create of an index with the same name counts as existing.
 *
 * Search indexes build in the background. With [awaitReady] null it returns once the index is requested; otherwise it
 * polls the index until it is queryable and throws [SearchIndexNotReadyException] when [awaitReady] passes first.
 * Needs a deployment that serves Atlas Search (Atlas, or the Atlas local image: `testGodwit(atlasSearch = true)`).
 */
fun MongoCollection<*>.ensureSearchIndex(name: String, definition: Bson, awaitReady: Duration? = null): Boolean = TODO()

/**
 * Drops index [indexName] from this collection when `listIndexes` shows it. Returns true when this call dropped it,
 * false when no such index exists, on every server version (from MongoDB 8.3 `dropIndexes` itself succeeds for a
 * missing index). A concurrent drop (IndexNotFound, code 27) counts as gone. Not allowed in a transaction.
 */
fun MongoCollection<*>.dropIndexIfExists(indexName: String): Boolean = TODO()
