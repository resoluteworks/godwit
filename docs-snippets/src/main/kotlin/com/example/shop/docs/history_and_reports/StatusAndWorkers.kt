package com.example.shop.docs.history_and_reports

import com.example.shop.loadShopConfig
import com.example.shop.migrations.shopMigrations
import com.example.shop.services.CustomerService
import com.example.shop.services.HttpIdentityProvider
import com.mongodb.kotlin.client.MongoClient
import godwit.core.Godwit
import godwit.core.Migration
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * A deploy pipeline step, run before the rollout: prints what the new release would do to the database and returns
 * the exit code, 0 when nothing is pending.
 */
fun checkDeploy(): Int {
    val config = loadShopConfig()
    val client = MongoClient.create(config.mongo.uri)
    val database = client.getDatabase(config.mongo.database)
    val identity = HttpIdentityProvider(config.identity.baseUrl, config.identity.apiKey)
    val migrations = shopMigrations(config, CustomerService(database), identity)

    val status = Godwit(client, config.mongo.database).status(migrations)
    println("pending: ${status.pending}")
    println("problems: ${status.problems}")
    println("unknown applied: ${status.unknownApplied}")
    return if (status.isUpToDate) 0 else 1
}

/**
 * Waits up to [timeout] for the shop's processes to finish migrating, checking every 5 seconds, then fails with
 * PendingMigrationsException. For a worker deployed together with the shop, so it starts as soon as the schema is ready.
 */
fun awaitUpToDate(godwit: Godwit, migrations: List<Migration>, timeout: Duration) {
    val deadline = TimeSource.Monotonic.markNow() + timeout
    while (!godwit.status(migrations).isUpToDate) {
        if (deadline.hasPassedNow()) godwit.requireUpToDate(migrations)
        Thread.sleep(5_000)
    }
}
