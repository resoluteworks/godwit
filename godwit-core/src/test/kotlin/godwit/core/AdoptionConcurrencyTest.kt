package godwit.core

import godwit.core.fixtures.CommandRecorder
import godwit.core.fixtures.CountingHook
import godwit.core.fixtures.GodwitFixture
import godwit.core.fixtures.LogCapture
import godwit.core.fixtures.StandaloneProcess
import godwit.core.fixtures.awaitOrFail
import godwit.core.fixtures.collection
import godwit.core.fixtures.keyValues
import godwit.core.fixtures.line
import godwit.core.fixtures.origins
import godwit.core.fixtures.plantAdopted
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.bson.Document
import org.slf4j.LoggerFactory
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private val log = LoggerFactory.getLogger(AdoptionConcurrencyTest::class.java)

/** The processes that start together. */
private const val PROCESSES = 4

private val oldIds = listOf("001-initial-setup", "002-carts", "003-file-store")

private fun adopted(id: String): Migration =
    migration(id).outsideTransaction { error("$id is adopted, so it never runs") }

/** The list adoption covers entirely: nothing is left to run once the hook's ids are recorded. */
private val adoptedOnly = oldIds.map(::adopted)

/**
 * Starts [PROCESSES] processes on [first]'s database with [config] and [list], each holding its first history read
 * until every one has made its own, so every one plans before any takes the lock. Returns their reports.
 */
private fun startTogether(first: GodwitFixture, config: GodwitConfig, list: List<Migration>): List<MigrationReport> {
    val together = CyclicBarrier(PROCESSES)
    val processes = (0 until PROCESSES).map { index ->
        val armed = AtomicBoolean(true)
        val recorder = CommandRecorder(onSucceeded = { command ->
            if (command.name == "find" && command.collection == "godwit-history" && armed.compareAndSet(true, false)) {
                together.await(1, TimeUnit.MINUTES)
            }
        })
        first.process("adoption-concurrency-$index", config, recorder = recorder)
    }
    val pool = Executors.newFixedThreadPool(PROCESSES)
    try {
        val starts = processes.map { process -> pool.submit(Callable { process.godwit.migrate(list) }) }
        return starts.map { it.get(2, TimeUnit.MINUTES) }.also { together.isBroken shouldBe false }
    } finally {
        pool.shutdownNow()
        processes.forEach { it.close() }
    }
}

/** Concurrent starts on a database adoption takes over: the lock serialises the hook's calls. */
class AdoptionConcurrencyTest : StringSpec() {
    init {
        "$PROCESSES processes start on an unadopted database with a migration left to run: the hook runs once" {
            val hook = CountingHook(*oldIds.toTypedArray())
            val config = GodwitConfig(adoptApplied = hook)
            GodwitFixture(appName = "adoption-concurrency-setup").use { first ->
                first.collection("schema-log").insertOne(Document("version", "003-file-store"))
                val list = adoptedOnly + migration("004-order-status").inTransaction { count("ordersPaid", 0) }

                LogCapture().use { logs ->
                    val reports = startTogether(first, config, list)

                    hook.calls shouldBe 1
                    val adoptedLine = logs.events("Adopted applied migrations").single().line
                    adoptedLine shouldBe
                        "Adopted applied migrations adopted=[001-initial-setup, 002-carts, 003-file-store] ignored=[]"
                    val (adopting, waited) = reports.partition { it.recorded.isNotEmpty() }
                    adopting.single().recorded.map { it.id } shouldBe oldIds
                    adopting.single().ran.map { it.id } shouldBe listOf("004-order-status")
                    waited.forEach { report ->
                        report.ran.shouldBeEmpty()
                        report.recorded.shouldBeEmpty()
                        report.upToDate shouldBe oldIds + "004-order-status"
                        report.lockWait.shouldNotBeNull()
                    }
                    logs.events("Acquired migration lock").size shouldBe PROCESSES
                    logs.events("Migrations complete").map { it.keyValues["recorded"] as Int }.sorted() shouldBe
                        listOf(0, 0, 0, 3)
                    log.info("adoption concurrency starts={} hookCalls={} trace={}", PROCESSES, hook.calls, adoptedLine)
                }
                first.history.origins() shouldBe oldIds.map { "$it ADOPTED" } + "004-order-status RAN"
            }
        }

        "with a list adoption covers entirely, every process that waited calls the hook again and records nothing" {
            val hook = CountingHook(*oldIds.toTypedArray())
            val config = GodwitConfig(adoptApplied = hook)
            GodwitFixture(appName = "adoption-covered-setup").use { first ->
                LogCapture().use { logs ->
                    val reports = startTogether(first, config, adoptedOnly)

                    hook.calls shouldBe PROCESSES
                    logs.events("Adopted applied migrations").map { it.keyValues["adopted"] as List<*> }
                        .sortedBy { it.size } shouldBe listOf(emptyList<String>(), emptyList(), emptyList(), oldIds)
                    reports.forEach { it.ran.shouldBeEmpty() }
                    reports.map { it.recorded.size }.sorted() shouldBe listOf(0, 0, 0, 3)
                }
                first.history.origins() shouldBe oldIds.map { "$it ADOPTED" }
            }
        }

        "on a standalone server, a process that reads a partial adoption before the lock waits and does not refuse it" {
            val list = listOf(
                adopted("001-a"),
                adopted("002-b"),
                adopted("003-c"),
                migration("004-d").outsideTransaction { count("runs", 1) }
            )
            val holderInHook = CountDownLatch(1)
            val otherRefused = CountDownLatch(1)
            val holderHook = CountingHook("001-a", "002-b", "003-c", onCall = {
                holderInHook.countDown()
                otherRefused.awaitOrFail()
            })
            val otherHook = CountingHook("001-a", "002-b", "003-c")
            val otherRecorder = CommandRecorder(onSucceeded = { command ->
                if (command.name == "findAndModify" && command.collection == "godwit-lock") otherRefused.countDown()
            })
            StandaloneProcess("adoption-partial-holder", GodwitConfig(adoptApplied = holderHook)).use { holder ->
                // An earlier adoption on this standalone server was interrupted after its first two writes.
                holder.history.plantAdopted("002-b", "003-c")
                holder.process("adoption-partial-other", GodwitConfig(adoptApplied = otherHook), otherRecorder)
                    .use { other ->
                        val pool = Executors.newSingleThreadExecutor()
                        try {
                            val holding = pool.submit(Callable { holder.godwit.migrate(list) })
                            holderInHook.awaitOrFail()

                            val waited = other.godwit.migrate(list)

                            val adopted = holding.get(1, TimeUnit.MINUTES)
                            adopted.recorded.map { it.id } shouldBe listOf("001-a")
                            adopted.ran.map { it.id } shouldBe listOf("004-d")
                            waited.ran.shouldBeEmpty()
                            waited.recorded.shouldBeEmpty()
                            waited.upToDate shouldBe listOf("001-a", "002-b", "003-c", "004-d")
                            waited.lockWait.shouldNotBeNull()
                            // At least one refused acquire while the holder adopted, then the one that took the lock.
                            val acquires = other.recorder.commands("findAndModify").filter {
                                it.collection ==
                                    "godwit-lock"
                            }
                            acquires.size shouldBeGreaterThan 1
                        } finally {
                            pool.shutdownNow()
                        }
                    }
                holderHook.calls shouldBe 1
                otherHook.calls shouldBe 0
                holder.history.origins() shouldBe listOf("001-a ADOPTED", "002-b ADOPTED", "003-c ADOPTED", "004-d RAN")
            }
        }
    }
}
