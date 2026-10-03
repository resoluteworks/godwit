package godwit.core

import com.mongodb.MongoCommandException
import com.mongodb.client.model.Filters.ne
import godwit.core.fixtures.CommandRecorder
import godwit.core.fixtures.CountingHook
import godwit.core.fixtures.GodwitFixture
import godwit.core.fixtures.LogCapture
import godwit.core.fixtures.StandaloneProcess
import godwit.core.fixtures.awaitHeartbeatLoss
import godwit.core.fixtures.blockRenewals
import godwit.core.fixtures.collection
import godwit.core.fixtures.failHistoryWrites
import godwit.core.fixtures.historyDocument
import godwit.core.fixtures.keyValues
import godwit.core.fixtures.line
import godwit.core.fixtures.origins
import godwit.core.fixtures.plantAdopted
import godwit.core.fixtures.referenceCountries
import godwit.core.fixtures.standaloneMongo
import godwit.core.fixtures.updatedIds
import godwit.core.internal.testTimings
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.bson.Document
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private val version: String = System.getProperty("godwit.version")

/** A once-only migration that adoption covers in these cases: it fails if it ever runs. */
private fun adopted(id: String): Migration =
    migration(id).outsideTransaction { error("$id is adopted, so it never runs") }

/** A once-only migration that runs, and counts its run. */
private fun runs(id: String): Migration = migration(id).outsideTransaction { count("runs", 1) }

private fun GodwitFixture.lockReleased(): Boolean = collection("godwit-lock").find().first().containsKey("releasedAt")

private fun MigrationReport.recordedIds(): List<String> = recorded.map { "${it.id} ${it.origin}" }

/**
 * Adoption: the hook runs under the lock while history holds nothing but ADOPTED documents, its adoptable ids that
 * history lacks are recorded before the plan is checked, and the order checks wait for that plan.
 */
