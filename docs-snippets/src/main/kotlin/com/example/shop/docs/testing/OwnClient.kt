package com.example.shop.docs.testing

// region: own-client
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.kotlin.client.MongoClient
import godwit.test.SessionEscapeDetector

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
// endregion
