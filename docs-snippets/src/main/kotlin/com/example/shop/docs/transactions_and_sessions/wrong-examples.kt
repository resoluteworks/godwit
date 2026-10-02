package com.example.shop.docs.transactions_and_sessions

// Examples of what not to do. Each compiles, and each is wrong at runtime; the doc explains why.

import com.example.shop.services.PaymentGateway
import com.mongodb.client.model.Filters.and
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.Filters.ne
import com.mongodb.client.model.Indexes.ascending
import com.mongodb.client.model.Indexes.compoundIndex
import com.mongodb.client.model.Indexes.descending
import com.mongodb.client.model.Updates.set
import godwit.core.Migration
import godwit.core.migration
import org.bson.Document
import org.bson.types.ObjectId

/** Wrong: an HTTP call per order inside the transaction. */
fun orderPaymentStatusInsideTransaction(gateway: PaymentGateway): Migration =
    migration("009-order-payment-status")
        .inTransaction {
            val orders = collection("orders")
            orders.find(session, and(ne("paymentId", null), exists("paymentStatus", false))).forEach { order ->
                val status = gateway.paymentStatus(order.getString("paymentId")) // HTTP, repeated on every retry
                orders.updateOne(session, eq("_id", order["_id"]), set("paymentStatus", status.name))
            }
        }

/** Wrong: the update does not pass the session, so it runs outside the transaction. */
fun orderPaymentStatusEscaping(gateway: PaymentGateway): Migration = migration("009-order-payment-status")
    .outsideTransaction {
        collection("orders")
            .find(and(ne("paymentId", null), exists("paymentStatus", false)))
            .map { order ->
                checkLock()
                order.getObjectId("_id") to gateway.paymentStatus(order.getString("paymentId"))
            }
            .toList()
            .toMap()
    }
    .inTransaction { statuses ->
        val orders = collection("orders")
        statuses.forEach { (orderId, status) ->
            orders.updateOne(eq("_id", orderId), set("paymentStatus", status.name)) // no session
        }
        count("ordersUpdated", statuses.size)
    }

/** Wrong: state outside the transaction survives a driver retry. */
fun orderPaymentStatusAudited(gateway: PaymentGateway, audit: MutableList<ObjectId>): Migration =
    migration("009-order-payment-status")
        .outsideTransaction {
            collection("orders")
                .find(and(ne("paymentId", null), exists("paymentStatus", false)))
                .map { order -> order.getObjectId("_id") to gateway.paymentStatus(order.getString("paymentId")) }
                .toList()
                .toMap()
        }
        .inTransaction { statuses ->
            statuses.forEach { (orderId, status) ->
                collection("orders").updateOne(session, eq("_id", orderId), set("paymentStatus", status.name))
                audit += orderId // a retry adds every id again
            }
        }

private val orderTotalsInOneTransaction = migration("order-totals-in-one-transaction")
    .inTransaction {
        val orders = collection("orders")
        orders.find(session, exists("totalMinor", false)).forEach { order ->
            orders.updateOne(session, eq("_id", order["_id"]), set("totalMinor", totalOf(order)))
        }
    }

private val orderTotalsIndexInTransaction = migration("order-totals-index-in-transaction")
    .inTransaction {
        collection("orders").createIndex(session, compoundIndex(ascending("customerId"), descending("totalMinor")))
    }

private fun totalOf(order: Document): Long =
    order.getList("lines", Document::class.java).orEmpty().sumOf { line ->
        line.getInteger("quantity").toLong() * line.getLong("unitPriceMinor")
    }
