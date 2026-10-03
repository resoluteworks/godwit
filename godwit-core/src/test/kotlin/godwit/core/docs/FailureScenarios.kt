package godwit.core.docs

import com.example.shop.docs.failure_and_recovery.customerEmailLowerInOneTransaction
import com.example.shop.docs.failure_and_recovery.customerEmailLowerIndexInTransaction
import com.example.shop.docs.failure_and_recovery.orderPaymentStatusFragile
import com.example.shop.docs.history_and_reports.markEmailIndexApplied
import com.example.shop.docs.history_and_reports.printHistory
import com.example.shop.migrations.appliedBeforeGodwit
import com.example.shop.migrations.customerEmailLower
import com.example.shop.migrations.customerEmailLowerIndex
import com.example.shop.migrations.orderPaymentStatus
import com.example.shop.services.CustomerService
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes.ascending
import com.mongodb.client.model.Updates.set
import godwit.core.GodwitConfig
import godwit.core.Migration
import godwit.core.internal.Tuning
import org.bson.BsonDocument
import org.bson.Document
import org.bson.types.ObjectId
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.http.HttpTimeoutException
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The last `_id` of page 40 of `006-order-totals` in [shopHistory]: the orders' `_id`s are 2 apart from the first. */
private const val PAGE_40_LAST_ID = "66fcf2a19b1e8a0012a1c0d4"

/** The orders of [shopHistory]: 20,600, so that page 41 of 500 is full. */
private const val HISTORY_ORDERS = 20_600

/** The order of page 41 whose line has no `unitPriceMinor`. */
private const val BAD_ORDER = 20_250

/** The command a matcher looks for: [name] on [collection]. */
private fun command(name: String, collection: String): (String, BsonDocument) -> Boolean = { commandName, command ->
    commandName == name && command.getString(name, null)?.value == collection
}

/** Runs [block] and returns what it printed to standard output, line by line. */
private fun printed(block: () -> Unit): List<String> {
    val buffer = ByteArrayOutputStream()
    val out = System.out
    System.setOut(PrintStream(buffer, true))
    try {
        block()
    } finally {
        System.setOut(out)
    }
    return buffer.toString().lines().dropLastWhile { it.isEmpty() }
}

/**
 * history-and-reports.md's database, start by start: a shop database migrated by hand to `003`, with `003` and the
 * others in its `schema-log`, adopted by the first godwit release, which also runs the repeatable and every-start
 * migrations. The release that adds `004` to `006` then starts twice: on the first start `004` retries after a write
 * conflict with checkout and `005` fails when the identity provider times out; on the next, `005` applies and `006`
 * fails in page 41 on an order without a price. Once the order is fixed, the next start resumes `006` after page 40.
 */
val shopHistory = Scenario("history-and-reports.md: one database's history") {
    val database = DocsMongo.newDatabase()
    var identityDown = false
    val identity = StubIdentityProvider { if (identityDown) throw HttpTimeoutException("request timed out") }
    Observer(database).use { observer ->
        observer.db.seedCustomers()
        observer.collection("customers").insertMany((1..812).map { customer(it, linked = false) })
        observer.collection("schema-log").insertMany(
            listOf("001-initial-setup", "002-carts", "003-file-store", "2025-02-cart-index-hotfix").map {
                Document("_id", ObjectId()).append("version", it).append("appliedAt", Date())
            }
        )
        observer.collection("orders").load(
            (0 until HISTORY_ORDERS).asSequence().map { i ->
                val id = objectIdAfter(PAGE_40_LAST_ID, 2L * (i - 19_999))
                when {
                    i < 1237 -> order(id, paid = i < 1200)
                    i == BAD_ORDER -> order(id, status = "PAID", lines = listOf(Document("quantity", 1)))
                    else -> order(id, status = "PAID")
                }
            }
        )
        val config = GodwitConfig(adoptApplied = ::appliedBeforeGodwit)
        val firstUpdate = AtCommand(matches = command("update", "orders")) {
            observer.collection("orders").updateOne(
                eq("_id", objectIdAfter(PAGE_40_LAST_ID, -2L * 19_999)),
                set("updatedAt", Date())
            )
        }
        ShopProcess("shop-7f9c4/1", database, config, listeners = listOf(firstUpdate)).use { shop ->
            val current = shopMigrations(CustomerService(shop.db), identity)
            logging("adopting start") { shop.godwit.migrate(current.release1()) }
            document("003 adopted", observer.history("003-file-store"))

            identityDown = true
            failing("deploy, first start") { shop.godwit.migrate(current) }
            identityDown = false
            document("005 failed", observer.history("005-customer-external-ids"))

            failing("deploy, next start") { shop.godwit.migrate(current) }
            document("006 failed", observer.history("006-order-totals"))
            text("printHistory", printed { printHistory(shop.godwit) })

            observer.collection("orders").updateOne(
                eq("_id", objectIdAfter(PAGE_40_LAST_ID, 2L * (BAD_ORDER - 19_999))),
                set("lines", listOf(Document("quantity", 1).append("unitPriceMinor", 1000L)))
            )
            logging("start after the order is fixed") { shop.godwit.migrate(current) }
        }
    }
}

