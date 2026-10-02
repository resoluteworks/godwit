package com.example.shop.docs.outside_transaction_steps

import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.Updates.set
import godwit.core.migration

/**
 * Orders placed before the shop sold in more than one currency have no `currency`; they were all in pounds. One
 * server-side update, idempotent through its filter: a retry updates only the orders an interrupted run did not reach.
 */
val orderCurrency = migration("011-order-currency")
    .outsideTransaction {
        val result = collection("orders").updateMany(exists("currency", false), set("currency", "GBP"))
        count("ordersUpdated", result.modifiedCount)
    }
