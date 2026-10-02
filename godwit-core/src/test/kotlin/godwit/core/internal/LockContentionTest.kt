package godwit.core.internal

import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Updates.set
import godwit.core.fixtures.TestMongo
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bson.Document
import org.slf4j.LoggerFactory
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

private val log = LoggerFactory.getLogger(LockContentionTest::class.java)

private const val PROCESSES = 8
private const val ACQUISITIONS = 50

class LockContentionTest : StringSpec() {
    init {
        "8 processes acquiring and releasing 50 times each never hold the lock at the same moment" {
            TestMongo.database().use { db ->
                val processes = (1..PROCESSES).map {
                    LockProcess(db, "contention-$it", testTimings.copy(waitTimeout = 5.minutes))
                }
                val counted = db.database.getCollection("counter", Document::class.java)
                counted.insertOne(Document("_id", "c").append("n", 0))
                val holders = AtomicInteger()
                val maxHolders = AtomicInteger()
                val pool = Executors.newFixedThreadPool(PROCESSES)
                val began = TimeSource.Monotonic.markNow()
                try {
                    val start = CountDownLatch(1)
                    val runs = processes.map { p ->
                        val counter = p.client.getDatabase(db.name).getCollection("counter", Document::class.java)
                        pool.submit(
                            Callable {
                                start.await()
                                repeat(ACQUISITIONS) { i ->
                                    val held = p.lock.acquire("${p.name}-$i")
                                    try {
                                        maxHolders.accumulateAndGet(holders.incrementAndGet()) { a, b -> maxOf(a, b) }
                                        // A read, then a write of what it read: two holders at once lose an update.
                                        val n = counter.find(eq("_id", "c")).first().getInteger("n")
                                        counter.updateOne(eq("_id", "c"), set("n", n + 1))
                                        held.checkLock(null)
                                    } finally {
                                        holders.decrementAndGet()
                                        held.release()
                                    }
                                }
                            }
                        )
                    }
                    start.countDown()
                    runs.forEach { it.get(10, TimeUnit.MINUTES) }
                } finally {
                    pool.shutdownNow()
                    processes.forEach { it.close() }
                }

                maxHolders.get() shouldBe 1
                val total = counted.find().first().getInteger("n")
                total shouldBe PROCESSES * ACQUISITIONS
                log.info(
                    "lock contention processes={} acquisitions={} maxConcurrentHolders={} durationMs={}",
                    PROCESSES,
                    total,
                    maxHolders.get(),
                    began.elapsedNow().inWholeMilliseconds
                )
            }
        }
    }
}
