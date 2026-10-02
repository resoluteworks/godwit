package godwit.core.internal

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.MongoCommandException
import com.mongodb.ReadConcern
import com.mongodb.ReadPreference
import com.mongodb.WriteConcern
import com.mongodb.client.model.Filters.eq
import com.mongodb.kotlin.client.MongoClient
import com.mongodb.kotlin.client.MongoCollection
import godwit.core.GodwitConfig
import godwit.core.HistoryState
import godwit.core.LockLostException
import godwit.core.MigrationKind
import godwit.core.Origin
import godwit.core.StepKind
import godwit.core.everyStart
import godwit.core.fixtures.CommandRecorder
import godwit.core.fixtures.TestDatabase
import godwit.core.fixtures.TestMongo
import godwit.core.fixtures.race
import godwit.core.migration
import godwit.core.repeatable
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.bson.BsonBoolean
import org.bson.BsonDocument
import org.bson.Document
import org.bson.codecs.StringCodec
import org.bson.codecs.configuration.CodecConfigurationException
import org.bson.codecs.configuration.CodecRegistries
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

private val log = LoggerFactory.getLogger(HistoryStoreTest::class.java)

/** How many fresh ids a race test runs its two writes on, each pair released together by a barrier. */
private const val RACES = 50

/**
 * A duplicate key a fail point can answer with: the server refuses to fake 11000 without the key it collided on, and
 * the driver counts 11001 as a duplicate key too.
 */
private const val FAKE_DUPLICATE_KEY = 11001

private const val HOLDER = "shop-7f9c4/1"
private const val OWNER = "5b0f3c6e-2a41-4f7e-9d1c-0e8a7b6c5d4f"
private const val RUN_ID = "0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f"

private val writer = Writer(OWNER, HOLDER, RUN_ID)
private val takeover =
    Writer("9a7c1e20-64b3-4c8f-a5d2-7e3f10b2c946", "shop-2b8e1/1", "0199a4c2-8a40-7d12-b3c4-1e2f3a4b5c6d")
private val startedAt: Instant = Instant.parse("2026-10-02T10:14:05.120Z")
private val finishedAt: Instant = Instant.parse("2026-10-02T10:14:05.204Z")
private val version: String = System.getProperty("godwit.version")

private val orderStatus = migration("004-order-status").inTransaction { }
private val carts = migration("002-carts", description = "Carts and their indexes").outsideTransaction { }
private val countries = repeatable("reference-countries", "2026-10-01", description = "\$countries").inTransaction { }
private val bootstrap = everyStart("bootstrap-customers").outsideTransaction { }.inTransaction { }
private val baseline =
    migration("100-baseline", supersedes = listOf("001-initial-setup", "002-carts")).outsideTransaction { }

private val applied = AppliedRun(mapOf("ordersPaid" to 1200L, "ordersPending" to 37L), 0, 84, finishedAt)

/** A history store on a database of its own, through a client whose commands [recorder] sees. */
private class Fixture(val recorder: CommandRecorder = CommandRecorder(), appName: String = "history-store") :
    AutoCloseable {
    val db: TestDatabase = TestMongo.database()
    val client: MongoClient = TestMongo.client(appName, recorder)
    val store = HistoryStore(Bookkeeping(client, db.name, GodwitConfig(holder = HOLDER)))
    val history: MongoCollection<Document> = db.database.getCollection("godwit-history", Document::class.java)
    val orders: MongoCollection<Document> = client.getDatabase(db.name).getCollection("orders", Document::class.java)

    fun stored(id: String): Document? = history.find(eq("_id", id)).firstOrNull()

    /** The last command named [name] that this fixture's client sent to the history collection. */
    fun sent(name: String): BsonDocument = recorder.commands(name).last().command

    fun update(command: BsonDocument): BsonDocument = command.getArray("updates")[0].asDocument()

    override fun close() {
        client.close()
        db.close()
    }
}

private fun json(text: String): BsonDocument = BsonDocument.parse(text)

private fun date(instant: Instant) = $$"""{"$date": "$$instant"}"""

