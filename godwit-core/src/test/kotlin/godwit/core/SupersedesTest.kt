package godwit.core

import godwit.core.fixtures.CommandRecorder
import godwit.core.fixtures.GodwitFixture
import godwit.core.fixtures.LogCapture
import godwit.core.fixtures.awaitHeartbeatLoss
import godwit.core.fixtures.blockRenewals
import godwit.core.fixtures.collection
import godwit.core.fixtures.historyDocument
import godwit.core.fixtures.keyValues
import godwit.core.fixtures.line
import godwit.core.fixtures.seeded
import godwit.core.internal.testTimings
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.bson.Document
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes

private val version: String = System.getProperty("godwit.version")

/** The six once-only migrations the shop's first squash replaces. */
private val old = listOf(
    "001-initial-setup",
    "002-carts",
    "003-file-store",
    "004-order-status",
    "005-customer-external-ids",
    "006-order-totals"
)

private val oldList = old.joinToString(", ")

/** `100-baseline`, which counts its runs in `baseline-runs`. */
private val baseline = migration("100-baseline", description = "End state of 001 to 006", supersedes = old)
    .outsideTransaction {
        collection("baseline-runs").insertOne(Document("ran", true))
        count("runs", 1)
    }

private fun runs(id: String): Migration = migration(id).outsideTransaction { count("runs", 1) }

private fun GodwitFixture.applied(vararg ids: String) = history.insertMany(ids.map { historyDocument(it) })

/**
 * A squash: while the baseline has no APPLIED document, every replaced id APPLIED records it SUPERSEDED without running
 * it, none runs it, and some is a conflict; once it is APPLIED, its stored list only keeps the old ids known.
 */
