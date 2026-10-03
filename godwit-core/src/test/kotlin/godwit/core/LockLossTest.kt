package godwit.core

import com.mongodb.MongoCommandException
import com.mongodb.MongoOperationTimeoutException
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates.inc
import godwit.core.fixtures.CommandRecorder
import godwit.core.fixtures.GodwitFixture
import godwit.core.fixtures.LogCapture
import godwit.core.fixtures.RecordedCommand
import godwit.core.fixtures.TestMongo
import godwit.core.fixtures.awaitHeartbeatLoss
import godwit.core.fixtures.awaitOrFail
import godwit.core.fixtures.blockRenewals
import godwit.core.fixtures.keyValues
import godwit.core.fixtures.line
import godwit.core.internal.testTimings
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.bson.Document
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Short lock timings: the local deadline is 2 s after the last renewal was sent, the lease ends 1 s later. */
private val shortLock = GodwitConfig(lock = testTimings.copy(waitTimeout = 1.minutes))

/** Waits until this run has lost the lock, checking it the way a step's loop does. */
private fun StepScope.awaitLockLoss() {
    val deadline = TimeSource.Monotonic.markNow() + 20.seconds
    while (runCatching { checkLock() }.isSuccess) {
        check(!deadline.hasPassedNow()) { "the lock was not lost within 20 s" }
        Thread.sleep(20)
    }
}

private fun TransactionScope.probe() {
    collection("probes").updateOne(session, eq("_id", id), inc("n", 1), UpdateOptions().upsert(true))
}

private fun RecordedCommand.isHistory(name: String) =
    this.name == name && command.getString(name).value == "godwit-history"

/** The update of a history document that records it APPLIED. */
private fun RecordedCommand.recordsApplied(): Boolean {
    if (!isHistory("update")) return false
    val set = command.getArray("updates")[0].asDocument().getDocument("u").getDocument("\$set", null)
    return set?.getString("state", null)?.value == "APPLIED"
}