/**
 * failure-and-recovery.md's manual repair: an operator built `008`'s index by hand as `emailLower_unique`, so
 * `008-customer-email-lower-index` fails with IndexOptionsConflict; the operator records it with `markApplied`.
 */
val markedByHand = Scenario("an index built by hand, then markApplied") {
    val database = shopDatabase()
    Observer(database).use { observer ->
        observer.collection("customers").insertMany((1..20).map { customer(it) })
        observer.collection("customers").createIndex(
            ascending("emailLower"),
            IndexOptions().name("emailLower_unique").unique(true)
                .partialFilterExpression(Document("emailLower", Document("\$exists", true)))
        )
        ShopProcess("shop-7f9c4/1", database).use { shop ->
            val list = shopMigrations(CustomerService(shop.db), StubIdentityProvider())
                .plusOnceOnly(customerEmailLower, customerEmailLowerIndex)
            failing("008 on production") { shop.godwit.migrate(list) }
        }
        ShopProcess("ops-laptop-3/48211", database).use { ops ->
            logging("markApplied") { markEmailIndexApplied(ops.godwit) }
        }
        document("008 marked", observer.history("008-customer-email-lower-index"))
    }
}

/**
 * failure-and-recovery.md's transaction past 60 s: `007-customer-email-lower` in one transaction. The server's
 * transaction lifetime is lowered to 1 s, and the server holds the `updateMany` of the customers (the fail point
 * `hangDuringBatchUpdate`, interruptible) until the lifetime ends and the server interrupts it, as the update of 2.4
 * million customers would still be running then.
 */
val transactionPastItsLifetime = Scenario("a transaction past its lifetime") {
    val database = shopDatabase()
    Observer(database).use { it.collection("customers").insertMany((1..20).map { n -> customer(n) }) }
    val holdUpdate = Document("configureFailPoint", "hangDuringBatchUpdate").append("mode", "alwaysOn").append(
        "data",
        Document("shouldCheckForInterrupt", true).append("nss", "$database.customers")
    )
    DocsMongo.admin(Document("setParameter", 1).append("transactionLifetimeLimitSeconds", 1))
    DocsMongo.admin(holdUpdate)
    try {
        val config = GodwitConfig(slowTransactionWarning = 1.seconds)
        ShopProcess("shop-7f9c4/1", database, config, Tuning(lifetimeGuidanceAfter = 1.seconds)).use { shop ->
            val list = shopMigrations(CustomerService(shop.db), StubIdentityProvider())
                .plusOnceOnly(customerEmailLowerInOneTransaction)
            failing("007 in one transaction") { shop.godwit.migrate(list) }
        }
    } finally {
        DocsMongo.admin(Document("configureFailPoint", "hangDuringBatchUpdate").append("mode", "off"))
        DocsMongo.admin(Document("setParameter", 1).append("transactionLifetimeLimitSeconds", 60))
    }
}

/** failure-and-recovery.md's DDL in a transaction: `008`'s index build inside `inTransaction`. */
val ddlInTransaction = Scenario("an index build in a transaction") {
    val database = shopDatabase()
    ShopProcess("shop-7f9c4/1", database).use { shop ->
        val list = shopMigrations(CustomerService(shop.db), StubIdentityProvider())
            .plusOnceOnly(customerEmailLower, customerEmailLowerIndexInTransaction)
        failing("008 in a transaction") { shop.godwit.migrate(list) }
    }
}

/**
 * failure-and-recovery.md's transaction body that throws: the fragile `009` reads the orders again in its
 * transaction, and an order paid after the outside step read the list is not in the prepared map.
 */
val paymentStatusFragile = Scenario("009 reads the orders again in its transaction") {
    val database = shopDatabase()
    Observer(database).use { observer ->
        observer.collection("orders").insertMany(
            (1..5).map { order(ObjectId(), status = "PAID").append("paymentId", "pay-$it") }
        )
        val paidMeanwhile = AtCommand(matches = { name, command ->
            name == "find" && command.getString("find", null)?.value == "godwit-history" &&
                command.getDocument("comment", null)?.getString("godwit", null)?.value == "009-order-payment-status"
        }) {
            observer.collection("orders").insertOne(
                order(ObjectId("66fe2b7c9b1e8a0012a4c3d7"), status = "PAID").append("paymentId", "pay-6")
            )
        }
        ShopProcess("shop-7f9c4/1", database, listeners = listOf(paidMeanwhile)).use { shop ->
            val list = shopMigrations(CustomerService(shop.db), StubIdentityProvider())
                .plusOnceOnly(orderPaymentStatusFragile(StubPaymentGateway()))
            failing("009") { shop.godwit.migrate(list) }
        }
        document("009 failed", observer.history("009-order-payment-status"))
    }
}

