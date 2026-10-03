package godwit.core.crash

import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.ne
import godwit.core.fixtures.GodwitFixture
import godwit.core.fixtures.LogCapture
import godwit.core.fixtures.TestMongo
import godwit.core.fixtures.line
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import org.bson.Document
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(BatchesCrashTest::class.java)

/** The page after which the child is killed. */
private const val KILLED_AFTER = 7

private const val PAGES = 21

/** The "Committed batch" line of page [batch] of [batchesCrashScenario]: orders 0 to 10049, 500 per page. */
private fun committed(batch: Int) =
    "Committed batch id=006-order-totals batch=$batch lastId=${minOf(batch * 500, CRASH_ORDERS) - 1}"

/** The "Committed batch" lines among a child's output, as the docs print them: message and key-value pairs. */
private fun committedLines(output: List<String>): List<String> =
    output.filter { " - Committed batch " in it }.map { it.substringAfter(" - ").trim() }

/**
 * The measurable outcome of `inBatches`: a child JVM pages 10,050 orders in pages of 500 and is killed after page 7
 * commits; the next start, in this JVM, resumes at page 8. Every order's `$inc` probe ends at exactly 1, the run
 * reports 21 pages, and the "Committed batch" lines of both processes number the pages 1 to 21 without a gap.
 */
class BatchesCrashTest : StringSpec() {
    init {
        "killed after page $KILLED_AFTER commits: the next start resumes at the page after it, every probe at 1" {
            TestMongo.database().use { db ->
                val orders = db.database.getCollection("orders", Document::class.java)
                (0 until CRASH_ORDERS).chunked(1000).forEach { ids ->
                    orders.insertMany(ids.map { Document("_id", it) })
                }

                val child = CrashHarness.crashAfterPage(KILLED_AFTER, TestMongo.connectionString, db.name)

                child.exitCode shouldNotBe 0
                child.openTransactionSession.shouldBeNull()
                val childLines = committedLines(child.output)
                childLines shouldBe (1..KILLED_AFTER).map(::committed)
                val crashed = db.database.getCollection("godwit-history", Document::class.java)
                    .find(eq("_id", "006-order-totals")).first().shouldNotBeNull()
                crashed.getString("state") shouldBe "RUNNING"
                crashed["checkpoint"] shouldBe Document("lastId", KILLED_AFTER * 500 - 1)
                    .append("batches", KILLED_AFTER)
                    .append("counts", Document("ordersUpdated", KILLED_AFTER * 500L))
                orders.countDocuments(eq("probe", 1)) shouldBe KILLED_AFTER * 500L

                GodwitFixture(
                    appName = "crash-parent",
                    config = batchesCrashConfig("crash-parent/1"),
                    db = db
                ).use { f ->
                    LogCapture().use { logs ->
                        val outcome = f.godwit.migrate(batchesCrashScenario())["006-order-totals"]

                        logs.events("Resuming interrupted migration").single().line shouldBe
                            "Resuming interrupted migration id=006-order-totals attempts=2"
                        val parentLines = logs.events("Committed batch").map { it.line }
                        parentLines shouldBe (KILLED_AFTER + 1..PAGES).map(::committed)
                        val applied = logs.events("Applied migration").single().line
                        applied shouldStartWith "Applied migration id=006-order-totals kind=ONCE steps=[IN_BATCHES] " +
                            "attempts=2 txRetries=0 batches=$PAGES durationMs="
                        applied shouldEndWith " ordersUpdated=$CRASH_ORDERS"
                        outcome.batches shouldBe PAGES
                        outcome.attempts shouldBe 2
                        outcome.count("ordersUpdated") shouldBe CRASH_ORDERS.toLong()
                        log.info(
                            "inBatches kill-and-resume trace: child batches {}, parent batches {}; {}",
                            childLines.map { it.substringAfter("batch=").substringBefore(" ") },
                            parentLines.map { it.substringAfter("batch=").substringBefore(" ") },
                            applied
                        )
                    }

                    orders.countDocuments(eq("probe", 1)) shouldBe CRASH_ORDERS.toLong()
                    orders.countDocuments(ne("probe", 1)) shouldBe 0L
                    val stored = f.stored("006-order-totals").shouldNotBeNull()
                    stored.getString("state") shouldBe "APPLIED"
                    stored.getInteger("attempts") shouldBe 2
                    stored.containsKey("checkpoint") shouldBe false
                    stored["counts"] shouldBe Document("ordersUpdated", CRASH_ORDERS.toLong())
                    f.godwit.migrate(batchesCrashScenario()).lockWait.shouldBeNull()
                }
            }
        }
    }
}
