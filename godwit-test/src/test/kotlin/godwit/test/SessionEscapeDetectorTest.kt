package godwit.test

import com.mongodb.MongoInterruptedException
import com.mongodb.MongoNamespace
import com.mongodb.ServerAddress
import com.mongodb.client.model.Aggregates.match
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.InsertOneModel
import com.mongodb.client.model.Updates.set
import com.mongodb.client.model.bulk.ClientNamespacedWriteModel
import com.mongodb.connection.ClusterId
import com.mongodb.connection.ConnectionDescription
import com.mongodb.connection.ServerId
import com.mongodb.event.CommandStartedEvent
import com.mongodb.kotlin.client.ClientSession
import com.mongodb.kotlin.client.MongoClient
import com.mongodb.kotlin.client.MongoCluster
import com.mongodb.kotlin.client.MongoCollection
import com.mongodb.kotlin.client.MongoDatabase
import godwit.core.Godwit
import godwit.core.HistoryState
import godwit.core.Migration
import godwit.core.MigrationFailedException
import godwit.core.StepKind
import godwit.core.TransactionScope
import godwit.core.fixtures.TestMongo
import godwit.core.migration
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.bson.BsonArray
import org.bson.BsonBinary
import org.bson.BsonBoolean
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.bson.BsonInt64
import org.bson.BsonString
import org.bson.Document
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.concurrent.thread

// The detector's state machine, over commands built by hand: what opens a step's transaction, what belongs to it, what
// ends it, what escapes it, and how the escape names the command.

private val connection = ConnectionDescription(ServerId(ClusterId(), ServerAddress()))

private fun event(command: BsonDocument) =
    CommandStartedEvent(null, 1L, 1, connection, "shop", command.firstKey, command)

private fun lsid(session: UUID) = BsonDocument("id", BsonBinary(session))

private val stepSession: UUID = UUID.randomUUID()

private val otherSession: UUID = UUID.randomUUID()

/** A command named [name] on [target], with whichever transaction fields the case needs. */
private fun command(
    name: String,
    target: Any = "orders",
    session: UUID? = null,
    txnNumber: Long? = null,
    autocommit: Boolean? = null,
    startTransaction: Boolean = false,
    comment: Any? = null
): BsonDocument {
    val command = BsonDocument(name, if (target is String) BsonString(target) else BsonInt32(target as Int))
    session?.let { command.append("lsid", lsid(it)) }
    txnNumber?.let { command.append("txnNumber", BsonInt64(it)) }
    if (startTransaction) command.append("startTransaction", BsonBoolean.TRUE)
    autocommit?.let { command.append("autocommit", BsonBoolean(it)) }
    when (comment) {
        is String -> command.append("comment", BsonString(comment))
        is BsonDocument -> command.append("comment", comment)
    }
    return command
}

/** The comment godwit's own commands carry: `{godwit: <subject>}`. */
private fun godwit(subject: String) = BsonDocument("godwit", BsonString(subject))

/** godwit's read that opens a step's transaction on [stepSession], transaction [txnNumber]. */
private fun opening(txnNumber: Long = 1, session: UUID = stepSession) = command(
    "find",
    "godwit-history",
    session = session,
    txnNumber = txnNumber,
    autocommit = false,
    startTransaction = true,
    comment = godwit("004-order-status")
)

/** A command of the step's transaction [txnNumber] on [stepSession]. */
private fun inStep(name: String, txnNumber: Long = 1) =
    command(name, session = stepSession, txnNumber = txnNumber, autocommit = false)

private fun SessionEscapeDetector.send(command: BsonDocument) = commandStarted(event(command))

private fun SessionEscapeDetector.escapes(command: BsonDocument): SessionEscapeError =
    shouldThrow<SessionEscapeError> { send(command) }

// The escape matrix: real migrations through testGodwit(), whose client has the detector installed.

/** What a driver operation acts on: `orders` of the test database, and the cluster for a client-level bulk write. */
private class Handles(val orders: MongoCollection<Document>, val cluster: MongoCluster, val databaseName: String)

/** A driver operation on `orders`, run with the step's session or without one, and the command it sends first. */
private class Operation(val name: String, val command: String, val run: Handles.(session: ClientSession?) -> Unit)

