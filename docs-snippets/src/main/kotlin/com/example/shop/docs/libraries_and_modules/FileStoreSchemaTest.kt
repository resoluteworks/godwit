package com.example.shop.docs.libraries_and_modules

// The file store library's own test. In the library's repository it lives in src/test/kotlin.

import com.example.filestore.FILES_COLLECTION
import godwit.test.testGodwit
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.bson.Document

class FileStoreSchemaTest : StringSpec({
    "ensureFileStoreSchema can run any number of times" {
        val database = testGodwit().database
        database.ensureFileStoreSchema()
        database.ensureFileStoreSchema()

        val indexes = database.getCollection(FILES_COLLECTION, Document::class.java).listIndexes()
            .map { it.getString("name") }
            .toList()
        indexes shouldContainExactlyInAnyOrder listOf(
            "_id_",
            "ownerId_1_createdAt_-1",
            "ownerId_1_contentType_1",
            "storageKey_1",
            "tempExpiresAt_1"
        )
    }
})
