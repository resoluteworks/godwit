package com.example.shop.docs.batched_backfills

// The alternatives the doc compares inBatches with. Both compile; neither is how the shop writes 006.

import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.UpdateOneModel
import com.mongodb.client.model.Updates.set
import godwit.core.migration
import org.bson.Document

private val authorLoop = migration("author-loop")
    .outsideTransaction {
        val orders = collection("orders")
        while (true) {
            checkLock()
            val page = orders.find(exists("totalMinor", false)).limit(500).toList()
            if (page.isEmpty()) break
            orders.bulkWrite(page.map { UpdateOneModel<Document>(eq("_id", it["_id"]), set("totalMinor", totalOf(it))) })
            count("ordersUpdated", page.size)
        }
    }

private fun totalOf(order: Document): Long =
    order.getList("lines", Document::class.java).orEmpty().sumOf { line ->
        line.getInteger("quantity").toLong() * line.getLong("unitPriceMinor")
    }

private val serverSideTotals = migration("server-side-totals")
    .outsideTransaction {
        val lineTotals = Document("\$map", Document("input", "\$lines").append("in", Document("\$multiply", listOf("\$\$this.quantity", "\$\$this.unitPriceMinor"))))
        val result = collection("orders").updateMany(
            exists("totalMinor", false),
            listOf(Document("\$set", Document("totalMinor", Document("\$sum", lineTotals))))
        )
        count("ordersUpdated", result.modifiedCount)
    }
