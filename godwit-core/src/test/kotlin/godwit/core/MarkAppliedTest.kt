package godwit.core

import godwit.core.fixtures.CommandRecorder
import godwit.core.fixtures.CountingHook
import godwit.core.fixtures.GodwitFixture
import godwit.core.fixtures.LogCapture
import godwit.core.fixtures.TestMongo
import godwit.core.fixtures.awaitHeartbeatLoss
import godwit.core.fixtures.awaitOrFail
import godwit.core.fixtures.blockRenewals
import godwit.core.fixtures.collection
import godwit.core.fixtures.historyDocument
import godwit.core.fixtures.keyValues
import godwit.core.fixtures.line
import godwit.core.fixtures.plantAdopted
import godwit.core.fixtures.referenceCountries
import godwit.core.internal.testTimings
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.bson.Document
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes

private val version: String = System.getProperty("godwit.version")

private const val REASON =
    "Unique index on customers.emailLower built by hand as emailLower_unique during the 2026-10-05 incident"

private const val ADOPTION_NOT_ENDED = "adoption has not ended on this database; run migrate() first so the " +
    "adoptApplied hook adopts, or call markApplied from a Godwit built without adoptApplied"

/**
 * Calls [Godwit.markApplied] for [id] on [godwit] and expects it to refuse with [T]: under the lock (acquired once and
 * released), with the history collection [historyName] unchanged and no "Marked migration applied" line. Returns the
 * exception.
 */
private inline fun <reified T : Throwable> GodwitFixture.shouldRefuseMark(
    id: String,
    godwit: Godwit = this.godwit,
    historyName: String = "godwit-history",
    lockName: String = "godwit-lock"
): T {
    val before = collection(historyName).find().toList()
    val refused = LogCapture().use { logs ->
        val refused = shouldThrow<T> { godwit.markApplied(id, REASON) }
        logs.events.map { it.message } shouldBe listOf("Acquired migration lock")
        refused
    }
    collection(historyName).find().toList() shouldBe before
    collection(lockName).find().first().containsKey("releasedAt") shouldBe true
    return refused
}

