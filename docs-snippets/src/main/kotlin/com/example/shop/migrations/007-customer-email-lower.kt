package com.example.shop.migrations

import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.UpdateOneModel
import com.mongodb.client.model.Updates.set
import godwit.core.migration
import org.bson.Document
import java.util.Locale

/**
 * Stores `emailLower` on every customer, for case-insensitive sign-in. Computed in Kotlin because the server's
 * `$toLower` is defined for ASCII only. Customers without an email are counted and left alone; the `_id` paging moves
 * past them, so they do not stop the run.
 */
val customerEmailLower = migration("007-customer-email-lower")
    .inBatches("customers", pending = exists("emailLower", false), batchSize = 1000) { customers ->
        val updates = customers.mapNotNull { customer ->
            val email = customer.getString("email") ?: return@mapNotNull null
            UpdateOneModel<Document>(eq("_id", customer["_id"]), set("emailLower", email.trim().lowercase(Locale.ROOT)))
        }
        if (updates.isNotEmpty()) collection("customers").bulkWrite(session, updates)
        count("customersUpdated", updates.size)
        count("customersWithoutEmail", customers.size - updates.size)
    }
