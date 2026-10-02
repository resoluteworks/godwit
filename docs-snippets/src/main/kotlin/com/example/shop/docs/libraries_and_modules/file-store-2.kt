package com.example.shop.docs.libraries_and_modules

// File store 2.0 as its library code would read, and the shop migration that adopts it.

import com.example.filestore.FILES_COLLECTION
import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.Filters.`in`
import com.mongodb.client.model.IndexModel
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes.ascending
import com.mongodb.client.model.Indexes.compoundIndex
import com.mongodb.client.model.Indexes.descending
import com.mongodb.client.model.Updates.set
import com.mongodb.kotlin.client.ClientSession
import com.mongodb.kotlin.client.MongoDatabase
import godwit.core.ensureCollection
import godwit.core.migration
import godwit.core.repeatable
import org.bson.Document
import org.bson.conversions.Bson
import java.util.concurrent.TimeUnit

/**
 * Creates the file store's collection and indexes. Idempotent, so it is safe to call on every start or from a
 * migration. The library ships this function, not migrations: an app calls it from one of its own migrations.
 */
fun MongoDatabase.ensureFileStoreSchema() {
    ensureCollection(FILES_COLLECTION)
    getCollection(FILES_COLLECTION, Document::class.java).createIndexes(
        listOf(
            IndexModel(compoundIndex(ascending("ownerId"), descending("createdAt"))),
            IndexModel(compoundIndex(ascending("ownerId"), ascending("contentType"))),
            IndexModel(ascending("storageKey"), IndexOptions().unique(true)),
            IndexModel(ascending("tempExpiresAt"), IndexOptions().expireAfter(0L, TimeUnit.SECONDS))
        )
    )
}

/** The content type of a file stored without one. */
const val DEFAULT_CONTENT_TYPE = "application/octet-stream"

/**
 * Gives every file stored before 2.0 the default content type and returns how many it changed. Idempotent: it only
 * touches files without a content type. Runs in the caller's transaction through [session].
 */
fun MongoDatabase.setDefaultContentTypes(session: ClientSession): Long =
    getCollection(FILES_COLLECTION, Document::class.java)
        .updateMany(session, exists("contentType", false), set("contentType", DEFAULT_CONTENT_TYPE))
        .modifiedCount

/** The files [setDefaultContentType] has not handled yet, for an app that pages through them. */
val filesWithoutContentType: Bson = exists("contentType", false)

/** Gives [files] the default content type, in the caller's transaction. */
fun MongoDatabase.setDefaultContentType(session: ClientSession, files: List<Document>) {
    getCollection(FILES_COLLECTION, Document::class.java)
        .updateMany(session, `in`("_id", files.map { it["_id"] }), set("contentType", DEFAULT_CONTENT_TYPE))
}

/** Changes whenever ensureFileStoreSchema() changes. Published by the library. */
const val FILE_STORE_SCHEMA_VERSION = "2"

/** File store 2.0: its new index first, then the content type of the files stored before it. */
val fileStoreContentType = migration("023-file-store-content-type")
    .outsideTransaction {
        database.ensureFileStoreSchema()
    }
    .inTransaction {
        count("filesUpdated", database.setDefaultContentTypes(session))
    }

/** The same change for a files collection too large for one transaction. */
val fileStoreContentTypeInBatches = migration("023-file-store-content-type")
    .outsideTransaction {
        database.ensureFileStoreSchema()
    }
    .inBatches(FILES_COLLECTION, pending = filesWithoutContentType, batchSize = 1000) { files ->
        database.setDefaultContentType(session, files)
        count("filesUpdated", files.size)
    }

/** Re-applies the file store schema whenever the library's schema version changes. */
val fileStoreSchema = repeatable("file-store-schema", revision = FILE_STORE_SCHEMA_VERSION)
    .outsideTransaction {
        database.ensureFileStoreSchema()
    }
