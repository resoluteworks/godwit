package com.example.shop.migrations

import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.Indexes.ascending
import com.mongodb.client.model.Indexes.compoundIndex
import com.mongodb.client.model.Indexes.descending
import com.mongodb.client.model.UpdateOneModel
import com.mongodb.client.model.Updates.set
import godwit.core.migration
import org.bson.Document

/**
 * Stores `totalMinor` on every order, computed from its lines. Too many orders for one transaction, so each page of
 * 500 is its own transaction that also commits the last `_id` it handled; a restart resumes after it. The outside
 * step adds the index that the "largest orders" query uses.
 */
val orderTotals = migration("006-order-totals")
    .outsideTransaction {
        collection("orders").createIndex(compoundIndex(ascending("customerId"), descending("totalMinor")))
    }
    .inBatches("orders", pending = exists("totalMinor", false), batchSize = 500) { orders ->
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