class SupersedesTest : StringSpec() {
    init {
        "every replaced id applied: the baseline is recorded SUPERSEDED without running, with its stored list" {
            GodwitFixture().use { f ->
                f.applied(*old.toTypedArray())
                val bootstrap = everyStart("bootstrap-customers").outsideTransaction { }
                val list = listOf(baseline, runs("101-product-slugs"), bootstrap)

                val report = LogCapture().use { logs ->
                    val report = f.godwit.migrate(list)

                    logs.events.map { it.message } shouldBe listOf(
                        "Acquired migration lock",
                        "Recorded superseded migration",
                        "Running migration",
                        "Applied migration",
                        "Running migration",
                        "Applied migration",
                        "Migrations complete"
                    )
                    val recorded = logs.events("Recorded superseded migration").single()
                    recorded.line shouldBe "Recorded superseded migration id=100-baseline supersedes=[$oldList]"
                    recorded.level.toString() shouldBe "INFO"
                    logs.events("Migrations complete").single().keyValues["recorded"] shouldBe 1
                    logs.events("Unknown applied migrations").shouldBeEmpty()
                    report
                }

                report.recorded.map { "${it.id} ${it.origin}" } shouldBe listOf("100-baseline SUPERSEDED")
                report["100-baseline"].steps.shouldBeEmpty()
                report["100-baseline"].attempts shouldBe 0
                report.ran.map { it.id } shouldBe listOf("101-product-slugs", "bootstrap-customers")
                report.upToDate.shouldBeEmpty()
                report.unknownApplied.shouldBeEmpty()
                f.collection("baseline-runs").countDocuments() shouldBe 0L
                val stored = f.stored("100-baseline").shouldNotBeNull()
                stored.getString("kind") shouldBe "ONCE"
                stored["steps"] shouldBe emptyList<String>()
                stored.getString("state") shouldBe "APPLIED"
                stored.getString("origin") shouldBe "SUPERSEDED"
                stored["supersedes"] shouldBe old
                stored.getInteger("attempts") shouldBe 0
                stored.containsKey("counts") shouldBe false
                stored.getString("holder") shouldBe "godwit-runner/1"
                stored.getString("runId") shouldBe report.runId
                stored.getString("godwitVersion") shouldBe version
                stored.getInteger("v") shouldBe 1
                f.godwit.status(list).isUpToDate shouldBe true
            }
        }

        "no replaced id applied: the baseline runs and stores its supersedes list" {
            GodwitFixture().use { f ->
                val report = f.godwit.migrate(baseline, runs("101-product-slugs"))

                report.ran.map { it.id } shouldBe listOf("100-baseline", "101-product-slugs")
                report.recorded.shouldBeEmpty()
                f.collection("baseline-runs").countDocuments() shouldBe 1L
                val stored = f.stored("100-baseline").shouldNotBeNull()
                stored.getString("origin") shouldBe "RAN"
                stored["steps"] shouldBe listOf("OUTSIDE_TRANSACTION")
                stored["supersedes"] shouldBe old
            }
        }

        "some replaced ids applied: PlanConflictException naming them, and nothing runs or is recorded" {
            GodwitFixture().use { f ->
                f.applied(*old.take(4).toTypedArray())
                f.recorder.clear()

                val conflict = shouldThrow<PlanConflictException> {
                    f.godwit.migrate(baseline, runs("101-product-slugs"))
                }

                conflict.problems shouldBe listOf(
                    "100-baseline supersedes 6 migrations, but only 001-initial-setup, 002-carts, 003-file-store, " +
                        "004-order-status are applied. Deploy the previous release first."
                )
                f.stored("100-baseline").shouldBeNull()
                f.collection("baseline-runs").countDocuments() shouldBe 0L
                f.recorder.commands.map { it.name } shouldBe listOf("find")
                f.godwit.status(listOf(baseline)).problems shouldBe conflict.problems
            }
        }

        "an APPLIED baseline is not evaluated again: a database it built that ran three of the old six rolls forward" {
            GodwitFixture().use { f ->
                f.godwit.migrate(baseline)
                // An older release, which declares the six, ran the first three over the baseline's schema.
                f.applied(*old.take(3).toTypedArray())

                val report = f.godwit.migrate(baseline, runs("101-product-slugs"))

                report.ran.map { it.id } shouldBe listOf("101-product-slugs")
                report.upToDate shouldBe listOf("100-baseline")
                report.unknownApplied.shouldBeEmpty()
                f.collection("baseline-runs").countDocuments() shouldBe 1L
            }
        }

        "the stored list keeps the old ids known after the code drops the supersedes argument" {
            GodwitFixture(config = GodwitConfig(unknownApplied = UnknownApplied.FAIL)).use { f ->
                f.applied(*old.toTypedArray())
                f.godwit.migrate(baseline)
                val withoutList = migration("100-baseline", description = "End state of 001 to 006")
                    .outsideTransaction { error("applied, so it never runs") }

                val list = listOf(withoutList, runs("101-product-slugs"))
                LogCapture().use { logs ->
                    val report = f.godwit.migrate(list)

                    report.unknownApplied.shouldBeEmpty()
                    report.ran.map { it.id } shouldBe listOf("101-product-slugs")
                    logs.events("Unknown applied migrations").shouldBeEmpty()
                }
                f.godwit.status(list).unknownApplied.shouldBeEmpty()
                f.godwit.status(list).problems.shouldBeEmpty()
            }
        }

        "a recorded baseline is exempt from the out-of-order policy; a pending migration after it is not" {
            GodwitFixture().use { f ->
                f.applied(*old.toTypedArray())
                f.applied("007-customer-email-lower")
                val squash = migration("006-baseline", supersedes = old).outsideTransaction { error("recorded only") }
                val list = listOf(squash, runs("007-customer-email-lower"), runs("008-customer-email-lower-index"))

                val report = f.godwit.migrate(list)

                report.recorded.map { "${it.id} ${it.origin}" } shouldBe listOf("006-baseline SUPERSEDED")
                report.upToDate shouldBe listOf("007-customer-email-lower")
                report.ran.map { it.id } shouldBe listOf("008-customer-email-lower-index")
                report.ran.single().outOfOrder shouldBe false
            }
        }

        "a baseline that failed, then had its six applied by a rollback, is recorded without lastError or checkpoint" {
            GodwitFixture(config = seeded).use { f ->
                f.collection("orders").insertMany((1..4).map { Document("_id", it) })
                var failing = true
                val batched = migration("100-baseline", description = "End state of 001 to 006", supersedes = old)
                    .inBatches("orders", Document(), batchSize = 2) { page ->
                        if (failing && page.any { it["_id"] == 3 }) error("the second page fails")
                    }

                // Release N on a new database: the baseline commits its first page, then fails.
                shouldThrow<MigrationFailedException> { f.godwit.migrate(batched) }
                val failed = f.stored("100-baseline").shouldNotBeNull()
                failed.getString("state") shouldBe "FAILED"
                failed.keys shouldContainAll listOf("lastError", "checkpoint")

                // Release N-1 runs the six it declares; release N then finds them all applied.
                f.godwit.migrate(old.map(::runs)).ran.map { it.id } shouldBe old
                failing = false
                val report = f.godwit.migrate(batched)

                report.recorded.map { "${it.id} ${it.origin}" } shouldBe listOf("100-baseline SUPERSEDED")
                report.ran.shouldBeEmpty()
                val stored = f.stored("100-baseline").shouldNotBeNull()
                stored.getString("state") shouldBe "APPLIED"
                stored.getString("origin") shouldBe "SUPERSEDED"
                stored["supersedes"] shouldBe old
                stored.containsKey("lastError") shouldBe false
                stored.containsKey("checkpoint") shouldBe false
                // The failed run's facts stay: what it ran and how often.
                stored["steps"] shouldBe listOf("IN_BATCHES")
                stored.getInteger("attempts") shouldBe 1
                stored.getString("description") shouldBe "End state of 001 to 006"
                val entry = f.godwit.history().single { it.id == "100-baseline" }
                entry.lastError.shouldBeNull()
                entry.checkpoint.shouldBeNull()
            }
        }

        "a lock lost before the squash is recorded throws LockLostException naming it, and nothing is recorded" {
            LogCapture().use { logs ->
                val reads = AtomicInteger()
                val recorder = CommandRecorder(onSucceeded = { command ->
                    // The second history read is the one under the lock: the heartbeat loses the lock right after it.
                    val history = command.name == "find" && command.collection == "godwit-history"
                    if (history && reads.incrementAndGet() == 2) {
                        blockRenewals("supersedes-lock-lost").use { logs.awaitHeartbeatLoss() }
                    }
                })
                val config = GodwitConfig(lock = testTimings.copy(waitTimeout = 1.minutes))
                GodwitFixture(appName = "supersedes-lock-lost", config = config, recorder = recorder).use { f ->
                    f.applied(*old.toTypedArray())

                    val lost = shouldThrow<LockLostException> { f.godwit.migrate(baseline, runs("101-product-slugs")) }

                    lost.id shouldBe "100-baseline"
                    f.stored("100-baseline").shouldBeNull()
                    f.stored("101-product-slugs").shouldBeNull()
                    f.collection("baseline-runs").countDocuments() shouldBe 0L
                    logs.events("Recorded superseded migration").shouldBeEmpty()
                    logs.events("Migrations complete").shouldBeEmpty()
                    logs.events("Lost migration lock").single().keyValues["reason"] shouldBe "DEADLINE_PASSED"
                }
            }
        }

        "a squash another run recorded after the plan under the lock is up to date, and its document stays" {
            val appliedByAnother = historyDocument("100-baseline").append("supersedes", old)
            lateinit var f: GodwitFixture
            val reads = AtomicInteger()
            val recorder = CommandRecorder(onSucceeded = { command ->
                // The second history read is the one under the lock; another run records the baseline right after it.
                if (command.name == "find" && command.collection == "godwit-history" && reads.incrementAndGet() == 2) {
                    f.history.insertOne(appliedByAnother)
                }
            })
            f = GodwitFixture(recorder = recorder)
            f.use {
                f.applied(*old.toTypedArray())

                LogCapture().use { logs ->
                    val report = f.godwit.migrate(baseline)

                    report.recorded.shouldBeEmpty()
                    report.upToDate shouldBe listOf("100-baseline")
                    logs.events("Recorded superseded migration").shouldBeEmpty()
                }
                f.stored("100-baseline") shouldBe appliedByAnother
            }
        }
    }
}
