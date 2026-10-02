package com.example.shop.docs.failure_and_recovery

import com.mongodb.client.model.Filters.and
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.ne
import com.mongodb.client.model.Updates.set
import godwit.core.migration

/**
 * 004-order-status marked an order PENDING when it had no paidAt. Orders paid through the gateway's asynchronous
 * flow have a paymentId and no paidAt: they are paid. This migration corrects them; 004 stays as it is.
 */
val paidOrdersWithoutPaidAt = migration("022-paid-orders-without-paid-at")
    .inTransaction {
        val result = collection("orders").updateMany(
            session,
            and(eq("status", "PENDING"), ne("paymentId", null), eq("paidAt", null)),
            set("status", "PAID")
        )
        count("ordersPaid", result.modifiedCount)
    }
