package godwit.core

import com.mongodb.client.model.Filters.eq
import godwit.core.fixtures.GodwitFixture
import godwit.core.fixtures.HeldAcknowledgement
import godwit.core.fixtures.LogCapture
import godwit.core.fixtures.TestMongo
import godwit.core.fixtures.keyValues
import godwit.core.fixtures.line
import godwit.core.fixtures.probe
import godwit.core.fixtures.seeded
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.bson.Document
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** A run of a page's body: the body's [attempt] and the `_id`s of its page. */
private data class PageRun(val attempt: Int, val ids: List<Int>)

/**
 * `006-order-totals` over [collection] in pages of 3, incrementing each order's `probe` and counting `ordersUpdated`.
 * Each run of a page's body is added to [runs], with the `batches` of the stored checkpoint as the body starts in
 * [checkpoints] when it is given; [onPage] runs after the page's writes, with the number of body runs so far.
 */
private fun GodwitFixture.totals(
    collection: String,
    runs: MutableList<PageRun>,
    checkpoints: MutableList<Int?>? = null,
    onPage: (Int) -> Unit = {}
) = migration("006-order-totals").inBatches(collection, Document(), batchSize = 3) { page ->
    runs += PageRun(attempt, page.map { it.getInteger("_id") })
    checkpoints?.add(stored("006-order-totals")!!.get("checkpoint", Document::class.java)?.getInteger("batches"))
    probe(collection, page)
    count("ordersUpdated", page.size)
    onPage(runs.size)
}

private fun GodwitFixture.insertOrders() = collection("orders").insertMany((1..7).map { Document("_id", it) })

/** How a retried commit of a page's transaction keeps every page committed exactly once. */
class BatchesCommitRetryTest : StringSpec() {
    init {
        "a transient error on a page's commit runs that page again; the checkpoint advances only when it commits" {
            GodwitFixture(appName = "batches-commit-transient", config = seeded).use { f ->
                f.insertOrders()
                val runs = mutableListOf<PageRun>()
                val checkpoints = mutableListOf<Int?>()
                val transient = Document("errorCode", 112).append("errorLabels", listOf("TransientTransactionError"))
                var failCommit: AutoCloseable? = null
                val totals = f.totals("orders", runs, checkpoints) { run ->
                    if (run == 2) {
                        failCommit = TestMongo.failCommand(
                            f.appName,
                            listOf("commitTransaction"),
                            Document("times", 1),
                            transient
                        )
                    }
                }
                f.recorder.clear()

                val outcome = LogCapture().use { logs ->
                    val outcome = f.godwit.migrate(totals)["006-order-totals"]
                    failCommit?.close()
                    logs.events("Retrying transaction").single().line shouldBe
                        "Retrying transaction id=006-order-totals attempt=2 error=commit"
                    logs.events("Committed batch").map { it.keyValues["batch"] } shouldBe listOf(1, 2, 3)
                    logs.events("Applied migration").single().keyValues["txRetries"] shouldBe 1
                    outcome
                }

                runs shouldBe listOf(
                    PageRun(1, listOf(1, 2, 3)),
                    PageRun(1, listOf(4, 5, 6)),
                    PageRun(2, listOf(4, 5, 6)),
                    PageRun(1, listOf(7))
                )
                checkpoints shouldBe listOf(null, 1, 1, 2)
                // The last page's read commits once before the check for other _id types and once with APPLIED.
                f.recorder.commands("commitTransaction").map { it.errorCode } shouldBe
                    listOf(null, 112, null, null, null)
                outcome.transactionRetries shouldBe 1
                outcome.batches shouldBe 3
                outcome.count("ordersUpdated") shouldBe 7L
                f.stored("006-order-totals")!!.getInteger("transactionRetries") shouldBe 1
                f.collection("orders").countDocuments(eq("probe", 1)) shouldBe 7L
            }
        }

        "an UnknownTransactionCommitResult on a page's commit retries the commit alone" {
            GodwitFixture(appName = "batches-commit-unknown", config = seeded).use { f ->
                f.insertOrders()
                val runs = mutableListOf<PageRun>()
                val checkpoints = mutableListOf<Int?>()
                val unknown = Document("errorCode", 8).append("errorLabels", listOf("UnknownTransactionCommitResult"))
                var failCommit: AutoCloseable? = null
                val totals = f.totals("orders", runs, checkpoints) { run ->
                    if (run == 2) {
                        failCommit = TestMongo.failCommand(
                            f.appName,
                            listOf("commitTransaction"),
                            Document("times", 1),
                            unknown
                        )
                    }
                }
                f.recorder.clear()

                val outcome = LogCapture().use { logs ->
                    val outcome = f.godwit.migrate(totals)["006-order-totals"]
                    failCommit?.close()
                    logs.events("Retrying transaction").shouldBeEmpty()
                    outcome
                }

                runs.map { it.attempt } shouldBe listOf(1, 1, 1)
                checkpoints shouldBe listOf(null, 1, 2)
                // Page 2's commit is sent twice; the last page's read commits before the type check and with APPLIED.
                f.recorder.commands("commitTransaction") shouldHaveSize 5
                outcome.transactionRetries shouldBe 0
                outcome.batches shouldBe 3
                f.collection("orders").countDocuments(eq("probe", 1)) shouldBe 7L
            }
        }

        "the last page's commit applies and then times out on the client: APPLIED, every page counted and logged" {
            // 7 orders end with a page of one, 6 with a read that finds nothing. Either way two full pages commit, and
            // the last read commits twice, before the check for other _id types and with APPLIED: the fourth commit.
            for ((orders, pages) in mapOf(7 to 3, 6 to 2)) {
                withClue("$orders orders") {
                    val commits = AtomicInteger()
                    val held = HeldAcknowledgement { it.name == "commitTransaction" && commits.incrementAndGet() == 4 }
                    GodwitFixture(
                        appName = "batches-last-commit-timeout",
                        config = seeded,
                        recorder = held.recorder,
                        // The client gives up on the held commit after this long. Each earlier transaction and the
                        // check must finish within it too, which leaves room for a loaded machine.
                        timeout = 5.seconds
                    ).use { f ->
                        f.collection("orders").insertMany((1..orders).map { Document("_id", it) })
                        val runs = mutableListOf<PageRun>()

                        val outcome = LogCapture().use { logs ->
                            val outcome = f.godwit.migrate(f.totals("orders", runs))["006-order-totals"]
                            logs.events("Migration failed").shouldBeEmpty()
                            logs.events("Committed batch").map { it.keyValues["batch"] } shouldBe (1..pages).toList()
                            logs.events("Applied migration").single().keyValues["batches"] shouldBe pages
                            outcome
                        }

                        val commit = f.recorder.commands("commitTransaction").last()
                        commit.finished shouldBe true
                        commit.reply shouldBe null
                        runs.map { it.ids } shouldBe (1..orders).chunked(3)
                        outcome.batches shouldBe pages
                        outcome.count("ordersUpdated") shouldBe orders.toLong()
                        val stored = f.stored("006-order-totals").shouldNotBeNull()
                        stored.getString("state") shouldBe "APPLIED"
                        stored.containsKey("lastError") shouldBe false
                        stored.containsKey("checkpoint") shouldBe false
                        f.collection("orders").countDocuments(eq("probe", 1)) shouldBe orders.toLong()
                    }
                }
            }
        }
    }
}
