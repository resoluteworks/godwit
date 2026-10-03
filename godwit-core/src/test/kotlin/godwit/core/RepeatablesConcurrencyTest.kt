package godwit.core

import com.mongodb.client.model.Filters.eq
import godwit.core.fixtures.CommandRecorder
import godwit.core.fixtures.GodwitFixture
import godwit.core.fixtures.LogCapture
import godwit.core.fixtures.collection
import godwit.core.fixtures.keyValues
import godwit.core.fixtures.line
import godwit.core.fixtures.referenceCountries
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.slf4j.LoggerFactory
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private val log = LoggerFactory.getLogger(RepeatablesConcurrencyTest::class.java)

/** The processes that start together. */
private const val PROCESSES = 4

/** The revision changes, each followed by one start of every process. */
private const val ROUNDS = 3

private val setup = migration("001-initial-setup").outsideTransaction { ensureCollection("customers") }

/** The runs of `reference-countries` that committed: its probe. */
private fun GodwitFixture.committedRuns(): Int =
    collection("probes").find(eq("_id", "reference-countries")).first().getInteger("n")

/**
 * Several processes, each with a client and a `Godwit` of its own, start together after a revision change: the lock
 * serialises them, and the plan each one makes under the lock finds the revision applied once the first has run it.
 */
class RepeatablesConcurrencyTest : StringSpec() {
    init {
        "$PROCESSES processes that start together after a revision change run the repeatable once, runCount + 1" {
            GodwitFixture(appName = "concurrency-first").use { first ->
                first.godwit.migrate(setup, referenceCountries("r1"))

                // Each process holds its first history read of a start until every process has made its own, so every
                // one of them plans the repeatable as due before any of them takes the lock.
                val together = CyclicBarrier(PROCESSES)
                val armed = List(PROCESSES) { AtomicBoolean(false) }
                val processes = (0 until PROCESSES).map { index ->
                    val recorder = CommandRecorder(onSucceeded = { command ->
                        if (command.name == "find" && command.collection == "godwit-history" &&
                            armed[index].compareAndSet(true, false)
                        ) {
                            together.await(1, TimeUnit.MINUTES)
                        }
                    })
                    first.process("concurrency-$index", recorder = recorder)
                }
                val pool = Executors.newFixedThreadPool(PROCESSES)
                try {
                    for (round in 1..ROUNDS) {
                        val revision = "r${round + 1}"
                        val runCountBefore = first.stored("reference-countries")!!.getLong("runCount")
                        val committedBefore = first.committedRuns()
                        armed.forEach { it.set(true) }

                        withClue("round $round, revision $revision") {
                            LogCapture().use { logs ->
                                val list = listOf(setup, referenceCountries(revision))
                                val starts = processes.map { process ->
                                    pool.submit(Callable { process.godwit.migrate(list) })
                                }
                                val reports = starts.map { it.get(2, TimeUnit.MINUTES) }

                                together.isBroken shouldBe false
                                val (ran, waited) = reports.partition { it.ran.isNotEmpty() }
                                ran.single().ran.map { it.id } shouldBe listOf("reference-countries")
                                ran.single()["reference-countries"].attempts shouldBe 1
                                waited.forEach { report ->
                                    report.upToDate shouldBe listOf("001-initial-setup", "reference-countries")
                                    report.lockWait.shouldNotBeNull()
                                }
                                reports.forEach { it.lockWait.shouldNotBeNull() }

                                val applied = logs.events("Applied migration").single().line
                                applied shouldStartWith
                                    "Applied migration id=reference-countries kind=REPEATABLE steps=[IN_TRANSACTION] "
                                logs.events("Acquired migration lock").size shouldBe PROCESSES
                                val ranPerStart = logs.events("Migrations complete").map { it.keyValues["ran"] as Int }
                                ranPerStart.sorted() shouldBe listOf(0, 0, 0, 1)
                                logs.events("Migrations up to date").shouldBeEmpty()

                                val runCountAfter = first.stored("reference-countries")!!.getLong("runCount")
                                runCountAfter shouldBe runCountBefore + 1
                                first.stored("reference-countries")!!.getString("revision") shouldBe revision
                                first.committedRuns() shouldBe committedBefore + 1
                                log.info(
                                    "repeatables concurrency round={} starts={} runCount={}->{} trace={}",
                                    round,
                                    PROCESSES,
                                    runCountBefore,
                                    runCountAfter,
                                    applied.substringBefore(" durationMs=")
                                )
                            }
                        }
                    }
                } finally {
                    pool.shutdownNow()
                    processes.forEach { it.close() }
                }

                LogCapture().use { logs ->
                    val later = first.godwit.migrate(setup, referenceCountries("r${ROUNDS + 1}"))

                    later.lockWait.shouldBeNull()
                    logs.events.single().message shouldBe "Migrations up to date"
                }
                first.stored("reference-countries")!!.getLong("runCount") shouldBe ROUNDS + 1L
            }
        }
    }
}
