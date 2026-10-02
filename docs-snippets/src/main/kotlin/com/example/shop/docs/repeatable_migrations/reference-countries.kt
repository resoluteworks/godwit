package com.example.shop.docs.repeatable_migrations

import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.nin
import com.mongodb.client.model.ReplaceOptions
import godwit.core.repeatable
import org.bson.Document

/** The countries the shop ships to. Change the revision below in the same commit as this list. */
private val COUNTRIES = listOf(
    "GB" to "United Kingdom",
    "IE" to "Ireland",
    "FR" to "France",
    "DE" to "Germany",
    "ES" to "Spain"
)

/**
 * Applied once per revision, after every pending once-only migration. A start at the same revision skips it without
 * taking the lock.
 */
val referenceCountries = repeatable("reference-countries", revision = "2026-11-15")
    .inTransaction {
        val countries = collection("countries")
        COUNTRIES.forEach { (code, name) ->
            countries.replaceOne(
                session,
                eq("_id", code),
                Document("_id", code).append("name", name),
                ReplaceOptions().upsert(true)
            )
        }
        count("countriesRemoved", countries.deleteMany(session, nin("_id", COUNTRIES.map { it.first })).deletedCount)
    }
