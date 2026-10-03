package godwit.test

import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates.inc
import com.mongodb.client.model.Updates.set
import godwit.core.Godwit
import godwit.core.GodwitConfig
import godwit.core.InvalidMigrationsException
import godwit.core.MigrationFailedException
import godwit.core.MigrationKind
import godwit.core.Origin
import godwit.core.PlanConflictException
import godwit.core.StepScope
import godwit.core.TransactionScope
import godwit.core.UnknownApplied
import godwit.core.UntrackedDatabaseException
import godwit.core.everyStart
import godwit.core.fixtures.LogCapture
import godwit.core.fixtures.TestMongo
import godwit.core.migration
import godwit.core.repeatable
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.bson.Document
import java.util.Date
import java.util.UUID

/** How often [StepScope.id] has run: `{_id: id, n}` in `runs`, incremented outside any transaction. */
private fun StepScope.recordRun() {
    collection("runs").updateOne(eq("_id", id), inc("n", 1), UpdateOptions().upsert(true))
}

private fun TestGodwit.runs(id: String): Int =
    database.getCollection("runs", Document::class.java).find(eq("_id", id)).firstOrNull()?.getInteger("n") ?: 0

/** Marks every order without a status PENDING: safe to run twice, and the second run counts 0. */
private val orderStatus = migration("004-order-status").inTransaction {
    count(
        "ordersPending",
        collection("orders").updateMany(session, exists("status", false), set("status", "PENDING")).modifiedCount
    )
}

private val carts = migration("002-carts").outsideTransaction { ensureCollection("carts") }

private val fileStore = migration("003-file-store").outsideTransaction { ensureCollection("files") }

private val orderTotals = migration("006-order-totals").inTransaction {
    collection("orders").updateMany(session, exists("totalMinor", false), set("totalMinor", 0L))
}

private fun TransactionScope.countRun() {
    collection("runs").updateOne(session, eq("_id", id), inc("n", 1), UpdateOptions().upsert(true))
    count("runs", 1)
}

private val referenceCountries = repeatable("reference-countries", "2026-10-01").inTransaction { countRun() }

private val bootstrapCustomers = everyStart("bootstrap-customers").inTransaction { countRun() }

private fun TestGodwit.orders() = database.getCollection("orders", Document::class.java)

private fun TestGodwit.historyIds() = godwit.history().map { it.id }

