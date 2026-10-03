package godwit.core

import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.Updates.set
import godwit.core.fixtures.GodwitFixture
import godwit.core.fixtures.RecordedCommand
import godwit.core.fixtures.TestMongo
import godwit.core.fixtures.seeded
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.bson.BsonDocument
import org.bson.BsonString
import org.bson.Document

private val RecordedCommand.opensTransaction: Boolean
    get() = command.getBoolean("startTransaction", null)?.value == true

/** The command that opened each transaction the client ran, in order. */
private fun GodwitFixture.openings(): List<RecordedCommand> = recorder.commands.filter { it.opensTransaction }

private fun godwitComment(id: String) = BsonDocument("godwit", BsonString(id))

/** godwit's two collections. */
private val GODWIT = setOf("godwit-history", "godwit-lock")

/**
 * Every transaction of a transactional step opens with godwit's own read, before the step's first command: the history
 * document's read in `inTransaction`, the page's read in `inBatches`, each carrying the comment `{godwit: <id>}`.
 * godwit-test's `SessionEscapeDetector` recognises a step's transaction by it, and godwit's other commands by the same
 * comment, which every command to the history and lock collections carries.
 */
class TransactionOpeningTest : StringSpec() {
    init {
        "every command a call sends to the history and lock collections carries the comment {godwit: ...}" {
            GodwitFixture(appName = "opening-bookkeeping", config = seeded).use { f ->
                val migrations = listOf(
                    migration("002-carts").outsideTransaction { ensureCollection("carts") },
                    migration("004-order-status").inTransaction {
                        collection("orders").insertOne(session, Document("status", "PENDING"))
                    },
                    migration("006-order-totals").inBatches("orders", exists("_id"), batchSize = 1) { },
                    migration("007-failing").outsideTransaction { error("an order without lines") }
                )

                shouldThrow<MigrationFailedException> { f.godwit.migrate(migrations) }
                f.godwit.markApplied("007-failing", "fixed by hand")
                f.godwit.status(migrations)
                f.godwit.history()

                val bookkeeping = f.recorder.commands.filter {
                    it.command[it.name]?.let { target -> target.isString && target.asString().value in GODWIT } == true
                }
                bookkeeping.map { it.name }.toSet() shouldBe setOf("find", "findAndModify", "update")
                bookkeeping.filter { it.command.getDocument("comment", null)?.containsKey("godwit") != true }
                    .map { it.command.toJson() }.shouldBeEmpty()
            }
        }

        "an inTransaction step's transaction opens with godwit's read of the migration's history document" {
            GodwitFixture(appName = "opening-in-transaction").use { f ->
                val orderStatus = migration("004-order-status").inTransaction {
                    collection("orders").insertOne(session, Document("status", "PENDING"))
                }

                f.godwit.migrate(orderStatus)

                val opening = f.openings().single()
                opening.name shouldBe "find"
                opening.command.getString("find").value shouldBe "godwit-history"
                opening.command.getDocument("filter") shouldBe BsonDocument("_id", BsonString("004-order-status"))
                opening.command.getDocument("comment") shouldBe godwitComment("004-order-status")
                val commands = f.recorder.commands
                val commit = commands.indexOfFirst { it.name == "commitTransaction" }
                val transaction = commands.subList(commands.indexOf(opening), commit + 1)
                transaction.map { it.name } shouldBe listOf("find", "insert", "update", "commitTransaction")
                transaction.map { it.command["lsid"] }.toSet() shouldHaveSize 1
            }
        }

        "a transaction the driver runs again opens again with godwit's read" {
            GodwitFixture(appName = "opening-retry").use { f ->
                val conflict = Document("errorCode", 112).append("errorLabels", listOf("TransientTransactionError"))
                val orderStatus = migration("004-order-status").inTransaction {
                    collection("orders").insertOne(session, Document("status", "PENDING"))
                }

                TestMongo.failCommand(f.appName, listOf("insert"), Document("times", 1), conflict).use {
                    f.godwit.migrate(orderStatus)["004-order-status"].transactionRetries shouldBe 1
                }

                val openings = f.openings()
                openings.map { it.name } shouldBe listOf("find", "find")
                openings.map { it.command.getDocument("comment") }.toSet() shouldBe
                    setOf(godwitComment("004-order-status"))
                openings.map { it.command.getInt64("txnNumber") }.toSet() shouldHaveSize 2
            }
        }

        "every page of an inBatches step opens with its page read" {
            GodwitFixture(appName = "opening-batches").use { f ->
                val orderTotals = migration("006-order-totals")
                    .outsideTransaction { collection("orders").insertMany(List(25) { Document("_id", it) }) }
                    .inBatches("orders", exists("totalMinor", false), batchSize = 10) { page ->
                        page.forEach {
                            collection("orders").updateOne(session, Document("_id", it["_id"]), set("totalMinor", 0L))
                        }
                    }

                f.godwit.migrate(orderTotals)["006-order-totals"].batches shouldBe 3

                val openings = f.openings()
                openings.map { it.name }.toSet() shouldBe setOf("find")
                openings.map { it.command.getString("find").value }.toSet() shouldBe setOf("orders")
                openings.map { it.command.getDocument("comment") }.toSet() shouldBe
                    setOf(godwitComment("006-order-totals"))
                openings shouldHaveSize 4
            }
        }
    }
}
