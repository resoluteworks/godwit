package com.example.filestore

import com.mongodb.client.model.IndexModel
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes.ascending
import com.mongodb.client.model.Indexes.compoundIndex
import com.mongodb.client.model.Indexes.descending
import com.mongodb.kotlin.client.MongoDatabase
import godwit.core.ensureCollection
import org.bson.Document
import java.util.concurrent.TimeUnit

/** The collection the file store keeps file metadata in. */
const val FILES_COLLECTION = "files"

/**
 * Creates the file store's collection and indexes. Idempotent, so it is safe to call on every start or from a
 * migration. The library ships this function, not migrations: an app calls it from one of its own migrations.
 */
fun MongoDatabase.ensureFileStoreSchema() {
    ensureCollection(FILES_COLLECTION)
    getCollection(FILES_COLLECTION, Document::class.java).createIndexes(
        listOf(
            IndexModel(compoundIndex(ascending("ownerId"), descending("createdAt"))),
            IndexModel(ascending("storageKey"), IndexOptions().unique(true)),
            IndexModel(ascending("tempExpiresAt"), IndexOptions().expireAfter(0L, TimeUnit.SECONDS))
        )
    )
}
