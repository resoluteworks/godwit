package com.example.shop.docs.failure_and_recovery

import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Field
import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.Updates.set
import godwit.core.migration
import org.bson.Document

/** Gives every product a URL slug: its SKU in lower case. */
val productSlugs = migration("007-product-slugs")
    .inTransaction {
        val result = collection("products").updateMany(
            session,
            exists("slug", false),
            listOf(Aggregates.set(Field("slug", Document("\$toLower", "\$sku"))))
        )
        count("productsUpdated", result.modifiedCount)
    }

/** Gives every cart the shop's currency, as first written on its branch: numbered 007 as well. */
val cartCurrencyClashing = migration("007-cart-currency")
    .inTransaction {
        val result = collection("carts").updateMany(session, exists("currency", false), set("currency", "GBP"))
        count("cartsUpdated", result.modifiedCount)
    }
