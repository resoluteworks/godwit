package godwit.core

import ch.qos.logback.classic.Level
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.Filters.`in`
import com.mongodb.client.model.Updates.set
import godwit.core.fixtures.GodwitFixture
import godwit.core.fixtures.LogCapture
import godwit.core.fixtures.keyValues
import godwit.core.fixtures.line
import godwit.core.fixtures.probe
import godwit.core.fixtures.seeded
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.bson.BsonInt32
import org.bson.Document
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/** The `_id`s of [page], which the specs of this file insert as ints. */
private fun ids(page: List<Document>): List<Int> = page.map { it.getInteger("_id") }

/** Documents with the int `_id`s [ids] in [collection], through the test's own client. */
private fun GodwitFixture.insert(collection: String, ids: IntRange) {
    if (!ids.isEmpty()) collection(collection).insertMany(ids.map { Document("_id", it) })
}

/** The number of documents of [collection] whose probe is exactly 1. */
private fun GodwitFixture.probedOnce(collection: String): Long = collection(collection).countDocuments(eq("probe", 1))

/** How `inBatches` pages a collection, per batched-backfills.md and architecture.md's "Checkpoint writes". */
class BatchesTest : StringSpec() {
    init {
        "pages of 4 over 0, 1, 3, 4, 5 and 8 documents: the step never sees an empty page, the last page applies" {
            GodwitFixture(appName = "batches-sizes", config = seeded).use { f ->
                val cases = mapOf(
                    0 to emptyList(),
                    1 to listOf(listOf(1)),
                    3 to listOf(listOf(1, 2, 3)),
                    4 to listOf(listOf(1, 2, 3, 4)),
                    5 to listOf(listOf(1, 2, 3, 4), listOf(5)),
                    8 to listOf(listOf(1, 2, 3, 4), listOf(5, 6, 7, 8))
                )
                for ((size, pages) in cases) {
                    withClue("$size documents") {
                        // No document is inserted for 0, so the collection does not exist.
                        val orders = "orders-$size"
                        val id = "006-order-totals-$size"
                        f.insert(orders, 1..size)
                        val seen = mutableListOf<List<Int>>()
                        val totals = migration(id).inBatches(orders, Document(), batchSize = 4) { page ->
                            seen += ids(page)
                            probe(orders, page)
                            count("ordersUpdated", page.size)
                        }
                        f.recorder.clear()

                        LogCapture().use { logs ->
                            val outcome = f.godwit.migrate(totals)[id]

                            seen shouldBe pages
                            outcome.steps shouldBe listOf(StepKind.IN_BATCHES)
                            outcome.batches shouldBe pages.size
                            outcome.count("ordersUpdated") shouldBe size.toLong()
                            logs.events("Committed batch").map { it.line } shouldBe pages.mapIndexed { index, page ->
                                "Committed batch id=$id batch=${index + 1} lastId=${page.last()}"
                            }
                            logs.events("Committed batch").forEach { it.level shouldBe Level.DEBUG }
                            logs.events("Applied migration").single().keyValues["batches"] shouldBe pages.size
                        }
                        // One commit per full page, and two for the last read: one before the check for other _id
                        // types, which runs between them, and one with APPLIED. A run that reads nothing at all has no
                        // _id type to check, and commits once.
                        f.recorder.commands("commitTransaction") shouldHaveSize size / 4 + if (size == 0) 1 else 2
                        val stored = f.stored(id).shouldNotBeNull()
                        stored.getString("state") shouldBe "APPLIED"
                        stored.containsKey("checkpoint") shouldBe false
                        stored["counts"] shouldBe
                            if (size == 0) Document() else Document("ordersUpdated", size.toLong())
                        f.probedOnce(orders) shouldBe size.toLong()
                    }
                }
            }
        }

        "each page's transaction is timed on its own: a slow page logs Slow transaction before its Committed batch" {
            GodwitFixture(config = seeded.copy(slowTransactionWarning = 500.milliseconds)).use { f ->
                f.insert("orders", 1..5)
                val totals = migration("006-order-totals").inBatches("orders", Document(), batchSize = 2) { page ->
                    if (ids(page) == listOf(3, 4)) Thread.sleep(600)
                    probe("orders", page)
                }

                LogCapture().use { logs ->
                    f.godwit.migrate(totals)

                    // Other pages may be slow too on a loaded machine; the second page's line sits between the
                    // Committed batch lines of the first and second pages.
                    val timeline = logs.events.filter { it.message in setOf("Slow transaction", "Committed batch") }
                    val secondPage = timeline
                        .dropWhile { it.line != "Committed batch id=006-order-totals batch=1 lastId=2" }
                        .drop(1)
                        .takeWhile { it.message != "Committed batch" }
                    val slow = secondPage.single()
                    slow.message shouldBe "Slow transaction"
                    slow.keyValues["id"] shouldBe "006-order-totals"
                    slow.keyValues["attempt"] shouldBe 1
                    slow.keyValues["durationMs"].shouldBeInstanceOf<Long>() shouldBeGreaterThanOrEqual 600L
                    logs.events("Committed batch").map { it.keyValues["batch"] } shouldBe listOf(1, 2, 3)
                }
            }
        }

        "pending is evaluated on every page: a document the app changes so it stops matching is not read" {
            GodwitFixture(config = seeded).use { f ->
                f.insert("orders", 1..7)
                val seen = mutableListOf<List<Int>>()
                val totals = migration("006-order-totals")
                    .inBatches("orders", exists("totalMinor", false), batchSize = 3) { page ->
                        seen += ids(page)
                        // The app totals order 5 while the first page runs; the next page's read no longer selects it.
                        if (seen.size == 1) f.collection("orders").updateOne(eq("_id", 5), set("totalMinor", 99L))
                        collection("orders").updateMany(
                            session,
                            `in`(
                                "_id",
                                page.map {
                                    it["_id"]
                                }
                            ),
                            set("totalMinor", 1L)
                        )
                    }

                val outcome = f.godwit.migrate(totals)["006-order-totals"]

                seen shouldBe listOf(listOf(1, 2, 3), listOf(4, 6, 7))
                outcome.batches shouldBe 2
                f.collection("orders").find(eq("_id", 5)).first().getLong("totalMinor") shouldBe 99L
                f.collection("orders").countDocuments(eq("totalMinor", 1L)) shouldBe 6L
            }
        }

        "a document the step leaves matching pending is not read again: the _id cursor moves past it" {
            GodwitFixture(config = seeded).use { f ->
                f.collection("customers").insertMany(
                    (1..5).map { Document("_id", it).apply { if (it % 2 == 1) append("email", "C$it@Example.com") } }
                )
                val seen = mutableListOf<List<Int>>()
                val emailLower = migration("007-customer-email-lower")
                    .inBatches("customers", exists("emailLower", false), batchSize = 2) { customers ->
                        seen += ids(customers)
                        val withEmail = customers.filter { it.getString("email") != null }
                        withEmail.forEach { customer ->
                            collection("customers").updateOne(
                                session,
                                eq("_id", customer["_id"]),
                                set("emailLower", customer.getString("email").lowercase())
                            )
                        }
                        count("customersUpdated", withEmail.size)
                        count("customersWithoutEmail", customers.size - withEmail.size)
                    }

                val outcome = f.godwit.migrate(emailLower)["007-customer-email-lower"]

                seen shouldBe listOf(listOf(1, 2), listOf(3, 4), listOf(5))
                outcome.counts shouldBe mapOf("customersUpdated" to 3L, "customersWithoutEmail" to 2L)
                f.collection("customers").countDocuments(exists("emailLower", false)) shouldBe 2L
                f.stored("007-customer-email-lower")!!.getString("state") shouldBe "APPLIED"
            }
        }

        "the outside step runs first; each page commits a checkpoint with the counters so far, the outcome adds up" {
            GodwitFixture(config = seeded).use { f ->
                f.insert("orders", 1..5)
                val events = mutableListOf<String>()
                val checkpoints = mutableListOf<Document?>()
                val totals = migration("006-order-totals")
                    .outsideTransaction {
                        events += "outside"
                        count("indexesCreated", 1)
                    }
                    .inBatches("orders", Document(), batchSize = 2) { page ->
                        events += "page ${ids(page)}"
                        checkpoints += f.stored("006-order-totals")!!.get("checkpoint", Document::class.java)
                        probe("orders", page)
                        count("ordersUpdated", page.size)
                        count("pages", 1)
                    }

                val outcome = f.godwit.migrate(totals)["006-order-totals"]

                events shouldBe listOf("outside", "page [1, 2]", "page [3, 4]", "page [5]")
                checkpoints shouldBe listOf(
                    null,
                    Document("lastId", 2).append("batches", 1)
                        .append("counts", Document("ordersUpdated", 2L).append("pages", 1L)),
                    Document("lastId", 4).append("batches", 2)
                        .append("counts", Document("ordersUpdated", 4L).append("pages", 2L))
                )
                outcome.steps shouldBe listOf(StepKind.OUTSIDE_TRANSACTION, StepKind.IN_BATCHES)
                outcome.batches shouldBe 3
                outcome.transactionRetries shouldBe 0
                outcome.counts shouldBe mapOf("indexesCreated" to 1L, "ordersUpdated" to 5L, "pages" to 3L)
                outcome.counts.keys.toList() shouldBe listOf("indexesCreated", "ordersUpdated", "pages")
                val stored = f.stored("006-order-totals").shouldNotBeNull()
                stored["counts"] shouldBe Document("indexesCreated", 1L).append("ordersUpdated", 5L).append("pages", 3L)
                stored.getInteger("transactionRetries") shouldBe 0
                stored.containsKey("checkpoint") shouldBe false
                f.probedOnce("orders") shouldBe 5L
            }
        }

        "a page that throws rolls back alone: FAILED with the checkpoint kept, and the next start continues there" {
            GodwitFixture(config = seeded).use { f ->
                f.insert("orders", 1..7)
                val outsideRuns = AtomicInteger()
                var broken = true
                val seen = mutableListOf<List<Int>>()
                fun totals(batchSize: Int) = migration("006-order-totals")
                    .outsideTransaction { outsideRuns.incrementAndGet() }
                    .inBatches("orders", Document(), batchSize) { page ->
                        seen += ids(page)
                        probe("orders", page)
                        count("ordersUpdated", page.size)
                        if (broken && 5 in ids(page)) error("order 5 has a line without unitPriceMinor")
                    }

                val failure = LogCapture().use { logs ->
                    val failure = shouldThrow<MigrationFailedException> { f.godwit.migrate(totals(batchSize = 2)) }
                    logs.events.filter {
                        it.message in setOf("Committed batch", "Migration failed")
                    }.map { it.line } shouldBe
                        listOf(
                            "Committed batch id=006-order-totals batch=1 lastId=2",
                            "Committed batch id=006-order-totals batch=2 lastId=4",
                            "Migration failed id=006-order-totals step=IN_BATCHES attempts=1 " +
                                "error=java.lang.IllegalStateException: order 5 has a line without unitPriceMinor"
                        )
                    failure
                }

                failure.id shouldBe "006-order-totals"
                failure.step shouldBe StepKind.IN_BATCHES
                failure.message shouldBe
                    "Migration 006-order-totals failed in IN_BATCHES: order 5 has a line without unitPriceMinor"
                failure.report.ran.shouldBeEmpty()
                val failed = f.stored("006-order-totals").shouldNotBeNull()
                failed.getString("state") shouldBe "FAILED"
                failed["checkpoint"] shouldBe
                    Document("lastId", 4).append("batches", 2).append("counts", Document("ordersUpdated", 4L))
                failed.get("lastError", Document::class.java).getString("step") shouldBe "IN_BATCHES"
                f.probedOnce("orders") shouldBe 4L
                val checkpoint = f.godwit.history().single().checkpoint.shouldNotBeNull()
                checkpoint.lastId shouldBe BsonInt32(4)
                checkpoint.batches shouldBe 2

                // Fixed and deployed with pages of 3: the run resumes after the same document, the outside step first.
                broken = false
                seen.clear()
                val outcome = LogCapture().use { logs ->
                    val outcome = f.godwit.migrate(totals(batchSize = 3))["006-order-totals"]
                    logs.events("Committed batch").map { it.line } shouldBe
                        listOf("Committed batch id=006-order-totals batch=3 lastId=7")
                    outcome
                }

                seen shouldBe listOf(listOf(5, 6, 7))
                outsideRuns.get() shouldBe 2
                outcome.attempts shouldBe 2
                outcome.batches shouldBe 3
                outcome.count("ordersUpdated") shouldBe 7L
                val applied = f.stored("006-order-totals").shouldNotBeNull()
                applied.getString("state") shouldBe "APPLIED"
                applied.containsKey("checkpoint") shouldBe false
                applied.containsKey("lastError") shouldBe false
                f.probedOnce("orders") shouldBe 7L
            }
        }
    }
}
