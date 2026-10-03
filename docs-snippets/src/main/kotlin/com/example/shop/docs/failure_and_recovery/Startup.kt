package com.example.shop.docs.failure_and_recovery

import com.example.shop.loadShopConfig
import com.example.shop.migrations.shopMigrations
import com.example.shop.services.CustomerService
import com.example.shop.services.HttpIdentityProvider
import com.example.shop.services.OrderService
import com.example.shop.startHttpServer
import com.mongodb.kotlin.client.MongoClient
import godwit.core.Godwit
import godwit.core.MigrationFailedException
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("shop")

/**
 * The shop's start with the failure spelled out: a failed migration stops the start before the HTTP server, logs
 * what ran before it, and exits non-zero so the orchestrator restarts the process, which retries the migration.
 */
fun main() {
    val config = loadShopConfig()
    val client = MongoClient.create(config.mongo.uri)
    val database = client.getDatabase(config.mongo.database)
    val customers = CustomerService(database)
    val orders = OrderService(database)
    val identity = HttpIdentityProvider(config.identity.baseUrl, config.identity.apiKey)

    try {
        Godwit(client, config.mongo.database).migrate(shopMigrations(config, customers, identity))
    } catch (e: MigrationFailedException) {
        log.error("Migration {} failed in {}; this start ran {} first", e.id, e.step, e.report.ran.map { it.id }, e)
        exitProcess(1)
    }

    startHttpServer(customers, orders)
}
