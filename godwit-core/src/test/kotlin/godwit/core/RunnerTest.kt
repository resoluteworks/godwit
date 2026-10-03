package godwit.core

import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates.inc
import com.mongodb.client.model.Updates.set
import godwit.core.fixtures.CommandRecorder
import godwit.core.fixtures.GodwitFixture
import godwit.core.fixtures.LogCapture
import godwit.core.fixtures.TestMongo
import godwit.core.fixtures.keyValues
import godwit.core.fixtures.line
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.bson.Document
import java.time.Instant
import java.util.Date
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

private val version: String = System.getProperty("godwit.version")

/** `{_id: id, n: 1}` incremented in [collection] on [session]: a transactional effect that must commit exactly once. */
private fun TransactionScope.probe(collection: String = "probes") {
    collection(collection).updateOne(session, eq("_id", id), inc("n", 1), UpdateOptions().upsert(true))
}

/** A history document as an earlier run left it. */
private fun planted(id: String, state: HistoryState, attempts: Int = 1, origin: Origin = Origin.RAN) =
    Document("_id", id).append("kind", "ONCE").append("steps", listOf("OUTSIDE_TRANSACTION"))
        .append("state", state.name).append("origin", origin.name).append("attempts", attempts)
        .append("owner", "token-of-an-earlier-run").append("holder", "shop-earlier/1").append("runId", "run-earlier")

