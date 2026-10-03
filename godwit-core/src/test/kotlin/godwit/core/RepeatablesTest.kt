package godwit.core

import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.model.Sorts.ascending
import com.mongodb.client.model.Updates.combine
import com.mongodb.client.model.Updates.inc
import com.mongodb.client.model.Updates.set
import godwit.core.fixtures.CommandRecorder
import godwit.core.fixtures.GodwitFixture
import godwit.core.fixtures.LogCapture
import godwit.core.fixtures.NOVEMBER_COUNTRIES
import godwit.core.fixtures.awaitOrFail
import godwit.core.fixtures.blockRenewals
import godwit.core.fixtures.collection
import godwit.core.fixtures.keyValues
import godwit.core.fixtures.line
import godwit.core.fixtures.lockOperations
import godwit.core.fixtures.referenceCountries
import godwit.core.internal.testTimings
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.bson.BsonDocument
import org.bson.BsonInt64
import org.bson.Document
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes

private val log = LoggerFactory.getLogger(RepeatablesTest::class.java)

/** Short lock timings: the local deadline is 2 s after the last renewal was sent, the lease ends 1 s later. */
private val shortLock = GodwitConfig(lock = testTimings.copy(waitTimeout = 1.minutes))

private val setup = migration("001-initial-setup").outsideTransaction { ensureCollection("customers") }

private val carts = migration("002-carts").inTransaction {
    collection("carts").insertOne(session, Document("_id", "first-cart"))
}

/** `reference-countries` at `2026-11-15` with a bug: it writes Spain, then throws, so its transaction rolls back. */
private val brokenNovember = repeatable("reference-countries", "2026-11-15").inTransaction {
    collection("countries").insertOne(session, Document("_id", "ES"))
    error("a country without a name")
}

/** The ids in `countries`, sorted. */
private fun GodwitFixture.countries(): List<String> =
    collection("countries").find().sort(ascending("_id")).map { it.getString("_id") }.toList()

/** The runs of `reference-countries` that committed: its probe. */
private fun GodwitFixture.committedRuns(): Int =
    collection("probes").find(eq("_id", "reference-countries")).firstOrNull()?.getInteger("n") ?: 0

/** The history update that records [id] APPLIED. */
private fun GodwitFixture.appliedRecord(id: String) = recorder.commands("update").single {
    it.collection == "godwit-history" &&
        it.command.getArray("updates")[0].asDocument().getDocument("q").getString("_id").value == id
}

