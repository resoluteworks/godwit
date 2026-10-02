package com.example.shop.docs.ordering_and_validation

import com.example.shop.ShopConfig
import com.example.shop.migrations.carts
import com.example.shop.migrations.fileStore
import com.example.shop.migrations.initialSetup
import com.example.shop.migrations.shopMigrations
import com.example.shop.services.CustomerService
import com.example.shop.services.IdentityProvider
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.model.UpdateOneModel
import com.mongodb.client.model.Updates.set
import java.util.Locale
import godwit.core.Godwit
import godwit.core.Migration
import godwit.core.Target
import godwit.core.migration
import godwit.core.repeatable
import org.bson.Document

// Lists that compile and fail validateMigrations (and therefore migrate, status and requireUpToDate) before any I/O.

fun invalidId(): List<Migration> = listOf(
    migration("007 product slugs: from SKU").inTransaction { }
)

fun declaredTwice(): List<Migration> = listOf(
    initialSetup(searchIndexWait = null),
    carts,
    fileStore,
    carts
)

fun supersededTwice(): List<Migration> = listOf(
    migration("100-baseline", supersedes = listOf("001-initial-setup", "002-carts")).outsideTransaction { },
    migration("101-carts-baseline", supersedes = listOf("002-carts")).outsideTransaction { }
)

fun supersedesDeclared(): List<Migration> = listOf(
    carts,
    migration("100-baseline", supersedes = listOf("001-initial-setup", "002-carts")).outsideTransaction { }
)

fun appendedAtTheEnd(config: ShopConfig, customers: CustomerService, identity: IdentityProvider): List<Migration> =
    shopMigrations(config, customers, identity) + productSlugs

val referenceCurrencies = repeatable("reference-currencies", revision = "")
    .inTransaction {
        collection("currencies").replaceOne(
            session,
            eq("_id", "GBP"),
            Document("_id", "GBP").append("name", "Pound sterling"),
            ReplaceOptions().upsert(true)
        )
    }

val productSearchText = repeatable("product-search-text", revision = "2026-10-01")
    .inBatches("products", pending = exists("searchText", false)) { products ->
        collection("products").bulkWrite(
            session,
            products.map { product ->
                UpdateOneModel<Document>(eq("_id", product["_id"]), set("searchText", product.getString("name")))
            }
        )
    }

val customerEmailLowerOversized = migration("007-customer-email-lower")
    .inBatches("customers", pending = exists("emailLower", false), batchSize = 50_000) { customers ->
        val updates = customers.mapNotNull { customer ->
            val email = customer.getString("email") ?: return@mapNotNull null
            UpdateOneModel<Document>(eq("_id", customer["_id"]), set("emailLower", email.trim().lowercase(Locale.ROOT)))
        }
        if (updates.isNotEmpty()) collection("customers").bulkWrite(session, updates)
        count("customersUpdated", updates.size)
        count("customersWithoutEmail", customers.size - updates.size)
    }

fun migrateToTypo(godwit: Godwit, migrations: List<Migration>) {
    godwit.migrate(migrations, target = Target.Before("004-order-totals"))
}
