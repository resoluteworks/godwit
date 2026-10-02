package com.example.shop.docs.libraries_and_modules

import com.example.filestore.FILES_COLLECTION
import com.example.shop.ShopConfig
import com.example.shop.migrations.bootstrapCustomers
import com.example.shop.migrations.carts
import com.example.shop.migrations.customerExternalIds
import com.example.shop.migrations.fileStore
import com.example.shop.migrations.initialSetup
import com.example.shop.migrations.orderStatus
import com.example.shop.migrations.orderTotals
import com.example.shop.migrations.referenceCountries
import com.example.shop.migrations.shopMigrations
import com.example.shop.services.CustomerService
import com.example.shop.services.IdentityProvider
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes.ascending
import godwit.core.Migration
import godwit.core.migration

// What godwit does not support: library-shipped migrations, and module-level lists concatenated by the app.
// Both compile; validateMigrations rejects both lists.

/** Not supported: a library that ships its own migrations. */
val fileStoreMigrations: List<Migration> = listOf(
    migration("001-files-collection").outsideTransaction {
        ensureCollection(FILES_COLLECTION)
    },
    migration("002-files-indexes").outsideTransaction {
        collection(FILES_COLLECTION).createIndex(ascending("storageKey"), IndexOptions().unique(true))
    }
)

/** The app appends the library's list to its own. validateMigrations rejects the result. */
fun shopMigrationsWithLibrary(config: ShopConfig, customers: CustomerService, identity: IdentityProvider) =
    shopMigrations(config, customers, identity) + fileStoreMigrations

/** In the orders module. Not this: a module-level list fixes the order of that module's migrations only. */
val orderMigrations: List<Migration> = listOf(orderStatus, orderTotals)

/** In the app module, concatenating module lists. validateMigrations rejects the result. */
fun shopMigrationsByModule(config: ShopConfig, customers: CustomerService, identity: IdentityProvider) =
    listOf(initialSetup(config.mongo.searchIndexWait), carts, fileStore) +
        orderMigrations +
        listOf(
            customerExternalIds(customers, identity),
            referenceCountries,
            bootstrapCustomers(config.bootstrap.customers, customers, identity)
        )
