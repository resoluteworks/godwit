package com.example.shop.docs.batched_backfills

import com.mongodb.client.model.Filters.`in`
import com.mongodb.client.model.Filters.size
import godwit.core.migration

/** Orders with no lines were never real orders: a checkout bug created them. */
val removeEmptyOrders = migration("018-remove-empty-orders")
    .inBatches("orders", pending = size("lines", 0), batchSize = 500) { orders ->
        val result = collection("orders").deleteMany(session, `in`("_id", orders.map { it["_id"] }))
        count("ordersRemoved", result.deletedCount)
    }
