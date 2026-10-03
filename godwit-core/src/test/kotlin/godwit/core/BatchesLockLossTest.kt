package godwit.core

import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Updates.set
import godwit.core.fixtures.CommandRecorder
import godwit.core.fixtures.GodwitFixture
import godwit.core.fixtures.LogCapture
import godwit.core.fixtures.RecordedCommand
import godwit.core.fixtures.TestMongo
import godwit.core.fixtures.awaitHeartbeatLoss
import godwit.core.fixtures.awaitOrFail
import godwit.core.fixtures.blockRenewals
import godwit.core.fixtures.line
import godwit.core.fixtures.probe
import godwit.core.internal.testTimings
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.bson.Document
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes

/**
 * Short lock timings: the local deadline is 2 s after the last renewal was sent, the lease ends 1 s later. The specs
 * seed their orders before the first migration runs, which the untracked-database guard would refuse.
 */
private val shortLock =
    GodwitConfig(lock = testTimings.copy(waitTimeout = 1.minutes), untrackedDatabase = UntrackedDatabase.RUN_ALL)

/** A checkpoint write: the update of a history document, inside a page's transaction, that sets `checkpoint`. */
private fun RecordedCommand.isCheckpointWrite(): Boolean {
    if (name != "update" || command.getString("update").value != "godwit-history") return false
    val set = command.getArray("updates")[0].asDocument().getDocument("u").getDocument("\$set", null)
    return set?.containsKey("checkpoint") == true
}

/**
 * `006-order-totals` over 9 orders in pages of 3, incrementing each order's `probe`; the `_id`s of each page it runs
 * go to [seen], and [onPage] runs after the page's writes, with the number of pages run so far.
 */
private fun totals(seen: MutableList<List<Int>>, onPage: (Int) -> Unit = {}) =
    migration("006-order-totals").inBatches("orders", Document(), batchSize = 3) { page ->
        seen += page.map { it.getInteger("_id") }
        probe("orders", page)
        count("ordersUpdated", page.size)
        onPage(seen.size)
    }

private fun GodwitFixture.insertOrders() = collection("orders").insertMany((1..9).map { Document("_id", it) })

