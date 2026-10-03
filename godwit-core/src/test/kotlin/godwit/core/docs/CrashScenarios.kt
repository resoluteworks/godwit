package godwit.core.docs

import com.example.shop.services.CustomerService
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import com.mongodb.kotlin.client.MongoClient
import godwit.core.Godwit
import godwit.core.GodwitConfig
import godwit.core.Migration
import godwit.core.crash.CRASH_POINT_LINE
import godwit.core.crash.CrashHarness
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/** Where the shop's process dies in the docs' crash examples, each marked by a command its client is about to send. */
enum class DocsCrashPoint(
    val list: (List<Migration>) -> List<Migration>,
    val reached: (CommandStartedEvent) -> Boolean
) {
    /** `006-order-totals` has committed page 37 and is about to read page 38, the first command of its transaction. */
    PAGE_38({ it }, pageRead(38)),

    /** `001-initial-setup` has created `customers` and `orders` and their indexes, and not `products`. */
    PRODUCTS({ it }, { it.commandName == "create" && it.command.getString("create").value == "products" }),

    /** `004-order-status` has run both of its updates in its transaction, which is about to commit. */
    COMMIT_004({
        it.without("005-customer-external-ids", "006-order-totals")
    }, { it.commandName == "commitTransaction" })
}

private fun pageRead(page: Int): (CommandStartedEvent) -> Boolean {
    val reads = AtomicInteger()
    val isPageRead = isPageRead("orders", "006-order-totals")
    return { isPageRead(it.commandName, it.command) && reads.incrementAndGet() == page }
}

/** The holder of the process that dies, and its lock timings: its lease ends within 3 s of its last renewal. */
private val dyingProcess = GodwitConfig(lock = shortLease, holder = "shop-7f9c4/1")

/**
 * The child JVM of the docs' crash examples: `DocsCrashMainKt <connection string> <database> <point>`. It migrates the
 * shop's list for [DocsCrashPoint] as `shop-7f9c4/1` and stops dead when its client is about to send the point's
 * command: it prints [CRASH_POINT_LINE] with the session of an open transaction, and blocks until the parent kills it.
 * It exits with 3 when the run ends first.
 */
fun main(args: Array<String>) {
    val (connectionString, database, pointName) = args
    val point = DocsCrashPoint.valueOf(pointName)
    val stop = object : CommandListener {
        override fun commandStarted(event: CommandStartedEvent) {
            if (!point.reached(event)) return
            val session = event.command.getDocument("lsid", null)
                ?.takeIf { event.command.containsKey("txnNumber") && event.commandName == "commitTransaction" }
            println("$CRASH_POINT_LINE $pointName lsid=${session?.toJson()}")
            System.out.flush()
            Thread.sleep(Long.MAX_VALUE)
        }
    }
    val settings = MongoClientSettings.builder()
        .applyConnectionString(ConnectionString(connectionString))
        .applicationName("shop-7f9c4")
        .addCommandListener(stop)
        .build()
    MongoClient.create(settings).use { client ->
        val list = point.list(shopMigrations(CustomerService(client.getDatabase(database)), StubIdentityProvider()))
        Godwit(client, database, dyingProcess).migrate(list)
    }
    println("$CRASH_POINT_LINE not reached: the migrations finished")
    exitProcess(3)
}

/** Kills a child JVM running the shop on [database] at [point], and ends the transaction it left open, if any. */
private fun killShopAt(point: DocsCrashPoint, database: String) {
    val child = CrashHarness.crash(
        "godwit.core.docs.CrashScenariosKt",
        point.name,
        DocsMongo.connectionString,
        database
    )
    DocsMongo.client("docs-crash-cleanup").use { CrashHarness.abortOpenTransaction(it, child) }
}

/** The first `_id` of the orders of [killedDuringPage38]: page 37 ends 18,499 after it. */
private const val PAGE_37_LAST_ID = "66f1c3e2a8b4d10f2e7c9a40"

/**
 * batched-backfills.md: the pod running `006-order-totals` over 120,318 orders is killed during page 38. The next
 * process waits for the dead pod's lease, runs the outside step again and continues after page 37.
 */
val killedDuringPage38 = Scenario("006 killed during page 38") {
    val database = shopDatabase { it.without("006-order-totals") }
    Observer(database).use { observer ->
        observer.collection("orders").load(
            (0 until 120_318 - 1237).asSequence().map { order(objectIdAfter(PAGE_37_LAST_ID, it - 18_499L)) }
        )
        killShopAt(DocsCrashPoint.PAGE_38, database)
        document("006 after the kill", observer.history("006-order-totals"))
        ShopProcess("shop-2b8e1/1", database).use { shop ->
            logging("next start") {
                shop.godwit.migrate(shopMigrations(CustomerService(shop.db), StubIdentityProvider()))
            }
        }
    }
}

/**
 * failure-and-recovery.md: the pod running `001-initial-setup` on a new database is killed after creating `customers`
 * and `orders`, before `products`. The next start resumes it.
 */
val killedMidOutsideStep = Scenario("001 killed mid outside step") {
    val database = DocsMongo.newDatabase()
    Observer(database).use { observer ->
        killShopAt(DocsCrashPoint.PRODUCTS, database)
        document("001 after the kill", observer.history("001-initial-setup"))
        ShopProcess("shop-2b8e1/1", database).use { shop ->
            logging("next start") {
                shop.godwit.migrate(shopMigrations(CustomerService(shop.db), StubIdentityProvider()))
            }
        }
    }
}

/**
 * failure-and-recovery.md: the pod is killed while `004-order-status`'s transaction is open, both updates run, the
 * commit not sent. The next start, after the lease, resumes it.
 */
val killedMidTransaction = Scenario("004 killed mid transaction") {
    val database = shopDatabase { it.release1() }
    killShopAt(DocsCrashPoint.COMMIT_004, database)
    ShopProcess("shop-2b8e1/1", database).use { shop ->
        logging("next start") {
            shop.godwit.migrate(
                shopMigrations(CustomerService(shop.db), StubIdentityProvider())
                    .without("005-customer-external-ids", "006-order-totals")
            )
        }
    }
}