private val operations = listOf(
    Operation("find", "find") { session ->
        if (session == null) orders.find().toList() else orders.find(session).toList()
    },
    Operation("insertOne", "insert") { session ->
        if (session == null) orders.insertOne(Document()) else orders.insertOne(session, Document())
    },
    Operation("updateOne", "update") { session ->
        val update = set("touched", true)
        if (session == null) orders.updateOne(eq("_id", 1), update) else orders.updateOne(session, eq("_id", 1), update)
    },
    Operation("deleteOne", "delete") { session ->
        if (session == null) orders.deleteOne(eq("_id", 3)) else orders.deleteOne(session, eq("_id", 3))
    },
    Operation("aggregate", "aggregate") { session ->
        val pipeline = listOf(match(exists("_id")))
        if (session == null) orders.aggregate(pipeline).toList() else orders.aggregate(session, pipeline).toList()
    },
    Operation("bulkWrite", "insert") { session ->
        val models = listOf(InsertOneModel(Document()))
        if (session == null) orders.bulkWrite(models) else orders.bulkWrite(session, models)
    },
    Operation("client bulkWrite", "bulkWrite") { session ->
        val models = listOf(ClientNamespacedWriteModel.insertOne(MongoNamespace(databaseName, "orders"), Document()))
        if (session == null) cluster.bulkWrite(models) else cluster.bulkWrite(session, models)
    }
)

/** An app service built on the test database, as the shop's services are, whose method forwards its session. */
private class OrderService(private val database: MongoDatabase, private val cluster: MongoCluster) {
    fun call(operation: Operation, session: ClientSession?) =
        Handles(database.getCollection("orders", Document::class.java), cluster, database.name).(operation.run)(session)
}

/** An app service that writes in a transaction of its own, on a session of its own. */
private class AuditService(private val client: MongoClient, databaseName: String) {
    private val audit = client.getDatabase(databaseName).getCollection("audit", Document::class.java)

    fun record(event: String) = client.startSession().use { session ->
        session.withTransaction({ audit.insertOne(session, Document("event", event)) })
    }

    /** As [record], but it swallows its own failures and commits whatever its transaction holds. */
    fun recordBestEffort(event: String) = client.startSession().use { session ->
        session.startTransaction()
        runCatching { audit.insertOne(session, Document("event", event)) }
        runCatching { session.commitTransaction() }
    }
}

/** An app service that ends the transaction of the session it is given, which a service must never do. */
private class SettlementService {
    fun settle(session: ClientSession, commit: Boolean) =
        if (commit) session.commitTransaction() else session.abortTransaction()
}

/** How a step reaches the operation: through the step's own `collection(...)`, or through an app service. */
private enum class Path { DIRECTLY, THROUGH_A_SERVICE }

private const val ID = "010-escape"

/** A migration whose transactional step of [kind] runs [body] once, on the seeded `orders`. */
private fun stepRunning(kind: StepKind, body: TransactionScope.() -> Unit): Migration = when (kind) {
    StepKind.IN_TRANSACTION -> migration(ID).inTransaction { body() }
    else -> migration(ID).inBatches("orders", exists("_id"), batchSize = 10) { body() }
}

/** [operation] in a step of [kind], reached by [path], with the step's session when [withSession]. */
private fun operationStep(kind: StepKind, operation: Operation, path: Path, withSession: Boolean, db: TestGodwit) =
    stepRunning(kind) {
        val session = if (withSession) session else null
        when (path) {
            Path.DIRECTLY -> Handles(collection("orders"), db.client, db.databaseName).(operation.run)(session)
            Path.THROUGH_A_SERVICE -> OrderService(db.database, db.client).call(operation, session)
        }
    }

/** A new test database with three orders, `_id` 1 to 3. */
private fun seeded(): TestGodwit = testGodwit().also { db ->
    db.database.getCollection("orders", Document::class.java).insertMany(List(3) { Document("_id", it + 1) })
}

private class Case(val name: String, val check: () -> Unit)

/** The step fails with [MigrationFailedException] whose cause is the escape of [command] on [collection]. */
private fun escape(name: String, kind: StepKind, command: String, collection: String, step: (TestGodwit) -> Migration) =
    Case("$name in $kind") {
        val db = seeded()

        val failure = shouldThrow<MigrationFailedException> { db.godwit.runIsolated(step(db)) }

        val escape = failure.cause.shouldBeInstanceOf<SessionEscapeError>()
        escape.command shouldBe command
        escape.collection shouldBe collection
        failure.message shouldBe "Migration $ID failed in $kind: ${escape.message}"
    }

/**
 * The step fails with [MigrationFailedException] whose cause is godwit's [IllegalStateException] for a step that ended
 * godwit's transaction, and history records it FAILED. The detector sees that commit or abort as the end of the
 * step's transaction, so an escape after it runs unseen; godwit's check before its APPLIED record or checkpoint fails
 * the step instead.
 */
