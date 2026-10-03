package com.example.shop.docs.outside_transaction_steps

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
