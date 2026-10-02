package com.example.shop.docs.adopting_an_existing_database

// region: wire
import com.example.shop.ShopConfig
import com.example.shop.migrations.appliedBeforeGodwit
import com.example.shop.migrations.shopMigrations
import com.example.shop.services.CustomerService
import com.example.shop.services.IdentityProvider
import com.mongodb.kotlin.client.MongoClient
import godwit.core.Godwit
import godwit.core.GodwitConfig
import godwit.core.MigrationReport

/** Migrates the shop database, adopting the changes that the hand-maintained `schema-log` already records. */
fun migrateWithAdoption(client: MongoClient, config: ShopConfig, identity: IdentityProvider): MigrationReport {
    val customers = CustomerService(client.getDatabase(config.mongo.database))
    val godwit = Godwit(client, config.mongo.database, GodwitConfig(adoptApplied = ::appliedBeforeGodwit))
    return godwit.migrate(shopMigrations(config, customers, identity))
}
// endregion
