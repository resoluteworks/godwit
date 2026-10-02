package com.example.shop.docs.adopting_an_existing_database

// region: changelog-hook
import com.mongodb.client.model.Sorts.ascending
import com.mongodb.kotlin.client.MongoDatabase
import org.bson.Document

/**
 * The ids as the old changelog spells them, mapped to the ids in the shop's list. An id that is not in the map is
 * returned as it is.
 */
private val RENAMED = mapOf(
    "create-core-collections" to "001-initial-setup",
    "create-carts" to "002-carts",
    "file-store-collection" to "003-file-store",
    "backfill-order-status" to "004-order-status"
)

/**
 * The changes that the old `changelog` collection records as done. A row is
 * `{ changeId, state: "DONE" | "FAILED", finishedAt }` and a change has one row per attempt; the newest row of a
 * change decides, so a change that failed once and then succeeded counts as applied.
 */
fun appliedInChangelog(database: MongoDatabase): Set<String> =
    database.getCollection("changelog", Document::class.java)
        .find()
        .sort(ascending("finishedAt"))
        .toList()
        .associate { row -> row.getString("changeId") to row.getString("state") }
        .filterValues { state -> state == "DONE" }
        .keys
        .map { changeId -> RENAMED[changeId] ?: changeId }
        .toSet()
// endregion