/** Repeatable migrations: run after the once-only ones, again on every new revision, under the same guarantees. */
class RepeatablesTest : StringSpec() {
    init {
        "a first run applies a repeatable after the once-only migrations, recording revision and runCount with it" {
            GodwitFixture().use { f ->
                LogCapture().use { logs ->
                    val before = Instant.now()
                    val report = f.godwit.migrate(setup, carts, referenceCountries("2026-10-01"))

                    report.ran.map { it.id } shouldBe listOf("001-initial-setup", "002-carts", "reference-countries")
                    val outcome = report["reference-countries"]
                    outcome.kind shouldBe MigrationKind.Repeatable("2026-10-01")
                    outcome.origin shouldBe Origin.RAN
                    outcome.steps shouldBe listOf(StepKind.IN_TRANSACTION)
                    outcome.attempts shouldBe 1
                    outcome.counts shouldBe mapOf("countriesRemoved" to 0L)
                    logs.events("Running migration").map { it.line } shouldBe listOf(
                        "Running migration id=001-initial-setup kind=ONCE steps=[OUTSIDE_TRANSACTION] attempt=1",
                        "Running migration id=002-carts kind=ONCE steps=[IN_TRANSACTION] attempt=1",
                        "Running migration id=reference-countries kind=REPEATABLE steps=[IN_TRANSACTION] attempt=1"
                    )
                    val applied = logs.events("Applied migration").last().line
                    applied shouldStartWith "Applied migration id=reference-countries kind=REPEATABLE " +
                        "steps=[IN_TRANSACTION] attempts=1 txRetries=0 batches=0 durationMs="
                    applied shouldEndWith " countriesRemoved=0"

                    val stored = f.stored("reference-countries").shouldNotBeNull()
                    stored.getString("kind") shouldBe "REPEATABLE"
                    stored.getString("revision") shouldBe "2026-10-01"
                    stored["steps"] shouldBe listOf("IN_TRANSACTION")
                    stored.getString("state") shouldBe "APPLIED"
                    stored.getString("origin") shouldBe "RAN"
                    stored.getInteger("attempts") shouldBe 1
                    stored.getLong("runCount") shouldBe 1L
                    stored.getDate("lastRunAt") shouldBe stored.getDate("finishedAt")
                    stored.getDate("lastRunAt").toInstant().isBefore(before) shouldBe false
                    stored["counts"] shouldBe Document("countriesRemoved", 0L)
                    stored.getString("runId") shouldBe report.runId
                }
                f.countries() shouldBe listOf("DE", "FR", "GB", "IE")

                // The record that stores the revision and counts the run is the last write of the step's transaction.
                val record = f.appliedRecord("reference-countries")
                val update = record.command.getArray("updates")[0].asDocument().getDocument("u")
                update.getDocument("\$set").getString("revision").value shouldBe "2026-10-01"
                update.getDocument("\$inc") shouldBe BsonDocument("runCount", BsonInt64(1))
                record.command.getBoolean("autocommit").value shouldBe false
                val commit = f.recorder.commands("commitTransaction").last()
                record.command["lsid"] shouldBe commit.command["lsid"]
                f.recorder.commands.indexOf(record) shouldBe f.recorder.commands.indexOf(commit) - 1

                val entry = f.godwit.history().single { it.id == "reference-countries" }
                entry.kind shouldBe MigrationKind.Repeatable("2026-10-01")
                entry.runCount shouldBe 1L
                entry.lastRunAt shouldBe entry.finishedAt
            }
        }

        "a start at the stored revision skips the repeatable without the lock; each new revision runs it once more" {
            GodwitFixture().use { f ->
                val revisions =
                    listOf("2026-10-01", "2026-10-01", "2026-11-15", "2026-11-15", "2026-11-15", "2026-12-01")
                LogCapture().use { logs ->
                    var changes = 0
                    revisions.forEachIndexed { index, revision ->
                        f.recorder.clear()
                        val report = f.godwit.migrate(setup, referenceCountries(revision))
                        val changed = index == 0 || revision != revisions[index - 1]
                        if (changed) changes++
                        withClue("start ${index + 1}, revision $revision") {
                            if (changed) {
                                report.ran.last().id shouldBe "reference-countries"
                                report["reference-countries"].attempts shouldBe 1
                                report.lockWait.shouldNotBeNull()
                                f.recorder.commands.lockOperations() shouldBe listOf("acquire", "release")
                            } else {
                                report.ran.shouldBeEmpty()
                                report.upToDate shouldBe listOf("001-initial-setup", "reference-countries")
                                report.lockWait.shouldBeNull()
                                f.recorder.commands.map { it.name } shouldBe listOf("find")
                            }
                            val stored = f.stored("reference-countries").shouldNotBeNull()
                            stored.getString("revision") shouldBe revision
                            stored.getLong("runCount") shouldBe changes.toLong()
                            stored.getInteger("attempts") shouldBe 1
                            f.committedRuns() shouldBe changes
                        }
                    }

                    val trace = logs.events.filter { it.message in setOf("Applied migration", "Migrations up to date") }
                    trace.map { it.message } shouldBe listOf(
                        "Applied migration",
                        "Applied migration",
                        "Migrations up to date",
                        "Applied migration",
                        "Migrations up to date",
                        "Migrations up to date",
                        "Applied migration"
                    )
                    trace.filter { it.keyValues["id"] == "reference-countries" }.map { it.keyValues["kind"] } shouldBe
                        listOf("REPEATABLE", "REPEATABLE", "REPEATABLE")
                    log.info(
                        "repeatable starts revisions={} trace={}",
                        revisions,
                        trace.map { it.line.substringBefore(" steps=").substringBefore(" durationMs=") }
                    )
                }
            }
        }

        "a failed run is retried on the next start, and attempts restart at 1 with the next revision" {
            GodwitFixture().use { f ->
                f.godwit.migrate(setup, referenceCountries("2026-10-01"))
                val email = migration("007-customer-email-lower").outsideTransaction { count("customersUpdated", 0) }

                val failure = LogCapture().use { logs ->
                    val failure = shouldThrow<MigrationFailedException> {
                        f.godwit.migrate(setup, email, brokenNovember)
                    }
                    logs.events("Migration failed").single().line shouldBe
                        "Migration failed id=reference-countries step=IN_TRANSACTION attempts=1 " +
                        "error=java.lang.IllegalStateException: a country without a name"
                    failure
                }

                failure.id shouldBe "reference-countries"
                failure.step shouldBe StepKind.IN_TRANSACTION
                failure.report.ran.map { it.id } shouldBe listOf("007-customer-email-lower")
                f.stored("007-customer-email-lower")!!.getString("state") shouldBe "APPLIED"
                val failed = f.stored("reference-countries").shouldNotBeNull()
                failed.getString("state") shouldBe "FAILED"
                failed.getString("revision") shouldBe "2026-10-01"
                failed.getLong("runCount") shouldBe 1L
                failed.getInteger("attempts") shouldBe 1
                failed.get("lastError", Document::class.java).getString("step") shouldBe "IN_TRANSACTION"
                f.countries() shouldBe listOf("DE", "FR", "GB", "IE")
                f.godwit.status(listOf(setup, email, brokenNovember)).pending shouldBe listOf("reference-countries")

                val fixed = referenceCountries("2026-11-15", NOVEMBER_COUNTRIES)
                LogCapture().use { logs ->
                    val retried = f.godwit.migrate(setup, email, fixed)

                    retried.ran.map { it.id } shouldBe listOf("reference-countries")
                    retried["reference-countries"].attempts shouldBe 2
                    logs.events("Resuming interrupted migration").shouldBeEmpty()
                    logs.events("Running migration").single().line shouldBe
                        "Running migration id=reference-countries kind=REPEATABLE steps=[IN_TRANSACTION] attempt=2"
                }
                val applied = f.stored("reference-countries").shouldNotBeNull()
                applied.getString("state") shouldBe "APPLIED"
                applied.getString("revision") shouldBe "2026-11-15"
                applied.getLong("runCount") shouldBe 2L
                applied.getInteger("attempts") shouldBe 2
                applied.containsKey("lastError") shouldBe false
                f.countries() shouldBe listOf("DE", "ES", "FR", "GB", "IE")

                val next = f.godwit.migrate(setup, email, referenceCountries("2026-12-01", NOVEMBER_COUNTRIES))
                next["reference-countries"].attempts shouldBe 1
                f.stored("reference-countries")!!.getInteger("attempts") shouldBe 1
                f.stored("reference-countries")!!.getLong("runCount") shouldBe 3L
            }
        }

        "a release rolled back after its revision failed runs its own revision: a document not APPLIED is due" {
            GodwitFixture().use { f ->
                val october = referenceCountries("2026-10-01")
                f.godwit.migrate(october)
                shouldThrow<MigrationFailedException> { f.godwit.migrate(brokenNovember) }
                f.stored("reference-countries")!!.getString("revision") shouldBe "2026-10-01"

                val rolledBack = f.godwit.migrate(october)

                rolledBack.ran.map { it.id } shouldBe listOf("reference-countries")
                rolledBack["reference-countries"].attempts shouldBe 2
                val stored = f.stored("reference-countries").shouldNotBeNull()
                stored.getString("state") shouldBe "APPLIED"
                stored.getString("revision") shouldBe "2026-10-01"
                stored.getLong("runCount") shouldBe 2L
                f.godwit.migrate(october).lockWait.shouldBeNull()
            }
        }

        "an older release that starts after a newer one applies its own revision: revisions are only compared" {
            GodwitFixture().use { f ->
                val october = referenceCountries("2026-10-01")
                val november = referenceCountries("2026-11-15", NOVEMBER_COUNTRIES)
                f.godwit.migrate(october)
                f.godwit.migrate(november)
                f.countries() shouldBe listOf("DE", "ES", "FR", "GB", "IE")

                val older = f.godwit.migrate(october)

                older["reference-countries"].count("countriesRemoved") shouldBe 1L
                f.countries() shouldBe listOf("DE", "FR", "GB", "IE")
                f.stored("reference-countries")!!.getString("revision") shouldBe "2026-10-01"
                f.stored("reference-countries")!!.getLong("runCount") shouldBe 3L

                f.godwit.migrate(november).ran.map { it.id } shouldBe listOf("reference-countries")
                f.countries() shouldBe listOf("DE", "ES", "FR", "GB", "IE")
                f.stored("reference-countries")!!.getString("revision") shouldBe "2026-11-15"
                f.stored("reference-countries")!!.getLong("runCount") shouldBe 4L
            }
        }

        "a repeatable whose id last ran as an every-start migration is due: changing the kind and back restores it" {
            GodwitFixture().use { f ->
                val october = referenceCountries("2026-10-01")
                // The release in between keeps the id and adds Spain on every start.
                val everyStartCountries = everyStart("reference-countries").inTransaction {
                    val spain = Document("_id", "ES").append("name", "Spain")
                    collection("countries").replaceOne(session, eq("_id", "ES"), spain, ReplaceOptions().upsert(true))
                }
                f.godwit.migrate(october)

                f.godwit.migrate(everyStartCountries)["reference-countries"].attempts shouldBe 1
                val between = f.stored("reference-countries").shouldNotBeNull()
                between.getString("kind") shouldBe "EVERY_START"
                between.getString("state") shouldBe "APPLIED"
                between.containsKey("revision") shouldBe false
                between.getLong("runCount") shouldBe 2L
                f.godwit.history().single().kind shouldBe MigrationKind.EveryStart
                f.countries() shouldBe listOf("DE", "ES", "FR", "GB", "IE")
                f.godwit.status(listOf(october)).pending shouldBe listOf("reference-countries")

                val back = f.godwit.migrate(october)

                back.ran.map { it.id } shouldBe listOf("reference-countries")
                back["reference-countries"].attempts shouldBe 1
                back["reference-countries"].count("countriesRemoved") shouldBe 1L
                f.countries() shouldBe listOf("DE", "FR", "GB", "IE")
                val stored = f.stored("reference-countries").shouldNotBeNull()
                stored.getString("kind") shouldBe "REPEATABLE"
                stored.getString("revision") shouldBe "2026-10-01"
                stored.getLong("runCount") shouldBe 3L
                f.godwit.history().single().kind shouldBe MigrationKind.Repeatable("2026-10-01")
                f.committedRuns() shouldBe 2
                f.godwit.migrate(october).lockWait.shouldBeNull()
            }
        }

        "an interrupted repeatable is due at any revision, resumed with a warning, its attempts counting on" {
            GodwitFixture().use { f ->
                f.history.insertOne(
                    Document("_id", "reference-countries").append("kind", "REPEATABLE").append("revision", "2026-10-01")
                        .append("steps", listOf("IN_TRANSACTION")).append("state", "RUNNING").append("origin", "RAN")
                        .append("attempts", 1).append("runCount", 1L).append("owner", "token-of-a-crashed-run")
                        .append("holder", "shop-crashed/1").append("runId", "run-of-a-crashed-process")
                )

                LogCapture().use { logs ->
                    val report = f.godwit.migrate(referenceCountries("2026-10-01"))

                    report["reference-countries"].attempts shouldBe 2
                    logs.events("Resuming interrupted migration").single().line shouldBe
                        "Resuming interrupted migration id=reference-countries attempts=2"
                }
                val stored = f.stored("reference-countries").shouldNotBeNull()
                stored.getString("state") shouldBe "APPLIED"
                stored.getString("holder") shouldBe "godwit-runner/1"
                stored.getInteger("attempts") shouldBe 2
                stored.getLong("runCount") shouldBe 2L
            }
        }

        "a repeatable another run applied after the plan under the lock runs again: its marker is unconditional" {
            val armed = AtomicBoolean(false)
            val reads = AtomicInteger()
            lateinit var f: GodwitFixture
            val recorder = CommandRecorder(onSucceeded = { command ->
                // The second history read of the armed call is the one under the lock. A run that lost the lock
                // commits the same revision right after it, before this run's marker.
                if (armed.get() && command.name == "find" && command.collection == "godwit-history" &&
                    reads.incrementAndGet() == 2
                ) {
                    f.history.updateOne(
                        eq("_id", "reference-countries"),
                        combine(
                            set("state", "APPLIED"),
                            set("revision", "2026-11-15"),
                            set("owner", "token-of-a-run-that-lost-the-lock"),
                            inc("runCount", 1L)
                        )
                    )
                }
            })
            f = GodwitFixture(recorder = recorder)
            f.use {
                f.godwit.migrate(referenceCountries("2026-10-01"))
                f.recorder.clear()
                armed.set(true)

                val report = f.godwit.migrate(referenceCountries("2026-11-15", NOVEMBER_COUNTRIES))

                report.ran.map { it.id } shouldBe listOf("reference-countries")
                report["reference-countries"].attempts shouldBe 1
                report.upToDate.shouldBeEmpty()
                val marker = f.recorder.commands("findAndModify").single { it.collection == "godwit-history" }
                marker.errorCode.shouldBeNull()
                val stored = f.stored("reference-countries").shouldNotBeNull()
                stored.getString("state") shouldBe "APPLIED"
                stored.getString("holder") shouldBe "godwit-runner/1"
                stored.getLong("runCount") shouldBe 3L
                f.committedRuns() shouldBe 2
            }
        }

        "a stale run's marker after another run applied the revision reopens it, and the next start runs it again" {
            val atMarker = CountDownLatch(1)
            val applied = CountDownLatch(1)
            var block: AutoCloseable? = null
            val recorder = CommandRecorder(onStarted = { command ->
                // This run has passed its check before the marker. It pauses past its lease here: its renewals stop
                // reaching the server, and its marker leaves only once another run has applied the same revision.
                if (command.name == "findAndModify" && command.collection == "godwit-history") {
                    block = blockRenewals("repeatables-stale")
                    atMarker.countDown()
                    applied.awaitOrFail()
                }
            })
            GodwitFixture(appName = "repeatables-stale", config = shortLock, recorder = recorder).use { a ->
                a.process("repeatables-takeover", shortLock).use { b ->
                    val november = referenceCountries("2026-11-15", NOVEMBER_COUNTRIES)
                    b.godwit.migrate(referenceCountries("2026-10-01"))
                    val stale = CompletableFuture.supplyAsync { runCatching { a.godwit.migrate(november) } }
                    atMarker.awaitOrFail()

                    val takeover = b.godwit.migrate(november)
                    applied.countDown()
                    val staleFailure = stale.get(1, TimeUnit.MINUTES).exceptionOrNull()
                    block?.close()

                    takeover["reference-countries"].attempts shouldBe 1
                    staleFailure.shouldBeInstanceOf<LockLostException>().id shouldBe "reference-countries"
                    staleFailure.cause.shouldBeNull()
                    val reopened = b.stored("reference-countries").shouldNotBeNull()
                    reopened.getString("state") shouldBe "RUNNING"
                    reopened.getString("holder") shouldBe "repeatables-stale/1"
                    reopened.getInteger("attempts") shouldBe 1
                    reopened.getString("revision") shouldBe "2026-11-15"
                    reopened.getLong("runCount") shouldBe 2L
                    b.committedRuns() shouldBe 2
                    b.godwit.status(listOf(november)).pending shouldBe listOf("reference-countries")
                    shouldThrow<PendingMigrationsException> { b.godwit.requireUpToDate(listOf(november)) }

                    LogCapture().use { logs ->
                        b.godwit.migrate(november)["reference-countries"].attempts shouldBe 2
                        logs.events("Resuming interrupted migration").single().line shouldBe
                            "Resuming interrupted migration id=reference-countries attempts=2"
                    }
                    val again = b.stored("reference-countries").shouldNotBeNull()
                    again.getString("state") shouldBe "APPLIED"
                    again.getLong("runCount") shouldBe 3L
                    b.committedRuns() shouldBe 3
                    b.countries() shouldBe listOf("DE", "ES", "FR", "GB", "IE")
                }
            }
        }

        "a due repeatable never runs under Target.Before or Target.Through, and the report does not list it" {
            GodwitFixture().use { f ->
                val list = listOf(setup, carts, referenceCountries("2026-10-01"))

                val through = f.godwit.migrate(list, Target.Through("002-carts"))

                through.ran.map { it.id } shouldBe listOf("001-initial-setup", "002-carts")
                through.pending.shouldBeEmpty()
                f.stored("reference-countries").shouldBeNull()
                f.recorder.clear()

                val before = f.godwit.migrate(list, Target.Before("002-carts"))

                before.lockWait.shouldBeNull()
                before.ran.shouldBeEmpty()
                before.upToDate shouldBe listOf("001-initial-setup", "002-carts")
                before.pending.shouldBeEmpty()
                f.recorder.commands.map { it.name } shouldBe listOf("find")
                f.godwit.status(list).pending shouldBe listOf("reference-countries")

                f.godwit.migrate(list).ran.map { it.id } shouldBe listOf("reference-countries")
            }
        }

        "a due repeatable is pending in status and holds back requireUpToDate until a start applies its revision" {
            GodwitFixture().use { f ->
                val october = listOf(setup, referenceCountries("2026-10-01"))
                val november = listOf(setup, referenceCountries("2026-11-15", NOVEMBER_COUNTRIES))
                f.godwit.status(october).pending shouldBe listOf("001-initial-setup", "reference-countries")

                f.godwit.migrate(october)
                f.godwit.requireUpToDate(october)

                f.godwit.status(november).pending shouldBe listOf("reference-countries")
                shouldThrow<PendingMigrationsException> { f.godwit.requireUpToDate(november) }.pending shouldBe
                    listOf("reference-countries")
                f.godwit.migrate(november)
                f.godwit.requireUpToDate(november)
            }
        }

        "a repeatable deleted from the list is unknown applied; with its document deleted, a listing release runs it" {
            GodwitFixture().use { f ->
                f.godwit.migrate(setup, referenceCountries("2026-10-01"))

                LogCapture().use { logs ->
                    val without = f.godwit.migrate(setup)

                    without.unknownApplied shouldBe listOf("reference-countries")
                    without.lockWait.shouldBeNull()
                    logs.events("Unknown applied migrations").single().line shouldBe
                        "Unknown applied migrations ids=[reference-countries]"
                }
                f.process("repeatables-strict", GodwitConfig(unknownApplied = UnknownApplied.FAIL)).use { strict ->
                    shouldThrow<PlanConflictException> { strict.godwit.migrate(setup) }.problems shouldBe listOf(
                        "reference-countries is applied, but the list does not declare it (UnknownApplied.FAIL)"
                    )
                }

                f.history.deleteOne(eq("_id", "reference-countries"))
                val again = f.godwit.migrate(setup, referenceCountries("2026-10-01"))

                again.ran.map { it.id } shouldBe listOf("reference-countries")
                f.stored("reference-countries")!!.getLong("runCount") shouldBe 1L
            }
        }

        "an outside-only repeatable records its revision and runCount with the APPLIED write after its step" {
            GodwitFixture().use { f ->
                fun synonyms(revision: String) =
                    repeatable("search-synonyms", revision).outsideTransaction { count("synonyms", 3) }

                f.godwit.migrate(synonyms("v1"))
                f.godwit.migrate(synonyms("v1")).lockWait.shouldBeNull()
                f.recorder.clear()
                val second = f.godwit.migrate(synonyms("v2"))

                second["search-synonyms"].counts shouldBe mapOf("synonyms" to 3L)
                val record = f.appliedRecord("search-synonyms")
                record.command.containsKey("autocommit") shouldBe false
                val stored = f.stored("search-synonyms").shouldNotBeNull()
                stored.getString("revision") shouldBe "v2"
                stored.getLong("runCount") shouldBe 2L
                stored.getDate("lastRunAt") shouldBe stored.getDate("finishedAt")
            }
        }

        "a repeatable whose run loses the lock records nothing: revision and runCount stay as the last run left them" {
            GodwitFixture(appName = "repeatables-lockloss", config = shortLock).use { f ->
                f.godwit.migrate(referenceCountries("2026-10-01"))
                var block: AutoCloseable? = null
                val long = repeatable("reference-countries", "2026-11-15").inTransaction {
                    collection("countries").insertOne(session, Document("_id", "ES"))
                    block = blockRenewals(f.appName)
                    while (true) {
                        checkLock()
                        Thread.sleep(20)
                    }
                }

                val lost = shouldThrow<LockLostException> { f.godwit.migrate(long) }
                block?.close()

                lost.id shouldBe "reference-countries"
                lost.cause.shouldBeNull()
                val stored = f.stored("reference-countries").shouldNotBeNull()
                stored.getString("state") shouldBe "RUNNING"
                stored.getString("revision") shouldBe "2026-10-01"
                stored.getLong("runCount") shouldBe 1L
                stored.containsKey("lastError") shouldBe false
                f.countries() shouldBe listOf("DE", "FR", "GB", "IE")

                LogCapture().use { logs ->
                    val resumed = f.godwit.migrate(referenceCountries("2026-11-15", NOVEMBER_COUNTRIES))

                    resumed["reference-countries"].attempts shouldBe 2
                    logs.events("Resuming interrupted migration") shouldHaveSize 1
                }
                f.stored("reference-countries")!!.getLong("runCount") shouldBe 2L
                f.stored("reference-countries")!!.getString("revision") shouldBe "2026-11-15"
                f.committedRuns() shouldBe 2
            }
        }
    }
}