private fun endedTransaction(name: String, kind: StepKind, step: (TestGodwit) -> Migration) = Case("$name in $kind") {
    val db = seeded()

    val failure = shouldThrow<MigrationFailedException> { db.godwit.runIsolated(step(db)) }

    failure.step shouldBe kind
    failure.cause.shouldBeInstanceOf<IllegalStateException>().message.shouldStartWith(
        "The step ended godwit's transaction on its session"
    )
    db.godwit.history().single { it.id == ID }.state shouldBe HistoryState.FAILED
}

/** The step applies: nothing it does escapes. */
private fun control(name: String, step: (TestGodwit) -> Migration) = Case(name) {
    val db = seeded()
    db.godwit.runIsolated(step(db))
    db.godwit shouldHaveApplied ID
}

private val stepKinds = listOf(StepKind.IN_TRANSACTION, StepKind.IN_BATCHES)

private val escapes: List<Case> = stepKinds.flatMap { kind ->
    operations.flatMap { operation ->
        Path.entries.map { path ->
            escape(
                "${operation.name} ${path.name.lowercase()} without the session",
                kind,
                operation.command,
                "orders"
            ) {
                operationStep(kind, operation, path, withSession = false, db = it)
            }
        }
    } + listOf(
        escape("a service with a session and a transaction of its own", kind, "insert", "audit") { db ->
            stepRunning(kind) { AuditService(db.client, db.databaseName).record("repriced") }
        },
        escape("an escape after a service's own commit", kind, "update", "orders") { db ->
            stepRunning(kind) {
                AuditService(db.client, db.databaseName).recordBestEffort("repriced")
                collection("orders").updateOne(eq("_id", 1), set("touched", true))
            }
        }
    ) + listOf(false, true).map { commit ->
        endedTransaction(
            "a service that ${if (commit) "commits" else "aborts"} the session it was given, then an escape",
            kind
        ) {
            stepRunning(kind) {
                collection("orders").updateOne(session, eq("_id", 1), set("touched", true))
                SettlementService().settle(session, commit)
                collection("orders").updateOne(eq("_id", 2), set("escaped", true))
            }
        }
    }
}

private val controls: List<Case> = stepKinds.flatMap { kind ->
    operations.flatMap { operation ->
        Path.entries.map { path ->
            control("${operation.name} ${path.name.lowercase()} with the session in $kind") {
                operationStep(kind, operation, path, withSession = true, db = it)
            }
        }
    } + control("a command on another thread in $kind") {
        stepRunning(kind) { thread { collection("thread-writes").insertOne(Document("from", "a thread")) }.join() }
    }
} + listOf(
    control("every operation without a session in an outside step") { db ->
        migration(ID).outsideTransaction {
            operations.forEach { Handles(collection("orders"), db.client, db.databaseName).(it.run)(null) }
        }
    },
    control("godwit's marker and record around an outside step and a transaction") {
        migration(ID).outsideTransaction { count("outside", 1) }.inTransaction {
            collection("orders").updateMany(session, exists("_id"), set("touched", true))
        }
    },
    control("godwit's checkpoints between pages of an outside step and inBatches") {
        migration(ID).outsideTransaction { collection("payments").insertMany(List(25) { Document() }) }
            .inBatches("payments", exists("_id"), batchSize = 10) { page ->
                page.forEach { collection("payments").updateOne(session, eq("_id", it["_id"]), set("seen", true)) }
                count("seen", page.size)
            }
    }
)

/**
 * Runs a transactional step on [db] that interrupts its own thread after its first write, then clears the interrupt.
 * The step's next command, and the driver's abort after it, fail to check out a connection before the listener sees
 * them, so the detector never sees the transaction end. The call fails with the driver's interrupt.
 */
private fun interruptedStep(db: TestGodwit) {
    val interrupted = migration("013-interrupted").inTransaction {
        collection("orders").updateOne(session, eq("_id", 1), set("touched", true))
        Thread.currentThread().interrupt()
        collection("orders").updateOne(session, eq("_id", 2), set("touched", true))
    }
    val failure = try {
        shouldThrow<MigrationFailedException> { db.godwit.runIsolated(interrupted) }
    } finally {
        // As a test framework's timeout does after it interrupts a test: the thread runs the next test.
        Thread.interrupted()
    }
    failure.cause.shouldBeInstanceOf<MongoInterruptedException>()
}

