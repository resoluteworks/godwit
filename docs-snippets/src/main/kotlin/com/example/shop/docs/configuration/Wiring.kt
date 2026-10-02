package com.example.shop.docs.configuration

// region: wiring
import com.example.shop.ShopConfig
import com.example.shop.migrations.shopMigrations
import com.example.shop.services.CustomerService
import com.example.shop.services.IdentityProvider
import com.mongodb.kotlin.client.MongoClient
import godwit.core.Godwit
import godwit.core.MigrationReport

fun migrateShop(
    client: MongoClient,
    config: ShopConfig,
    identity: IdentityProvider,
    environment: Environment,
    holder: String
): MigrationReport {
    val customers = CustomerService(client.getDatabase(config.mongo.database))
    val godwit = Godwit(client, config.mongo.database, godwitConfig(environment, holder))
    return godwit.migrate(shopMigrations(config, customers, identity))
}
// endregion