class RunnerPathTest : StringSpec() {
    init {
        "forget deletes the history document, so the next migrate runs the migration again" {
            val db = testGodwit()
            val counted = migration("003-file-store").outsideTransaction { recordRun() }
            db.godwit.migrate(carts, counted)

            db.godwit.forget("003-file-store")

            db.historyIds() shouldBe listOf("002-carts")
            db.godwit.migrate(carts, counted).ran.map { it.id } shouldBe listOf("003-file-store")
            db.runs("003-file-store") shouldBe 2
        }

        "forget deletes from the configured history collection of any Godwit, and a missing id stays missing" {
            TestMongo.client().use { client ->
                val databaseName = UUID.randomUUID().toString()
                val config = GodwitConfig(historyCollection = "schema-history", lockCollection = "schema-lock")
                val godwit = Godwit(client, databaseName, config)
                godwit.migrate(carts, fileStore)

                godwit.forget("010-never-applied")
                godwit.forget("003-file-store")

                godwit.history().map { it.id } shouldBe listOf("002-carts")
                client.getDatabase(databaseName).getCollection("schema-history", Document::class.java)
                    .countDocuments() shouldBe 1L
            }
        }

        "rerun runs a once-only migration again after the ones applied after it, and the second run counts 0" {
            val db = testGodwit()
            val migrations = listOf(carts, orderStatus, orderTotals)
            db.godwit.migrate(migrations)
            db.orders().insertOne(Document("_id", "open"))

            val first = db.godwit.rerun(migrations, "004-order-status")
            val second = db.godwit.rerun(migrations, "004-order-status")

            first.count("ordersPending") shouldBe 1L
            second.count("ordersPending") shouldBe 0L
            second.outOfOrder shouldBe true
            db.godwit.history().single { it.id == "004-order-status" }.outOfOrder shouldBe true
            db.godwit shouldHaveApplied "004-order-status"
            db.godwit.status(migrations).isUpToDate shouldBe true
        }

        "rerun runs the migrations listed before the id that are not applied, and none after it" {
            val db = testGodwit()
            val migrations = listOf(carts, fileStore, orderStatus, orderTotals)
            db.godwit.runIsolated(orderStatus)

            db.godwit.rerun(migrations, "004-order-status")

            db.historyIds() shouldContainExactly listOf("002-carts", "003-file-store", "004-order-status")
        }

        "rerun after runIsolated on a database with other collections runs the migration again with counts 0" {
            val db = testGodwit()
            db.database.getCollection("audit-log", Document::class.java).insertOne(Document("at", Date()))
            db.orders().insertMany(listOf(Document("_id", "open"), Document("_id", "paid").append("status", "PAID")))
            shouldThrow<UntrackedDatabaseException> { db.godwit.migrate(orderStatus) }

            db.godwit.runIsolated(orderStatus).count("ordersPending") shouldBe 1L

            db.godwit.rerun(listOf(orderStatus), "004-order-status").count("ordersPending") shouldBe 0L
            db.historyIds() shouldBe listOf("004-order-status")
        }

        "rerun runs a repeatable or every-start migration again with Target.Latest" {
            val db = testGodwit()
            val migrations = listOf(carts, referenceCountries, bootstrapCustomers)
            db.godwit.migrate(migrations)

            val repeatable = db.godwit.rerun(migrations, "reference-countries")
            val everyStart = db.godwit.rerun(migrations, "bootstrap-customers")

            repeatable.kind shouldBe MigrationKind.Repeatable("2026-10-01")
            everyStart.kind shouldBe MigrationKind.EveryStart
            db.runs("reference-countries") shouldBe 2
            db.runs("bootstrap-customers") shouldBe 3
            db.godwit.history().single { it.id == "reference-countries" }.runCount shouldBe 1L
        }

        "rerun keeps the configured out-of-order policy for a repeatable" {
            val db = testGodwit()
            db.godwit.migrate(carts, orderTotals, referenceCountries)
            db.godwit.forget("002-carts")

            shouldThrow<PlanConflictException> {
                db.godwit.rerun(listOf(carts, orderTotals, referenceCountries), "reference-countries")
            }
        }

        "runIsolated and rerun never call the adoption hook, and leave the untracked-database guard off" {
            var hookCalls = 0
            val db = testGodwit(
                GodwitConfig(adoptApplied = {
                    hookCalls++
                    setOf("004-order-status")
                })
            )
            db.database.getCollection("schema-log", Document::class.java).insertOne(Document("version", "004"))
            db.orders().insertOne(Document("_id", "open"))

            db.godwit.runIsolated(orderStatus).count("ordersPending") shouldBe 1L
            db.godwit.rerun(listOf(orderStatus), "004-order-status").origin shouldBe Origin.RAN

            hookCalls shouldBe 0
        }

        "rerun refuses an id the list does not hold, and an invalid list, before forgetting anything" {
            val db = testGodwit()
            db.godwit.migrate(carts, orderStatus)

            shouldThrow<IllegalArgumentException> { db.godwit.rerun(listOf(carts, orderStatus), "010-unknown") }
                .message shouldBe "rerun: the list holds no migration 010-unknown"
            shouldThrow<InvalidMigrationsException> { db.godwit.rerun(listOf(orderStatus, carts), "004-order-status") }

            db.historyIds() shouldBe listOf("002-carts", "004-order-status")
        }

        "runIsolated runs one migration on a database with unrelated data, without its predecessors" {
            val db = testGodwit()
            db.database.getCollection("audit-log", Document::class.java).insertOne(Document("at", Date()))
            db.orders().insertMany(listOf(Document("_id", 1), Document("_id", 2)))

            val outcome = db.godwit.runIsolated(orderStatus)

            outcome.count("ordersPending") shouldBe 2L
            db.historyIds() shouldBe listOf("004-order-status")
            db.database.getCollection("audit-log", Document::class.java).countDocuments() shouldBe 1L
        }

        "runIsolated ignores the other applied ids, under UnknownApplied.FAIL too" {
            val db = testGodwit(GodwitConfig(unknownApplied = UnknownApplied.FAIL))
            db.godwit.migrate(carts, fileStore)

            LogCapture().use { logs ->
                db.godwit.runIsolated(orderStatus).id shouldBe "004-order-status"

                logs.events("Unknown applied migrations").single().keyValuePairs.single().value shouldBe
                    listOf("002-carts", "003-file-store")
            }
            db.historyIds() shouldBe listOf("002-carts", "003-file-store", "004-order-status")
        }

        "runIsolated of a migration that history records applied throws AssertionError, and runs nothing" {
            val db = testGodwit()
            val counted = migration("002-carts").outsideTransaction { recordRun() }
            db.godwit.runIsolated(counted)

            shouldThrow<AssertionError> { db.godwit.runIsolated(counted) }.message shouldBe
                "002-carts did not run: history records it applied. forget(\"002-carts\") first to run it again, " +
                "or use rerun"

            db.runs("002-carts") shouldBe 1
        }

        "shouldHaveApplied passes for an APPLIED id" {
            val db = testGodwit()
            db.godwit.migrate(carts)

            db.godwit shouldHaveApplied "002-carts"
        }

        "shouldHaveApplied fails for an id history does not hold" {
            val db = testGodwit()

            shouldThrow<AssertionError> { db.godwit shouldHaveApplied "002-carts" }.message shouldBe
                "expected 002-carts to be APPLIED, but history has no document for it"
        }

        "shouldHaveApplied fails for a FAILED id with its last error, and for a RUNNING one" {
            val db = testGodwit()
            shouldThrow<MigrationFailedException> {
                db.godwit.migrate(migration("002-carts").outsideTransaction { error("disk full") })
            }
            db.database.getCollection("godwit-history", Document::class.java).insertOne(
                Document(
                    "_id",
                    "003-file-store"
                ).append("kind", "ONCE").append("state", "RUNNING").append("origin", "RAN")
            )

            shouldThrow<AssertionError> { db.godwit shouldHaveApplied "002-carts" }.message shouldBe
                "expected 002-carts to be APPLIED, but history records it FAILED " +
                "(lastError: java.lang.IllegalStateException: disk full)"
            shouldThrow<AssertionError> { db.godwit shouldHaveApplied "003-file-store" }.message shouldBe
                "expected 003-file-store to be APPLIED, but history records it RUNNING"
        }
    }
}
