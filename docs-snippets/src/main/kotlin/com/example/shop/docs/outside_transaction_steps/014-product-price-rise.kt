package com.example.shop.docs.outside_transaction_steps

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates.inc
import godwit.core.migration

/** Every price goes up by 1.00. `$inc` is not idempotent, so the update commits with the history record. */
val productPriceRise = migration("014-product-price-rise")
    .inTransaction {
        val result = collection("products").updateMany(session, Filters.empty(), inc("priceMinor", 100L))
        count("productsRepriced", result.modifiedCount)
    }
