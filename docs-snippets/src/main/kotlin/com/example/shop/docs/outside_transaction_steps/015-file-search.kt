package com.example.shop.docs.outside_transaction_steps

import com.example.filestore.FILES_COLLECTION
import com.mongodb.kotlin.client.MongoDatabase
import godwit.core.dropIndexIfExists
import godwit.core.ensureSearchIndex
import godwit.core.migration
import org.bson.Document

/**
 * A setup function a library can ship: idempotent, built on godwit's public DDL extensions, and called from the app's
 * own migration, which provides the lock and the history record.
 */
fun MongoDatabase.ensureFileSearch() {
    val files = getCollection(FILES_COLLECTION, Document::class.java)
    files.dropIndexIfExists("ownerId_1")
    files.ensureSearchIndex("file-search", Document("mappings", Document("dynamic", true)))
}

val fileSearch = migration("015-file-search")
    .outsideTransaction {
        database.ensureFileSearch()
    }
