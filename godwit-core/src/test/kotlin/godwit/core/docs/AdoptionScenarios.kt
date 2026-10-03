package godwit.core.docs

import com.example.shop.docs.failure_and_recovery.productSlugs
import com.example.shop.migrations.appliedBeforeGodwit
import com.example.shop.migrations.orderPaymentStatus
import com.example.shop.services.CustomerService
import com.mongodb.kotlin.client.MongoDatabase
import godwit.core.GodwitConfig
import godwit.core.OutOfOrder
import godwit.core.UnknownApplied
import godwit.core.fixtures.LogCapture
import org.bson.Document
import org.bson.types.ObjectId
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/** A shop database migrated by hand to [applied], with each applied id and [others] as a row of its `schema-log`. */
private fun handMigratedDatabase(applied: List<String>, others: List<String> = emptyList()): String {
    val database = DocsMongo.newDatabase()
    Observer(database).use { observer ->
        observer.db.seedCustomers()
        observer.collection("orders").insertMany((1..10).map { order(ObjectId(), status = "PAID") })
        observer.collection("products").insertOne(Document("_id", ObjectId()).append("sku", "SHOE-RED"))
        listOf("carts", "files").forEach { observer.db.createCollection(it) }
        observer.collection("schema-log").insertMany(
            (applied + others).map { Document("_id", ObjectId()).append("version", it).append("appliedAt", Date()) }
        )
    }
    return database
}

/** The configuration of the shop's production: the hook that reads `schema-log`. */
private val adopting = GodwitConfig(adoptApplied = ::appliedBeforeGodwit)

/**
 * adopting-an-existing-database.md's walkthrough: production was migrated by hand to `004`, and its `schema-log` also
 * lists `cleanup-temp-data` and `reference-countries`. Two instances of the first godwit release start at once:
 * `shop-7f9c4/1` adopts and runs the rest while `shop-2b8e1/1` waits, logging the holder, and then runs only the
 * every-start migration.
 */
val adoptionByTwoInstances = Scenario("adoption, with two instances starting at once") {
    val database = handMigratedDatabase(
        listOf("001-initial-setup", "002-carts", "003-file-store", "004-order-status"),
        listOf("cleanup-temp-data", "reference-countries")
    )
    LogCapture().use { capture ->
        val failures = ConcurrentHashMap<String, Throwable>()
        val first = background("shop-7f9c4", failures) {
            ShopProcess("shop-7f9c4/1", database, adopting).use { shop ->
                val identity = StubIdentityProvider { capture.awaitLogged("Waiting for migration lock") }
                shop.godwit.migrate(shopMigrations(CustomerService(shop.db), identity))
            }
        }
        capture.awaitLogged("Acquired migration lock")
        val second = background("shop-2b8e1", failures) {
            ShopProcess("shop-2b8e1/1", database, adopting).use { shop ->
                shop.godwit.migrate(shopMigrations(CustomerService(shop.db), StubIdentityProvider()))
            }
        }
        first.awaitEnd()
        second.awaitEnd()
        failures.values.firstOrNull()?.let { throw it }
        logs("shop-7f9c4/1", capture.events.ofThread("shop-7f9c4"))
        logs("shop-2b8e1/1", capture.events.ofThread("shop-2b8e1"))
    }
}

/**
 * The untracked-database guard: a hook that reads `schema-lg` (a typo) adopts nothing on a database with collections;
 * a restored backup started without the hook finds collections and no history.
 */
val untrackedDatabases = Scenario("untracked databases") {
    val typo = handMigratedDatabase(listOf("001-initial-setup")).also { database ->
        Observer(database).use { observer -> listOf("carts", "files").forEach { observer.collection(it).drop() } }
    }
    val readsTheWrongCollection = { db: MongoDatabase ->
        db.getCollection("schema-lg", Document::class.java).find().map { it.getString("version") }.toList().toSet()
    }
    ShopProcess("shop-7f9c4/1", typo, GodwitConfig(adoptApplied = readsTheWrongCollection)).use { shop ->
        failing("the hook adopts nothing") {
            shop.godwit.migrate(shopMigrations(CustomerService(shop.db), StubIdentityProvider()))
        }
    }
    val restore = handMigratedDatabase(listOf("001-initial-setup", "002-carts", "003-file-store"))
    Observer(restore).use {
        it.collection("countries").insertOne(Document("_id", "GB").append("name", "United Kingdom"))
    }
    ShopProcess("shop-7f9c4/1", restore).use { shop ->
        failing("a restored backup") {
            shop.godwit.migrate(shopMigrations(CustomerService(shop.db), StubIdentityProvider()))
        }
    }
}

/** adopting-an-existing-database.md: `markApplied` on production before the first godwit release has started. */
val markBeforeAdoption = Scenario("markApplied before adoption") {
    val database = handMigratedDatabase(listOf("001-initial-setup", "002-carts", "003-file-store"))
    ShopProcess("ops-laptop-3/48211", database, adopting).use { ops ->
        failing("markApplied") {
            ops.godwit.markApplied("002-carts", reason = "created by hand on 2026-03-02, see ticket SHOP-212")
        }
    }
}

