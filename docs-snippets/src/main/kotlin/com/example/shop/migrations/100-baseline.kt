package com.example.shop.migrations

import com.example.filestore.ensureFileStoreSchema
import com.example.shop.ShopConfig
import com.example.shop.services.CustomerService
import com.example.shop.services.IdentityProvider
import com.mongodb.client.model.IndexModel
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes.ascending
import com.mongodb.client.model.Indexes.compoundIndex
import com.mongodb.client.model.Indexes.descending
import godwit.core.Migration
import godwit.core.migration
import org.bson.Document
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/** The once-only migrations that the baseline replaces: all of them, as the release before the squash lists them. */
val squashedIds = listOf(
    "001-initial-setup",
    "002-carts",
    "003-file-store",
    "004-order-status",
    "005-customer-external-ids",
    "006-order-totals"
)

/**
 * The end state of 001 to 006 as schema only. A fresh database has no data in the old shapes, so the backfills 004
 * to 006 have nothing to do and are not repeated here, except for the index that 006 adds.
 */
fun baseline(searchIndexWait: Duration?): Migration = migration(
    "100-baseline",
    description = "End state of 001 to 006",
    supersedes = squashedIds
).outsideTransaction {
    ensureCollection("customers")
    ensureCollection("orders")
    ensureCollection("products")
    ensureCollection("carts")
    database.ensureFileStoreSchema()

    collection("customers").createIndex(ascending("email"), IndexOptions().unique(true))
    collection("orders").createIndexes(
        listOf(
            IndexModel(compoundIndex(ascending("customerId"), descending("placedAt"))),
            IndexModel(ascending("status")),
            IndexModel(compoundIndex(ascending("customerId"), descending("totalMinor")))
        )
    )
    collection("products").createIndex(ascending("sku"), IndexOptions().unique(true))
    collection("carts").createIndexes(
        listOf(
            IndexModel(ascending("customerId"), IndexOptions().unique(true)),
            IndexModel(ascending("updatedAt"), IndexOptions().expireAfter(30L, TimeUnit.DAYS))
        )
    )
    ensureSearchIndex(
        "products",
        name = "product-search",
        definition = Document("mappings", Document("dynamic", true)),
        awaitReady = searchIndexWait
    )
}

/** The list of the release that contains the squash: the baseline replaces the six, the rest of the list stays. */
fun squashedMigrations(config: ShopConfig, customers: CustomerService, identity: IdentityProvider): List<Migration> =
    listOf(
        baseline(searchIndexWait = config.mongo.searchIndexWait),
        referenceCountries,
        bootstrapCustomers(config.bootstrap.customers, customers, identity)
    )
