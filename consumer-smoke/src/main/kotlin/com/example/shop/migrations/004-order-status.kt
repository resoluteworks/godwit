package com.example.shop.migrations

import com.mongodb.client.model.Filters.and
import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.Updates.set
import godwit.core.migration

/**
 * Gives every order a status. Orders written before the field existed are PAID when they have `paidAt`, PENDING
 * otherwise. The updates and the history record commit in one transaction, so the backfill applies exactly once.
 */
val orderStatus = migration("004-order-status")
    .inTransaction {
        val orders = collection("orders")
        val paid = orders.updateMany(
            session,
            and(exists("status", false), exists("paidAt", true)),
            set("status", "PAID")
        )
        val pending = orders.updateMany(session, exists("status", false), set("status", "PENDING"))
        count("ordersPaid", paid.modifiedCount)
        count("ordersPending", pending.modifiedCount)
    }
