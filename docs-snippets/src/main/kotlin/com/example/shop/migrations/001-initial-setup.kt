package com.example.shop.migrations

import com.mongodb.client.model.IndexModel
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes.ascending
import com.mongodb.client.model.Indexes.compoundIndex
import com.mongodb.client.model.Indexes.descending
import godwit.core.Migration
import godwit.core.migration
import org.bson.Document
import kotlin.time.Duration

/**
 * Customers, orders and products: the collections, their indexes and the product search index. Every call is
 * idempotent, so a retry after a crash converges. [searchIndexWait] is how long to wait for the search index to
 * become queryable; null returns as soon as it is requested.
 */
fun initialSetup(searchIndexWait: Duration?): Migration = migration("001-initial-setup")
    .outsideTransaction {
        ensureCollection("customers")
        ensureCollection("orders")
        ensureCollection("products")

        collection("customers").createIndex(ascending("email"), IndexOptions().unique(true))
        collection("orders").createIndexes(
            listOf(
                IndexModel(compoundIndex(ascending("customerId"), descending("placedAt"))),
                IndexModel(ascending("status"))
            )
        )
        collection("products").createIndex(ascending("sku"), IndexOptions().unique(true))
        ensureSearchIndex(
            "products",
            name = "product-search",
            definition = Document("mappings", Document("dynamic", true)),
            awaitReady = searchIndexWait
        )
    }
