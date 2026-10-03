package com.example.shop

import com.example.shop.migrations.carts
import com.example.shop.migrations.orderStatus
import com.mongodb.kotlin.client.MongoClient
import godwit.core.Godwit
import godwit.core.Migration

/** Every migration, in run order. The list is the registry: a migration that is not listed never runs. */
val migrations: List<Migration> = listOf(carts, orderStatus)

fun main() {
    val client = MongoClient.create(System.getenv("MONGO_URI"))
    Godwit(client, "shop").migrate(migrations)
    // Build the app's services from this same client, then start serving requests.
}
