package godwit.core.docs

import com.example.shop.docs.failure_and_recovery.cartCurrencyClashing
import com.example.shop.docs.failure_and_recovery.productSlugs
import com.example.shop.migrations.carts
import com.example.shop.migrations.fileStore
import com.example.shop.migrations.orderStatus
import com.example.shop.migrations.orderTotals
import com.example.shop.services.CustomerService
import com.mongodb.client.model.Filters.exists
import godwit.core.Godwit
import godwit.core.InvalidMigrationsException
import godwit.core.Migration
import godwit.core.Target
import godwit.core.migration
import godwit.core.repeatable
import godwit.core.validateMigrations
import org.bson.Document

/** The shop's current list, with stand-in services: validation needs no database and runs no step. */
private fun shop(): List<Migration> = DocsMongo.client("docs-validation").use { client ->
    shopMigrations(CustomerService(client.getDatabase("validation")), StubIdentityProvider())
}

private fun Produced.invalid(name: String, migrations: List<Migration>) {
    exception(
        name,
        runCatching { validateMigrations(migrations) }.exceptionOrNull() as? InvalidMigrationsException
            ?: error("$name passed validation")
    )
}

/** ordering-and-validation.md's lists that break each rule, and the other docs' invalid lists. */
val invalidLists = Scenario("invalid migration lists") {
    val shop = shop()
    invalid("invalid id", listOf(migration("007 product slugs: from SKU").inTransaction { }))
    invalid("declared twice", listOf(initialSetupWithoutSearch, carts, fileStore, carts))
    invalid(
        "superseded twice",
        listOf(
            migration("100-baseline", supersedes = listOf("001-initial-setup", "002-carts")).outsideTransaction { },
            migration("101-carts-baseline", supersedes = listOf("002-carts")).outsideTransaction { }
        )
    )
    invalid(
        "superseded twice in one list",
        listOf(migration("100-baseline", supersedes = listOf("002-carts", "002-carts")).outsideTransaction { })
    )
    invalid(
        "supersedes a declared id",
        listOf(
            carts,
            migration("100-baseline", supersedes = listOf("001-initial-setup", "002-carts")).outsideTransaction { }
        )
    )
    invalid("two branches number 007", shop.plusOnceOnly(productSlugs, cartCurrencyClashing))
    invalid("once-only appended at the end", shop + productSlugs)
    invalid("blank revision", listOf(repeatable("reference-currencies", revision = "").inTransaction { }))
    invalid(
        "repeatable inBatches",
        listOf(
            repeatable("product-search-text", revision = "2026-10-01")
                .inBatches("products", pending = exists("searchText", false)) { }
        )
    )
    invalid(
        "batch size above the ceiling",
        listOf(
            migration("007-customer-email-lower")
                .inBatches("customers", pending = exists("emailLower", false), batchSize = 50_000) { }
        )
    )
    invalid("a library's list appended", shop + fileStoreLibraryMigrations)
    invalid(
        "module lists concatenated",
        listOf(initialSetupWithoutSearch, carts, fileStore) + listOf(orderStatus, orderTotals) +
            shop.filter { it.id in setOf("005-customer-external-ids", "reference-countries", "bootstrap-customers") }
    )
    invalid("a merge with two 007s", listOf(carts, productSlugs, cartCurrencyClashing))
    DocsMongo.client("docs-validation").use { client ->
        val godwit = Godwit(client, DocsMongo.newDatabase())
        exception(
            "target typo",
            runCatching { godwit.migrate(shop, target = Target.Before("004-order-totals")) }.exceptionOrNull()
                ?: error("the target typo passed")
        )
    }
}

/** libraries-and-modules.md's file store library that ships migrations of its own. */
private val fileStoreLibraryMigrations: List<Migration> = listOf(
    migration("001-files-collection").outsideTransaction { ensureCollection("files") },
    migration("002-files-indexes").outsideTransaction { collection("files").createIndex(Document("storageKey", 1)) }
)

/** Every problem line the invalid lists produced, for the problem templates the docs quote. */
fun Produced.problemLines(): List<String> = listOf(
    "invalid id", "declared twice", "superseded twice", "superseded twice in one list", "supersedes a declared id",
    "two branches number 007", "once-only appended at the end", "blank revision", "repeatable inBatches",
    "batch size above the ceiling", "a library's list appended", "module lists concatenated", "a merge with two 007s",
    "target typo"
).flatMap { (exception(it) as InvalidMigrationsException).problems }
