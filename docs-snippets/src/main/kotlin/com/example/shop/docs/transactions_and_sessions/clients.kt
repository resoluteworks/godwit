package com.example.shop.docs.transactions_and_sessions

import com.example.shop.loadShopConfig
import com.example.shop.migrations.shopMigrations
import com.example.shop.services.CustomerService
import com.example.shop.services.HttpIdentityProvider
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.kotlin.client.MongoClient
import godwit.core.Godwit
import godwit.test.SessionEscapeDetector

/** Wrong: godwit and CustomerService use two clients, so 005's session is invalid in CustomerService. */
fun startWithTwoClients() {
    val config = loadShopConfig()
    val godwitClient = MongoClient.create(config.mongo.uri)
    val appClient = MongoClient.create(config.mongo.uri)
    val customers = CustomerService(appClient.getDatabase(config.mongo.database))
    val identity = HttpIdentityProvider(config.identity.baseUrl, config.identity.apiKey)

    Godwit(godwitClient, config.mongo.database).migrate(shopMigrations(config, customers, identity))
}

/**
 * A client for a cluster that the tests manage themselves. The detector fails any transactional step that runs a
 * command without its session.
 */
fun clientWithEscapeDetector(uri: String): MongoClient = MongoClient.create(
    MongoClientSettings.builder()
        .applyConnectionString(ConnectionString(uri))
        .addCommandListener(SessionEscapeDetector())
        .build()
)
