package com.example.shop.docs.failure_and_recovery

import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Field
import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes.ascending
import godwit.core.migration
import org.bson.Document

/** Every customer in one transaction: fine on a test database, past the 60 s transaction lifetime on production. */
val customerEmailLowerInOneTransaction = migration("007-customer-email-lower")
    .inTransaction {
        val result = collection("customers").updateMany(
            session,
            exists("emailLower", false),
            listOf(Aggregates.set(Field("emailLower", Document("\$toLower", "\$email"))))
        )
        count("customersUpdated", result.modifiedCount)
    }

/**
 * No atomicity needed: one server-side update, idempotent through its filter, safe to run again after a crash.
 * Correct only when every email is ASCII, because the server's `$toLower` lowercases ASCII letters only.
 */
val customerEmailLowerOutside = migration("007-customer-email-lower")
    .outsideTransaction {
        val result = collection("customers").updateMany(
            exists("emailLower", false),
            listOf(Aggregates.set(Field("emailLower", Document("\$toLower", "\$email"))))
        )
        count("customersUpdated", result.modifiedCount)
    }

/** Compiles, and fails at runtime: an index on an existing collection cannot be built inside a transaction. */
val customerEmailLowerIndexInTransaction = migration("008-customer-email-lower-index")
    .inTransaction {
        collection("customers").createIndex(
            session,
            ascending("emailLower"),
            IndexOptions().unique(true).partialFilterExpression(exists("emailLower", true))
        )
    }