class HistoryStoreTest : StringSpec() {
    init {
        "readAll reads every document in id order with one find, majority read concern, on the primary" {
            Fixture().use { f ->
                f.history.insertMany(
                    listOf(
                        Document("_id", "b").append("kind", "ONCE").append("state", "APPLIED").append("origin", "RAN"),
                        Document("_id", "a").append("kind", "REPEATABLE").append("state", "FAILED")
                            .append("origin", "RAN").append("revision", "r1"),
                        Document("_id", "c").append("kind", "ONCE").append("state", "APPLIED")
                            .append("origin", "SUPERSEDED").append("supersedes", listOf("a", "b"))
                    )
                )
                f.recorder.clear()

                val all = f.store.readAll()

                all.map { it.getString("_id") } shouldBe listOf("a", "b", "c")
                val find = f.recorder.commands.single()
                find.name shouldBe "find"
                find.command.getString("find").value shouldBe "godwit-history"
                find.command.getDocument("filter", BsonDocument()) shouldBe BsonDocument()
                find.command.getDocument("sort") shouldBe json("""{"_id": 1}""")
                find.command.getDocument("readConcern") shouldBe json("""{"level": "majority"}""")

                all.map { it.toHistoryRecord() } shouldBe listOf(
                    HistoryRecord("a", StoredKind.REPEATABLE, HistoryState.FAILED, Origin.RAN, revision = "r1"),
                    HistoryRecord("b", StoredKind.ONCE, HistoryState.APPLIED, Origin.RAN),
                    HistoryRecord(
                        "c",
                        StoredKind.ONCE,
                        HistoryState.APPLIED,
                        Origin.SUPERSEDED,
                        supersedes = listOf("a", "b")
                    )
                )
                f.store.read("b")!!.getString("state") shouldBe "APPLIED"
                f.store.read("missing").shouldBeNull()
            }
        }

        "the once-only marker is an upsert conditional on state != APPLIED that increments attempts" {
            Fixture().use { f ->
                val marked = f.store.markRunning(orderStatus, writer, startedAt).shouldNotBeNull()

                val command = f.sent("findAndModify")
                command.getString("findAndModify").value shouldBe "godwit-history"
                command.getDocument("query") shouldBe
                    json($$"""{"_id": "004-order-status", "state": {"$ne": "APPLIED"}}""")
                command.getDocument("update") shouldBe json(
                    $$"""
                    {
                      "$set": {
                        "kind": "ONCE", "steps": ["IN_TRANSACTION"], "state": "RUNNING", "origin": "RAN",
                        "owner": "$$OWNER", "holder": "$$HOLDER", "runId": "$$RUN_ID",
                        "startedAt": $${date(startedAt)}, "godwitVersion": "$$version", "v": 1
                      },
                      "$inc": {"attempts": 1},
                      "$unset": {"description": ""}
                    }
                    """
                )
                command.getBoolean("upsert").value shouldBe true
                command.getBoolean("new").value shouldBe true
                command.getDocument("writeConcern") shouldBe json("""{"w": "majority"}""")

                marked shouldBe f.stored("004-order-status")
                marked.getInteger("attempts") shouldBe 1
                marked.getString("godwitVersion") shouldBe version
            }
        }

        "the once-only marker on a FAILED document counts the attempt and keeps lastError and checkpoint" {
            Fixture().use { f ->
                val lastError = Document("type", "java.lang.IllegalStateException").append("message", "boom")
                val checkpoint = Document("lastId", 40).append("batches", 4).append("counts", Document("n", 40L))
                f.history.insertOne(
                    Document(
                        "_id",
                        "002-carts"
                    ).append("kind", "ONCE").append("state", "FAILED").append("origin", "RAN")
                        .append("attempts", 1).append("owner", "old-owner").append("lastError", lastError)
                        .append("checkpoint", checkpoint).append("durationMs", 30412L)
                )

                val marked = f.store.markRunning(carts, writer, startedAt).shouldNotBeNull()

                marked.getString("state") shouldBe "RUNNING"
                marked.getInteger("attempts") shouldBe 2
                marked.getString("owner") shouldBe OWNER
                marked.getString("description") shouldBe "Carts and their indexes"
                marked["steps"] shouldBe listOf("OUTSIDE_TRANSACTION")
                marked["lastError"] shouldBe lastError
                marked["checkpoint"] shouldBe checkpoint
                marked.getLong("durationMs") shouldBe 30412L
                f.sent("findAndModify").getDocument("update").getDocument("\$set")
                    .getString("description").value shouldBe "Carts and their indexes"

                f.store.markRunning(carts, writer, startedAt)!!.getInteger("attempts") shouldBe 3
            }
        }

        "the once-only marker on an APPLIED document hits a duplicate key: another run applied it, nothing changes" {
            Fixture().use { f ->
                f.store.markRunning(orderStatus, writer, startedAt)
                f.store.recordApplied(orderStatus, OWNER, applied)
                val before = f.stored("004-order-status")

                f.store.markRunning(orderStatus, takeover, startedAt).shouldBeNull()

                f.stored("004-order-status") shouldBe before
                // The first duplicate key could be a concurrent first marker; the second one, sent once the document
                // exists, can only be the APPLIED document.
                f.recorder.commands("findAndModify").map { it.errorCode } shouldBe listOf(null, 11000, 11000)
            }
        }

        "a once-only marker whose upsert hits a duplicate key on a document that is not APPLIED is sent once more" {
            Fixture(appName = "history-duplicate-marker").use { f ->
                f.store.markRunning(orderStatus, writer, startedAt)
                TestMongo.failCommand(
                    "history-duplicate-marker",
                    listOf("findAndModify"),
                    Document("times", 1),
                    Document("errorCode", FAKE_DUPLICATE_KEY)
                ).use {
                    val marked = f.store.markRunning(orderStatus, takeover, startedAt).shouldNotBeNull()

                    marked.getString("state") shouldBe "RUNNING"
                    marked.getString("owner") shouldBe takeover.owner
                    marked.getInteger("attempts") shouldBe 2
                }
                f.recorder.commands("findAndModify").map { it.errorCode } shouldBe
                    listOf(null, FAKE_DUPLICATE_KEY, null)
                f.stored("004-order-status")!!.getString("owner") shouldBe takeover.owner
            }
        }

        "two first once-only markers racing on a missing document both start it: the losing insert is sent once more" {
            Fixture(appName = "history-race-markers-a").use { f ->
                TestMongo.client("history-race-markers-b", f.recorder).use { client ->
                    val other = HistoryStore(Bookkeeping(client, f.db.name, GodwitConfig(holder = takeover.holder)))
                    val migrations = (0 until RACES).map { migration("race-%02d".format(it)).inTransaction { } }

                    val markers = race(
                        RACES,
                        { f.store.markRunning(migrations[it], writer, startedAt) },
                        { other.markRunning(migrations[it], takeover, startedAt) }
                    )

                    markers.forEachIndexed { round, (first, second) ->
                        val id = migrations[round].id
                        withClue(id) {
                            listOf(first, second).map { it.shouldNotBeNull().getInteger("attempts") }.sorted() shouldBe
                                listOf(1, 2)
                            f.stored(id)!!.getInteger("attempts") shouldBe 2
                        }
                    }
                    val duplicateKeys = f.recorder.commands("findAndModify").count { it.errorCode == 11000 }
                    log.info("first markers raced races={} duplicateKeys={}", RACES, duplicateKeys)
                    duplicateKeys shouldBeGreaterThan 0
                }
            }
        }

        "a SUPERSEDED or MARKED record racing a first marker on a missing document records it APPLIED" {
            Fixture(appName = "history-race-records-a").use { f ->
                TestMongo.client("history-race-records-b", f.recorder).use { client ->
                    val other = HistoryStore(Bookkeeping(client, f.db.name, GodwitConfig(holder = takeover.holder)))
                    val migrations = (0 until RACES).map {
                        migration("squash-%02d".format(it), supersedes = listOf("001-initial-setup")).inTransaction { }
                    }
                    fun origin(round: Int) = if (round % 2 == 0) "SUPERSEDED" else "MARKED"

                    val records = race(
                        RACES,
                        { f.store.markRunning(migrations[it], writer, startedAt) },
                        { round ->
                            if (origin(round) == "SUPERSEDED") {
                                other.recordSuperseded(migrations[round], takeover, finishedAt)
                            } else {
                                other.recordMarked(migrations[round].id, "built by hand", takeover, finishedAt)
                            }
                        }
                    )

                    records.forEachIndexed { round, (_, recorded) ->
                        val id = migrations[round].id
                        withClue(id) {
                            recorded shouldBe true
                            val stored = f.stored(id).shouldNotBeNull()
                            stored.getString("state") shouldBe "APPLIED"
                            stored.getString("origin") shouldBe origin(round)
                            stored.getString("owner") shouldBe takeover.owner
                        }
                    }
                    val skipped = records.count { (marker, _) -> marker == null }
                    val duplicateKeys = f.recorder.commands.count { it.errorCode == 11000 }
                    log.info(
                        "records raced markers races={} markersSkipped={} duplicateKeys={}",
                        RACES,
                        skipped,
                        duplicateKeys
                    )
                    duplicateKeys shouldBeGreaterThan 0
                }
            }
        }

        "the repeatable marker is an unconditional pipeline upsert that restarts attempts at 1 after APPLIED" {
            Fixture().use { f ->
                f.history.insertOne(
                    Document("_id", "reference-countries").append("kind", "REPEATABLE").append("state", "APPLIED")
                        .append("origin", "RAN").append("revision", "2026-09-01").append("attempts", 3)
                        .append("runCount", 1L)
                )
                val dollarWriter = writer.copy(holder = "\$shop-7f9c4/1")

                val marked = f.store.markRunning(countries, dollarWriter, startedAt).shouldNotBeNull()

                marked.getString("state") shouldBe "RUNNING"
                marked.getInteger("attempts") shouldBe 1
                marked.getString("revision") shouldBe "2026-09-01"
                marked.getLong("runCount") shouldBe 1L
                marked.getString("holder") shouldBe "\$shop-7f9c4/1"
                marked.getString("description") shouldBe "\$countries"
                val command = f.sent("findAndModify")
                command.getDocument("query") shouldBe json("""{"_id": "reference-countries"}""")
                command.getArray("update").single().asDocument() shouldBe json(
                    $$"""
                    {
                      "$set": {
                        "kind": "REPEATABLE", "steps": ["IN_TRANSACTION"], "state": "RUNNING", "origin": "RAN",
                        "attempts": {"$cond": [{"$eq": ["$state", "APPLIED"]}, 1, {"$add": [{"$ifNull": ["$attempts", 0]}, 1]}]},
                        "owner": {"$literal": "$$OWNER"}, "holder": {"$literal": "$shop-7f9c4/1"},
                        "runId": {"$literal": "$$RUN_ID"}, "startedAt": $${date(startedAt)},
                        "godwitVersion": "$$version", "v": 1, "description": {"$literal": "$countries"}
                      }
                    }
                    """
                )
                command.getBoolean("upsert").value shouldBe true
                command.getBoolean("new").value shouldBe true
                command.getDocument("writeConcern") shouldBe json("""{"w": "majority"}""")

                f.store.markRunning(countries, writer, startedAt)!!.getInteger("attempts") shouldBe 2
            }
        }

        "the every-start marker inserts a document with attempts 1 and removes a description it no longer declares" {
            Fixture().use { f ->
                f.store.markRunning(bootstrap, writer, startedAt)!!.getInteger("attempts") shouldBe 1
                f.history.updateOne(eq("_id", "bootstrap-customers"), Document("\$set", Document("description", "old")))

                val marked = f.store.markRunning(bootstrap, writer, startedAt).shouldNotBeNull()

                marked.getString("kind") shouldBe "EVERY_START"
                marked["steps"] shouldBe listOf("OUTSIDE_TRANSACTION", "IN_TRANSACTION")
                marked.getInteger("attempts") shouldBe 2
                marked shouldNotContainKey "description"
                f.sent("findAndModify").getArray("update").single().asDocument().getDocument("\$set")
                    .getString("description").value shouldBe "\$\$REMOVE"
            }
        }

        "the markers go on the session that later carries the step's transaction, whose snapshot follows them" {
            Fixture().use { f ->
                f.client.startSession().use { session ->
                    f.store.markRunning(orderStatus, writer, startedAt, session).shouldNotBeNull()
                    f.store.markRunning(countries, writer, startedAt, session).shouldNotBeNull()
                    session.withTransaction(
                        {
                            f.store.recordApplied(orderStatus, OWNER, applied, session)
                            f.store.recordApplied(countries, OWNER, applied, session)
                        },
                        TRANSACTION_OPTIONS
                    )
                }

                f.stored("004-order-status")!!.getString("state") shouldBe "APPLIED"
                f.stored("reference-countries")!!.getString("state") shouldBe "APPLIED"
                val commands = f.recorder.commands.filter { it.name in setOf("findAndModify", "update") }
                commands.map { it.name } shouldBe listOf("findAndModify", "findAndModify", "update", "update")
                commands.map { it.command.getDocument("lsid") }.toSet().size shouldBe 1
                commands.take(2).forEach { it.command.containsKey("autocommit") shouldBe false }
                commands[2].command.getDocument("readConcern").containsKey("afterClusterTime") shouldBe true
            }
        }

        "the APPLIED record outside a transaction is fenced on owner and RUNNING and clears lastError" {
            Fixture().use { f ->
                f.store.markRunning(orderStatus, writer, startedAt)
                f.history.updateOne(
                    eq("_id", "004-order-status"),
                    Document("\$set", Document("lastError", Document("type", "x")).append("checkpoint", Document()))
                )

                f.store.recordApplied(orderStatus, OWNER, applied)

                val command = f.sent("update")
                val update = f.update(command)
                update.getDocument("q") shouldBe json(
                    """{"_id": "004-order-status", "owner": "$OWNER", "state": "RUNNING"}"""
                )
                update.getDocument("u") shouldBe json(
                    $$"""
                    {
                      "$set": {
                        "state": "APPLIED", "counts": {"ordersPaid": {"$numberLong": "1200"}, "ordersPending": {"$numberLong": "37"}},
                        "transactionRetries": 0, "durationMs": {"$numberLong": "84"}, "finishedAt": $${date(finishedAt)}
                      },
                      "$unset": {"lastError": "", "checkpoint": ""}
                    }
                    """
                )
                update.getBoolean("upsert", BsonBoolean.FALSE).value shouldBe false
                command.getDocument("writeConcern") shouldBe json("""{"w": "majority"}""")

                val stored = f.stored("004-order-status").shouldNotBeNull()
                stored.getString("state") shouldBe "APPLIED"
                stored["counts"] shouldBe Document("ordersPaid", 1200L).append("ordersPending", 37L)
                stored.getDate("finishedAt") shouldBe Date.from(finishedAt)
                listOf("lastError", "checkpoint", "revision", "runCount", "lastRunAt", "outOfOrder", "supersedes")
                    .forEach { stored shouldNotContainKey it }
            }
        }

        "a repeatable records its revision, lastRunAt and runCount; an every-start migration lastRunAt and runCount" {
            Fixture().use { f ->
                repeat(2) { run ->
                    f.store.markRunning(countries, writer, startedAt)
                    f.store.recordApplied(countries, OWNER, applied.copy(counts = emptyMap()))
                    val stored = f.stored("reference-countries").shouldNotBeNull()
                    stored.getString("revision") shouldBe "2026-10-01"
                    stored.getDate("lastRunAt") shouldBe Date.from(finishedAt)
                    stored.getLong("runCount") shouldBe run + 1L
                    stored["counts"] shouldBe Document()
                }
                f.update(f.sent("update")).getDocument("u").getDocument("\$inc") shouldBe
                    json($$"""{"runCount": {"$numberLong": "1"}}""")

                f.store.markRunning(bootstrap, writer, startedAt)
                f.store.recordApplied(bootstrap, OWNER, applied)
                val everyStart = f.stored("bootstrap-customers").shouldNotBeNull()
                everyStart.getLong("runCount") shouldBe 1L
                everyStart.getDate("lastRunAt") shouldBe Date.from(finishedAt)
                everyStart shouldNotContainKey "revision"
            }
        }

        "an out-of-order run records outOfOrder and a superseding migration that runs stores its supersedes list" {
            Fixture().use { f ->
                f.store.markRunning(carts, writer, startedAt)
                f.store.recordApplied(carts, OWNER, applied.copy(outOfOrder = true))
                f.stored("002-carts")!!.getBoolean("outOfOrder") shouldBe true

                f.store.markRunning(baseline, writer, startedAt)
                f.store.recordApplied(baseline, OWNER, applied)
                val stored = f.stored("100-baseline").shouldNotBeNull()
                stored["supersedes"] shouldBe listOf("001-initial-setup", "002-carts")
                stored shouldNotContainKey "outOfOrder"
            }
        }

        "the APPLIED record on the step's session commits with the step's writes in its transaction" {
            Fixture().use { f ->
                f.store.markRunning(orderStatus, writer, startedAt)
                f.recorder.clear()

                f.client.startSession().use { session ->
                    session.withTransaction(
                        {
                            f.orders.insertOne(session, Document("_id", 1).append("status", "PAID"))
                            f.store.recordApplied(orderStatus, OWNER, applied, session)
                        },
                        TRANSACTION_OPTIONS
                    )
                }

                f.stored("004-order-status")!!.getString("state") shouldBe "APPLIED"
                f.orders.countDocuments() shouldBe 1L
                val record = f.recorder.commands("update").single().command
                record.getBoolean("autocommit").value shouldBe false
                record.containsKey("writeConcern") shouldBe false
                f.recorder.commands("commitTransaction").single().command.getDocument("writeConcern") shouldBe
                    json("""{"w": "majority"}""")
            }
        }

        "a stale owner's APPLIED record matches nothing: LockLostException, and its transaction rolls back" {
            Fixture().use { f ->
                f.store.markRunning(orderStatus, writer, startedAt)
                f.store.markRunning(orderStatus, takeover, startedAt)
                val before = f.stored("004-order-status")

                shouldThrow<LockLostException> { f.store.recordApplied(orderStatus, OWNER, applied) }
                    .message shouldBe "Lost the migration lock while running 004-order-status"

                val error = shouldThrow<LockLostException> {
                    f.client.startSession().use { session ->
                        session.withTransaction(
                            {
                                f.orders.insertOne(session, Document("_id", 1))
                                f.store.recordApplied(orderStatus, OWNER, applied, session)
                            },
                            TRANSACTION_OPTIONS
                        )
                    }
                }
                error.id shouldBe "004-order-status"
                error.cause.shouldBeNull()
                f.orders.countDocuments() shouldBe 0L
                f.stored("004-order-status") shouldBe before
            }
        }

        "the FAILED record is fenced on the owner and RUNNING and keeps the error as lastError" {
            Fixture().use { f ->
                f.store.markRunning(carts, writer, startedAt)
                val error = IllegalStateException("request timed out")

                f.store.markFailed(
                    "002-carts",
                    OWNER,
                    FailedRun(error, StepKind.OUTSIDE_TRANSACTION, 30412, finishedAt)
                ) shouldBe
                    true

                val command = f.sent("update")
                val update = f.update(command)
                update.getDocument("q") shouldBe json("""{"_id": "002-carts", "owner": "$OWNER", "state": "RUNNING"}""")
                val set = update.getDocument("u").getDocument("\$set")
                set.keys shouldBe setOf("state", "durationMs", "finishedAt", "lastError")
                update.getDocument("u").keys shouldBe setOf("\$set")
                command.getDocument("writeConcern") shouldBe json("""{"w": "majority"}""")

                val stored = f.stored("002-carts").shouldNotBeNull()
                stored.getString("state") shouldBe "FAILED"
                stored.getLong("durationMs") shouldBe 30412L
                stored.getDate("finishedAt") shouldBe Date.from(finishedAt)
                val lastError = stored.get("lastError", Document::class.java)
                lastError.keys.toList() shouldBe listOf("type", "message", "stack", "step", "at")
                lastError.getString("type") shouldBe "java.lang.IllegalStateException"
                lastError.getString("message") shouldBe "request timed out"
                lastError.getString("stack") shouldBe capUtf8(error.stackTraceToString(), STACK_CAP_BYTES)
                lastError.getString("step") shouldBe "OUTSIDE_TRANSACTION"
                lastError.getDate("at") shouldBe Date.from(finishedAt)
            }
        }

        "a stale owner's FAILED write and a FAILED write on an APPLIED document of the same owner match nothing" {
            Fixture().use { f ->
                val failure = FailedRun(IllegalStateException("x"), StepKind.IN_TRANSACTION, 1, finishedAt)
                f.store.markRunning(orderStatus, writer, startedAt)
                f.store.markRunning(orderStatus, takeover, startedAt)
                val taken = f.stored("004-order-status")

                f.store.markFailed("004-order-status", OWNER, failure) shouldBe false
                f.stored("004-order-status") shouldBe taken

                f.store.recordApplied(orderStatus, takeover.owner, applied)
                val appliedDocument = f.stored("004-order-status")
                f.store.markFailed("004-order-status", takeover.owner, failure) shouldBe false
                f.stored("004-order-status") shouldBe appliedDocument
            }
        }

        "lastError leaves out a missing message and a failure between steps" {
            Fixture().use { f ->
                f.store.markRunning(carts, writer, startedAt)

                f.store.markFailed("002-carts", OWNER, FailedRun(RuntimeException(), null, 5, finishedAt)) shouldBe true

                val lastError = f.stored("002-carts")!!.get("lastError", Document::class.java)
                lastError.keys.toList() shouldBe listOf("type", "stack", "at")
                lastError.getString("type") shouldBe "java.lang.RuntimeException"
            }
        }

        "lastError.stack keeps the first 8 KB of the stack trace" {
            Fixture().use { f ->
                f.store.markRunning(carts, writer, startedAt)
                val error = IllegalStateException("x".repeat(20_000))

                f.store.markFailed("002-carts", OWNER, FailedRun(error, StepKind.OUTSIDE_TRANSACTION, 5, finishedAt))

                val stack = f.stored("002-carts")!!.get("lastError", Document::class.java).getString("stack")
                stack.encodeToByteArray().size shouldBe STACK_CAP_BYTES
                stack shouldBe error.stackTraceToString().take(STACK_CAP_BYTES)
            }
        }

        "capUtf8 never splits a character at the cut" {
            capUtf8("short", 8) shouldBe "short"
            capUtf8("12345678", 8) shouldBe "12345678"
            capUtf8("123456789", 8) shouldBe "12345678"
            capUtf8("1234567\u00e9", 8) shouldBe "1234567"
            capUtf8("123456\uD83D\uDE00", 8) shouldBe "123456"
            capUtf8("12345\uD83D\uDE00", 8) shouldBe "12345"
            capUtf8("1234\uD83D\uDE00", 8) shouldBe "1234\uD83D\uDE00"
            capUtf8("1234\uD83D\uDE00x", 8) shouldBe "1234\uD83D\uDE00"
        }

        "adopted records are insert-only upserts in one transaction that checks the lock before its commit" {
            val events = CopyOnWriteArrayList<String>()
            val recorder = CommandRecorder(onSucceeded = { events += it.name })
            Fixture(recorder).use { f ->
                val inserted = f.store.recordAdopted(
                    listOf("001-initial-setup", "002-carts"),
                    writer,
                    finishedAt,
                    transactions = true
                ) { events += "checkLock" }

                inserted shouldBe listOf("001-initial-setup", "002-carts")
                events shouldBe listOf("update", "update", "checkLock", "commitTransaction")
                val updates = recorder.commands("update").map { it.command }
                updates.map { f.update(it).getDocument("q") } shouldBe listOf(
                    json("""{"_id": "001-initial-setup"}"""),
                    json("""{"_id": "002-carts"}""")
                )
                f.update(updates[0]).getDocument("u") shouldBe json(
                    $$"""
                    {
                      "$setOnInsert": {
                        "state": "APPLIED", "origin": "ADOPTED", "owner": "$$OWNER", "holder": "$$HOLDER",
                        "runId": "$$RUN_ID", "finishedAt": $${date(finishedAt)}, "godwitVersion": "$$version", "v": 1,
                        "kind": "ONCE", "steps": [], "attempts": 0
                      }
                    }
                    """
                )
                updates.forEach { f.update(it).getBoolean("upsert").value shouldBe true }
                updates.map { it.getDocument("lsid") }.toSet().size shouldBe 1
                updates.map { it.getInt64("txnNumber") }.toSet().size shouldBe 1
                updates[0].getBoolean("startTransaction").value shouldBe true
                updates[0].getDocument("readConcern").getString("level").value shouldBe "snapshot"
                updates.forEach { it.getBoolean("autocommit").value shouldBe false }
                recorder.commands("commitTransaction").single().command.getDocument("writeConcern") shouldBe
                    json("""{"w": "majority"}""")

                val stored = f.stored("002-carts").shouldNotBeNull()
                stored.getString("state") shouldBe "APPLIED"
                stored.getString("origin") shouldBe "ADOPTED"
                stored["steps"] shouldBe emptyList<String>()
                stored.getInteger("attempts") shouldBe 0
                stored shouldNotContainKey "counts"
            }
        }

        "recording adopted ids twice leaves the first records unchanged" {
            Fixture().use { f ->
                f.store.recordAdopted(listOf("001-initial-setup", "002-carts"), writer, finishedAt, true) {}
                val first = f.history.find().toList()

                f.store.recordAdopted(
                    listOf("001-initial-setup", "002-carts"),
                    takeover,
                    finishedAt.plusSeconds(60),
                    true
                ) {}.shouldBeEmpty()

                f.history.find().toList() shouldBe first
            }
        }

        "an adoption write over a RUNNING document leaves it RUNNING" {
            Fixture().use { f ->
                f.store.markRunning(carts, takeover, startedAt)
                val running = f.stored("002-carts")

                f.store.recordAdopted(listOf("001-initial-setup", "002-carts"), writer, finishedAt, true) {} shouldBe
                    listOf("001-initial-setup")

                f.stored("002-carts") shouldBe running
                f.stored("001-initial-setup")!!.getString("origin") shouldBe "ADOPTED"
            }
        }

        "the adoption records commit together or not at all: an error on the second write records nothing" {
            Fixture(appName = "history-adoption-error").use { f ->
                val error = TestMongo.failCommand(
                    "history-adoption-error",
                    listOf("update"),
                    Document("skip", 1),
                    Document("errorCode", 2)
                ).use {
                    shouldThrow<MongoCommandException> {
                        f.store.recordAdopted(listOf("001-initial-setup", "002-carts"), writer, finishedAt, true) {}
                    }
                }

                error.code shouldBe 2
                f.history.countDocuments() shouldBe 0L
            }
        }

        "a lost lock before the adoption commit records nothing and throws LockLostException" {
            Fixture().use { f ->
                shouldThrow<LockLostException> {
                    f.store.recordAdopted(listOf("001-initial-setup", "002-carts"), writer, finishedAt, true) {
                        throw LockLostException(null)
                    }
                }.message shouldBe "Lost the migration lock"

                f.history.countDocuments() shouldBe 0L
                f.recorder.commands("commitTransaction").shouldBeEmpty()
            }
        }

        "an adoption transaction over an id another run has just adopted commits and changes nothing" {
            TestMongo.client("history-other-run").use { otherClient ->
                var interfered = false
                lateinit var other: HistoryStore
                val recorder = CommandRecorder(onSucceeded = { command ->
                    val inTransaction = command.command.containsKey("autocommit")
                    if (!interfered && command.name == "update" && inTransaction) {
                        interfered = true
                        // Another run's adoption commits 002 after this transaction's snapshot was taken.
                        other.recordAdopted(listOf("002-carts"), takeover, finishedAt, transactions = false) {}
                    }
                })
                Fixture(recorder).use { f ->
                    other = HistoryStore(Bookkeeping(otherClient, f.db.name, GodwitConfig(holder = takeover.holder)))

                    val inserted =
                        f.store.recordAdopted(listOf("001-initial-setup", "002-carts"), writer, finishedAt, true) {}

                    inserted shouldBe listOf("001-initial-setup")
                    f.stored("002-carts")!!.getString("owner") shouldBe takeover.owner
                    f.stored("001-initial-setup")!!.getString("owner") shouldBe OWNER
                    // The two inserts of 002 meet in a write conflict, which the driver retries with the whole body.
                    recorder.commands.single { it.errorCode != null }.errorCode shouldBe 112
                    recorder.commands("update").count { it.command.containsKey("startTransaction") } shouldBe 2
                }
            }
        }

        "without transactions adoption writes one document per id, last-listed first, checking the lock before each" {
            val events = CopyOnWriteArrayList<String>()
            val recorder = CommandRecorder(onSucceeded = { command ->
                if (command.name == "update") {
                    val id = command.command.getArray("updates")[0].asDocument().getDocument("q").getString("_id")
                    events += "write ${id.value}"
                }
            })
            Fixture(recorder).use { f ->
                val ids = listOf("001-initial-setup", "002-carts", "003-file-store")

                f.store.recordAdopted(ids, writer, finishedAt, transactions = false) { events += "checkLock" } shouldBe
                    ids.reversed()

                events shouldBe listOf(
                    "checkLock",
                    "write 003-file-store",
                    "checkLock",
                    "write 002-carts",
                    "checkLock",
                    "write 001-initial-setup"
                )
                recorder.commands("update").forEach { command ->
                    command.command.containsKey("autocommit") shouldBe false
                    command.command.getDocument("writeConcern") shouldBe json("""{"w": "majority"}""")
                }

                f.history.deleteMany(Document())
                f.store.markRunning(carts, takeover, startedAt)
                val running = f.stored("002-carts")
                f.store.recordAdopted(ids, writer, finishedAt, transactions = false) {} shouldBe
                    listOf("003-file-store", "001-initial-setup")
                f.stored("002-carts") shouldBe running

                f.history.deleteMany(Document())
                var checks = 0
                shouldThrow<LockLostException> {
                    f.store.recordAdopted(ids, writer, finishedAt, transactions = false) {
                        if (++checks == 2) throw LockLostException(null)
                    }
                }
                f.history.find().map { it.getString("_id") }.toList() shouldBe listOf("003-file-store")
            }
        }

        "the SUPERSEDED record is an upsert conditional on state != APPLIED that stores the supersedes list" {
            Fixture().use { f ->
                f.store.recordSuperseded(baseline, writer, finishedAt) shouldBe true

                val update = f.update(f.sent("update"))
                update.getDocument("q") shouldBe json($$"""{"_id": "100-baseline", "state": {"$ne": "APPLIED"}}""")
                update.getDocument("u") shouldBe json(
                    $$"""
                    {
                      "$set": {
                        "state": "APPLIED", "origin": "SUPERSEDED", "owner": "$$OWNER", "holder": "$$HOLDER",
                        "runId": "$$RUN_ID", "finishedAt": $${date(finishedAt)}, "godwitVersion": "$$version", "v": 1,
                        "supersedes": ["001-initial-setup", "002-carts"]
                      },
                      "$setOnInsert": {"kind": "ONCE", "steps": [], "attempts": 0}
                    }
                    """
                )
                update.getBoolean("upsert").value shouldBe true
                val stored = f.stored("100-baseline").shouldNotBeNull()
                stored.getString("kind") shouldBe "ONCE"
                stored["steps"] shouldBe emptyList<String>()
                stored.getInteger("attempts") shouldBe 0
                stored shouldNotContainKey "counts"

                f.store.recordSuperseded(baseline, takeover, finishedAt) shouldBe false
                f.stored("100-baseline") shouldBe stored
                f.recorder.commands("update").takeLast(2).map { it.errorCode } shouldBe listOf(11000, 11000)
            }
        }

        "the SUPERSEDED record over a FAILED document keeps its steps and attempts" {
            Fixture().use { f ->
                f.store.markRunning(baseline, writer, startedAt)
                f.store.markFailed("100-baseline", OWNER, FailedRun(IllegalStateException("x"), null, 1, finishedAt))

                f.store.recordSuperseded(baseline, takeover, finishedAt) shouldBe true

                val stored = f.stored("100-baseline").shouldNotBeNull()
                stored.getString("state") shouldBe "APPLIED"
                stored.getString("origin") shouldBe "SUPERSEDED"
                stored.getString("owner") shouldBe takeover.owner
                stored["steps"] shouldBe listOf("OUTSIDE_TRANSACTION")
                stored.getInteger("attempts") shouldBe 1
            }
        }

        "the MARKED record inserts a once-only document for a missing id" {
            Fixture().use { f ->
                f.store.recordMarked("008-customer-email-lower-index", "built by hand", writer, finishedAt) shouldBe
                    true

                val update = f.update(f.sent("update"))
                update.getDocument("q") shouldBe
                    json($$"""{"_id": "008-customer-email-lower-index", "state": {"$ne": "APPLIED"}}""")
                update.getDocument("u") shouldBe json(
                    $$"""
                    {
                      "$set": {
                        "state": "APPLIED", "origin": "MARKED", "owner": "$$OWNER", "holder": "$$HOLDER",
                        "runId": "$$RUN_ID", "finishedAt": $${date(finishedAt)}, "godwitVersion": "$$version", "v": 1,
                        "reason": "built by hand"
                      },
                      "$unset": {"lastError": "", "checkpoint": ""},
                      "$setOnInsert": {"kind": "ONCE", "steps": [], "attempts": 0}
                    }
                    """
                )
                update.getBoolean("upsert").value shouldBe true
                val stored = f.stored("008-customer-email-lower-index").shouldNotBeNull()
                stored.getString("kind") shouldBe "ONCE"
                stored.getString("reason") shouldBe "built by hand"
                stored.getInteger("attempts") shouldBe 0
            }
        }

        "the MARKED record over a FAILED document removes lastError and checkpoint and keeps the failed run's facts" {
            Fixture().use { f ->
                f.store.markRunning(carts, writer, startedAt)
                f.history.updateOne(eq("_id", "002-carts"), Document("\$set", Document("checkpoint", Document())))
                f.store.markFailed("002-carts", OWNER, FailedRun(IllegalStateException("x"), null, 212, finishedAt))

                f.store.recordMarked("002-carts", "built by hand", takeover, finishedAt) shouldBe true

                val stored = f.stored("002-carts").shouldNotBeNull()
                stored.getString("state") shouldBe "APPLIED"
                stored.getString("origin") shouldBe "MARKED"
                stored.getString("reason") shouldBe "built by hand"
                stored.getString("holder") shouldBe takeover.holder
                stored shouldNotContainKey "lastError"
                stored shouldNotContainKey "checkpoint"
                stored["steps"] shouldBe listOf("OUTSIDE_TRANSACTION")
                stored.getInteger("attempts") shouldBe 1
                stored.getLong("durationMs") shouldBe 212L
                stored.getDate("startedAt") shouldBe Date.from(startedAt)
            }
        }

        "the MARKED record on an APPLIED document changes nothing" {
            Fixture().use { f ->
                f.store.markRunning(orderStatus, writer, startedAt)
                f.store.recordApplied(orderStatus, OWNER, applied)
                val before = f.stored("004-order-status")

                f.store.recordMarked("004-order-status", "built by hand", takeover, finishedAt) shouldBe false

                f.stored("004-order-status") shouldBe before
                f.recorder.commands("update").takeLast(2).map { it.errorCode } shouldBe listOf(11000, 11000)
            }
        }

        "a SUPERSEDED or MARKED record that hits a duplicate key on a document that is not APPLIED is sent again" {
            Fixture(appName = "history-duplicate-record").use { f ->
                f.store.markRunning(baseline, writer, startedAt)
                f.store.markRunning(carts, writer, startedAt)
                val records = listOf(
                    { f.store.recordSuperseded(baseline, takeover, finishedAt) },
                    { f.store.recordMarked("002-carts", "built by hand", takeover, finishedAt) }
                )

                records.forEach { record ->
                    TestMongo.failCommand(
                        "history-duplicate-record",
                        listOf("update"),
                        Document("times", 1),
                        Document("errorCode", FAKE_DUPLICATE_KEY)
                    ).use { record() shouldBe true }
                }

                f.recorder.commands("update").map { it.errorCode } shouldBe
                    listOf(FAKE_DUPLICATE_KEY, null, FAKE_DUPLICATE_KEY, null)
                f.stored("100-baseline")!!.getString("origin") shouldBe "SUPERSEDED"
                f.stored("002-carts")!!.getString("origin") shouldBe "MARKED"
                listOf("100-baseline", "002-carts").forEach { f.stored(it)!!.getString("state") shouldBe "APPLIED" }
            }
        }

        "a driver error other than a duplicate key propagates unchanged" {
            Fixture(appName = "history-unauthorized").use { f ->
                TestMongo.failCommand(
                    "history-unauthorized",
                    listOf("findAndModify", "update"),
                    "alwaysOn",
                    Document("errorCode", 13)
                ).use {
                    shouldThrow<MongoCommandException> { f.store.markRunning(orderStatus, writer, startedAt) }
                        .code shouldBe 13
                    shouldThrow<MongoCommandException> {
                        f.store.recordMarked("004-order-status", "x", writer, finishedAt)
                    }.code shouldBe 13
                }
                f.recorder.commands.map { it.name } shouldBe listOf("findAndModify", "update")
                f.history.countDocuments() shouldBe 0L
            }
        }

        "the bookkeeping collections ignore the app client's codecs, concerns and read preference" {
            TestMongo.database().use { db ->
                val settings = MongoClientSettings.builder()
                    .applyConnectionString(ConnectionString(TestMongo.connectionString))
                    .writeConcern(WriteConcern.W1)
                    .readConcern(ReadConcern.LOCAL)
                    .readPreference(ReadPreference.secondaryPreferred())
                    .codecRegistry(CodecRegistries.fromCodecs(StringCodec()))
                    .build()
                MongoClient.create(settings).use { client ->
                    val bookkeeping = Bookkeeping(
                        client,
                        db.name,
                        GodwitConfig(
                            historyCollection = "schema-history",
                            lockCollection = "schema-lock",
                            holder = "x/1"
                        )
                    )
                    listOf(bookkeeping.history, bookkeeping.lock).forEach { collection ->
                        collection.codecRegistry.get(Document::class.java).shouldNotBeNull()
                        collection.writeConcern shouldBe WriteConcern.MAJORITY
                        collection.readConcern shouldBe ReadConcern.MAJORITY
                        collection.readPreference shouldBe ReadPreference.primary()
                    }
                    bookkeeping.history.namespace.collectionName shouldBe "schema-history"
                    bookkeeping.lock.namespace.collectionName shouldBe "schema-lock"
                    bookkeeping.lockId shouldBe "schema-history"
                    bookkeeping.history.timeout(TimeUnit.MILLISECONDS).shouldBeNull()
                    bookkeeping.lock.timeout(TimeUnit.MILLISECONDS) shouldBe 5000L

                    shouldThrow<CodecConfigurationException> {
                        client.getDatabase(db.name).codecRegistry.get(Document::class.java)
                    }
                    HistoryStore(bookkeeping).markRunning(orderStatus, writer, startedAt).shouldNotBeNull()
                    bookkeeping.startSession().use { it.isCausallyConsistent shouldBe true }
                }
            }
        }

        "history documents carry the build's version and format, and each kind stores its name" {
            GODWIT_VERSION shouldBe version
            DOCUMENT_FORMAT shouldBe 1
            MigrationKind.Once.stored shouldBe StoredKind.ONCE
            MigrationKind.EveryStart.stored shouldBe StoredKind.EVERY_START
            MigrationKind.Repeatable("r").stored shouldBe StoredKind.REPEATABLE
        }
    }
}
