package godwit.core.internal

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import godwit.core.GodwitConfig
import godwit.core.MigrationFailedException
import godwit.core.OutOfOrder
import godwit.core.StepKind
import godwit.core.fixtures.GodwitFixture
import godwit.core.fixtures.LogCapture
import godwit.core.fixtures.TestMongo
import godwit.core.fixtures.keyValues
import godwit.core.fixtures.line
import godwit.core.migration
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import org.awaitility.kotlin.withPollInterval
import org.bson.Document
import org.bson.types.ObjectId
import java.io.File
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.toJavaDuration

private const val RUN_ID = "0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f"

/** One event of the catalogue: its level, message and fixed keys, and whether the step's counters follow them. */
private data class Row(val level: String, val message: String, val keys: List<String>, val counters: Boolean)

/** The catalogue in the `Godwit` KDoc: `| Level | Message | Keys |`, notes in parentheses, `then counts` last. */
private fun kdocCatalogue(): List<Row> {
    val row = Regex("""^ \* \| (INFO|WARN|DEBUG|ERROR)\s*\| (.+?)\s*\| (.+?)\s*\|$""")
    return File("src/main/kotlin/godwit/core/Godwit.kt").readLines().mapNotNull { row.find(it) }.map { match ->
        val (level, message, keys) = match.destructured
        val names = keys.replace(Regex("""\([^)]*\)"""), "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        Row(level, message, names - "then counts", "then counts" in names)
    }
}

/** The catalogue in history-and-reports.md: `| Level | Message | Keys | Example |`, keys in backticks. */
private fun docsCatalogue(): List<Row> {
    val section = File(
        "../docs/history-and-reports.md"
    ).readText().substringAfter("\n## Log lines").substringBefore("\n## ")
    val row = Regex("""^\| (INFO|WARN|DEBUG|ERROR) \| (.+?) \| (.+?) \| .+ \|$""")
    return section.lines().mapNotNull { row.find(it) }.map { match ->
        val (level, message, keys) = match.destructured
        val plain = keys.replace(Regex("""\([^)]*\)"""), "")
        Row(level, message, Regex("`(\\w+)`").findAll(plain).map { it.groupValues[1] }.toList(), "counter" in plain)
    }
}

private val counts = mapOf("ordersPaid" to 1200L, "ordersPending" to 37L)

/** Every function of [Log], called once with values like the docs' examples, in the catalogue's order. */
private fun logEveryEvent() {
    val expiresAt = Instant.parse("2026-10-02T10:15:00.210Z")
    val steps = listOf(StepKind.OUTSIDE_TRANSACTION, StepKind.IN_TRANSACTION)
    Log.migrationsUpToDate(RUN_ID, 7, 6)
    Log.waitingForMigrationLock("shop-7f9c4/1", RUN_ID, expiresAt, 10_004)
    Log.acquiredMigrationLock(RUN_ID, 212)
    Log.lockRenewalFailed(RUN_ID, "shop-7f9c4/1", IllegalStateException("Timed out"))
    Log.lostMigrationLock(RUN_ID, "shop-7f9c4/1", LossReason.DEADLINE_PASSED)
    Log.lockReleaseFailed(RUN_ID, "shop-7f9c4/1", IllegalStateException("Timed out"))
    Log.adoptedAppliedMigrations(listOf("001-initial-setup", "002-carts"), listOf("2025-02-cart-index-hotfix"))
    Log.recordedSupersededMigration("100-baseline", listOf("001-initial-setup", "006-order-totals"))
    Log.resumingInterruptedMigration("006-order-totals", 2)
    Log.runningOutOfOrderMigration("007-product-slugs", listOf("008-cart-currency"))
    Log.runningMigration("004-order-status", StoredKind.ONCE, listOf(StepKind.IN_TRANSACTION), 1)
    Log.retryingTransaction("004-order-status", 2, "WriteConflict (112)")
    Log.slowTransaction("007-customer-email-lower", 1, 24_310)
    Log.committedBatch("006-order-totals", 41, ObjectId("66fcf2a19b1e8a0012a1c4bc"))
    Log.appliedMigration("bootstrap-customers", StoredKind.EVERY_START, steps, 1, 0, 0, 290, counts)
    Log.unknownAppliedMigrations(listOf("009-order-payment-status"))
    Log.migrationFailed("005-customer-external-ids", StepKind.OUTSIDE_TRANSACTION, 1, IllegalStateException("x"))
    Log.migrationsComplete(RUN_ID, 2, 0, 6, 212, 402)
    Log.markedMigrationApplied("008-customer-email-lower-index", "built by hand", "ops-laptop-3/48211")
}

private fun ILoggingEvent.toRow(): Row {
    val keys = keyValues.keys.toList()
    return Row(level.toString(), message, keys - counts.keys, keys.containsAll(counts.keys))
}

private fun LogCapture.single(message: String): ILoggingEvent = events(message).single()

class LogCatalogueTest : StringSpec() {
    init {
        "every event of the KDoc catalogue and of history-and-reports.md is one Log function with its level and keys" {
            val kdoc = kdocCatalogue()
            val docs = docsCatalogue()
            kdoc shouldHaveSize 19

            val logged = LogCapture().use { logs ->
                logEveryEvent()
                logs.events.map { it.toRow() }
            }

            logged shouldBe kdoc
            logged shouldBe docs
        }

        "values print as the docs show them: lists in brackets, instants with milliseconds, errors as class: message" {
            LogCapture().use { logs ->
                logEveryEvent()
                Log.waitingForMigrationLock("shop-7f9c4/1", RUN_ID, Instant.parse("2026-10-02T10:15:00Z"), 0)

                logs.single("Running migration").line shouldBe
                    "Running migration id=004-order-status kind=ONCE steps=[IN_TRANSACTION] attempt=1"
                logs.single("Applied migration").line shouldBe
                    "Applied migration id=bootstrap-customers kind=EVERY_START " +
                    "steps=[OUTSIDE_TRANSACTION, IN_TRANSACTION] attempts=1 txRetries=0 batches=0 durationMs=290 " +
                    "ordersPaid=1200 ordersPending=37"
                logs.events("Waiting for migration lock").map { it.keyValues["expiresAt"] } shouldBe
                    listOf("2026-10-02T10:15:00.210Z", "2026-10-02T10:15:00.000Z")
                logs.single("Lost migration lock").line shouldBe
                    "Lost migration lock runId=$RUN_ID holder=shop-7f9c4/1 reason=DEADLINE_PASSED"
                logs.single("Lock renewal failed").keyValues["error"] shouldBe
                    "java.lang.IllegalStateException: Timed out"
                logs.single("Adopted applied migrations").line shouldBe
                    "Adopted applied migrations adopted=[001-initial-setup, 002-carts] " +
                    "ignored=[2025-02-cart-index-hotfix]"
                logs.single("Committed batch").line shouldBe
                    "Committed batch id=006-order-totals batch=41 lastId=66fcf2a19b1e8a0012a1c4bc"
                logs.single("Committed batch").level shouldBe Level.DEBUG
                val failed = logs.single("Migration failed")
                failed.line shouldBe
                    "Migration failed id=005-customer-external-ids step=OUTSIDE_TRANSACTION attempts=1 " +
                    "error=java.lang.IllegalStateException: x"
                failed.throwableProxy.shouldNotBeNull().className shouldBe "java.lang.IllegalStateException"
                logs.events.forEach { withClue(it.message) { it.loggerName shouldBe "godwit" } }
            }
        }

        "the wait, acquire, renewal-failed, lost and release-failed lines of the real lock carry their keys" {
            val catalogue = kdocCatalogue().associateBy { it.message }
            TestMongo.database().use { db ->
                LogCapture().use { logs ->
                    db.plantLease("token-of-a-crashed-process", "shop-dead1/1", RUN_ID, 1.minutes.inWholeMilliseconds)
                    val clock = TestTimeSource()
                    var ended = false
                    LockProcess(
                        db,
                        "catalogue",
                        testTimings.copy(waitTimeout = 1.minutes),
                        wait = VirtualWait(clock) {
                            clock += it
                            if (!ended && logs.events("Waiting for migration lock").isNotEmpty()) {
                                db.endLease()
                                ended = true
                            }
                        }
                    ).use { p ->
                        val held = p.lock.acquire(RUN_ID)
                        TestMongo.failCommand(
                            "catalogue",
                            listOf("update"),
                            Document("times", 1),
                            Document("errorCode", 13)
                        )
                            .use {
                                await atMost 5.seconds.toJavaDuration() until
                                    { logs.events("Lock renewal failed").isNotEmpty() }
                            }
                        db.lockCollection.deleteOne(Document("_id", "godwit-history"))
                        await atMost 5.seconds.toJavaDuration() withPollInterval 20.milliseconds.toJavaDuration() until
                            {
                                held.isLost()
                            }
                        TestMongo.failCommand(
                            "catalogue",
                            listOf("update"),
                            "alwaysOn",
                            Document("errorCode", 13)
                        ).use {
                            held.release()
                        }

                        val waiting = logs.single("Waiting for migration lock")
                        waiting.keyValues["holder"] shouldBe "shop-dead1/1"
                        logs.single("Acquired migration lock").keyValues["runId"] shouldBe RUN_ID
                        logs.single("Lock renewal failed").keyValues["holder"] shouldBe "catalogue/1"
                        logs.single("Lost migration lock").keyValues["reason"] shouldBe "NOT_OWNER"
                        logs.single("Lock release failed").keyValues["error"].toString() shouldStartWith
                            "com.mongodb.MongoCommandException"
                        logs.events.map { it.message }.distinct() shouldBe listOf(
                            "Waiting for migration lock",
                            "Acquired migration lock",
                            "Lock renewal failed",
                            "Lost migration lock",
                            "Lock release failed"
                        )
                        logs.events.forEach { event ->
                            withClue(event.line) {
                                val row = catalogue.getValue(event.message)
                                event.level.toString() shouldBe row.level
                                event.keyValues.keys.toList() shouldBe row.keys
                            }
                        }
                    }
                }
            }
        }

        "every event the runner emits for once-only migrations has its catalogue level and keys" {
            val catalogue = kdocCatalogue().associateBy { it.message }
            val config = GodwitConfig(outOfOrder = OutOfOrder.RUN, slowTransactionWarning = 1.milliseconds)
            GodwitFixture(appName = "catalogue-runner", config = config).use { f ->
                fun planted(id: String, state: String) = Document("_id", id).append("kind", "ONCE")
                    .append("steps", listOf("OUTSIDE_TRANSACTION")).append("state", state).append("origin", "RAN")
                    .append("attempts", 1).append("owner", "an-earlier-run")
                f.history.insertMany(
                    listOf(
                        planted("001-resumed", "RUNNING"),
                        planted("003-applied", "APPLIED"),
                        planted("099-x", "APPLIED")
                    )
                )
                val migrations = listOf(
                    migration("001-resumed").outsideTransaction { count("made", 1) },
                    migration("002-out-of-order").inTransaction {
                        Thread.sleep(5)
                        collection("orders").insertOne(session, Document("status", "PAID"))
                        count("ordersPaid", 1)
                    },
                    migration("003-applied").outsideTransaction { }
                )
                val failing = migration("004-failing").outsideTransaction { error("boom") }
                val transient = Document("errorCode", 112).append("errorLabels", listOf("TransientTransactionError"))

                LogCapture().use { logs ->
                    TestMongo.failCommand(f.appName, listOf("insert"), Document("times", 1), transient).use {
                        f.godwit.migrate(migrations)
                    }
                    f.godwit.migrate(migrations)
                    shouldThrow<MigrationFailedException> { f.godwit.migrate(migrations + failing) }

                    logs.events.map { it.message }.toSet() shouldBe setOf(
                        "Unknown applied migrations",
                        "Acquired migration lock",
                        "Resuming interrupted migration",
                        "Running migration",
                        "Applied migration",
                        "Running out-of-order migration",
                        "Retrying transaction",
                        "Slow transaction",
                        "Migrations complete",
                        "Migrations up to date",
                        "Migration failed"
                    )
                    logs.events.forEach { event ->
                        withClue(event.line) {
                            val row = catalogue.getValue(event.message)
                            event.level.toString() shouldBe row.level
                            val keys = event.keyValues.keys.toList()
                            keys.take(row.keys.size) shouldBe row.keys
                            if (!row.counters) keys shouldBe row.keys
                        }
                    }
                    logs.events("Applied migration").map { it.keyValues.keys.drop(7) } shouldBe
                        listOf(listOf("made"), listOf("ordersPaid"))
                }
            }
        }
    }
}
