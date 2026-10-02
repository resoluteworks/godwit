package com.example.shop.docs.libraries_and_modules

import com.example.shop.ShopConfig
import com.example.shop.migrations.bootstrapCustomers
import com.example.shop.migrations.carts
import com.example.shop.migrations.customerExternalIds
import com.example.shop.migrations.fileStore
import com.example.shop.migrations.initialSetup
import com.example.shop.migrations.orderStatus
import com.example.shop.migrations.orderTotals
import com.example.shop.migrations.referenceCountries
import com.example.shop.services.CustomerService
import com.example.shop.services.IdentityProvider
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes.descending
import com.mongodb.kotlin.client.MongoClient
import godwit.core.Godwit
import godwit.core.Migration
import godwit.core.migration

/** The shop's list once it upgrades to file store 2.0. */
fun shopMigrations(config: ShopConfig, customers: CustomerService, identity: IdentityProvider): List<Migration> =
    listOf(
        initialSetup(searchIndexWait = config.mongo.searchIndexWait),
        carts,
        fileStore,
        orderStatus,
        customerExternalIds(customers, identity),
        orderTotals,
        fileStoreContentType,
        referenceCountries,
        bootstrapCustomers(config.bootstrap.customers, customers, identity)
    )

/** The reporting database's own list. It never touches the shop database. */
fun reportingMigrations(): List<Migration> = listOf(
    migration("001-daily-sales").outsideTransaction {
        ensureCollection("daily-sales")
        collection("daily-sales").createIndex(descending("day"), IndexOptions().unique(true))
    }
)

/** Two databases: two Godwit instances, two lists, two histories, two locks. */
fun migrateBothDatabases(
    client: MongoClient,
    config: ShopConfig,
    customers: CustomerService,
    identity: IdentityProvider
) {
    Godwit(client, config.mongo.database).migrate(shopMigrations(config, customers, identity))
    Godwit(client, "shop-reporting").migrate(reportingMigrations())
}
