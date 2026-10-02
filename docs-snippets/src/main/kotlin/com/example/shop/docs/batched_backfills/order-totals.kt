package com.example.shop.docs.batched_backfills

import com.mongodb.client.model.Filters.and
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.Filters.type
import com.mongodb.client.model.UpdateOneModel
import com.mongodb.client.model.Updates.set
import godwit.core.TransactionScope
import godwit.core.migration
import org.bson.BsonType
import org.bson.Document

// In the shop's code this extension sits in 006-order-totals.kt, next to the private totalOf it calls.

/** The page step of 006, as a scope extension so later migrations reuse it. */
fun TransactionScope.writeOrderTotals(orders: List<Document>) {
    collection("orders").bulkWrite(
        session,
        orders.map { order -> UpdateOneModel<Document>(eq("_id", order["_id"]), set("totalMinor", totalOf(order))) }
    )
    count("ordersUpdated", orders.size)
}

private fun totalOf(order: Document): Long =
    order.getList("lines", Document::class.java).orEmpty().sumOf { line ->
        line.getInteger("quantity").toLong() * line.getLong("unitPriceMinor")
    }

private val orderTotalsStringsOnly = migration("006-order-totals")
    .inBatches("orders", pending = and(exists("totalMinor", false), type("_id", BsonType.STRING)), batchSize = 500) { orders ->
        writeOrderTotals(orders)
    }

/** The orders with ObjectId `_id`s, which 006, narrowed to the type of its checkpoint, no longer selects. */
val objectIdOrderTotals = migration("017-object-id-order-totals")
    .inBatches("orders", pending = and(exists("totalMinor", false), type("_id", BsonType.OBJECT_ID)), batchSize = 500) { orders ->
        writeOrderTotals(orders)
    }

/** Totals for the orders that pods still running the previous release wrote while 006 ran. */
val orderTotalsCatchUp = migration("019-order-totals-catch-up")
    .inBatches("orders", pending = exists("totalMinor", false), batchSize = 500) { orders ->
        writeOrderTotals(orders)
    }