/**
 * The shop wired with two clients: one for its services, one for godwit. On a database whose customers are not
 * linked yet, `005` passes godwit's session to the services' client; on a new database, `005` has no one to link, and
 * `bootstrap-customers` is the first to do so.
 */
val twoClients = Scenario("services on another MongoClient") {
    val existing = shopDatabase { it.without("005-customer-external-ids", "006-order-totals") }
    Observer(existing).use { it.collection("customers").insertMany((1..812).map { n -> customer(n, linked = false) }) }
    DocsMongo.client("shop-services").use { services ->
        ShopProcess("shop-7f9c4/1", existing).use { shop ->
            failing("005 with two clients") {
                shop.godwit.migrate(
                    shopMigrations(CustomerService(services.getDatabase(existing)), StubIdentityProvider())
                )
            }
        }
        val fresh = DocsMongo.newDatabase()
        ShopProcess("shop-7f9c4/1", fresh).use { shop ->
            failing("bootstrap-customers with two clients") {
                shop.godwit.migrate(
                    shopMigrations(CustomerService(services.getDatabase(fresh)), StubIdentityProvider())
                )
            }
        }
    }
}

/** A release that adds `004` and `006` on a database with `slowTransactionWarning` below every transaction's time. */
val slowTransactions = Scenario("transactions slower than slowTransactionWarning") {
    val database = shopDatabase { it.release1() }
    val config = GodwitConfig(slowTransactionWarning = 1.milliseconds)
    ShopProcess("shop-7f9c4/1", database, config).use { shop ->
        logging("slow transactions") {
            shop.godwit.migrate(shopMigrations(CustomerService(shop.db), StubIdentityProvider()))
        }
    }
}

/**
 * The two other reasons `Retrying transaction` gives: a network error in the body (the server closes the connection
 * on `004`'s first update) and a transient error on the commit.
 */
val otherRetries = Scenario("a network error in a transaction body, and a transient error on its commit") {
    for ((name, failure) in listOf(
        "network error" to Pair(listOf("update"), Document("closeConnection", true)),
        "commit" to Pair(
            listOf("commitTransaction"),
            Document("errorCode", 112).append("errorLabels", listOf("TransientTransactionError"))
        )
    )) {
        val database = shopDatabase { it.release1() }
        ShopProcess("shop-7f9c4/1", database).use { shop ->
            val data = Document(failure.second)
            if (name == "network error") data.append("namespace", "$database.orders")
            DocsMongo.failCommand(shop.appName, failure.first, Document("times", 1), data).use {
                logging(name) {
                    shop.godwit.migrate(shopMigrations(CustomerService(shop.db), StubIdentityProvider()))
                }
            }
        }
    }
}

/** failure-and-recovery.md's first history read when the cluster is unreachable. */
val clusterUnreachable = Scenario("the cluster unreachable at start") {
    Partition(DocsMongo.address).use { partition ->
        partition.cut()
        ShopProcess(
            "shop-7f9c4/1",
            DocsMongo.newDatabase(),
            connectionString = partition.connectionString,
            settings = { applyToClusterSettings { it.serverSelectionTimeout(500, TimeUnit.MILLISECONDS) } }
        ).use { shop ->
            failing("first history read") {
                shop.godwit.migrate(shopMigrations(CustomerService(shop.db), StubIdentityProvider()))
            }
        }
    }
}

/**
 * batched-backfills.md's orders with two `_id` types: 3,000 imported orders have string `_id`s, the rest ObjectIds.
 * Pages 1 to 6 hold the strings; the check before the last page finds the ObjectIds.
 */
val mixedIdTypes = Scenario("orders with _ids of two types") {
    val database = shopDatabase { it.without("006-order-totals") }
    Observer(database).use { observer ->
        observer.collection("orders").deleteMany(Document())
        observer.collection("orders").load((1..3000).asSequence().map { order("ord-%05d".format(it), status = "PAID") })
        observer.collection("orders").load((1..1000).asSequence().map { order(ObjectId(), status = "PAID") })
    }
    ShopProcess("shop-7f9c4/1", database).use { shop ->
        failing("006 over two _id types") {
            shop.godwit.migrate(shopMigrations(CustomerService(shop.db), StubIdentityProvider()))
        }
    }
}

/** dependencies.md's worker, deployed with a list that adds `009` before the shop has run it. */
val workerBeforeTheShop = Scenario("a worker that starts before the shop migrated") {
    val database = shopDatabase()
    ShopProcess("worker-1/1", database).use { worker ->
        val list = shopMigrations(CustomerService(worker.db), StubIdentityProvider())
            .plusOnceOnly(orderPaymentStatus(StubPaymentGateway()))
        failing("requireUpToDate") { worker.godwit.requireUpToDate(list) }
    }
}
