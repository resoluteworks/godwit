package com.example.shop.docs.dependencies

import com.example.shop.loadShopConfig
import com.example.shop.services.CustomerService
import com.example.shop.services.ExternalUser
import com.example.shop.services.HttpIdentityProvider
import com.example.shop.services.IdentityProvider
import com.example.shop.services.OrderService
import com.example.shop.services.PaymentGateway
import com.example.shop.services.PaymentStatus
import com.example.shop.startHttpServer
import com.mongodb.kotlin.client.MongoClient
import godwit.core.Godwit

/** The shop's startup once 009 is added. The gateway connects only if a migration calls it. */
fun main() {
    val config = loadShopConfig()
    MongoClient.create(config.mongo.uri).use { client ->
        val database = client.getDatabase(config.mongo.database)
        val customers = CustomerService(database)
        val orders = OrderService(database)
        val identity = HttpIdentityProvider(config.identity.baseUrl, config.identity.apiKey)
        val gateway = LazyPaymentGateway { connectPaymentGateway() }

        Godwit(client, config.mongo.database).migrate(shopMigrations(config, customers, identity, gateway))

        startHttpServer(customers, orders)
    }
}

/** Connects on first use, so a start that runs no gateway migration never connects. */
class LazyPaymentGateway(connect: () -> PaymentGateway) : PaymentGateway {
    private val gateway by lazy(connect)

    override fun paymentStatus(paymentId: String): PaymentStatus = gateway.paymentStatus(paymentId)
}

/** Signs in to the payment gateway: slow, and it throws while the gateway is down. */
fun connectPaymentGateway(): PaymentGateway = TODO("the gateway's client library")

/** Wrong: godwit and the services use two clients, so the services reject godwit's session. */
fun mainWithTwoClients() {
    val config = loadShopConfig()
    val servicesClient = MongoClient.create(config.mongo.uri)
    val customers = CustomerService(servicesClient.getDatabase(config.mongo.database))
    val identity = HttpIdentityProvider(config.identity.baseUrl, config.identity.apiKey)
    val gateway = LazyPaymentGateway { connectPaymentGateway() }

    val godwitClient = MongoClient.create(config.mongo.uri)
    Godwit(godwitClient, config.mongo.database).migrate(shopMigrations(config, customers, identity, gateway))
}

/** A background worker deployed next to the shop. It never migrates; it refuses to start on an older schema. */
fun workerMain() {
    val config = loadShopConfig()
    MongoClient.create(config.mongo.uri).use { client ->
        val database = client.getDatabase(config.mongo.database)
        val migrations = shopMigrations(config, CustomerService(database), NoIdentityProvider, NoPaymentGateway)

        Godwit(client, config.mongo.database).requireUpToDate(migrations)

        processPaidOrders(OrderService(database))
    }
}

/** The worker has no identity provider credentials. requireUpToDate runs no step, so nothing calls this. */
private object NoIdentityProvider : IdentityProvider {
    override fun findOrCreateUser(email: String): ExternalUser = error("The worker does not run migrations")
}

/** The worker has no payment gateway credentials either. */
private object NoPaymentGateway : PaymentGateway {
    override fun paymentStatus(paymentId: String): PaymentStatus = error("The worker does not run migrations")
}

/** The worker's job. */
fun processPaidOrders(orders: OrderService): Unit = TODO("the worker's loop")
