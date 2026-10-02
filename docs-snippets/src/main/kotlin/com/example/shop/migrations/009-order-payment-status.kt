package com.example.shop.migrations

import com.example.shop.services.PaymentGateway
import com.mongodb.client.model.Filters.and
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.Filters.ne
import com.mongodb.client.model.Updates.set
import godwit.core.Migration
import godwit.core.migration

/**
 * Stores the payment gateway's status on every order that has a payment. The gateway calls run outside any
 * transaction, one per order; they only read, so a retry repeats them harmlessly. The statuses reach the transaction
 * as the outside step's value.
 */
fun orderPaymentStatus(gateway: PaymentGateway): Migration = migration("009-order-payment-status")
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
            orders.updateOne(session, eq("_id", orderId), set("paymentStatus", status.name))
        }
        count("ordersUpdated", statuses.size)
    }
