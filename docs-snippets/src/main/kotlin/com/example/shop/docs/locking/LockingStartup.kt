package com.example.shop.docs.locking

import com.example.shop.loadShopConfig
import com.example.shop.migrations.shopMigrations
import com.example.shop.services.CustomerService
import com.example.shop.services.HttpIdentityProvider
import com.example.shop.services.OrderService
import com.example.shop.startHttpServer
import com.mongodb.kotlin.client.MongoClient
import godwit.core.Godwit
import godwit.core.GodwitConfig
import godwit.core.LockConfig
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes

private val log = LoggerFactory.getLogger("shop")

/**
 * The shop's start: migrate, then serve. 006-order-totals, the longest migration, takes about 2.5 minutes on the
 * production orders collection, which keeps growing, so a process that starts while another one migrates waits up
 * to 15 minutes.
 */
fun main() {
    val config = loadShopConfig()
    val client = MongoClient.create(config.mongo.uri)
    val database = client.getDatabase(config.mongo.database)
    val customers = CustomerService(database)
    val orders = OrderService(database)
    val identity = HttpIdentityProvider(config.identity.baseUrl, config.identity.apiKey)
    val godwit = Godwit(
        client,
        config.mongo.database,
        GodwitConfig(lock = LockConfig(waitTimeout = 15.minutes))
    )

    val report = godwit.migrate(shopMigrations(config, customers, identity))
    log.info("Migrated: ran={} lockWait={}", report.ran.map { it.id }, report.lockWait)

    startHttpServer(customers, orders)
}
