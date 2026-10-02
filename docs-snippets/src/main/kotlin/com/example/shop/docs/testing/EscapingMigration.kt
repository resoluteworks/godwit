package com.example.shop.docs.testing

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates.inc
import godwit.core.migration

// region: escape
// compiles, and the detector fails it: the update is missing `session`
val productPriceRiseWithoutSession = migration("014-product-price-rise")
    .inTransaction {
        collection("products").updateMany(Filters.empty(), inc("priceMinor", 100L))
    }
// endregion

// region: no-escape
/** Every price goes up by 1.00. `$inc` is not idempotent, so the update commits with the history record. */
val productPriceRise = migration("014-product-price-rise")
    .inTransaction {
        val result = collection("products").updateMany(session, Filters.empty(), inc("priceMinor", 100L))
        count("productsRepriced", result.modifiedCount)
    }
// endregion
