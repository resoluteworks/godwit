package com.example.shop.migrations

import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes.ascending
import godwit.core.migration

/**
 * The unique index on `emailLower`, after 007 has filled it in. Partial, so customers without an email (no
 * `emailLower`) do not collide with each other.
 */
val customerEmailLowerIndex = migration("008-customer-email-lower-index")
    .outsideTransaction {
        collection("customers").createIndex(
            ascending("emailLower"),
            IndexOptions().unique(true).partialFilterExpression(exists("emailLower", true))
        )
    }
