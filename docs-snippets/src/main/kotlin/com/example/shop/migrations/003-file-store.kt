package com.example.shop.migrations

import com.example.filestore.ensureFileStoreSchema
import godwit.core.migration

/** The file store library's collection and indexes, through the library's own idempotent setup function. */
val fileStore = migration("003-file-store")
    .outsideTransaction {
        database.ensureFileStoreSchema()
    }