/** `markApplied`: the audited escape hatch for once-only migrations, under the lock. */
class MarkAppliedTest : StringSpec() {
    init {
        "a missing document becomes a once-only APPLIED document with origin MARKED, logged at WARN" {
            GodwitFixture().use { f ->
                LogCapture().use { logs ->
                    f.godwit.markApplied("008-customer-email-lower-index", REASON)

                    logs.events.map { it.message } shouldBe
                        listOf("Acquired migration lock", "Marked migration applied")
                    val marked = logs.events("Marked migration applied").single()
                    marked.line shouldBe "Marked migration applied id=008-customer-email-lower-index " +
                        "reason=$REASON holder=godwit-runner/1"
                    marked.level.toString() shouldBe "WARN"
                }

                val stored = f.stored("008-customer-email-lower-index").shouldNotBeNull()
                stored.keys shouldBe setOf(
                    "_id", "state", "origin", "owner", "holder", "runId", "finishedAt", "godwitVersion", "v", "reason",
                    "kind", "steps", "attempts"
                )
                stored.getString("kind") shouldBe "ONCE"
                stored["steps"] shouldBe emptyList<String>()
                stored.getString("state") shouldBe "APPLIED"
                stored.getString("origin") shouldBe "MARKED"
                stored.getString("reason") shouldBe REASON
                stored.getInteger("attempts") shouldBe 0
                stored.getString("holder") shouldBe "godwit-runner/1"
                UUID.fromString(stored.getString("runId")).toString() shouldBe stored.getString("runId")
                val lock = f.collection("godwit-lock").find().first()
                stored.getString("owner") shouldBe lock.getString("owner")
                lock.containsKey("releasedAt") shouldBe true
                stored.getString("godwitVersion") shouldBe version
                stored.getInteger("v") shouldBe 1

                val index = migration("008-customer-email-lower-index").outsideTransaction { error("marked") }
                f.godwit.migrate(index).upToDate shouldBe listOf("008-customer-email-lower-index")
            }
        }

        "a FAILED once-only document becomes APPLIED with origin MARKED, keeping the failed run's facts" {
            GodwitFixture().use { f ->
                val index = migration("008-customer-email-lower-index").outsideTransaction {
                    error("Index already exists with a different name: emailLower_unique")
                }
                shouldThrow<MigrationFailedException> { f.godwit.migrate(index) }
                val failed = f.stored("008-customer-email-lower-index").shouldNotBeNull()

                f.godwit.markApplied("008-customer-email-lower-index", REASON)

                val stored = f.stored("008-customer-email-lower-index").shouldNotBeNull()
                stored.getString("state") shouldBe "APPLIED"
                stored.getString("origin") shouldBe "MARKED"
                stored.getString("reason") shouldBe REASON
                stored.containsKey("lastError") shouldBe false
                stored["steps"] shouldBe listOf("OUTSIDE_TRANSACTION")
                stored.getInteger("attempts") shouldBe 1
                stored.getLong("durationMs") shouldBe failed.getLong("durationMs")
                stored.getDate("startedAt") shouldBe failed.getDate("startedAt")
                stored.getString("owner") shouldNotBe failed.getString("owner")
                f.godwit.migrate(index).upToDate shouldBe listOf("008-customer-email-lower-index")
            }
        }

        "a RUNNING once-only document becomes APPLIED with origin MARKED, and its checkpoint is removed" {
            GodwitFixture().use { f ->
                val checkpoint = Document("lastId", 41).append("batches", 3).append("counts", Document())
                val running = historyDocument("006-order-totals", HistoryState.RUNNING).append("checkpoint", checkpoint)
                f.history.insertOne(running)

                f.godwit.markApplied("006-order-totals", "backfilled by hand")

                val stored = f.stored("006-order-totals").shouldNotBeNull()
                stored.getString("state") shouldBe "APPLIED"
                stored.getString("origin") shouldBe "MARKED"
                stored.getString("reason") shouldBe "backfilled by hand"
                stored.containsKey("checkpoint") shouldBe false
            }
        }

        for (origin in Origin.entries) {
            "an APPLIED once-only document with origin $origin is left unchanged and nothing is logged" {
                GodwitFixture().use { f ->
                    f.history.insertOne(historyDocument("004-order-status", origin = origin))
                    val before = f.stored("004-order-status")

                    LogCapture().use { logs ->
                        f.godwit.markApplied("004-order-status", REASON)

                        logs.events.map { it.message } shouldBe listOf("Acquired migration lock")
                    }
                    f.stored("004-order-status") shouldBe before
                    f.collection("godwit-lock").find().first().containsKey("releasedAt") shouldBe true
                }
            }
        }

        for (kind in listOf("REPEATABLE", "EVERY_START")) {
            for (state in listOf(HistoryState.FAILED, HistoryState.APPLIED)) {
                "a document of kind $kind that is $state is refused with IllegalArgumentException, and left unchanged" {
                    GodwitFixture().use { f ->
                        f.history.insertOne(historyDocument("reference-countries", state, kind = kind))

                        val refused = f.shouldRefuseMark<IllegalArgumentException>("reference-countries")

                        refused.message shouldBe "reference-countries is $kind in history; markApplied records " +
                            "once-only migrations only. A repeatable or every-start migration is due whatever its " +
                            "history says: fix it in code, or remove it from the list"
                    }
                }
            }
        }

        "an APPLIED repeatable at its current revision is refused as well" {
            GodwitFixture().use { f ->
                f.godwit.migrate(referenceCountries("2026-10-01"))

                f.shouldRefuseMark<IllegalArgumentException>("reference-countries")

                f.godwit.migrate(referenceCountries("2026-10-01")).upToDate shouldBe listOf("reference-countries")
            }
        }

        "a blank reason throws IllegalArgumentException before taking the lock or sending anything" {
            val recorder = CommandRecorder()
            TestMongo.client("mark-blank-reason", recorder).use { client ->
                val godwit = Godwit(client, "never-touched", GodwitConfig(holder = "ops-laptop-3/48211"))
                for (reason in listOf("", " ", "\t\n")) {
                    shouldThrow<IllegalArgumentException> { godwit.markApplied("008-x", reason) }.message shouldBe
                        "reason must not be blank: it is the audit trail of the mark"
                }
                recorder.commands.shouldBeEmpty()
            }
        }

        "it waits for the lock a running migration holds, then finds what that run left" {
            val stepStarted = CountDownLatch(1)
            val markRefused = CountDownLatch(1)
            GodwitFixture(appName = "mark-runner").use { f ->
                val operatorRecorder = CommandRecorder(onSucceeded = { command ->
                    if (command.name == "findAndModify" && command.collection == "godwit-lock") markRefused.countDown()
                })
                f.process("mark-operator", recorder = operatorRecorder).use { operator ->
                    val slow = migration("002-carts").outsideTransaction {
                        stepStarted.countDown()
                        markRefused.awaitOrFail()
                    }
                    val pool = Executors.newSingleThreadExecutor()
                    try {
                        val running = pool.submit(Callable { f.godwit.migrate(slow) })
                        stepStarted.awaitOrFail()

                        LogCapture().use { logs ->
                            operator.godwit.markApplied("002-carts", REASON)

                            logs.events("Marked migration applied").shouldBeEmpty()
                        }
                        running.get(1, TimeUnit.MINUTES).ran.map { it.id } shouldBe listOf("002-carts")
                    } finally {
                        pool.shutdownNow()
                    }
                    val acquires = operatorRecorder.commands("findAndModify").filter { it.collection == "godwit-lock" }
                    acquires.size shouldBeGreaterThan 1
                    f.stored("002-carts")!!.getString("origin") shouldBe "RAN"

                    operator.godwit.markApplied("003-file-store", REASON)
                    f.stored("003-file-store")!!.getString("holder") shouldBe "mark-operator/1"
                }
            }
        }

        "with adoptApplied set, refused while history is empty or holds only ADOPTED documents, the id's own included" {
            val hook = CountingHook("001-initial-setup")
            GodwitFixture(config = GodwitConfig(adoptApplied = hook)).use { f ->
                f.shouldRefuseMark<IllegalStateException>("002-carts").message shouldBe ADOPTION_NOT_ENDED

                f.history.plantAdopted("001-initial-setup", "002-carts")

                f.shouldRefuseMark<IllegalStateException>("003-file-store").message shouldBe ADOPTION_NOT_ENDED
                f.shouldRefuseMark<IllegalStateException>("002-carts").message shouldBe ADOPTION_NOT_ENDED
                hook.calls shouldBe 0
            }
        }

        "with adoptApplied set, it marks once history holds a document that adoption did not write" {
            GodwitFixture(config = GodwitConfig(adoptApplied = CountingHook("001-initial-setup"))).use { f ->
                f.history.plantAdopted("001-initial-setup")
                f.history.insertOne(historyDocument("002-carts"))

                f.godwit.markApplied("003-file-store", REASON)

                f.stored("003-file-store")!!.getString("origin") shouldBe "MARKED"
            }
        }

        for (adoptedFirst in listOf(false, true)) {
            val history = if (adoptedFirst) "only ADOPTED documents" else "an empty history"
            "from config.copy(adoptApplied = null) it marks on $history, in the app's history and lock collections" {
                val hook = CountingHook("001-initial-setup")
                val config = GodwitConfig(
                    historyCollection = "shop-history",
                    lockCollection = "shop-lock",
                    adoptApplied = hook
                )
                GodwitFixture(config = config).use { f ->
                    if (adoptedFirst) f.history.plantAdopted("001-initial-setup")
                    val refused = f.shouldRefuseMark<IllegalStateException>(
                        "002-carts",
                        historyName = "shop-history",
                        lockName = "shop-lock"
                    )
                    refused.message shouldBe ADOPTION_NOT_ENDED

                    val repair = Godwit(f.client, f.db.name, f.config.copy(adoptApplied = null))
                    LogCapture().use { logs ->
                        repair.markApplied("002-carts", "created by hand on 2026-03-02, see ticket SHOP-212")

                        logs.events("Marked migration applied").single().keyValues["id"] shouldBe "002-carts"
                    }

                    val marked = f.collection("shop-history").find(Document("_id", "002-carts")).first()
                    marked.getString("origin") shouldBe "MARKED"
                    val lock = f.collection("shop-lock").find().first()
                    lock.getString("_id") shouldBe "shop-history"
                    lock.getString("holder") shouldBe "godwit-runner/1"
                    lock.containsKey("releasedAt") shouldBe true
                    val names = f.database.listCollectionNames().toList()
                    names.contains("godwit-history") shouldBe false
                    names.contains("godwit-lock") shouldBe false
                    hook.calls shouldBe 0
                }
            }
        }

        "a lock lost before the mark's write throws LockLostException, and nothing is written" {
            LogCapture().use { logs ->
                var armed = true
                val recorder = CommandRecorder(onSucceeded = { command ->
                    // The history read under the lock: the heartbeat loses the lock before the mark goes on.
                    if (armed && command.name == "find" && command.collection == "godwit-history") {
                        armed = false
                        blockRenewals("mark-lock-lost").use { logs.awaitHeartbeatLoss() }
                    }
                })
                val config = GodwitConfig(lock = testTimings.copy(waitTimeout = 1.minutes))
                GodwitFixture(appName = "mark-lock-lost", config = config, recorder = recorder).use { f ->
                    val lost = shouldThrow<LockLostException> { f.godwit.markApplied("008-x", REASON) }

                    lost.message shouldBe "Lost the migration lock"
                    lost.id.shouldBeNull()
                    f.stored("008-x").shouldBeNull()
                    logs.events("Marked migration applied").shouldBeEmpty()
                    logs.events("Lost migration lock").single().keyValues["reason"] shouldBe "DEADLINE_PASSED"
                }
            }
        }
    }
}
