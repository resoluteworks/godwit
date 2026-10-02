package com.example.shop.docs.failure_and_recovery

import com.example.shop.loadShopConfig
import com.mongodb.kotlin.client.MongoClient
import godwit.core.Godwit

/**
 * An operator's one-off command. Production's customers collection already has a unique index on emailLower, built
 * by hand during an incident under another name, so 008 fails there with IndexOptionsConflict. The hand-made index
 * is the one 008 would build: record 008 as applied instead of running it.
 */
fun markHandBuiltIndex() {
    val config = loadShopConfig()
    MongoClient.create(config.mongo.uri).use { client ->
        Godwit(client, config.mongo.database).markApplied(
            "008-customer-email-lower-index",
            reason = "Unique index on customers.emailLower built by hand as emailLower_unique during the 2026-10-05 incident"
        )
    }
}