/** How a run that loses the lock between or during pages stops, and how the next holder resumes. */
class BatchesLockLossTest : StringSpec() {
    init {
        "the lock is lost while a page commits: the page rolls back and the takeover resumes after the last page" {
            val held = CountDownLatch(1)
            val takenOver = CountDownLatch(1)
            val checkpointWrites = AtomicInteger()
            val recorderA = CommandRecorder(
                onStarted = { command ->
                    // Page 2 of the stale run has passed its checkLock(); its checkpoint write waits here, unsent,
                    // while the run's renewals fail, its lease ends and another process takes the migration over.
                    if (command.isCheckpointWrite() && checkpointWrites.incrementAndGet() == 2) {
                        val failRenewals = TestMongo.failCommand(
                            "batches-lockloss-stale",
                            listOf("update"),
                            "alwaysOn",
                            Document("errorCode", 13)
                        )
                        held.countDown()
                        takenOver.awaitOrFail()
                        failRenewals.close()
                    }
                }
            )
            val recorderB = CommandRecorder(
                onSucceeded = { command ->
                    if (command.name == "findAndModify" && command.command.getString("findAndModify").value ==
                        "godwit-history"
                    ) {
                        takenOver.countDown()
                    }
                }
            )
            GodwitFixture(appName = "batches-lockloss-stale", config = shortLock, recorder = recorderA).use { a ->
                a.process("batches-lockloss-takeover", shortLock, recorder = recorderB).use { b ->
                    a.insertOrders()
                    val staleSeen = CopyOnWriteArrayList<List<Int>>()
                    val takeoverSeen = CopyOnWriteArrayList<List<Int>>()

                    LogCapture().use { logs ->
                        val stale = CompletableFuture.supplyAsync {
                            runCatching { a.godwit.migrate(totals(staleSeen)) }.exceptionOrNull()
                        }
                        held.awaitOrFail()
                        val report = b.godwit.migrate(totals(takeoverSeen))
                        val staleFailure = stale.get(1, TimeUnit.MINUTES)

                        staleFailure.shouldBeInstanceOf<LockLostException>().id shouldBe "006-order-totals"
                        staleFailure.cause.shouldBeNull()
                        val outcome = report["006-order-totals"]
                        outcome.attempts shouldBe 2
                        outcome.batches shouldBe 3
                        outcome.count("ordersUpdated") shouldBe 9L
                        logs.events("Committed batch").map { it.line } shouldBe listOf(
                            "Committed batch id=006-order-totals batch=1 lastId=3",
                            "Committed batch id=006-order-totals batch=2 lastId=6",
                            "Committed batch id=006-order-totals batch=3 lastId=9"
                        )
                        logs.events("Migration failed").single().line shouldBe
                            "Migration failed id=006-order-totals step=IN_BATCHES attempts=1 " +
                            "error=godwit.core.LockLostException: Lost the migration lock while running " +
                            "006-order-totals"
                    }

                    // The takeover's marker landed after page 2's transaction started, so the held checkpoint write
                    // conflicts with it; the driver runs the page again, and its first checkLock() throws.
                    staleSeen shouldBe listOf(listOf(1, 2, 3), listOf(4, 5, 6))
                    takeoverSeen.first() shouldBe listOf(4, 5, 6)
                    takeoverSeen.last() shouldBe listOf(7, 8, 9)
                    val checkpointWrites = recorderA.commands.filter { it.isCheckpointWrite() }
                    checkpointWrites shouldHaveSize 2
                    checkpointWrites[1].errorCode shouldBe 112
                    a.collection("orders").countDocuments(eq("probe", 1)) shouldBe 9L
                    val stored = a.stored("006-order-totals").shouldNotBeNull()
                    stored.getString("state") shouldBe "APPLIED"
                    stored.getString("holder") shouldBe "batches-lockloss-takeover/1"
                }
            }
        }

        "a page whose transaction sees another run's marker: its fenced checkpoint write matches nothing" {
            lateinit var f: GodwitFixture
            val commits = AtomicInteger()
            val recorder = CommandRecorder(
                onSucceeded = { command ->
                    // Page 1 has committed. Another run's marker takes the document over before page 2 starts, while
                    // this run still holds the lock by its own clock.
                    if (command.name == "commitTransaction" && commits.incrementAndGet() == 1) {
                        f.history.updateOne(eq("_id", "006-order-totals"), set("owner", "token-of-another-run"))
                    }
                }
            )
            f = GodwitFixture(appName = "batches-fenced-checkpoint", config = shortLock, recorder = recorder)
            f.use {
                f.insertOrders()
                val seen = mutableListOf<List<Int>>()

                val lost = LogCapture().use { logs ->
                    val lost = shouldThrow<LockLostException> { f.godwit.migrate(totals(seen)) }
                    logs.events("Lost migration lock").shouldBeEmpty()
                    lost
                }

                lost.id shouldBe "006-order-totals"
                lost.cause.shouldBeNull()
                seen shouldBe listOf(listOf(1, 2, 3), listOf(4, 5, 6))
                val checkpointWrites = f.recorder.commands.filter { it.isCheckpointWrite() }
                checkpointWrites shouldHaveSize 2
                checkpointWrites[1].reply.shouldNotBeNull().getInt32("n").value shouldBe 0
                f.recorder.commands("commitTransaction") shouldHaveSize 1
                f.collection("orders").countDocuments(eq("probe", 1)) shouldBe 3L
                val stored = f.stored("006-order-totals").shouldNotBeNull()
                stored.getString("state") shouldBe "RUNNING"
                stored.getString("owner") shouldBe "token-of-another-run"
                stored.get("checkpoint", Document::class.java).getInteger("batches") shouldBe 1
            }
        }

        "a page step that returns after the lock was lost: the check before its checkpoint rolls the page back" {
            LogCapture().use { logs ->
                GodwitFixture(appName = "batches-lockloss-step", config = shortLock).use { f ->
                    f.insertOrders()
                    val seen = mutableListOf<List<Int>>()
                    val losing = totals(seen) { pages ->
                        if (pages == 2) blockRenewals(f.appName).use { logs.awaitHeartbeatLoss() }
                    }

                    val lost = shouldThrow<LockLostException> { f.godwit.migrate(losing) }

                    lost.cause.shouldBeNull()
                    seen shouldBe listOf(listOf(1, 2, 3), listOf(4, 5, 6))
                    f.recorder.commands.filter { it.isCheckpointWrite() } shouldHaveSize 1
                    f.collection("orders").countDocuments(eq("probe", 1)) shouldBe 3L
                    val stored = f.stored("006-order-totals").shouldNotBeNull()
                    stored.getString("state") shouldBe "RUNNING"
                    stored.containsKey("lastError") shouldBe false
                    stored["checkpoint"] shouldBe
                        Document("lastId", 3).append("batches", 1).append("counts", Document("ordersUpdated", 3L))

                    f.process("batches-lockloss-step-next", shortLock).use { next ->
                        seen.clear()
                        val outcome = next.godwit.migrate(totals(seen))["006-order-totals"]

                        seen shouldBe listOf(listOf(4, 5, 6), listOf(7, 8, 9))
                        outcome.attempts shouldBe 2
                        outcome.batches shouldBe 3
                        logs.events("Resuming interrupted migration").single().line shouldBe
                            "Resuming interrupted migration id=006-order-totals attempts=2"
                    }
                    f.collection("orders").countDocuments(eq("probe", 1)) shouldBe 9L
                }
            }
        }

        "the lock lost between pages: the check at the start of the next page stops it before its read" {
            LogCapture().use { logs ->
                val commits = AtomicInteger()
                val recorder = CommandRecorder(
                    onSucceeded = { command ->
                        // Page 1 has committed. The lock is lost before the next page's transaction starts.
                        if (command.name == "commitTransaction" && commits.incrementAndGet() == 1) {
                            blockRenewals("batches-lockloss-between").use { logs.awaitHeartbeatLoss() }
                        }
                    }
                )
                GodwitFixture(appName = "batches-lockloss-between", config = shortLock, recorder = recorder).use { f ->
                    f.insertOrders()
                    val seen = mutableListOf<List<Int>>()

                    val lost = shouldThrow<LockLostException> { f.godwit.migrate(totals(seen)) }

                    lost.cause.shouldBeNull()
                    seen shouldBe listOf(listOf(1, 2, 3))
                    f.recorder.commands("find").filter { it.command.getString("find").value == "orders" } shouldHaveSize
                        1
                    logs.events("Migration failed").single().line shouldEndWith
                        "error=godwit.core.LockLostException: Lost the migration lock while running 006-order-totals"
                    val stored = f.stored("006-order-totals").shouldNotBeNull()
                    stored.getString("state") shouldBe "RUNNING"
                    stored.get("checkpoint", Document::class.java).getInteger("batches") shouldBe 1
                }
            }
        }
    }
}
