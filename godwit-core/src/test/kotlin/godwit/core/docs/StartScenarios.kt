package godwit.core.docs

import com.example.shop.migrations.carts
import com.example.shop.migrations.orderStatus
import com.example.shop.services.CustomerService
import godwit.core.Godwit
import godwit.core.GodwitConfig
import godwit.core.LockConfig
import godwit.core.LockLostException
import godwit.core.fixtures.LogCapture
import godwit.core.fixtures.standaloneMongo
import org.bson.Document
import org.bson.types.ObjectId
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Short lock timings, for the scenarios that wait for a lease to end: a lost or dead holder's lease ends within 3 s. */
val shortLease = LockConfig(lease = 3.seconds, heartbeat = 500.milliseconds, safetyMargin = 1.seconds)

/** README.md: the first start on a new database runs `002-carts` and `004-order-status`; a later start finds nothing. */
val readme = Scenario("README: the first and a later start") {
    ShopProcess("shop-7f9c4/1", DocsMongo.newDatabase()).use { shop ->
        val migrations = listOf(carts, orderStatus)
        logging("first start") { shop.godwit.migrate(migrations) }
        logging("later start") { shop.godwit.migrate(migrations) }
    }
}

/**
 * The shop's releases on one database, as the docs follow them: release 1 runs `001` to `003` and the repeatable and
 * every-start migrations on a new database; the app takes 1,237 orders, 1,200 of them paid; release 2 adds
 * `004-order-status`; release 3 adds `005` and `006`, the current list. Then the starts of the current release, which
 * run `bootstrap-customers`, 214 runs in all, the last on `shop-c55d0/1`. Last, the release that bumps
 * `reference-countries` to `2026-11-15`.
 */
val shopReleases = Scenario("the shop's releases") {
    val database = DocsMongo.newDatabase()
    var holding: (() -> Unit)? = null
    val identity = StubIdentityProvider { holding?.invoke() }
    Observer(database).use { observer ->
        ShopProcess("shop-7f9c4/1", database).use { shop ->
            val current = shopMigrations(CustomerService(shop.db), identity)
            shop.godwit.migrate(current.without("004-order-status", "005-customer-external-ids", "006-order-totals"))
            document("reference-countries applied", observer.history("reference-countries"))
            shop.collection("orders").load((0 until 1237).asSequence().map { order(ObjectId(), paid = it < 1200) })

            logging("release adding 004") {
                shop.godwit.migrate(current.without("005-customer-external-ids", "006-order-totals"))
            }
            document("004 applied", observer.history("004-order-status"))
            shop.godwit.migrate(current)

            holding = { document("lock held", observer.lock()) }
            logging("start of the current release") { shop.godwit.migrate(current) }
            holding = null
            document("lock released", observer.lock())
            repeat(209) { shop.godwit.migrate(current) }
        }
        ShopProcess("shop-c55d0/1", database).use { shop ->
            shop.godwit.migrate(shopMigrations(CustomerService(shop.db), identity))
            document("bootstrap-customers after 214 starts", observer.history("bootstrap-customers"))
        }
        ShopProcess("shop-7f9c4/1", database).use { shop ->
            val bumped = shopMigrations(CustomerService(shop.db), identity)
                .map { if (it.id == "reference-countries") referenceCountriesNovember else it }
            shop.godwit.migrate(bumped)
            document("reference-countries bumped", observer.history("reference-countries"))
        }
    }
}

/**
 * The shop's list without its every-start migration, from its first release on: a start of the current release finds
 * nothing due, reads history once and takes no lock.
 */
val shopWithoutEveryStart = Scenario("the shop's list without bootstrap-customers") {
    ShopProcess("shop-7f9c4/1", DocsMongo.newDatabase()).use { shop ->
        val current = shopMigrations(CustomerService(shop.db), StubIdentityProvider()).without("bootstrap-customers")
        shop.godwit.migrate(current.release1())
        shop.collection("orders").load((0 until 1237).asSequence().map { order(ObjectId(), paid = it < 1200) })
        shop.godwit.migrate(current)
        logging("later start") { shop.godwit.migrate(current) }
    }
}

/**
 * locking.md's rolling deploy: release `2026.10.2` adds `006-order-totals` on a database at `005` with 1,199,873
 * orders. `shop-7f9c4/1` takes the lock and runs `006` (2400 pages of 500) and `bootstrap-customers`; `shop-2b8e1/1`
 * finds `006` due, waits for the lock, logging the holder every 10 s, and runs only `bootstrap-customers`. The first
 * pod's `bootstrap-customers` waits until the second has logged twice, as the 2.5 minutes of the docs' run would.
 */