class AdoptionTest : StringSpec() {
    init {
        "a full prefix: the returned ids are recorded ADOPTED under the lock, and only the migrations after it run" {
            val hook = CountingHook("001-initial-setup", "002-carts", "003-file-store")
            GodwitFixture(config = GodwitConfig(adoptApplied = hook)).use { f ->
                f.collection("schema-log").insertOne(Document("version", "003-file-store"))
                val list = listOf(
                    adopted("001-initial-setup"),
                    adopted("002-carts"),
                    adopted("003-file-store"),
                    runs("004-order-status"),
                    runs("005-customer-external-ids")
                )

                val report = LogCapture().use { logs ->
                    val report = f.godwit.migrate(list)

                    logs.events.map { it.message } shouldBe listOf(
                        "Acquired migration lock",
                        "Adopted applied migrations",
                        "Running migration",
                        "Applied migration",
                        "Running migration",
                        "Applied migration",
                        "Migrations complete"
                    )
                    logs.events("Adopted applied migrations").single().line shouldBe
                        "Adopted applied migrations adopted=[001-initial-setup, 002-carts, 003-file-store] ignored=[]"
                    val complete = logs.events("Migrations complete").single().keyValues
                    complete["ran"] shouldBe 2
                    complete["recorded"] shouldBe 3
                    complete["upToDate"] shouldBe 0
                    report
                }

                hook.calls shouldBe 1
                hook.database shouldBe f.db.name
                report.recordedIds() shouldBe
                    listOf("001-initial-setup ADOPTED", "002-carts ADOPTED", "003-file-store ADOPTED")
                report.recorded.forEach { outcome ->
                    withClue(outcome.id) {
                        outcome.kind shouldBe MigrationKind.Once
                        outcome.steps.shouldBeEmpty()
                        outcome.attempts shouldBe 0
                        outcome.transactionRetries shouldBe 0
                        outcome.batches shouldBe 0
                        outcome.counts shouldBe emptyMap<String, Long>()
                        outcome.outOfOrder shouldBe false
                        outcome.duration shouldBe Duration.ZERO
                    }
                }
                report["002-carts"].origin shouldBe Origin.ADOPTED
                report.ran.map { it.id } shouldBe listOf("004-order-status", "005-customer-external-ids")
                report.upToDate.shouldBeEmpty()

                val stored = f.stored("002-carts").shouldNotBeNull()
                stored.keys shouldBe setOf(
                    "_id", "state", "origin", "owner", "holder", "runId", "finishedAt", "godwitVersion", "v", "kind",
                    "steps", "attempts"
                )
                stored.getString("kind") shouldBe "ONCE"
                stored["steps"] shouldBe emptyList<String>()
                stored.getString("state") shouldBe "APPLIED"
                stored.getString("origin") shouldBe "ADOPTED"
                stored.getInteger("attempts") shouldBe 0
                stored.getString("holder") shouldBe "godwit-runner/1"
                stored.getString("runId") shouldBe report.runId
                stored.getString("owner") shouldBe f.collection("godwit-lock").find().first().getString("owner")
                stored.getString("godwitVersion") shouldBe version
                stored.getInteger("v") shouldBe 1
                f.godwit.status(list).isUpToDate shouldBe true

                // Migrations ran on this database, so adoption has ended: a later start does not call the hook.
                f.godwit.migrate(list + runs("006-order-totals")).ran.map { it.id } shouldBe listOf("006-order-totals")
                hook.calls shouldBe 1
            }
        }

        "ids the list does not declare as once-only are ignored and logged, never recorded or warned about" {
            val hook =
                CountingHook("001-initial-setup", "cleanup-temp-data", "reference-countries", "bootstrap-customers")
            GodwitFixture(config = GodwitConfig(adoptApplied = hook)).use { f ->
                val bootstrap = everyStart("bootstrap-customers").outsideTransaction { count("customersCreated", 0) }
                val list = listOf(
                    adopted("001-initial-setup"),
                    runs("002-carts"),
                    referenceCountries("2026-10-01"),
                    bootstrap
                )

                LogCapture().use { logs ->
                    val report = f.godwit.migrate(list)

                    logs.events("Adopted applied migrations").single().line shouldBe
                        "Adopted applied migrations adopted=[001-initial-setup] " +
                        "ignored=[bootstrap-customers, cleanup-temp-data, reference-countries]"
                    report.recordedIds() shouldBe listOf("001-initial-setup ADOPTED")
                    report.ran.map { it.id } shouldBe listOf("002-carts", "reference-countries", "bootstrap-customers")
                    report.unknownApplied.shouldBeEmpty()
                    logs.events("Unknown applied migrations").shouldBeEmpty()
                }
                f.history.origins() shouldBe listOf(
                    "001-initial-setup ADOPTED",
                    "002-carts RAN",
                    "bootstrap-customers RAN",
                    "reference-countries RAN"
                )
                f.stored("reference-countries")!!.getString("revision") shouldBe "2026-10-01"
            }
        }

        "ids that only a supersedes list names are adopted, and the baseline is then recorded SUPERSEDED" {
            val old = (1..6).map { "00$it-old" }
            val hook = CountingHook(*old.toTypedArray())
            GodwitFixture(config = GodwitConfig(adoptApplied = hook)).use { f ->
                val baseline = migration("100-baseline", supersedes = old).outsideTransaction { error("recorded only") }
                val list = listOf(baseline, runs("101-product-slugs"))

                LogCapture().use { logs ->
                    val report = f.godwit.migrate(list)

                    logs.events.map { it.message } shouldBe listOf(
                        "Acquired migration lock",
                        "Adopted applied migrations",
                        "Recorded superseded migration",
                        "Running migration",
                        "Applied migration",
                        "Migrations complete"
                    )
                    logs.events("Adopted applied migrations").single().line shouldBe
                        "Adopted applied migrations adopted=[${old.joinToString(", ")}] ignored=[]"
                    logs.events("Recorded superseded migration").single().line shouldBe
                        "Recorded superseded migration id=100-baseline supersedes=[${old.joinToString(", ")}]"
                    report.recordedIds() shouldBe old.map { "$it ADOPTED" } + "100-baseline SUPERSEDED"
                    report.ran.map { it.id } shouldBe listOf("101-product-slugs")
                    logs.events("Migrations complete").single().keyValues["recorded"] shouldBe 7
                }
                f.stored("100-baseline")!!["supersedes"] shouldBe old
                f.godwit.migrate(list).upToDate shouldBe listOf("100-baseline", "101-product-slugs")
                hook.calls shouldBe 1
            }
        }

        "a gap left after the hook follows OutOfOrder.FAIL: PlanConflictException, and the adopted records stay" {
            val hook = CountingHook("001-initial-setup", "003-file-store")
            GodwitFixture(config = GodwitConfig(adoptApplied = hook)).use { f ->
                val list = listOf(
                    adopted("001-initial-setup"),
                    runs("002-carts"),
                    adopted("003-file-store"),
                    runs("004-order-status")
                )

                val conflict = shouldThrow<PlanConflictException> { f.godwit.migrate(list) }

                conflict.problems shouldBe listOf(
                    "002-carts is pending, but 003-file-store, listed after it, is applied (out of order; " +
                        "OutOfOrder.RUN runs it)"
                )
                hook.calls shouldBe 1
                f.history.origins() shouldBe listOf("001-initial-setup ADOPTED", "003-file-store ADOPTED")
                f.lockReleased() shouldBe true

                // History holds nothing but ADOPTED documents, so every following start calls the hook again.
                shouldThrow<PlanConflictException> { f.godwit.migrate(list) }
                hook.calls shouldBe 2
            }
        }

        "a gap left after the hook follows OutOfOrder.RUN: the missing migration runs, recorded out of order" {
            val hook = CountingHook("001-initial-setup", "003-file-store")
            GodwitFixture(config = GodwitConfig(adoptApplied = hook, outOfOrder = OutOfOrder.RUN)).use { f ->
                val list = listOf(
                    adopted("001-initial-setup"),
                    runs("002-carts"),
                    adopted("003-file-store"),
                    runs("004-order-status")
                )

                LogCapture().use { logs ->
                    val report = f.godwit.migrate(list)

                    report.recordedIds() shouldBe listOf("001-initial-setup ADOPTED", "003-file-store ADOPTED")
                    report.ran.map { it.id } shouldBe listOf("002-carts", "004-order-status")
                    report["002-carts"].outOfOrder shouldBe true
                    report["004-order-status"].outOfOrder shouldBe false
                    logs.events("Running out-of-order migration").single().line shouldBe
                        "Running out-of-order migration id=002-carts appliedAfter=[003-file-store]"
                }
                f.stored("002-carts")!!.getBoolean("outOfOrder") shouldBe true
            }
        }

        "after a refused gap the next start calls the hook again, records only what it adds and removes nothing" {
            val hook = CountingHook("001-initial-setup", "003-file-store")
            GodwitFixture(config = GodwitConfig(adoptApplied = hook)).use { f ->
                val list = listOf(
                    adopted("001-initial-setup"),
                    adopted("002-carts"),
                    adopted("003-file-store"),
                    adopted("004-order-status"),
                    runs("005-customer-external-ids")
                )
                shouldThrow<PlanConflictException> { f.godwit.migrate(list) }
                val first = f.history.find(Document("_id", "001-initial-setup")).first()

                // The record is corrected: 002 and 004 are added, and 003 is deleted by mistake.
                hook.ids = setOf("001-initial-setup", "002-carts", "004-order-status")
                LogCapture().use { logs ->
                    val report = f.godwit.migrate(list)

                    logs.events("Adopted applied migrations").single().line shouldBe
                        "Adopted applied migrations adopted=[002-carts, 004-order-status] ignored=[]"
                    report.recordedIds() shouldBe listOf("002-carts ADOPTED", "004-order-status ADOPTED")
                    report.upToDate shouldBe listOf("001-initial-setup", "003-file-store")
                    report.ran.map { it.id } shouldBe listOf("005-customer-external-ids")
                }
                hook.calls shouldBe 2
                f.history.origins() shouldBe listOf(
                    "001-initial-setup ADOPTED",
                    "002-carts ADOPTED",
                    "003-file-store ADOPTED",
                    "004-order-status ADOPTED",
                    "005-customer-external-ids RAN"
                )
                f.stored("001-initial-setup") shouldBe first
            }
        }

        "an exception from the hook propagates unchanged, nothing is recorded and the next start calls it again" {
            val failure = IllegalStateException("not authorized on shop to execute command { find: \"schema-log\" }")
            var failing = true
            val hook = CountingHook("001-initial-setup", onCall = { if (failing) throw failure })
            GodwitFixture(config = GodwitConfig(adoptApplied = hook)).use { f ->
                val list = listOf(adopted("001-initial-setup"), runs("002-carts"))

                LogCapture().use { logs ->
                    shouldThrow<IllegalStateException> { f.godwit.migrate(list) } shouldBeSameInstanceAs failure

                    logs.events("Adopted applied migrations").shouldBeEmpty()
                    logs.events("Migrations complete").shouldBeEmpty()
                }
                f.history.countDocuments() shouldBe 0L
                f.lockReleased() shouldBe true

                failing = false
                f.godwit.migrate(list).recordedIds() shouldBe listOf("001-initial-setup ADOPTED")
                hook.calls shouldBe 2
            }
        }

        "a hook that adopts nothing on a database with collections trips the untracked-database guard" {
            for (returned in listOf(emptySet(), setOf("cleanup-temp-data"))) {
                withClue("the hook returns $returned") {
                    val hook = CountingHook(*returned.toTypedArray())
                    GodwitFixture(config = GodwitConfig(adoptApplied = hook)).use { f ->
                        f.collection("customers").insertOne(Document("email", "a@example.com"))
                        f.collection("schema-lg").insertOne(Document("version", "001-initial-setup"))

                        LogCapture().use { logs ->
                            val refused = shouldThrow<UntrackedDatabaseException> {
                                f.godwit.migrate(adopted("001-initial-setup"))
                            }

                            refused.collections shouldBe listOf("customers", "schema-lg")
                            logs.events("Adopted applied migrations").single().line shouldBe
                                "Adopted applied migrations adopted=[] ignored=[${returned.joinToString(", ")}]"
                        }
                        hook.calls shouldBe 1
                        f.history.countDocuments() shouldBe 0L
                        f.lockReleased() shouldBe true
                    }
                }
            }
        }

        "a new database with the hook configured runs every migration, the hook called once against nothing" {
            val hook = CountingHook()
            GodwitFixture(config = GodwitConfig(adoptApplied = hook)).use { f ->
                val list = listOf(runs("001-initial-setup"), runs("002-carts"))

                f.godwit.migrate(list).ran.map { it.id } shouldBe listOf("001-initial-setup", "002-carts")
                f.godwit.migrate(list + runs("003-file-store")).ran.map { it.id } shouldBe listOf("003-file-store")
                hook.calls shouldBe 1
            }
        }

        "system.* collections do not count: a database that holds only them runs everything" {
            val hook = CountingHook()
            GodwitFixture(config = GodwitConfig(adoptApplied = hook)).use { f ->
                f.collection("system.js").insertOne(Document("_id", "lower").append("value", "function(s) {}"))

                f.godwit.migrate(runs("001-initial-setup")).ran.map { it.id } shouldBe listOf("001-initial-setup")
                hook.calls shouldBe 1
            }
        }

        "a standalone server with a transactional migration due refuses before the lock, without calling the hook" {
            val hook = CountingHook("001-initial-setup", "004-order-status")
            StandaloneProcess("adoption-standalone-transactional", GodwitConfig(adoptApplied = hook)).use { p ->
                val list = listOf(adopted("001-initial-setup"), migration("004-order-status").inTransaction { })

                val refused = shouldThrow<TransactionsUnsupportedException> { p.godwit.migrate(list) }

                refused.due shouldBe listOf("004-order-status")
                hook.calls shouldBe 0
                p.recorder.commands.map { it.name } shouldBe listOf("find", "hello")
                p.database.listCollectionNames().toList().shouldBeEmpty()
            }
        }

        "an error that is not transient in the adoption transaction records nothing and the next start adopts" {
            val hook = CountingHook("001-initial-setup", "002-carts")
            GodwitFixture(appName = "adoption-error", config = GodwitConfig(adoptApplied = hook)).use { f ->
                val list = listOf(adopted("001-initial-setup"), adopted("002-carts"), runs("003-file-store"))

                val error = LogCapture().use { logs ->
                    val error =
                        f.failHistoryWrites(listOf("update"), Document("skip", 1), Document("errorCode", 2)).use {
                            shouldThrow<MongoCommandException> { f.godwit.migrate(list) }
                        }
                    // The hook returned under the lock, so its call is logged: the transaction recorded nothing.
                    logs.events("Adopted applied migrations").single().line shouldBe
                        "Adopted applied migrations adopted=[] ignored=[]"
                    logs.events("Migrations complete").shouldBeEmpty()
                    error
                }

                error.code shouldBe 2
                f.history.countDocuments() shouldBe 0L
                f.recorder.commands("commitTransaction").shouldBeEmpty()
                f.lockReleased() shouldBe true

                val report = f.godwit.migrate(list)
                report.recordedIds() shouldBe listOf("001-initial-setup ADOPTED", "002-carts ADOPTED")
                report.ran.map { it.id } shouldBe listOf("003-file-store")
                hook.calls shouldBe 2
            }
        }

        "a transient error in the adoption transaction is retried in the same call, and every id is recorded" {
            val hook = CountingHook("001-initial-setup", "002-carts")
            GodwitFixture(appName = "adoption-transient", config = GodwitConfig(adoptApplied = hook)).use { f ->
                val list = listOf(adopted("001-initial-setup"), adopted("002-carts"), runs("003-file-store"))
                val transient = Document("errorCode", 112).append("errorLabels", listOf("TransientTransactionError"))

                val report = LogCapture().use { logs ->
                    val report = f.failHistoryWrites(listOf("update"), Document("times", 1), transient).use {
                        f.godwit.migrate(list)
                    }
                    logs.events("Adopted applied migrations").single().line shouldBe
                        "Adopted applied migrations adopted=[001-initial-setup, 002-carts] ignored=[]"
                    report
                }

                report.recordedIds() shouldBe listOf("001-initial-setup ADOPTED", "002-carts ADOPTED")
                hook.calls shouldBe 1
                val opened = f.recorder.commands("update").filter { it.command.containsKey("startTransaction") }
                opened.map { it.errorCode } shouldBe listOf(112, null)
                f.recorder.commands("commitTransaction").size shouldBe 1
            }
        }

        "on a standalone server the records go one at a time, last-listed first, each id after those it supersedes" {
            val hook = CountingHook("001-a", "002-b", "003-baseline", "004-d")
            StandaloneProcess("adoption-standalone-order", GodwitConfig(adoptApplied = hook)).use { p ->
                val baseline = migration("003-baseline", supersedes = listOf("001-a", "002-b"))
                    .outsideTransaction { error("adopted, so it never runs") }
                val list = listOf(baseline, adopted("004-d"), runs("005-e"))

                val report = p.godwit.migrate(list)

                report.recordedIds() shouldBe
                    listOf("001-a ADOPTED", "002-b ADOPTED", "003-baseline ADOPTED", "004-d ADOPTED")
                report.ran.map { it.id } shouldBe listOf("005-e")
                p.recorder.updatedIds().take(4) shouldBe listOf("004-d", "003-baseline", "002-b", "001-a")
                p.recorder.commands.none { it.command.containsKey("startTransaction") } shouldBe true
                p.recorder.commands("hello").size shouldBe 1
            }
        }

        for (outOfOrder in OutOfOrder.entries) {
            "an interrupted standalone adoption completes on the next start under OutOfOrder.$outOfOrder" {
                val hook = CountingHook("001-a", "002-b", "003-c")
                val config = GodwitConfig(adoptApplied = hook, outOfOrder = outOfOrder)
                StandaloneProcess("adoption-interrupted-${outOfOrder.name.lowercase()}", config).use { p ->
                    val list = listOf(adopted("001-a"), adopted("002-b"), adopted("003-c"), runs("004-d"))
                    val interrupted = LogCapture().use { logs ->
                        val interrupted = p.failHistoryWrites(
                            listOf("update"),
                            Document("skip", 2),
                            Document("errorCode", 2)
                        ).use {
                            shouldThrow<MongoCommandException> { p.godwit.migrate(list) }
                        }
                        // The two ids written before the failure stay recorded, and the interrupted call names them.
                        logs.events("Adopted applied migrations").single().line shouldBe
                            "Adopted applied migrations adopted=[002-b, 003-c] ignored=[]"
                        interrupted
                    }
                    interrupted.code shouldBe 2
                    p.history.origins() shouldBe listOf("002-b ADOPTED", "003-c ADOPTED")

                    // A start without the hook sees the partial adoption as a gap before 002-b.
                    p.process("adoption-without-hook", GodwitConfig()).use { without ->
                        shouldThrow<PlanConflictException> { without.godwit.migrate(list) }.problems shouldBe listOf(
                            "001-a is pending, but 002-b, listed after it, is applied (out of order; " +
                                "OutOfOrder.RUN runs it)"
                        )
                    }

                    p.godwit.status(list).problems.shouldBeEmpty()
                    LogCapture().use { logs ->
                        val report = p.godwit.migrate(list)

                        report.recordedIds() shouldBe listOf("001-a ADOPTED")
                        report.ran.map { it.id } shouldBe listOf("004-d")
                        report.ran.single().outOfOrder shouldBe false
                        logs.events("Running out-of-order migration").shouldBeEmpty()
                        logs.events("Adopted applied migrations").single().line shouldBe
                            "Adopted applied migrations adopted=[001-a] ignored=[]"
                    }
                    hook.calls shouldBe 2
                    p.history.origins() shouldBe listOf("001-a ADOPTED", "002-b ADOPTED", "003-c ADOPTED", "004-d RAN")
                }
            }
        }

        "an interrupted standalone adoption of ids a supersedes list names completes with the hook, never without it" {
            val hook = CountingHook("001-a", "002-b", "003-c")
            StandaloneProcess("adoption-interrupted-squash", GodwitConfig(adoptApplied = hook)).use { p ->
                val baseline = migration("100-baseline", supersedes = listOf("001-a", "002-b", "003-c"))
                    .outsideTransaction { error("recorded only") }
                val list = listOf(baseline, runs("101-e"))
                LogCapture().use { logs ->
                    p.failHistoryWrites(listOf("update"), Document("skip", 2), Document("errorCode", 2)).use {
                        shouldThrow<MongoCommandException> { p.godwit.migrate(list) }
                    }
                    logs.events("Adopted applied migrations").single().line shouldBe
                        "Adopted applied migrations adopted=[002-b, 003-c] ignored=[]"
                }
                p.history.origins() shouldBe listOf("002-b ADOPTED", "003-c ADOPTED")

                for (outOfOrder in OutOfOrder.entries) {
                    p.process("adoption-squash-without-hook", GodwitConfig(outOfOrder = outOfOrder)).use { without ->
                        withClue("without the hook, OutOfOrder.$outOfOrder") {
                            shouldThrow<PlanConflictException> { without.godwit.migrate(list) }.problems shouldBe
                                listOf(
                                    "100-baseline supersedes 3 migrations, but only 002-b, 003-c are applied. " +
                                        "Deploy the previous release first."
                                )
                        }
                    }
                }
                p.history.origins() shouldBe listOf("002-b ADOPTED", "003-c ADOPTED")

                // With the hook the partial supersede is no conflict before the lock: the baseline counts as due.
                val report = p.godwit.migrate(list)

                report.recordedIds() shouldBe listOf("001-a ADOPTED", "100-baseline SUPERSEDED")
                report.ran.map { it.id } shouldBe listOf("101-e")
                report.lockWait.shouldNotBeNull()
                hook.calls shouldBe 2
            }
        }

        // With nothing planted the hook's id is to record; with it planted ADOPTED there is nothing to record.
        val hookVariants = listOf("an id to record" to emptyList<String>(), "nothing to record" to listOf("001-a"))
        for ((variant, planted) in hookVariants) {
            "a lock lost while the hook runs throws LockLostException, with $variant: no record, no line" {
                LogCapture().use { logs ->
                    val appName = "adoption-lost-in-hook-${planted.size}"
                    // The hook reads for longer than the lease lasts without a renewal.
                    val hook = CountingHook("001-a", onCall = {
                        blockRenewals(appName).use { logs.awaitHeartbeatLoss() }
                    })
                    val config = GodwitConfig(adoptApplied = hook, lock = testTimings.copy(waitTimeout = 1.minutes))
                    GodwitFixture(appName = appName, config = config).use { f ->
                        if (planted.isNotEmpty()) f.history.plantAdopted(*planted.toTypedArray())
                        val before = f.history.find().toList()

                        val lost = shouldThrow<LockLostException> {
                            f.godwit.migrate(adopted("001-a"), runs("002-b"))
                        }

                        lost.id.shouldBeNull()
                        hook.calls shouldBe 1
                        f.history.find().toList() shouldBe before
                        logs.events("Adopted applied migrations").shouldBeEmpty()
                        logs.events("Running migration").shouldBeEmpty()
                        logs.events("Lost migration lock").single().keyValues["reason"] shouldBe "DEADLINE_PASSED"
                    }
                }
            }
        }

        "a lock lost before the adoption commit throws LockLostException, and the transaction records nothing" {
            LogCapture().use { logs ->
                var armed = true
                val recorder = CommandRecorder(onSucceeded = { command ->
                    // The first adoption write, inside the transaction: the heartbeat loses the lock before the commit.
                    if (armed && command.name == "update" && command.collection == "godwit-history") {
                        armed = false
                        blockRenewals("adoption-lost-before-commit").use { logs.awaitHeartbeatLoss() }
                    }
                })
                val hook = CountingHook("001-a", "002-b")
                val config = GodwitConfig(adoptApplied = hook, lock = testTimings.copy(waitTimeout = 1.minutes))
                GodwitFixture(appName = "adoption-lost-before-commit", config = config, recorder = recorder).use { f ->
                    val lost = shouldThrow<LockLostException> {
                        f.godwit.migrate(adopted("001-a"), adopted("002-b"), runs("003-c"))
                    }

                    lost.id.shouldBeNull()
                    f.history.countDocuments() shouldBe 0L
                    f.recorder.commands("update").filter { it.collection == "godwit-history" }.size shouldBe 2
                    f.recorder.commands("commitTransaction").shouldBeEmpty()
                    logs.events("Adopted applied migrations").single().line shouldBe
                        "Adopted applied migrations adopted=[] ignored=[]"
                    logs.events("Running migration").shouldBeEmpty()
                }
            }
        }

        "on a standalone server a lock lost after the first write stops the next one; the first stays, logged" {
            LogCapture().use { logs ->
                var armed = true
                val recorder = CommandRecorder(onSucceeded = { command ->
                    // The first adoption write (the last-listed id): the heartbeat loses the lock before the second.
                    if (armed && command.name == "update" && command.collection == "godwit-history") {
                        armed = false
                        blockRenewals("adoption-standalone-lost", standaloneMongo).use { logs.awaitHeartbeatLoss() }
                    }
                })
                val hook = CountingHook("001-a", "002-b", "003-c")
                val config = GodwitConfig(adoptApplied = hook, lock = testTimings.copy(waitTimeout = 1.minutes))
                StandaloneProcess("adoption-standalone-lost", config, recorder).use { p ->
                    val lost = shouldThrow<LockLostException> {
                        p.godwit.migrate(adopted("001-a"), adopted("002-b"), adopted("003-c"), runs("004-d"))
                    }

                    lost.id.shouldBeNull()
                    p.history.origins() shouldBe listOf("003-c ADOPTED")
                    p.recorder.updatedIds() shouldBe listOf("003-c")
                    logs.events("Adopted applied migrations").single().line shouldBe
                        "Adopted applied migrations adopted=[003-c] ignored=[]"
                    logs.events("Running migration").shouldBeEmpty()
                }
            }
        }

        "status on a partially adopted database lists the missing ids as pending and reports no problem" {
            val hook = CountingHook("001-a", "002-b", "003-c")
            GodwitFixture(config = GodwitConfig(adoptApplied = hook)).use { f ->
                f.collection("customers").insertOne(Document("email", "a@example.com"))
                f.history.plantAdopted("002-b", "003-c")
                val list = listOf(adopted("001-a"), adopted("002-b"), adopted("003-c"), runs("004-d"))
                f.recorder.clear()

                val status = f.godwit.status(list)

                status.pending shouldBe listOf("001-a", "004-d")
                status.problems.shouldBeEmpty()
                status.isUpToDate shouldBe false
                hook.calls shouldBe 0
                f.recorder.commands.map { it.name } shouldBe listOf("find")
                f.godwit.history().map { it.id } shouldBe listOf("002-b", "003-c")

                f.process("adoption-status-without-hook").use { without ->
                    without.godwit.status(list).problems shouldBe listOf(
                        "001-a is pending, but 002-b, listed after it, is applied (out of order; OutOfOrder.RUN runs it)"
                    )
                }
            }
        }

        for (origin in listOf(Origin.RAN, Origin.SUPERSEDED, Origin.MARKED)) {
            "the hook is not called while history holds a $origin document, and is called again once it is deleted" {
                val hook = CountingHook("001-a")
                GodwitFixture(config = GodwitConfig(adoptApplied = hook)).use { f ->
                    f.history.plantAdopted("001-a")
                    f.history.insertOne(historyDocument("002-b", origin = origin))
                    val list = listOf(adopted("001-a"), runs("002-b"), runs("003-c"))

                    f.godwit.migrate(list).ran.map { it.id } shouldBe listOf("003-c")
                    hook.calls shouldBe 0

                    f.history.deleteMany(ne("origin", "ADOPTED"))
                    val report = f.godwit.migrate(list)

                    hook.calls shouldBe 1
                    report.recorded.shouldBeEmpty()
                    report.ran.map { it.id } shouldBe listOf("002-b", "003-c")
                }
            }
        }

        "the first id marked without the hook before the first start ends adoption: the rest runs, unadopted" {
            val hook = CountingHook("001-a", "002-b")
            GodwitFixture(config = GodwitConfig(adoptApplied = hook)).use { f ->
                f.collection("schema-log").insertOne(Document("version", "001-a"))
                val list = listOf(adopted("001-a"), runs("002-b"), runs("003-c"))

                Godwit(f.client, f.db.name, f.config.copy(adoptApplied = null)).markApplied("001-a", "applied by hand")
                val report = f.godwit.migrate(list)

                hook.calls shouldBe 0
                report.recorded.shouldBeEmpty()
                report.ran.map { it.id } shouldBe listOf("002-b", "003-c")
            }
        }

        "a later id marked without the hook before the first start makes the ids before it out of order" {
            val hook = CountingHook("001-a", "002-b")
            GodwitFixture(config = GodwitConfig(adoptApplied = hook)).use { f ->
                val list = listOf(adopted("001-a"), adopted("002-b"), runs("003-c"))

                Godwit(f.client, f.db.name, f.config.copy(adoptApplied = null)).markApplied("002-b", "applied by hand")

                shouldThrow<PlanConflictException> { f.godwit.migrate(list) }.problems shouldBe listOf(
                    "001-a is pending, but 002-b, listed after it, is applied (out of order; OutOfOrder.RUN runs it)"
                )
                hook.calls shouldBe 0
            }
        }

        "a start with nothing due takes the fast path and does not call the hook" {
            val hook = CountingHook("001-a", "002-b")
            GodwitFixture(config = GodwitConfig(adoptApplied = hook)).use { f ->
                f.history.plantAdopted("001-a", "002-b")

                val report = f.godwit.migrate(adopted("001-a"), adopted("002-b"))

                report.lockWait.shouldBeNull()
                report.upToDate shouldBe listOf("001-a", "002-b")
                hook.calls shouldBe 0
            }
        }
    }
}
