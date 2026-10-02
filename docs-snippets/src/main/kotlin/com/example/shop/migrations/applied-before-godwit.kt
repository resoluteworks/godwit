package com.example.shop.migrations

import com.mongodb.kotlin.client.MongoDatabase
import org.bson.Document

/**
 * The migrations applied to a shop database before godwit tracked it, for `GodwitConfig.adoptApplied`. Those
 * databases were migrated by hand, with one document per applied change in `schema-log`:
 * `{ _id: ObjectId, version: "003-file-store", appliedAt: Date }`.
 */
fun appliedBeforeGodwit(database: MongoDatabase): Set<String> =
    database.getCollection("schema-log", Document::class.java)
        .find()
        .map { it.getString("version") }
        .toList()
        .toSet()
