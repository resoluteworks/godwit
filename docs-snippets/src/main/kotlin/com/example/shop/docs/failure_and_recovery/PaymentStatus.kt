package com.example.shop.docs.failure_and_recovery

import com.example.shop.services.PaymentGateway
import com.mongodb.client.model.Filters.and
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.Filters.ne
import com.mongodb.client.model.Updates.set
import godwit.core.Migration
import godwit.core.migration

/**
 * Fails when an order gets a payment between the outside step and the transaction: the transaction reads it again,
 * and its status is not in [statuses].
 */
fun orderPaymentStatusFragile(gateway: PaymentGateway): Migration = migration("009-order-payment-status")
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
        orders.find(session, and(ne("paymentId", null), exists("paymentStatus", false))).toList().forEach { order ->
            val status = statuses.getValue(order.getObjectId("_id"))
            orders.updateOne(session, eq("_id", order["_id"]), set("paymentStatus", status.name))
        }
        count("ordersUpdated", statuses.size)
    }