val rollingDeploy = Scenario("locking.md: a rolling deploy that adds 006") {
    val database = DocsMongo.newDatabase()
    ShopProcess("shop-7f9c4/1", database).use { setup ->
        setup.godwit.migrate(
            shopMigrations(CustomerService(setup.db), StubIdentityProvider()).without("006-order-totals")
        )
        setup.collection("orders").load((0 until 1_199_873).asSequence().map { order(ObjectId(), status = "PAID") })
    }
    LogCapture().use { capture ->
        val failures = ConcurrentHashMap<String, Throwable>()
        val first = background("shop-7f9c4", failures) {
            ShopProcess("shop-7f9c4/1", database).use { shop ->
                val identity = StubIdentityProvider { capture.awaitLogged("Waiting for migration lock", count = 2) }
                shop.godwit.migrate(shopMigrations(CustomerService(shop.db), identity))
            }
        }
        capture.awaitLogged("Acquired migration lock")
        val second = background("shop-2b8e1", failures) {
            ShopProcess("shop-2b8e1/1", database).use { shop ->
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
 * repeatable-migrations.md's rollout without an every-start migration: a new revision of `reference-countries` runs on
 * the first pod; the second, which read history before it applied, waits, finds it applied under the lock and runs
 * nothing.
 */
val nothingDueUnderTheLock = Scenario("a pod that finds the work done once it holds the lock") {
    val database = DocsMongo.newDatabase()
    val current = { shop: ShopProcess -> shopMigrations(CustomerService(shop.db), StubIdentityProvider()) }
    ShopProcess("shop-7f9c4/1", database).use { shop -> shop.godwit.migrate(current(shop)) }
    val bumped = { shop: ShopProcess ->
        current(shop).without("bootstrap-customers")
            .map { if (it.id == "reference-countries") referenceCountriesNovember else it }
    }
    LogCapture().use { capture ->
        val failures = ConcurrentHashMap<String, Throwable>()
        val secondRead = Gate()
        val holdTheFirst = AtCommand(matches = { name, command ->
            name == "update" && command.getString("update", null)?.value == "countries"
        }) { secondRead.pass() }
        val first = background("shop-7f9c4", failures) {
            ShopProcess("shop-7f9c4/1", database, listeners = listOf(holdTheFirst)).use { shop ->
                shop.godwit.migrate(bumped(shop))
            }
        }
        capture.awaitLogged("Acquired migration lock")
        val second = background("shop-2b8e1", failures) {
            val readHistory = AtCommand(onSucceeded = true, matches = { name, command ->
                name == "find" && command.getString("find", null)?.value == "godwit-history"
            }) { secondRead.open() }
            ShopProcess("shop-2b8e1/1", database, listeners = listOf(readHistory)).use { shop ->
                shop.godwit.migrate(bumped(shop))
            }
        }
        first.awaitEnd()
        second.awaitEnd()
        failures.values.firstOrNull()?.let { throw it }
        logs("shop-2b8e1/1", capture.events.ofThread("shop-2b8e1"))
    }
}

/**
 * failure-and-recovery.md's lock wait timeout: `shop-c55d0/1` waits for the lock while `shop-7f9c4/1` runs
 * `006-order-totals`, and gives up when its `waitTimeout` passes.
 */
val lockWaitTimeout = Scenario("a lock wait that times out") {
    val database = DocsMongo.newDatabase()
    ShopProcess("shop-7f9c4/1", database).use { setup ->
        setup.godwit.migrate(
            shopMigrations(CustomerService(setup.db), StubIdentityProvider()).without("006-order-totals")
        )
        setup.collection("orders").load((0 until 1000).asSequence().map { order(ObjectId(), status = "PAID") })
    }
    val failures = ConcurrentHashMap<String, Throwable>()
    val timedOut = Gate()
    val firstPage = AtCommand(matches = isPageRead("orders", "006-order-totals")) { timedOut.pass() }
    LogCapture().use { capture ->
        val holder = background("shop-7f9c4", failures) {
            ShopProcess("shop-7f9c4/1", database, listeners = listOf(firstPage)).use { shop ->
                shop.godwit.migrate(shopMigrations(CustomerService(shop.db), StubIdentityProvider()))
            }
        }
        capture.awaitLogged("Acquired migration lock")
        val config = GodwitConfig(lock = LockConfig(waitTimeout = 2.seconds))
        ShopProcess("shop-c55d0/1", database, config).use { shop ->
            failing("waiting process") {
                shop.godwit.migrate(shopMigrations(CustomerService(shop.db), StubIdentityProvider()))
            }
        }
        timedOut.open()
        holder.awaitEnd()
        failures.values.firstOrNull()?.let { throw it }
    }
}

/**
 * locking.md's edge case: an operator deletes the lock document while `shop-7f9c4/1` runs `006-order-totals`. The next
 * renewal matches nothing, and the run fails at its next check.
 */
val lockDocumentDeleted = Scenario("the lock document deleted during a run") {
    val database = DocsMongo.newDatabase()
    ShopProcess("shop-7f9c4/1", database).use { setup ->
        setup.godwit.migrate(
            shopMigrations(CustomerService(setup.db), StubIdentityProvider()).without("006-order-totals")
        )
        setup.collection("orders").load((0 until 1000).asSequence().map { order(ObjectId(), status = "PAID") })
    }
    LogCapture().use { capture ->
        val deleted = AtCommand(matches = isPageRead("orders", "006-order-totals")) {
            Observer(database).use { it.collection("godwit-lock").deleteMany(Document()) }
            capture.awaitLogged("Lost migration lock")
        }
        ShopProcess(
            "shop-7f9c4/1",
            database,
            GodwitConfig(lock = shortLease),
            listeners = listOf(deleted)
        ).use { shop ->
            val thrown = runCatching {
                shop.godwit.migrate(shopMigrations(CustomerService(shop.db), StubIdentityProvider()))
            }.exceptionOrNull()
            check(thrown is LockLostException) { "the run ended with $thrown" }
            exception("deleted lock", thrown)
        }
        logs("deleted lock", capture.events)
    }
}

/**
 * A standalone `mongod`, which has no transactions: the shop's first start on a new database finds transactional steps
 * due and stops before the lock.
 */
val standaloneServer = Scenario("the shop on a standalone server") {
    val client = standaloneMongo.client("shop-7f9c4")
    client.use {
        val database = DocsMongo.newDatabase()
        val godwit = Godwit(it, database, GodwitConfig(holder = "shop-7f9c4/1"))
        failing("first start") {
            godwit.migrate(shopMigrations(CustomerService(it.getDatabase(database)), StubIdentityProvider()))
        }
    }
}
