package com.example.shop.migrations

import com.example.shop.ShopConfig
import com.example.shop.services.CustomerService
import com.example.shop.services.IdentityProvider
import godwit.core.Migration

/**
 * Every shop migration, in run order. This list is the registry: a migration that is not listed never runs. The
 * parameters are everything the migrations need, and each migration takes only its own part.
 */
fun shopMigrations(
    config: ShopConfig,
    customers: CustomerService,
    identity: IdentityProvider
): List<Migration> = listOf(
    initialSetup(searchIndexWait = config.mongo.searchIndexWait),
    carts,
    fileStore,
    orderStatus,
    customerExternalIds(customers, identity),
    orderTotals,
    referenceCountries,
    bootstrapCustomers(config.bootstrap.customers, customers, identity)
)
