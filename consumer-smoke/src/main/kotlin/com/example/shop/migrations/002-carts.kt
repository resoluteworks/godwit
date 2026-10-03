package com.example.shop.migrations

import com.mongodb.client.model.IndexModel
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes.ascending
import godwit.core.migration
import java.util.concurrent.TimeUnit

/** Carts: one per customer, removed by the server 30 days after their last update. */
val carts = migration("002-carts", description = "One cart per customer, expiring after 30 days")
    .outsideTransaction {
        ensureCollection("carts")
        collection("carts").createIndexes(
            listOf(
                IndexModel(ascending("customerId"), IndexOptions().unique(true)),
                IndexModel(ascending("updatedAt"), IndexOptions().expireAfter(30L, TimeUnit.DAYS))
            )
        )
    }
