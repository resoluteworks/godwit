package com.example.shop.docs.declaring_migrations

import com.mongodb.client.model.Filters.and
import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.Updates.set
import godwit.core.migration
import org.bson.Document

/** Carts expire 60 days after their last update. 002-carts keeps its 30 days: it has run on every database. */
val cartExpiry60Days = migration("010-cart-expiry-60-days")
    .outsideTransaction {
        database.runCommand(
            Document("collMod", "carts")
                .append(
                    "index",
                    Document("keyPattern", Document("updatedAt", 1)).append("expireAfterSeconds", 60L * 24 * 60 * 60)
                )
                .append("writeConcern", Document("w", "majority"))
        )
    }

/**
 * 004-order-status under a new id. Where 004-order-status is applied, godwit records this one SUPERSEDED without
 * running it; where it is not, this one runs.
 */
val orderStatusBackfill = migration("004-order-status-backfill", supersedes = listOf("004-order-status"))
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
