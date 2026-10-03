package godwit.core.docs

import com.example.shop.services.CustomerService
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import com.mongodb.event.CommandSucceededEvent
import godwit.core.GodwitConfig
import godwit.core.LockConfig
import godwit.core.fixtures.LogCapture
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import org.bson.Document
import java.net.http.HttpTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** The last `_id` of page 1050 of `006-order-totals` in [networkPartition]. */
private const val PAGE_1050_LAST_ID = "66fd0c4e9b1e8a0012a3f5c2"

/**
 * The lock timings of the partitioned holder, scaled down from the defaults so that the run keeps their shape: each
 * renewal that cannot reach the primary fails after the lock operations' 5 s timeout, the second of them ends after
 * the local deadline (`lease - safetyMargin`, 8 s after the last renewal that succeeded), and the tick after it finds
 * the deadline passed.
 */
private val partitionedTimings = LockConfig(lease = 9.seconds, heartbeat = 1.seconds, safetyMargin = 1.seconds)

/**
 * locking.md's network partition during `006-order-totals`: `shop-7f9c4/1` reaches the database through a proxy that
 * cuts it off once page 1051 has been read, right after a renewal. Its renewals fail, its local deadline passes, the
 * page's write finds no primary (its client waits 5 s for one, as long as a lock operation's timeout, so the renewals
 * fail as they do with the default 30 s), and the page, run again by the driver after that server selection error,
 * finds the lock lost; the release cannot reach the database either. `shop-2b8e1/1`, which reaches the database
 * directly, then takes the migration over.
 */
val networkPartition = Scenario("a network partition during 006") {
    val database = shopDatabase { it.without("006-order-totals") }
    Observer(database).use { observer ->
        observer.collection("orders").load(
            (0 until 526_000).asSequence().map { order(objectIdAfter(PAGE_1050_LAST_ID, it - 524_999L)) }
        )
        LogCapture().use { capture ->
            Partition(DocsMongo.address).use { partition ->
                val renewals = object : CommandListener {
                    val succeeded = AtomicInteger()
                    private val sent = ConcurrentHashMap.newKeySet<Int>()

                    override fun commandStarted(event: CommandStartedEvent) {
                        if (event.commandName == "update" &&
                            event.command.getString("update", null)?.value == "godwit-lock"
                        ) {
                            sent += event.requestId
                        }
                    }

                    override fun commandSucceeded(event: CommandSucceededEvent) {
                        if (sent.remove(event.requestId)) succeeded.incrementAndGet()
                    }
                }
                val cut =
                    AtCommand(nth = 1051, onSucceeded = true, matches = isPageRead("orders", "006-order-totals")) {
                        val before = renewals.succeeded.get()
                        await atMost 5.seconds.toJavaDuration() until { renewals.succeeded.get() > before }
                        partition.cut()
                        capture.awaitLogged("Lost migration lock")
                        document("006 while the holder ran it", observer.history("006-order-totals"))
                    }
                val failures = ConcurrentHashMap<String, Throwable>()
                val holder = background("shop-7f9c4", failures) {
                    ShopProcess(
                        "shop-7f9c4/1",
                        database,
                        GodwitConfig(lock = partitionedTimings),
                        listeners = listOf(renewals, cut),
                        connectionString = partition.connectionString,
                        settings = { applyToClusterSettings { it.serverSelectionTimeout(5, TimeUnit.SECONDS) } }
                    ).use { shop ->
                        failing("partitioned holder") {
                            shop.godwit.migrate(shopMigrations(CustomerService(shop.db), StubIdentityProvider()))
                        }
                    }
                }
                holder.awaitEnd()
                failures.values.firstOrNull()?.let { throw it }
                logs("shop-7f9c4/1", capture.events.ofThread("shop-7f9c4"))
            }
            val takeover = AtCommand(matches = isPageRead("orders", "006-order-totals")) {
                document("006 after the takeover", observer.history("006-order-totals"))
            }
            ShopProcess("shop-2b8e1/1", database, listeners = listOf(takeover)).use { shop ->
                logging("shop-2b8e1/1") {
                    shop.godwit.migrate(shopMigrations(CustomerService(shop.db), StubIdentityProvider()))
                }
            }
        }
    }
}

/**
 * failure-and-recovery.md's step error at the moment the lock is lost: `005`'s identity provider call outlasts the
 * holder's local deadline (the renewals are held back meanwhile, as a stop-the-world pause holds the heartbeat), then
 * fails with its own timeout. Nobody has taken `005` over, so its FAILED write matches.
 */
val stepErrorWhenTheLockIsLost = Scenario("a step error as the lock is lost") {
    val database = shopDatabase { it.without("005-customer-external-ids", "006-order-totals") }
    Observer(database).use { observer ->
        observer.collection("customers").insertMany((1..3).map { customer(it, linked = false) })
    }
    LogCapture().use { capture ->
        lateinit var shop: ShopProcess
        val identity = StubIdentityProvider {
            DocsMongo.failCommand(
                shop.appName,
                listOf("update"),
                "alwaysOn",
                Document("blockConnection", true).append("blockTimeMS", 2500)
            ).use { capture.awaitLogged("Lost migration lock") }
            throw HttpTimeoutException("request timed out")
        }
        shop = ShopProcess("shop-7f9c4/1", database, GodwitConfig(lock = shortLease))
        shop.use {
            failing("005") { it.godwit.migrate(shopMigrations(CustomerService(it.db), identity)) }
        }
    }
}
