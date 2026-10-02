package com.example.shop

import com.example.shop.migrations.shopMigrations
import com.example.shop.services.CustomerService
import com.example.shop.services.HttpIdentityProvider
import com.example.shop.services.OrderService
import com.mongodb.kotlin.client.MongoClient
import godwit.core.Godwit

fun main() {
    val config = loadShopConfig()
    MongoClient.create(config.mongo.uri).use { client ->
        val database = client.getDatabase(config.mongo.database)
        val customers = CustomerService(database)
        val orders = OrderService(database)
        val identity = HttpIdentityProvider(config.identity.baseUrl, config.identity.apiKey)

        Godwit(client, config.mongo.database).migrate(shopMigrations(config, customers, identity))

        startHttpServer(customers, orders)
    }
}

/** The rest of the shop: serves requests until the process stops. */
fun startHttpServer(customers: CustomerService, orders: OrderService): Unit = TODO("the shop's HTTP server")
