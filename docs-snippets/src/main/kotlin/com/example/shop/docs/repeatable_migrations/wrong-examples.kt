package com.example.shop.docs.repeatable_migrations

// Examples validateMigrations rejects. Both compile; migrate() throws InvalidMigrationsException before any I/O.

import com.example.shop.migrations.carts
import com.example.shop.migrations.initialSetup
import com.example.shop.migrations.referenceCountries
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.UpdateOneModel
import com.mongodb.client.model.Updates.set
import godwit.core.Migration
import godwit.core.repeatable
import org.bson.Document

/** Wrong: a repeatable listed before a once-only migration. */
val misordered: List<Migration> = listOf(initialSetup(searchIndexWait = null), referenceCountries, carts)

/** Wrong: a repeatable cannot page through a collection. */
val productSearchTextEveryRevision = repeatable("product-search-text", revision = "3")
    .inBatches("products", pending = Filters.empty(), batchSize = 500) { products ->
        collection("products").bulkWrite(
            session,
            products.map { product -> UpdateOneModel<Document>(eq("_id", product["_id"]), set("searchText", searchTextOf(product))) }
        )
    }

internal fun searchTextOf(product: Document): String =
    listOfNotNull(product.getString("name"), product.getString("description"), product.getString("sku"))
        .joinToString(" ")
        .lowercase()