class SessionEscapeDetectorTest : StringSpec() {
    init {
        "the error names the command and the collection, and tells the step to pass its session" {
            val error = SessionEscapeError("update", "orders")
            error.message shouldBe "update on orders ran without the step's session, outside the transaction. " +
                "Pass `session` to the driver call or the service method."
            error.command shouldBe "update"
            error.collection shouldBe "orders"
            error.shouldBeInstanceOf<AssertionError>()
        }

        "a command without a collection is named against the database" {
            SessionEscapeError("dropDatabase", null).message shouldBe "dropDatabase on the database ran without the " +
                "step's session, outside the transaction. Pass `session` to the driver call or the service method."
        }

        "outside a step's transaction nothing is checked, a transaction that godwit did not open included" {
            val detector = SessionEscapeDetector()

            detector.send(command("insert"))
            detector.send(command("find", session = otherSession))
            detector.send(command("insert", session = otherSession, txnNumber = 1, startTransaction = true))
            detector.send(
                command("insert", session = otherSession, txnNumber = 1, startTransaction = true, comment = "godwit")
            )
            detector.send(
                command(
                    "find",
                    session = otherSession,
                    txnNumber = 1,
                    startTransaction = true,
                    comment = BsonDocument("app", BsonString("x"))
                )
            )
            detector.send(command("update"))
        }

        "godwit's read opens the step's transaction; its commands pass and every other command escapes" {
            val detector = SessionEscapeDetector()
            detector.send(opening())

            detector.send(inStep("update"))
            detector.send(inStep("getMore"))
            detector.escapes(command("update")).message shouldBe SessionEscapeError("update", "orders").message
            detector.escapes(command("find", session = otherSession)).collection shouldBe "orders"
            detector.escapes(command("insert", session = otherSession, txnNumber = 7, startTransaction = true))
                .command shouldBe "insert"
            detector.escapes(command("ping", 1)).collection shouldBe null
            detector.send(inStep("insert"))
        }

        "an abort of another session passes; a commit of another session escapes; neither ends the transaction" {
            val detector = SessionEscapeDetector()
            detector.send(opening())

            detector.send(command("abortTransaction", 1, session = otherSession, txnNumber = 3, autocommit = false))
            detector.escapes(command("commitTransaction", 1, session = otherSession, txnNumber = 3, autocommit = false))
                .collection shouldBe null
            detector.escapes(command("update")).command shouldBe "update"
        }

        "a commit or an abort of the step's session ends its transaction" {
            for (end in listOf("commitTransaction", "abortTransaction")) {
                val detector = SessionEscapeDetector()
                detector.send(opening())

                detector.send(inStep(end))

                detector.send(command("update"))
                detector.send(command("find", session = stepSession))
            }
        }

        "godwit's read that opens the next transaction tracks it instead, on the step's session or on another" {
            for (next in listOf(stepSession, otherSession)) {
                val detector = SessionEscapeDetector()
                detector.send(opening(txnNumber = 1))

                detector.send(opening(txnNumber = 2, session = next))

                detector.send(command("update", session = next, txnNumber = 2, autocommit = false))
                detector.escapes(inStep("update", txnNumber = 1)).command shouldBe "update"
            }
        }

        "a transaction the step's session starts without godwit's read escapes" {
            val detector = SessionEscapeDetector()
            detector.send(opening(txnNumber = 1))

            detector.escapes(
                command("find", session = stepSession, txnNumber = 2, autocommit = false, startTransaction = true)
            ).command shouldBe "find"
        }

        "godwit's own command outside the tracked transaction passes and ends it, on any session or none" {
            for (session in listOf(otherSession, stepSession, null)) {
                val detector = SessionEscapeDetector()
                detector.send(opening())

                detector.send(command("find", "godwit-history", session = session, comment = godwit("history")))

                detector.send(command("update"))
                detector.send(command("find", session = otherSession))
            }
        }

        "godwit's own command inside the tracked transaction belongs to it and leaves it tracked" {
            val detector = SessionEscapeDetector()
            detector.send(opening())

            detector.send(
                command(
                    "update",
                    "godwit-history",
                    session = stepSession,
                    txnNumber = 1,
                    autocommit = false,
                    comment = godwit("004-order-status")
                )
            )

            detector.escapes(command("update")).command shouldBe "update"
        }

        "ending the tracked transaction ends the calling thread's alone" {
            val detector = SessionEscapeDetector()
            val other = Executors.newSingleThreadExecutor()
            try {
                other.submit { detector.send(opening(session = otherSession)) }.get()
                detector.send(opening())

                detector.endTrackedTransaction()

                detector.send(command("update"))
                other.submit<Throwable?> { runCatching { detector.send(command("update")) }.exceptionOrNull() }.get()
                    .shouldBeInstanceOf<SessionEscapeError>()
            } finally {
                other.shutdown()
            }
        }

        "a command of the step's session outside its transaction escapes" {
            val detector = SessionEscapeDetector()
            detector.send(opening())

            detector.escapes(command("find", session = stepSession)).command shouldBe "find"
            detector.escapes(command("find", session = stepSession, txnNumber = 1, autocommit = true))
                .command shouldBe "find"
        }

        "an escape names the collection of a getMore and the namespaces of a client bulkWrite" {
            val detector = SessionEscapeDetector()
            detector.send(opening())
            val getMore = BsonDocument("getMore", BsonInt64(42)).append("collection", BsonString("orders"))
            val bulkWrite = BsonDocument("bulkWrite", BsonInt32(1)).append(
                "nsInfo",
                BsonArray(listOf("shop.orders", "shop.audit").map { BsonDocument("ns", BsonString(it)) })
            )

            detector.escapes(getMore).collection shouldBe "orders"
            detector.escapes(bulkWrite).collection shouldBe "orders, audit"
            detector.escapes(command("aggregate", 1)).collection shouldBe null
        }

        "each thread has its own step transaction" {
            val detector = SessionEscapeDetector()
            detector.send(opening())

            var escaped: Throwable? = null
            thread {
                escaped = runCatching { detector.send(command("update")) }.exceptionOrNull()
                detector.send(opening(session = otherSession))
            }.join()

            escaped shouldBe null
            detector.send(inStep("update"))
            detector.escapes(command("update", session = otherSession, txnNumber = 1, autocommit = false))
        }

        "the escape matrix: every escape fails its step, and no control does" {
            val missed = escapes.mapNotNull { case ->
                runCatching(case.check).exceptionOrNull()?.let { "${case.name}: $it" }
            }
            val falsePositives = controls.mapNotNull { case ->
                runCatching(case.check).exceptionOrNull()?.let { "${case.name}: $it" }
            }

            println(
                "escape matrix caught=${escapes.size - missed.size}/${escapes.size} falsePositives=${falsePositives.size}"
            )
            withClue((missed + falsePositives).joinToString("\n")) {
                missed.shouldBeEmpty()
                falsePositives.shouldBeEmpty()
            }
        }

        "after a step whose abort never reached the listener, godwit's next command on the thread is not an escape" {
            val db = seeded()

            interruptedStep(db)

            // The same client, through a Godwit of the test's own on another database: no testGodwit() call between.
            val next = Godwit(db.client, UUID.randomUUID().toString())
            next.migrate(migration("002-carts").outsideTransaction { ensureCollection("carts") })
            next shouldHaveApplied "002-carts"
        }

        "after a step whose abort never reached the listener, the next testGodwit() call starts the thread afresh" {
            interruptedStep(seeded())

            val next = testGodwit()
            next.database.getCollection("orders", Document::class.java).insertOne(Document("_id", 1))
            next.godwit.runIsolated(migration("002-carts").outsideTransaction { ensureCollection("carts") })
            next.godwit shouldHaveApplied "002-carts"
        }

        "a transaction the driver runs again opens again, and an escape in the second run is caught" {
            TestMongo.client("escape-retry", SessionEscapeDetector()).use { client ->
                val databaseName = UUID.randomUUID().toString()
                val godwit = Godwit(client, databaseName)
                val conflict = Document("errorCode", 112).append("errorLabels", listOf("TransientTransactionError"))

                val retried = migration("011-retried").inTransaction {
                    collection("orders").insertOne(session, Document("attempt", attempt))
                }
                TestMongo.failCommand("escape-retry", listOf("insert"), Document("times", 1), conflict).use {
                    godwit.migrate(retried)["011-retried"].transactionRetries shouldBe 1
                }

                val escapesOnRetry = migration("012-escapes-on-retry").inTransaction {
                    if (attempt == 1) {
                        collection("orders").insertOne(session, Document("attempt", attempt))
                    } else {
                        collection("orders").insertOne(Document("attempt", attempt))
                    }
                }
                val failure = TestMongo.failCommand(
                    "escape-retry",
                    listOf("insert"),
                    Document("times", 1),
                    conflict
                ).use {
                    shouldThrow<MigrationFailedException> { godwit.migrate(escapesOnRetry) }
                }
                failure.cause.shouldBeInstanceOf<SessionEscapeError>().command shouldBe "insert"
            }
        }
    }
}
