package com.example.shop.docs.failure_and_recovery

import com.example.shop.loadShopConfig
import com.example.shop.migrations.appliedBeforeGodwit
import com.example.shop.migrations.shopMigrations
import com.example.shop.services.CustomerService
import com.example.shop.services.HttpIdentityProvider
import com.mongodb.kotlin.client.MongoClient
import godwit.core.Godwit
import godwit.core.GodwitConfig
import godwit.core.OutOfOrder
import godwit.core.UntrackedDatabase

/** Wrong: godwit opens its sessions on migrationClient, and CustomerService runs on appClient. */
fun startWithTwoClients() {
    val config = loadShopConfig()
    val appClient = MongoClient.create(config.mongo.uri)
    val migrationClient = MongoClient.create(config.mongo.uri)
    val customers = CustomerService(appClient.getDatabase(config.mongo.database))
    val identity = HttpIdentityProvider(config.identity.baseUrl, config.identity.apiKey)

    Godwit(migrationClient, config.mongo.database).migrate(shopMigrations(config, customers, identity))
}

/** Staging runs feature branches early, so it lets a migration merged later run behind ones already applied. */
fun godwitConfigFor(environment: String): GodwitConfig =
    GodwitConfig(outOfOrder = if (environment == "staging") OutOfOrder.RUN else OutOfOrder.FAIL)

/** Databases created before godwit recorded their changes in schema-log: adopt them. */
val adoptingConfig = GodwitConfig(adoptApplied = ::appliedBeforeGodwit)

/** Only for a database whose collections every migration is known to handle, such as an empty copy. */
val runAllConfig = GodwitConfig(untrackedDatabase = UntrackedDatabase.RUN_ALL)
