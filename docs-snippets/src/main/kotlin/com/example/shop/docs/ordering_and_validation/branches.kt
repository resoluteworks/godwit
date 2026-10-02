package com.example.shop.docs.ordering_and_validation

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
import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Field
import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.Updates.set
import godwit.core.Migration
import godwit.core.migration
import org.bson.Document

/** Gives every product a URL slug: its SKU in lower case. */
val productSlugs = migration("007-product-slugs")
    .inTransaction {
        val result = collection("products").updateMany(
            session,
            exists("slug", false),
            listOf(Aggregates.set(Field("slug", Document("\$toLower", "\$sku"))))
        )
        count("productsUpdated", result.modifiedCount)
    }

/** Gives every cart the shop's currency, as first written on its branch. */
val cartCurrencyOnBranch = migration("007-cart-currency")
    .inTransaction {
        val result = collection("carts").updateMany(session, exists("currency", false), set("currency", "GBP"))
        count("cartsUpdated", result.modifiedCount)
    }

/** The same migration after its author renumbered it. */
val cartCurrency = migration("008-cart-currency")
    .inTransaction {
        val result = collection("carts").updateMany(session, exists("currency", false), set("currency", "GBP"))
        count("cartsUpdated", result.modifiedCount)
    }

/** main after both branches merged, before the renumbering. */
fun mergedMigrations(config: ShopConfig, customers: CustomerService, identity: IdentityProvider): List<Migration> =
    listOf(
        initialSetup(searchIndexWait = config.mongo.searchIndexWait),
        carts,
        fileStore,
        orderStatus,
        customerExternalIds(customers, identity),
        orderTotals,
        productSlugs,
        cartCurrencyOnBranch,
        referenceCountries,
        bootstrapCustomers(config.bootstrap.customers, customers, identity)
    )

/** The cart-currency branch as staging ran it, before 007-product-slugs merged. */
fun cartCurrencyBranchMigrations(
    config: ShopConfig,
    customers: CustomerService,
    identity: IdentityProvider
): List<Migration> =
    listOf(
        initialSetup(searchIndexWait = config.mongo.searchIndexWait),
        carts,
        fileStore,
        orderStatus,
        customerExternalIds(customers, identity),
        orderTotals,
        cartCurrency,
        referenceCountries,
        bootstrapCustomers(config.bootstrap.customers, customers, identity)
    )

/** Release 1.4: main after both branches merged and cart-currency was renumbered. */
fun release14Migrations(config: ShopConfig, customers: CustomerService, identity: IdentityProvider): List<Migration> =
    listOf(
        initialSetup(searchIndexWait = config.mongo.searchIndexWait),
        carts,
        fileStore,
        orderStatus,
        customerExternalIds(customers, identity),
        orderTotals,
        productSlugs,
        cartCurrency,
        referenceCountries,
        bootstrapCustomers(config.bootstrap.customers, customers, identity)
    )
