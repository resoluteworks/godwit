package com.example.shop.docs.dependencies

import com.example.shop.ShopConfig
import com.example.shop.migrations.bootstrapCustomers
import com.example.shop.migrations.carts
import com.example.shop.migrations.customerExternalIds
import com.example.shop.migrations.fileStore
import com.example.shop.migrations.initialSetup
import com.example.shop.migrations.orderStatus
import com.example.shop.migrations.orderPaymentStatus
import com.example.shop.migrations.orderTotals
import com.example.shop.migrations.referenceCountries
import com.example.shop.services.CustomerService
import com.example.shop.services.IdentityProvider
import com.example.shop.services.PaymentGateway
import godwit.core.Migration

/** The shop's list once 009 is added: the gateway joins the parameters, because 009 needs it. */
fun shopMigrations(
    config: ShopConfig,
    customers: CustomerService,
    identity: IdentityProvider,
    gateway: PaymentGateway
): List<Migration> = listOf(
    initialSetup(searchIndexWait = config.mongo.searchIndexWait),
    carts,
    fileStore,
    orderStatus,
    customerExternalIds(customers, identity),
    orderTotals,
    orderPaymentStatus(gateway),
    referenceCountries,
    bootstrapCustomers(config.bootstrap.customers, customers, identity)
)
