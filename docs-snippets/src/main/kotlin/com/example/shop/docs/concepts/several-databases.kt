package com.example.shop.docs.concepts

import com.example.shop.ShopConfig
import com.example.shop.migrations.shopMigrations
import com.example.shop.services.CustomerService
import com.example.shop.services.IdentityProvider
import com.mongodb.kotlin.client.MongoClient
import godwit.core.Godwit
import godwit.core.MigrationReport

/** One shop database per country, same schema. Each database has its own history and its own lock. */
fun migrateEveryShop(
    client: MongoClient,
    config: ShopConfig,
    identity: IdentityProvider,
    databaseNames: List<String>
): Map<String, MigrationReport> =
    databaseNames.associateWith { databaseName ->
        val customers = CustomerService(client.getDatabase(databaseName))
        Godwit(client, databaseName).migrate(shopMigrations(config, customers, identity))
    }
