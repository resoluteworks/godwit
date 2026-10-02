package com.example.shop.docs.repeatable_migrations

import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.nin
import com.mongodb.client.model.ReplaceOptions
import godwit.core.repeatable
import org.bson.Document
import java.security.MessageDigest

private val COUNTRIES = listOf(
    "GB" to "United Kingdom",
    "IE" to "Ireland",
    "FR" to "France",
    "DE" to "Germany",
    "ES" to "Spain"
)

/** The first 16 hex digits of the SHA-256 of the list: changes whenever an entry changes. */
private fun digestOf(entries: List<Pair<String, String>>): String =
    MessageDigest.getInstance("SHA-256")
        .digest(entries.joinToString("\n") { (code, name) -> "$code=$name" }.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(16)

private val referenceCountriesDerived = repeatable("reference-countries", revision = "v2-" + digestOf(COUNTRIES))
    .inTransaction {
        val countries = collection("countries")
        COUNTRIES.forEach { (code, name) ->
            countries.replaceOne(session, eq("_id", code), Document("_id", code).append("name", name), ReplaceOptions().upsert(true))
        }
        count("countriesRemoved", countries.deleteMany(session, nin("_id", COUNTRIES.map { it.first })).deletedCount)
    }