/** How a run that lost the lock stops, and what it leaves in history. */
class LockLossTest : StringSpec() {
    init {
        "a marker that lands after another run took the lock over stops its run before any step, and costs a retry" {
            val blocked = CountDownLatch(1)
            val aMarked = CountDownLatch(1)
            var block: AutoCloseable? = null
            val historyReads = AtomicInteger()
            val recorderA = CommandRecorder(
                onSucceeded = { command ->
                    // The second history read is the one under the lock: hold back the marker and the renewals.
                    if (command.isHistory("find") && historyReads.incrementAndGet() == 2) {
                        block = TestMongo.failCommand(
                            "lockloss-stale",
                            listOf("findAndModify", "update"),
                            "alwaysOn",
                            Document("blockConnection", true).append("blockTimeMS", 8000)
                        )
                        blocked.countDown()
                    }
                    if (command.isHistory("findAndModify")) {
                        block?.close()
                        aMarked.countDown()
                    }
                }
            )
            GodwitFixture(appName = "lockloss-stale", config = shortLock, recorder = recorderA).use { a ->
                a.process("lockloss-takeover", shortLock).use { b ->
                    val outsideRuns = mutableListOf<String>()
                    val staleList = listOf(
                        migration("004-order-status").outsideTransaction {
                            outsideRuns += "stale"
                        }.inTransaction { probe() }
                    )
                    val takeoverList = listOf(
                        migration("004-order-status")
                            .outsideTransaction {
                                outsideRuns += "takeover"
                                aMarked.awaitOrFail()
                            }
                            .inTransaction { probe() }
                    )

                    LogCapture().use { logs ->
                        val stale = CompletableFuture.supplyAsync {
                            runCatching { a.godwit.migrate(staleList) }.exceptionOrNull()
                        }
                        blocked.awaitOrFail()
                        val takeover = runCatching { b.godwit.migrate(takeoverList) }.exceptionOrNull()
                        val staleFailure = stale.get(1, TimeUnit.MINUTES)

                        staleFailure.shouldBeInstanceOf<LockLostException>().id shouldBe "004-order-status"
                        staleFailure.cause.shouldBeNull()
                        takeover.shouldBeInstanceOf<LockLostException>().cause.shouldBeNull()
                        outsideRuns shouldBe listOf("takeover")
                        logs.events("Lost migration lock").single().line shouldEndWith
                            "holder=lockloss-stale/1 reason=DEADLINE_PASSED"
                        logs.events("Migration failed").single().line shouldEndWith
                            "error=godwit.core.LockLostException: Lost the migration lock while running 004-order-status"
                    }

                    val takenOver = a.stored("004-order-status").shouldNotBeNull()
                    takenOver.getString("state") shouldBe "RUNNING"
                    takenOver.getString("holder") shouldBe "lockloss-stale/1"
                    takenOver.getInteger("attempts") shouldBe 2
                    a.collection("probes").countDocuments() shouldBe 0L

                    b.process("lockloss-next", shortLock).use { next ->
                        LogCapture().use { logs ->
                            val report = next.godwit.migrate(staleList)
                            report["004-order-status"].attempts shouldBe 3
                            logs.events("Resuming interrupted migration").single().line shouldBe
                                "Resuming interrupted migration id=004-order-status attempts=3"
                        }
                    }
                    a.collection("probes").find().first().getInteger("n") shouldBe 1
                }
            }
        }

        "a heartbeat blocked during a long step: the step's next checkLock() throws and the transaction aborts" {
            GodwitFixture(appName = "lockloss-heartbeat", config = shortLock).use { f ->
                var block: AutoCloseable? = null
                val long = migration("006-order-totals").inTransaction {
                    collection("orders").insertOne(session, Document("totalMinor", 1))
                    block = blockRenewals(f.appName)
                    while (true) {
                        checkLock()
                        Thread.sleep(20)
                    }
                }

                LogCapture().use { logs ->
                    val lost = shouldThrow<LockLostException> { f.godwit.migrate(long) }
                    block?.close()

                    lost.cause.shouldBeNull()
                    logs.events("Lost migration lock").single().line shouldEndWith "reason=DEADLINE_PASSED"
                    logs.events("Migration failed").single().line shouldBe
                        "Migration failed id=006-order-totals step=IN_TRANSACTION attempts=1 " +
                        "error=godwit.core.LockLostException: Lost the migration lock while running 006-order-totals"
                }
                f.collection("orders").countDocuments() shouldBe 0L
                val stored = f.stored("006-order-totals").shouldNotBeNull()
                stored.getString("state") shouldBe "RUNNING"
                stored.containsKey("lastError") shouldBe false
            }
        }

        "a step error once the deadline has passed is recorded FAILED and becomes the cause of LockLostException" {
            GodwitFixture(appName = "lockloss-step-error", config = shortLock).use { f ->
                val timeout = IllegalStateException("request timed out")
                val linking = migration("005-customer-external-ids").outsideTransaction {
                    blockRenewals(f.appName).use { awaitLockLoss() }
                    throw timeout
                }

                LogCapture().use { logs ->
                    val lost = shouldThrow<LockLostException> { f.godwit.migrate(linking) }

                    lost.cause shouldBeSameInstanceAs timeout
                    lost.suppressed.toList().shouldBeEmpty()
                    logs.events("Migration failed").single().line shouldBe
                        "Migration failed id=005-customer-external-ids step=OUTSIDE_TRANSACTION attempts=1 " +
                        "error=java.lang.IllegalStateException: request timed out"
                }
                val stored = f.stored("005-customer-external-ids").shouldNotBeNull()
                stored.getString("state") shouldBe "FAILED"
                stored.get("lastError", Document::class.java).getString("message") shouldBe "request timed out"
            }
        }

        "a FAILED write that fails after the lock was lost is attached to LockLostException as suppressed" {
            GodwitFixture(appName = "lockloss-write-fails", config = shortLock).use { f ->
                var failWrite: AutoCloseable? = null
                val linking = migration("005-customer-external-ids").outsideTransaction {
                    blockRenewals(f.appName).use { awaitLockLoss() }
                    failWrite =
                        TestMongo.failCommand(
                            f.appName,
                            listOf("update"),
                            Document("times", 1),
                            Document("errorCode", 13)
                        )
                    throw IllegalStateException("request timed out")
                }

                val lost = shouldThrow<LockLostException> { f.godwit.migrate(linking) }
                failWrite?.close()

                lost.suppressed.single().shouldBeInstanceOf<MongoCommandException>().code shouldBe 13
                f.stored("005-customer-external-ids")!!.getString("state") shouldBe "RUNNING"
            }
        }

        "a step error after another run's marker took the document keeps the cause; the FAILED write matches nothing" {
            val stalled = CountDownLatch(1)
            val takenOver = CountDownLatch(1)
            val staleDone = CountDownLatch(1)
            val recorderB = CommandRecorder(onSucceeded = { if (it.isHistory("findAndModify")) takenOver.countDown() })
            GodwitFixture(appName = "lockloss-cause-stale", config = shortLock).use { a ->
                a.process("lockloss-cause-takeover", shortLock, recorder = recorderB).use { b ->
                    val timeout = IllegalStateException("request timed out")
                    val staleList = listOf(
                        migration("005-customer-external-ids").outsideTransaction {
                            blockRenewals(a.appName).use { awaitLockLoss() }
                            stalled.countDown()
                            takenOver.awaitOrFail()
                            throw timeout
                        }
                    )
                    val takeoverList = listOf(
                        migration("005-customer-external-ids").outsideTransaction { staleDone.awaitOrFail() }
                    )

                    val takeover = CompletableFuture.supplyAsync {
                        stalled.awaitOrFail()
                        b.godwit.migrate(takeoverList)
                    }
                    val lost = shouldThrow<LockLostException> { a.godwit.migrate(staleList) }
                    staleDone.countDown()
                    val report = takeover.get(1, TimeUnit.MINUTES)

                    lost.cause shouldBeSameInstanceAs timeout
                    report["005-customer-external-ids"].attempts shouldBe 2
                    val stored = a.stored("005-customer-external-ids").shouldNotBeNull()
                    stored.getString("state") shouldBe "APPLIED"
                    stored.getString("holder") shouldBe "lockloss-cause-takeover/1"
                    stored.containsKey("lastError") shouldBe false
                }
            }
        }

        "a network error in a transaction once the deadline has passed: the driver runs the body again, which stops" {
            GodwitFixture(appName = "lockloss-network", config = shortLock).use { f ->
                val attempts = mutableListOf<Int>()
                val page = migration("006-order-totals").inTransaction {
                    attempts += attempt
                    if (attempt == 1) {
                        blockRenewals(f.appName).use { awaitLockLoss() }
                        TestMongo.failCommand(
                            f.appName,
                            listOf("insert"),
                            Document("times", 1),
                            Document("closeConnection", true)
                        ).use { collection("orders").insertOne(session, Document("totalMinor", 1)) }
                    }
                }

                LogCapture().use { logs ->
                    val lost = shouldThrow<LockLostException> { f.godwit.migrate(page) }

                    lost.cause.shouldBeNull()
                    logs.events("Retrying transaction").single().line shouldBe
                        "Retrying transaction id=006-order-totals attempt=2 error=MongoSocketReadException (-2)"
                }
                attempts shouldBe listOf(1)
                f.collection("orders").countDocuments() shouldBe 0L
                f.stored("006-order-totals")!!.getString("state") shouldBe "RUNNING"
            }
        }

        "a client-side timeout in a transaction once the deadline has passed is the cause of LockLostException" {
            GodwitFixture(appName = "lockloss-timeout", config = shortLock, timeout = 1500.milliseconds).use { f ->
                val attempts = mutableListOf<Int>()
                val page = migration("006-order-totals").inTransaction {
                    attempts += attempt
                    blockRenewals(f.appName).use { awaitLockLoss() }
                    collection("orders").insertOne(session, Document("totalMinor", 1))
                }

                val lost = shouldThrow<LockLostException> { f.godwit.migrate(page) }

                lost.cause.shouldBeInstanceOf<MongoOperationTimeoutException>()
                attempts shouldBe listOf(1)
                f.collection("orders").countDocuments() shouldBe 0L
                val stored = f.stored("006-order-totals").shouldNotBeNull()
                stored.getString("state") shouldBe "FAILED"
                stored.get("lastError", Document::class.java).getString("type") shouldBe
                    "com.mongodb.MongoOperationTimeoutException"
            }
        }

        "a transaction body that returns after the heartbeat lost the lock: the check after it stops the commit" {
            LogCapture().use { logs ->
                GodwitFixture(appName = "lockloss-body-returns", config = shortLock).use { f ->
                    val page = migration("006-order-totals").inTransaction {
                        probe()
                        blockRenewals(f.appName).use { logs.awaitHeartbeatLoss() }
                    }

                    val lost = shouldThrow<LockLostException> { f.godwit.migrate(page) }

                    lost.cause.shouldBeNull()
                    logs.events("Lost migration lock").single().threadName shouldStartWith "godwit-heartbeat-"
                    logs.events("Migration failed").single().line shouldBe
                        "Migration failed id=006-order-totals step=IN_TRANSACTION attempts=1 " +
                        "error=godwit.core.LockLostException: Lost the migration lock while running 006-order-totals"
                    f.recorder.commands.none { it.recordsApplied() } shouldBe true
                    f.recorder.commands("commitTransaction").shouldBeEmpty()
                    f.collection("probes").countDocuments() shouldBe 0L
                    val stored = f.stored("006-order-totals").shouldNotBeNull()
                    stored.getString("state") shouldBe "RUNNING"
                    stored.containsKey("lastError") shouldBe false
                }
            }
        }

        "an outside-only step that returns after the heartbeat lost the lock: nothing records it APPLIED" {
            LogCapture().use { logs ->
                GodwitFixture(appName = "lockloss-outside-returns", config = shortLock).use { f ->
                    val carts = migration("002-carts").outsideTransaction {
                        ensureCollection("carts")
                        blockRenewals(f.appName).use { logs.awaitHeartbeatLoss() }
                    }

                    val lost = shouldThrow<LockLostException> { f.godwit.migrate(carts) }

                    lost.cause.shouldBeNull()
                    logs.events("Lost migration lock").single().threadName shouldStartWith "godwit-heartbeat-"
                    logs.events("Migration failed").single().line shouldBe
                        "Migration failed id=002-carts step=OUTSIDE_TRANSACTION attempts=1 " +
                        "error=godwit.core.LockLostException: Lost the migration lock while running 002-carts"
                    f.recorder.commands.none { it.recordsApplied() } shouldBe true
                    val stored = f.stored("002-carts").shouldNotBeNull()
                    stored.getString("state") shouldBe "RUNNING"
                    stored.containsKey("lastError") shouldBe false
                }
            }
        }

        "a two-step migration whose outside step returns after the lock was lost stops before its transaction" {
            LogCapture().use { logs ->
                GodwitFixture(appName = "lockloss-between-steps", config = shortLock).use { f ->
                    var transactionRan = false
                    val linking = migration("005-customer-external-ids")
                        .outsideTransaction { blockRenewals(f.appName).use { logs.awaitHeartbeatLoss() } }
                        .inTransaction { transactionRan = true }

                    val lost = shouldThrow<LockLostException> { f.godwit.migrate(linking) }

                    lost.cause.shouldBeNull()
                    transactionRan shouldBe false
                    logs.events("Migration failed").single().line shouldBe
                        "Migration failed id=005-customer-external-ids step=OUTSIDE_TRANSACTION attempts=1 " +
                        "error=godwit.core.LockLostException: Lost the migration lock while running " +
                        "005-customer-external-ids"
                    f.stored("005-customer-external-ids")!!.getString("state") shouldBe "RUNNING"
                }
            }
        }

        "a lock lost after one migration committed stops the call before the next migration's marker" {
            LogCapture().use { logs ->
                val recorder = CommandRecorder(onSucceeded = { command ->
                    // 004 has committed. The lock is lost before the runner moves on to 005, with no check between.
                    if (command.name == "commitTransaction") {
                        blockRenewals("lockloss-between-migrations").use { logs.awaitHeartbeatLoss() }
                    }
                })
                GodwitFixture("lockloss-between-migrations", shortLock, recorder = recorder).use { f ->
                    var outsideRan = false
                    val orderStatus = migration("004-order-status").inTransaction { probe() }
                    val linking = migration("005-customer-external-ids").outsideTransaction { outsideRan = true }

                    val lost = shouldThrow<LockLostException> { f.godwit.migrate(orderStatus, linking) }

                    lost.id shouldBe "005-customer-external-ids"
                    lost.cause.shouldBeNull()
                    outsideRan shouldBe false
                    logs.events("Applied migration").single().keyValues["id"] shouldBe "004-order-status"
                    logs.events("Migration failed").shouldBeEmpty()
                    f.recorder.commands.filter { it.isHistory("findAndModify") }
                        .map { it.command.getDocument("query").getString("_id").value } shouldBe
                        listOf("004-order-status")
                    f.stored("004-order-status")!!.getString("state") shouldBe "APPLIED"
                    f.stored("005-customer-external-ids").shouldBeNull()
                    f.collection("probes").find().first().getInteger("n") shouldBe 1
                }
            }
        }

        "a commit that applies once the lock is lost and then times out: LockLostException, the migration APPLIED" {
            LogCapture().use { logs ->
                val hang = AtomicReference<AutoCloseable?>()
                val recorder = CommandRecorder(
                    // The commit applies and its reply waits before the majority acknowledgement, as every write does
                    // while the fail point is on, the lock renewals included: the lease deadline passes meanwhile.
                    onStarted = { command ->
                        if (command.name == "commitTransaction") {
                            hang.set(TestMongo.failPoint("hangBeforeWaitingForWriteConcern", "alwaysOn"))
                        }
                    },
                    onFailed = { command ->
                        if (command.name == "commitTransaction") {
                            logs.awaitHeartbeatLoss()
                            hang.get()?.close()
                        }
                    }
                )
                GodwitFixture(
                    appName = "lockloss-commit-applied",
                    config = shortLock,
                    recorder = recorder,
                    timeout = 1500.milliseconds
                ).use { f ->
                    val runs = AtomicInteger()
                    val page = migration("006-order-totals").inTransaction {
                        runs.incrementAndGet()
                        probe()
                    }

                    val lost = shouldThrow<LockLostException> { f.godwit.migrate(page) }

                    lost.id shouldBe "006-order-totals"
                    lost.cause.shouldBeInstanceOf<MongoOperationTimeoutException>()
                    logs.events("Migration failed").single().line shouldStartWith
                        "Migration failed id=006-order-totals step=IN_TRANSACTION attempts=1 " +
                        "error=com.mongodb.MongoOperationTimeoutException"
                    val stored = f.stored("006-order-totals").shouldNotBeNull()
                    stored.getString("state") shouldBe "APPLIED"
                    stored.getString("holder") shouldBe "lockloss-commit-applied/1"
                    stored.containsKey("lastError") shouldBe false
                    f.collection("probes").find().first().getInteger("n") shouldBe 1

                    f.godwit.migrate(page).upToDate shouldBe listOf("006-order-totals")
                    runs.get() shouldBe 1
                }
            }
        }
    }
}