class RunnerTest : StringSpec() {
    init {
        "an outside-only migration runs its step, then records APPLIED outside any transaction" {
            GodwitFixture().use { f ->
                val carts = migration("002-carts", description = "Carts").outsideTransaction {
                    count("collectionsCreated", if (ensureCollection("carts")) 1 else 0)
                }
                LogCapture().use { logs ->
                    val before = Instant.now()
                    val report = f.godwit.migrate(carts)

                    report.ran.map { it.id } shouldBe listOf("002-carts")
                    val outcome = report["002-carts"]
                    outcome.kind shouldBe MigrationKind.Once
                    outcome.origin shouldBe Origin.RAN
                    outcome.steps shouldBe listOf(StepKind.OUTSIDE_TRANSACTION)
                    outcome.attempts shouldBe 1
                    outcome.transactionRetries shouldBe 0
                    outcome.batches shouldBe 0
                    outcome.counts shouldBe mapOf("collectionsCreated" to 1L)
                    outcome.outOfOrder shouldBe false
                    report.recorded.shouldBeEmpty()
                    report.upToDate.shouldBeEmpty()
                    report.pending.shouldBeEmpty()
                    report.unknownApplied.shouldBeEmpty()
                    report.lockWait.shouldNotBeNull()
                    UUID.fromString(report.runId).toString() shouldBe report.runId

                    val stored = f.stored("002-carts").shouldNotBeNull()
                    stored.getString("kind") shouldBe "ONCE"
                    stored.getString("description") shouldBe "Carts"
                    stored["steps"] shouldBe listOf("OUTSIDE_TRANSACTION")
                    stored.getString("state") shouldBe "APPLIED"
                    stored.getString("origin") shouldBe "RAN"
                    stored.getInteger("attempts") shouldBe 1
                    stored.getInteger("transactionRetries") shouldBe 0
                    stored["counts"] shouldBe Document("collectionsCreated", 1L)
                    stored.getLong("durationMs") shouldBeGreaterThanOrEqual 0L
                    stored.getDate("startedAt").toInstant().isBefore(before) shouldBe false
                    stored.getDate("finishedAt").toInstant().isBefore(stored.getDate("startedAt").toInstant()) shouldBe
                        false
                    stored.getString("holder") shouldBe "godwit-runner/1"
                    stored.getString("runId") shouldBe report.runId
                    stored.getString("godwitVersion") shouldBe version
                    stored.getInteger("v") shouldBe 1
                    stored.containsKey("lastError") shouldBe false
                    f.database.listCollectionNames().toList().contains("carts") shouldBe true

                    val record = f.recorder.commands("update").single {
                        it.command.getString("update").value ==
                            "godwit-history"
                    }
                    record.command.containsKey("autocommit") shouldBe false
                    record.command.getDocument("writeConcern").getString("w").value shouldBe "majority"

                    logs.events.map { it.message } shouldBe
                        listOf(
                            "Acquired migration lock",
                            "Running migration",
                            "Applied migration",
                            "Migrations complete"
                        )
                    logs.events("Running migration").single().line shouldBe
                        "Running migration id=002-carts kind=ONCE steps=[OUTSIDE_TRANSACTION] attempt=1"
                    val applied = logs.events("Applied migration").single().keyValues
                    applied.keys.toList() shouldBe listOf(
                        "id",
                        "kind",
                        "steps",
                        "attempts",
                        "txRetries",
                        "batches",
                        "durationMs",
                        "collectionsCreated"
                    )
                    applied["txRetries"] shouldBe 0
                    applied["batches"] shouldBe 0
                    applied["collectionsCreated"] shouldBe 1L
                    val complete = logs.events("Migrations complete").single().keyValues
                    complete["runId"] shouldBe report.runId
                    complete["ran"] shouldBe 1
                    complete["recorded"] shouldBe 0
                    complete["upToDate"] shouldBe 0
                    complete["lockWaitMs"] shouldBe report.lockWait.inWholeMilliseconds
                }
            }
        }

        "a transactional migration writes its marker on the session that commits its writes with the APPLIED record" {
            GodwitFixture().use { f ->
                val orderStatus = migration("004-order-status").inTransaction {
                    val orders = collection("orders")
                    orders.insertOne(session, Document("_id", 1).append("paidAt", Date()))
                    orders.insertOne(session, Document("_id", 2))
                    val paid = orders.updateMany(session, exists("paidAt"), set("status", "PAID"))
                    val pending = orders.updateMany(session, exists("status", false), set("status", "PENDING"))
                    count("ordersPaid", paid.modifiedCount)
                    count("ordersPending", pending.modifiedCount)
                }

                val report = f.godwit.migrate(orderStatus)

                report["004-order-status"].counts shouldBe mapOf("ordersPaid" to 1L, "ordersPending" to 1L)
                report["004-order-status"].steps shouldBe listOf(StepKind.IN_TRANSACTION)
                f.collection("orders").find().toList().map { it.getString("status") } shouldBe listOf("PAID", "PENDING")
                val stored = f.stored("004-order-status").shouldNotBeNull()
                stored.getString("state") shouldBe "APPLIED"
                stored["counts"] shouldBe Document("ordersPaid", 1L).append("ordersPending", 1L)
                stored.containsKey("description") shouldBe false

                val marker = f.recorder.commands("findAndModify").single {
                    it.command.getString("findAndModify").value == "godwit-history"
                }
                val opened = f.recorder.commands.first {
                    it.command.getBoolean("startTransaction", null)?.value == true
                }
                opened.command.getDocument("readConcern").getString("level").value shouldBe "snapshot"
                opened.command["lsid"] shouldBe marker.command["lsid"]
                val record = f.recorder.commands("update").single {
                    it.command.getString("update").value ==
                        "godwit-history"
                }
                record.command["lsid"] shouldBe marker.command["lsid"]
                record.command.getBoolean("autocommit").value shouldBe false
                val commit = f.recorder.commands("commitTransaction").single()
                commit.command.getDocument("writeConcern").getString("w").value shouldBe "majority"
                f.recorder.commands.indexOf(record) shouldBe f.recorder.commands.indexOf(commit) - 1
            }
        }

        "a two-step migration hands its prepared value to the transaction and adds up the counters of both steps" {
            GodwitFixture().use { f ->
                val seen = mutableListOf<Any?>()
                val prepared = mapOf("a" to 1, "b" to 2)
                val twoSteps = migration("005-two-steps")
                    .outsideTransaction {
                        count("linked", 2)
                        count("lookups", 7)
                        prepared
                    }
                    .inTransaction { value ->
                        seen += value
                        value.forEach { (key, n) ->
                            collection("links").insertOne(session, Document("_id", key).append("n", n))
                        }
                        count("linked", value.size)
                        count("written", 1)
                    }

                val outcome = f.godwit.migrate(twoSteps)["005-two-steps"]

                seen.single() shouldBeSameInstanceAs prepared
                outcome.steps shouldBe listOf(StepKind.OUTSIDE_TRANSACTION, StepKind.IN_TRANSACTION)
                outcome.counts shouldBe mapOf("linked" to 4L, "lookups" to 7L, "written" to 1L)
                outcome.counts.keys.toList() shouldBe listOf("linked", "lookups", "written")
                outcome.count("missing") shouldBe 0L
                f.stored("005-two-steps")!!["counts"] shouldBe
                    Document("linked", 4L).append("lookups", 7L).append("written", 1L)
                f.collection("links").countDocuments() shouldBe 2L
            }
        }

        "every driver retry of the body gets the same prepared instance, so a drained or one-shot value breaks" {
            GodwitFixture(appName = "runner-prepared").use { f ->
                val seen = mutableListOf<List<Int>>()
                val sizes = mutableListOf<Int>()
                val drained = migration("006-drained")
                    .outsideTransaction { mutableListOf(1, 2, 3) }
                    .inTransaction { ids ->
                        seen += ids
                        sizes += ids.size
                        val taken = ids.toList()
                        ids.clear()
                        taken.forEach { collection("drained").insertOne(session, Document("_id", it)) }
                    }
                val oneShot = migration("007-one-shot")
                    .outsideTransaction { sequenceOf(1, 2, 3).constrainOnce() }
                    .inTransaction { ids -> ids.forEach { collection("once").insertOne(session, Document("_id", it)) } }

                transientInsert(f.appName).use { f.godwit.migrate(drained) }

                seen shouldHaveSize 2
                seen[1] shouldBeSameInstanceAs seen[0]
                sizes shouldBe listOf(3, 0)
                f.collection("drained").countDocuments() shouldBe 0L
                f.stored("006-drained")!!.getInteger("transactionRetries") shouldBe 1

                val failure = transientInsert(f.appName).use {
                    shouldThrow<MigrationFailedException> { f.godwit.migrate(drained, oneShot) }
                }
                failure.id shouldBe "007-one-shot"
                failure.step shouldBe StepKind.IN_TRANSACTION
                failure.cause.shouldBeInstanceOf<IllegalStateException>().message shouldBe
                    "This sequence can be consumed only once."
                f.collection("once").countDocuments() shouldBe 0L
                f.stored("007-one-shot")!!.getString("state") shouldBe "FAILED"
            }
        }

        "the report lists what ran, what was up to date, what the target left pending and the unknown applied ids" {
            GodwitFixture().use { f ->
                val first = migration("001-first").outsideTransaction { }
                val second = migration("002-second").inTransaction { probe() }
                val third = migration("003-third").inTransaction { probe() }
                f.history.insertOne(planted("001-first", HistoryState.APPLIED))
                f.history.insertOne(planted("000-from-a-newer-release", HistoryState.APPLIED))

                LogCapture().use { logs ->
                    val report = f.godwit.migrate(listOf(first, second, third), Target.Before("003-third"))

                    report.ran.map { it.id } shouldBe listOf("002-second")
                    report.upToDate shouldBe listOf("001-first")
                    report.pending shouldBe listOf("003-third")
                    report.unknownApplied shouldBe listOf("000-from-a-newer-release")
                    logs.events("Unknown applied migrations").single().line shouldBe
                        "Unknown applied migrations ids=[000-from-a-newer-release]"
                    logs.events("Unknown applied migrations").single().level.toString() shouldBe "WARN"
                    logs.events("Migrations complete").single().keyValues["upToDate"] shouldBe 1
                    shouldThrow<NoSuchElementException> { report["001-first"] }
                }

                val throughLatest = f.godwit.migrate(listOf(first, second, third))
                throughLatest.ran.map { it.id } shouldBe listOf("003-third")
                throughLatest.upToDate shouldBe listOf("001-first", "002-second")
                throughLatest.pending.shouldBeEmpty()
            }
        }

        "the call stops at the first failure: FAILED with lastError, the report covers what ran before it" {
            GodwitFixture().use { f ->
                val runs = AtomicInteger()
                val first = migration("001-first").inTransaction { probe() }
                val failing = migration("002-failing").outsideTransaction {
                    if (runs.incrementAndGet() == 1) throw IllegalStateException("the identity provider timed out")
                    count("linked", 3)
                }
                val third = migration("003-third").inTransaction { probe() }

                val failure = LogCapture().use { logs ->
                    val failure = shouldThrow<MigrationFailedException> { f.godwit.migrate(first, failing, third) }
                    logs.events("Migration failed").single().line shouldBe
                        "Migration failed id=002-failing step=OUTSIDE_TRANSACTION attempts=1 " +
                        "error=java.lang.IllegalStateException: the identity provider timed out"
                    logs.events("Migrations complete").shouldBeEmpty()
                    failure
                }

                failure.id shouldBe "002-failing"
                failure.step shouldBe StepKind.OUTSIDE_TRANSACTION
                failure.message shouldBe
                    "Migration 002-failing failed in OUTSIDE_TRANSACTION: the identity provider timed out"
                failure.cause.shouldBeInstanceOf<IllegalStateException>()
                failure.report.ran.map { it.id } shouldBe listOf("001-first")
                failure.report.lockWait.shouldNotBeNull()
                val stored = f.stored("002-failing").shouldNotBeNull()
                stored.getString("state") shouldBe "FAILED"
                stored.containsKey("finishedAt") shouldBe true
                val lastError = stored.get("lastError", Document::class.java)
                lastError.getString("type") shouldBe "java.lang.IllegalStateException"
                lastError.getString("message") shouldBe "the identity provider timed out"
                lastError.getString("stack") shouldStartWith "java.lang.IllegalStateException: the identity provider"
                lastError.getString("step") shouldBe "OUTSIDE_TRANSACTION"
                lastError.getDate("at").shouldNotBeNull()
                f.stored("003-third").shouldBeNull()
                f.collection("probes").find().toList().map { it.getString("_id") } shouldBe listOf("001-first")
                f.collection("godwit-lock").find().first().containsKey("releasedAt") shouldBe true

                val retried = f.godwit.migrate(first, failing, third)
                retried.ran.map { it.id } shouldBe listOf("002-failing", "003-third")
                retried["002-failing"].attempts shouldBe 2
                f.stored("002-failing")!!.containsKey("lastError") shouldBe false
                f.stored("002-failing")!!.getInteger("attempts") shouldBe 2
            }
        }

        "a transaction body that throws leaves none of its writes, and its migration FAILED in IN_TRANSACTION" {
            GodwitFixture().use { f ->
                val failing = migration("004-order-status").inTransaction {
                    probe()
                    error("an order without lines")
                }

                val failure = shouldThrow<MigrationFailedException> { f.godwit.migrate(failing) }

                failure.step shouldBe StepKind.IN_TRANSACTION
                f.collection("probes").countDocuments() shouldBe 0L
                f.stored("004-order-status")!!.get("lastError", Document::class.java).getString("step") shouldBe
                    "IN_TRANSACTION"
            }
        }

        "an Error thrown by a step fails the migration like an exception" {
            GodwitFixture().use { f ->
                val asserting = migration("001-asserting").outsideTransaction { throw AssertionError("escaped") }

                val failure = shouldThrow<MigrationFailedException> { f.godwit.migrate(asserting) }

                failure.cause.shouldBeInstanceOf<AssertionError>()
                f.stored("001-asserting")!!.getString("state") shouldBe "FAILED"
            }
        }

        "a RUNNING document is resumed with a warning, a FAILED one is retried without one; both count the attempt" {
            GodwitFixture().use { f ->
                val interrupted = migration("001-interrupted").outsideTransaction { }
                val failed = migration("002-failed").outsideTransaction { }
                f.history.insertOne(planted("001-interrupted", HistoryState.RUNNING))
                f.history.insertOne(planted("002-failed", HistoryState.FAILED, attempts = 3))

                LogCapture().use { logs ->
                    val report = f.godwit.migrate(interrupted, failed)

                    report["001-interrupted"].attempts shouldBe 2
                    report["002-failed"].attempts shouldBe 4
                    val resumed = logs.events("Resuming interrupted migration").single()
                    resumed.line shouldBe "Resuming interrupted migration id=001-interrupted attempts=2"
                    resumed.level.toString() shouldBe "WARN"
                    logs.events("Running migration").map { it.keyValues["attempt"] } shouldBe listOf(2, 4)
                }
            }
        }

        "a migration another run applied after the plan under the lock is skipped as up to date" {
            val appliedByAnother = Document("_id", "001-raced").append("kind", "ONCE").append("state", "APPLIED")
                .append("origin", "RAN").append("owner", "another-run").append("attempts", 1)
            lateinit var f: GodwitFixture
            val reads = AtomicInteger()
            val recorder = CommandRecorder(onSucceeded = { command ->
                // The second history read is the one under the lock; another run applies the migration right after it.
                if (command.name == "find" && command.command.getString("find").value == "godwit-history" &&
                    reads.incrementAndGet() == 2
                ) {
                    f.history.insertOne(appliedByAnother)
                }
            })
            f = GodwitFixture(recorder = recorder)
            f.use {
                val raced = migration("001-raced").inTransaction { probe() }

                val report = f.godwit.migrate(raced)

                report.ran.shouldBeEmpty()
                report.upToDate shouldBe listOf("001-raced")
                f.stored("001-raced") shouldBe appliedByAnother
                f.collection("probes").countDocuments() shouldBe 0L
                f.recorder.commands("findAndModify").filter {
                    it.command.getString("findAndModify").value ==
                        "godwit-history"
                }
                    .map { it.errorCode } shouldBe listOf(11000, 11000)
            }
        }

        "a conflict that appears under the lock stops the call before anything runs, and the lock is released" {
            lateinit var f: GodwitFixture
            val recorder = CommandRecorder(onSucceeded = { command ->
                if (command.name == "findAndModify" &&
                    command.command.getString("findAndModify").value == "godwit-lock"
                ) {
                    f.history.insertOne(planted("099-from-a-newer-release", HistoryState.APPLIED))
                }
            })
            f = GodwitFixture(config = GodwitConfig(unknownApplied = UnknownApplied.FAIL), recorder = recorder)
            f.use {
                val due = migration("001-due").inTransaction { probe() }

                val conflict = shouldThrow<PlanConflictException> { f.godwit.migrate(due) }

                conflict.problems shouldBe listOf(
                    "099-from-a-newer-release is applied, but the list does not declare it (UnknownApplied.FAIL)"
                )
                f.stored("001-due").shouldBeNull()
                f.collection("godwit-lock").find().first().containsKey("releasedAt") shouldBe true
            }
        }

        "a conflict found before the lock stops a call that has nothing to do, without touching the lock" {
            GodwitFixture(config = GodwitConfig(unknownApplied = UnknownApplied.FAIL)).use { f ->
                f.history.insertOne(planted("001-applied", HistoryState.APPLIED))
                f.history.insertOne(planted("099-from-a-newer-release", HistoryState.APPLIED))
                f.recorder.clear()

                shouldThrow<PlanConflictException> {
                    f.godwit.migrate(migration("001-applied").outsideTransaction { })
                }

                f.recorder.commands.map { it.name } shouldBe listOf("find")
            }
        }

        "OutOfOrder.RUN runs a migration listed before an applied one, warns, and records it out of order" {
            GodwitFixture(config = GodwitConfig(outOfOrder = OutOfOrder.RUN)).use { f ->
                f.history.insertOne(planted("008-cart-currency", HistoryState.APPLIED))
                val slugs = migration("007-product-slugs").inTransaction { probe() }
                val currency = migration("008-cart-currency").outsideTransaction { }

                LogCapture().use { logs ->
                    val report = f.godwit.migrate(slugs, currency)

                    report["007-product-slugs"].outOfOrder shouldBe true
                    logs.events("Running out-of-order migration").single().line shouldBe
                        "Running out-of-order migration id=007-product-slugs appliedAfter=[008-cart-currency]"
                }
                f.stored("007-product-slugs")!!.getBoolean("outOfOrder") shouldBe true
            }
        }

        "the untracked-database guard refuses a database with collections and no history, under the lock" {
            GodwitFixture().use { f ->
                f.collection("customers").insertOne(Document("email", "a@example.com"))
                f.collection("orders").insertOne(Document("total", 1))
                f.collection("system.js").insertOne(Document("_id", "f").append("value", 1))
                val carts = migration("002-carts").outsideTransaction { ensureCollection("carts") }

                val refused = shouldThrow<UntrackedDatabaseException> { f.godwit.migrate(carts) }

                refused.collections shouldBe listOf("customers", "orders")
                f.history.countDocuments() shouldBe 0L
                f.database.listCollectionNames().toList().contains("carts") shouldBe false
                f.collection("godwit-lock").find().first().containsKey("releasedAt") shouldBe true
                f.godwit.status(listOf(carts)).problems shouldBe listOf(refused.message)
            }
        }

        "under UntrackedDatabase.RUN_ALL, and on a database without collections, every migration runs" {
            GodwitFixture(config = GodwitConfig(untrackedDatabase = UntrackedDatabase.RUN_ALL)).use { f ->
                f.collection("customers").insertOne(Document("email", "a@example.com"))
                val carts = migration("002-carts").outsideTransaction { ensureCollection("carts") }

                f.godwit.status(listOf(carts)).problems.shouldBeEmpty()
                f.godwit.migrate(carts).ran.map { it.id } shouldBe listOf("002-carts")
            }
            GodwitFixture().use { f ->
                val carts = migration("002-carts").outsideTransaction { ensureCollection("carts") }

                f.godwit.status(listOf(carts)).problems.shouldBeEmpty()
                f.godwit.migrate(carts).ran.map { it.id } shouldBe listOf("002-carts")
            }
        }

        "status plans without the lock or any write: pending in run order, problems and unknown applied ids" {
            GodwitFixture().use { f ->
                f.history.insertOne(planted("002-applied", HistoryState.APPLIED))
                f.history.insertOne(planted("099-from-a-newer-release", HistoryState.APPLIED))
                val pendingFirst = migration("001-pending").outsideTransaction { }
                val applied = migration("002-applied").outsideTransaction { }
                val countries = repeatable("reference-countries", "2026-10-01").inTransaction { }
                val bootstrap = everyStart("bootstrap-customers").outsideTransaction { }
                f.recorder.clear()

                val status = f.godwit.status(listOf(pendingFirst, applied, countries, bootstrap))

                status.pending shouldBe listOf("001-pending", "reference-countries")
                status.problems shouldBe listOf(
                    "001-pending is pending, but 002-applied, listed after it, is applied (out of order; " +
                        "OutOfOrder.RUN runs it)"
                )
                status.unknownApplied shouldBe listOf("099-from-a-newer-release")
                status.isUpToDate shouldBe false
                f.recorder.commands.map { it.name } shouldBe listOf("find")
                f.database.listCollectionNames().toList().contains("godwit-lock") shouldBe false
            }
        }

        "requireUpToDate throws PendingMigrationsException with the pending ids and problems, and passes once applied" {
            GodwitFixture().use { f ->
                val carts = migration("002-carts").outsideTransaction { ensureCollection("carts") }
                val bootstrap = everyStart("bootstrap-customers").outsideTransaction { }

                val pending = shouldThrow<PendingMigrationsException> { f.godwit.requireUpToDate(listOf(carts)) }
                pending.pending shouldBe listOf("002-carts")
                pending.problems.shouldBeEmpty()
                pending.message shouldBe "The database is not up to date. Pending: [002-carts]. Problems: []"

                f.godwit.migrate(carts)
                f.godwit.requireUpToDate(listOf(carts, bootstrap))
                shouldThrow<InvalidMigrationsException> { f.godwit.requireUpToDate(listOf(carts, carts)) }
                shouldThrow<InvalidMigrationsException> { f.godwit.status(listOf(carts, carts)) }
            }
        }

        "history returns every document as a HistoryEntry, sorted by id" {
            GodwitFixture().use { f ->
                val carts = migration("002-carts", description = "Carts").outsideTransaction { count("made", 1) }
                val failing = migration("003-failing").inTransaction { error("boom") }
                f.godwit.migrate(carts)
                shouldThrow<MigrationFailedException> { f.godwit.migrate(carts, failing) }

                val history = f.godwit.history()

                history.map { it.id } shouldBe listOf("002-carts", "003-failing")
                val (applied, failed) = history
                failed.state shouldBe HistoryState.FAILED
                failed.lastError!!.type shouldBe "java.lang.IllegalStateException"
                failed.lastError.step shouldBe StepKind.IN_TRANSACTION
                failed.counts shouldBe emptyMap<String, Long>()
                applied.state shouldBe HistoryState.APPLIED
                applied.origin shouldBe Origin.RAN
                applied.kind shouldBe MigrationKind.Once
                applied.description shouldBe "Carts"
                applied.steps shouldBe listOf(StepKind.OUTSIDE_TRANSACTION)
                applied.counts shouldBe mapOf("made" to 1L)
                applied.duration.shouldNotBeNull()
                applied.holder shouldBe "godwit-runner/1"
                applied.godwitVersion shouldBe version
                f.godwit.history().map { it.id } shouldContainExactly listOf("002-carts", "003-failing")
            }
        }

        "migrate validates the list and the target before any I/O" {
            val recorder = CommandRecorder()
            TestMongo.client("runner-validation", recorder).use { client ->
                val godwit = Godwit(client, "never-touched")
                val carts = migration("002-carts").outsideTransaction { }

                shouldThrow<InvalidMigrationsException> { godwit.migrate(carts, carts) }
                shouldThrow<InvalidMigrationsException> { godwit.migrate(listOf(carts), Target.Before("001-missing")) }
                recorder.commands.shouldBeEmpty()
            }
        }
    }
}

/** A `TransientTransactionError` (a write conflict) on the first `insert` the client named [appName] sends. */
private fun transientInsert(appName: String): AutoCloseable = TestMongo.failCommand(
    appName,
    listOf("insert"),
    Document("times", 1),
    Document("errorCode", 112).append("errorLabels", listOf("TransientTransactionError"))
)