/**
 * Staging ran a branch that adds `008-cart-currency` early; main then merges `007-product-slugs`. The next release
 * finds `007` pending before the applied `008`: refused under `OutOfOrder.FAIL`, run under `OutOfOrder.RUN`.
 */
val stagingRanABranchEarly = Scenario("a staging database that ran a branch early") {
    val database = shopDatabase()
    Observer(database).use { observer ->
        observer.collection("products").insertMany(
            (1..412).map {
                Document("_id", ObjectId()).append("sku", "SKU-$it")
            }
        )
    }
    ShopProcess("shop-staging-1/1", database).use { staging ->
        staging.godwit.migrate(
            shopMigrations(CustomerService(staging.db), StubIdentityProvider()).plusOnceOnly(cartCurrency)
        )
        val merged = shopMigrations(CustomerService(staging.db), StubIdentityProvider())
            .plusOnceOnly(productSlugs, cartCurrency)
        failing("OutOfOrder.FAIL") { staging.godwit.migrate(merged) }
    }
    ShopProcess("shop-staging-1/1", database, GodwitConfig(outOfOrder = OutOfOrder.RUN)).use { staging ->
        logging("OutOfOrder.RUN") {
            staging.godwit.migrate(
                shopMigrations(
                    CustomerService(staging.db),
                    StubIdentityProvider()
                ).plusOnceOnly(productSlugs, cartCurrency)
            )
        }
        Observer(database).use { document("007 out of order", it.history("007-product-slugs")) }
        val older = shopMigrations(CustomerService(staging.db), StubIdentityProvider())
        logging("release 1.3") { staging.godwit.migrate(older) }
    }
    ShopProcess("shop-staging-1/1", database, GodwitConfig(unknownApplied = UnknownApplied.FAIL)).use { strict ->
        failing("UnknownApplied.FAIL") {
            strict.godwit.migrate(shopMigrations(CustomerService(strict.db), StubIdentityProvider()))
        }
    }
}

/**
 * Unknown applied ids: a module that migrates its own two migrations on the shop's database; the shop's list without
 * `009`, after a release that ran it; the list without `reference-countries`.
 */
val unknownAppliedIds = Scenario("unknown applied ids") {
    val database = shopDatabase()
    ShopProcess("shop-7f9c4/1", database).use { shop ->
        val current = shopMigrations(CustomerService(shop.db), StubIdentityProvider())
        logging("the customers module's own list") {
            shop.godwit.migrate(current.filter { it.id in setOf("005-customer-external-ids", "bootstrap-customers") })
        }
        logging("without reference-countries") { shop.godwit.migrate(current.without("reference-countries")) }
        shop.godwit.migrate(current.plusOnceOnly(orderPaymentStatus(StubPaymentGateway())))
        logging("without 009") { shop.godwit.migrate(current) }
    }
}

/**
 * squashing-migrations.md: `100-baseline` replaces `001` to `006`. It is recorded on a database where all six applied,
 * run on a new database, imported with adoption from a database migrated by hand, and refused on a demo database that
 * skipped `005` and `006`. Release N-1 deployed again after the squash finds the baseline unknown.
 */
val squash = Scenario("a squash into 100-baseline") {
    val squashed = { shop: ShopProcess ->
        shopMigrations(CustomerService(shop.db), StubIdentityProvider())
            .filter { it.id in setOf("reference-countries", "bootstrap-customers") }
            .let { listOf(baseline) + it }
    }
    val upToDate = shopDatabase()
    Observer(upToDate).use { observer ->
        ShopProcess("shop-7f9c4/1", upToDate).use { shop ->
            logging("recorded") { shop.godwit.migrate(squashed(shop)) }
            document("recorded baseline", observer.history("100-baseline"))
            logging("release N-1") {
                shop.godwit.migrate(shopMigrations(CustomerService(shop.db), StubIdentityProvider()))
            }
        }
    }
    val new = DocsMongo.newDatabase()
    Observer(new).use { observer ->
        ShopProcess("shop-9a1e2/1", new).use { shop ->
            logging("new database") { shop.godwit.migrate(squashed(shop)) }
            document("baseline that ran", observer.history("100-baseline"))
        }
    }
    val handMigrated = handMigratedDatabase(squashedIds)
    ShopProcess("shop-7f9c4/1", handMigrated, adopting).use { shop ->
        logging("adoption and squash") { shop.godwit.migrate(squashed(shop)) }
    }
    val demo = shopDatabase { it.without("005-customer-external-ids", "006-order-totals") }
    ShopProcess("shop-7f9c4/1", demo).use { shop ->
        failing("demo database") { shop.godwit.migrate(squashed(shop)) }
    }
}
