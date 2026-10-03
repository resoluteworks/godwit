package godwit.test

import com.mongodb.MongoNamespace
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Updates.set
import com.mongodb.client.model.bulk.ClientNamespacedWriteModel
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import godwit.core.fixtures.CommandRecorder
import godwit.core.fixtures.RecordedCommand
import godwit.core.fixtures.TestMongo
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.bson.Document
import java.util.UUID

/** A listener that throws [error] when a command named [command] starts. */
private class Throwing(private val command: String, private val error: () -> Throwable) : CommandListener {
    override fun commandStarted(event: CommandStartedEvent) {
        if (event.commandName == command) throw error()
    }
}

private val RecordedCommand.lsid: BsonDocument? get() = command.getDocument("lsid", null)

/**
 * Pins the driver behaviour [SessionEscapeDetector] relies on: what a `CommandStartedEvent` carries for commands sent
 * with and without a transaction's session, and what happens to an error thrown from a listener. A driver release that
 * changes either fails here, before the detector silently stops detecting.
 */
class DriverContractTest : StringSpec() {
    init {
        "a transaction's commands carry its lsid, txnNumber and autocommit false; the first startTransaction" {
            val recorder = CommandRecorder()
            TestMongo.client("contract-session", recorder).use { client ->
                val orders = client.getDatabase(
                    UUID.randomUUID().toString()
                ).getCollection("orders", Document::class.java)
                orders.insertOne(Document("_id", 1))
                client.startSession().use { session ->
                    recorder.clear()
                    session.withTransaction({
                        orders.find(session, eq("_id", 1)).comment(BsonDocument("godwit", BsonInt32(1))).toList()
                        orders.updateOne(session, eq("_id", 1), set("paid", true))
                        orders.insertOne(session, Document("_id", 2))
                    })
                    val lsid = session.wrapped.serverSession.identifier

                    val (find, update, insert, commit) = recorder.commands
                    recorder.commands.map { it.name } shouldBe listOf("find", "update", "insert", "commitTransaction")
                    for (command in recorder.commands) {
                        command.lsid shouldBe lsid
                        command.command.getInt64("txnNumber") shouldBe find.command.getInt64("txnNumber")
                    }
                    for (command in listOf(find, update, insert, commit)) {
                        command.command.getBoolean("autocommit").value shouldBe false
                    }
                    find.command.getBoolean("startTransaction").value shouldBe true
                    find.command.getDocument("comment") shouldBe BsonDocument("godwit", BsonInt32(1))
                    listOf(update, insert, commit).map { it.command.containsKey("startTransaction") } shouldBe
                        listOf(false, false, false)
                }
            }
        }

        "a command sent without a session inside the transaction carries another lsid and no autocommit" {
            // A retryable write carries a txnNumber of its own session, so only lsid and autocommit tell it apart.
            val recorder = CommandRecorder()
            TestMongo.client("contract-escape", recorder).use { client ->
                val orders = client.getDatabase(
                    UUID.randomUUID().toString()
                ).getCollection("orders", Document::class.java)
                // The collection exists before the transaction, which would otherwise create it and conflict with the
                // write outside it.
                orders.insertOne(Document("_id", 0))
                recorder.clear()
                client.startSession().use { session ->
                    session.withTransaction({
                        orders.insertOne(session, Document("_id", 1))
                        orders.insertOne(Document("_id", 2))
                    })
                    val (inTransaction, escaped) = recorder.commands("insert")

                    inTransaction.lsid shouldBe session.wrapped.serverSession.identifier
                    escaped.lsid shouldNotBe null
                    escaped.lsid shouldNotBe inTransaction.lsid
                    escaped.command.containsKey("autocommit") shouldBe false
                    escaped.command.containsKey("startTransaction") shouldBe false
                }
            }
        }

        "an Error thrown from a listener reaches the caller, and the command is never sent" {
            val error = AssertionError("escaped")
            TestMongo.client("contract-error", Throwing("insert") { error }).use { client ->
                val orders = client.getDatabase(
                    UUID.randomUUID().toString()
                ).getCollection("orders", Document::class.java)

                shouldThrow<AssertionError> { orders.insertOne(Document("_id", 1)) } shouldBeSameInstanceAs error

                orders.countDocuments() shouldBe 0L
            }
        }

        "an Exception thrown from a listener is swallowed, and the command runs" {
            TestMongo.client("contract-exception", Throwing("insert") { IllegalStateException("ignored") }).use {
                val orders = it.getDatabase(UUID.randomUUID().toString()).getCollection("orders", Document::class.java)

                orders.insertOne(Document("_id", 1))

                orders.countDocuments() shouldBe 1L
            }
        }

        "withTransaction aborts with the session's lsid after its body throws an Error, then rethrows it" {
            val recorder = CommandRecorder()
            TestMongo.client("contract-abort", recorder).use { client ->
                val orders = client.getDatabase(
                    UUID.randomUUID().toString()
                ).getCollection("orders", Document::class.java)
                client.startSession().use { session ->
                    val error = AssertionError("escaped")

                    shouldThrow<AssertionError> {
                        session.withTransaction<Unit>({
                            orders.insertOne(session, Document("_id", 1))
                            throw error
                        })
                    } shouldBeSameInstanceAs error

                    val abort = recorder.commands("abortTransaction").single()
                    abort.lsid shouldBe session.wrapped.serverSession.identifier
                    abort.command.getInt64("txnNumber") shouldBe recorder.commands("insert").single().command
                        .getInt64("txnNumber")
                    orders.countDocuments() shouldBe 0L
                }
            }
        }

        "a getMore names its collection in collection, and a client bulkWrite its namespaces in nsInfo" {
            val recorder = CommandRecorder()
            TestMongo.client("contract-names", recorder).use { client ->
                val databaseName = UUID.randomUUID().toString()
                val orders = client.getDatabase(databaseName).getCollection("orders", Document::class.java)
                orders.insertMany(List(3) { Document("_id", it) })

                orders.find().batchSize(1).toList() shouldHaveSize 3
                client.bulkWrite(
                    listOf(ClientNamespacedWriteModel.insertOne(MongoNamespace(databaseName, "orders"), Document()))
                )

                recorder.commands("getMore").first().command.getString("collection").value shouldBe "orders"
                recorder.commands("bulkWrite").single().command.getArray("nsInfo")
                    .map { it.asDocument().getString("ns").value } shouldBe listOf("$databaseName.orders")
            }
        }
    }
}
