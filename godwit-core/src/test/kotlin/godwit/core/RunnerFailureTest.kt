package godwit.core

import com.mongodb.MongoCommandException
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Updates.combine
import com.mongodb.client.model.Updates.set
import godwit.core.fixtures.CommandRecorder
import godwit.core.fixtures.GodwitFixture
import godwit.core.fixtures.LogCapture
import godwit.core.fixtures.RecordedCommand
import godwit.core.fixtures.TestMongo
import godwit.core.fixtures.line
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.bson.Document
import org.bson.conversions.Bson

/** The error a step throws in these tests. */
private val boom = IllegalStateException("boom")

/** Server error 13 (Unauthorized): not retried by the driver, so the command fails once. */
private val unauthorized = Document("errorCode", 13)

/** The history document of the migration [StepScope.id], as this step sees it, changed by [update]. */
private fun StepScope.rewriteOwnDocument(update: Bson) {
    database.getCollection("godwit-history", Document::class.java).updateOne(eq("_id", id), update)
}

/** The update of a history document that records it APPLIED. */
private fun RecordedCommand.recordsApplied(): Boolean {
    if (name != "update" || command.getString("update").value != "godwit-history") return false
    val set = command.getArray("updates")[0].asDocument().getDocument("u").getDocument("\$set", null)
    return set?.getString("state", null)?.value == "APPLIED"
}

/**
 * A process whose first APPLIED record of `002-carts` fails with [unauthorized] (the step turns the fail point on),
 * and whose history document is then changed by [update] through the test's own client, before godwit sends the record
 * again. With [failRead], the next read of the history collection fails with [unauthorized] too.
 */
private class FailedRecordFixture(appName: String, update: Bson, failRead: Boolean = false) : AutoCloseable {
    private val failPoints = mutableListOf<AutoCloseable>()

    val recorder: CommandRecorder = CommandRecorder(onFailed = { command ->
        if (command.recordsApplied() && failPoints.size == 1) {
            fixture.history.updateOne(eq("_id", "002-carts"), update)
            if (failRead) {
                val history = Document(unauthorized).append("namespace", "${fixture.db.name}.godwit-history")
                failPoints += TestMongo.failCommand(appName, listOf("find"), Document("times", 1), history)
            }
        }
    })

    val fixture: GodwitFixture = GodwitFixture(appName = appName, recorder = recorder)

    val carts = migration("002-carts").outsideTransaction {
        failPoints += TestMongo.failCommand(appName, listOf("update"), Document("times", 1), unauthorized)
    }

    override fun close() {
        failPoints.forEach { it.close() }
        fixture.close()
    }
}

/**
 * The runner's own history writes failing around a step: the FAILED record, its fence, the read that follows a fence
 * that matched nothing, and the APPLIED record of a migration with only an outside step.
 */
class RunnerFailureTest : StringSpec() {
    init {
        "a FAILED write that fails leaves the document RUNNING and is attached to the exception as suppressed" {
            GodwitFixture(appName = "failure-write-fails").use { f ->
                lateinit var failPoint: AutoCloseable
                val failing = migration("001-failing").outsideTransaction {
                    failPoint = TestMongo.failCommand(f.appName, listOf("update"), Document("times", 1), unauthorized)
                    throw boom
                }

                val failure = LogCapture().use { logs ->
                    val failure = shouldThrow<MigrationFailedException> { f.godwit.migrate(failing) }
                    logs.events("Migration failed").single().line shouldBe
                        "Migration failed id=001-failing step=OUTSIDE_TRANSACTION attempts=1 " +
                        "error=java.lang.IllegalStateException: boom"
                    failure
                }
                failPoint.close()

                failure.cause shouldBeSameInstanceAs boom
                failure.suppressed.single().shouldBeInstanceOf<MongoCommandException>().code shouldBe 13
                f.stored("001-failing")!!.getString("state") shouldBe "RUNNING"
            }
        }

        "a FAILED write that matches nothing on a document APPLIED by this run reports the migration as applied" {
            GodwitFixture().use { f ->
                val committed = migration("001-committed")
                    .outsideTransaction {
                        // As if the transaction had committed and only the reply to its commit had failed.
                        rewriteOwnDocument(
                            combine(
                                set("state", "APPLIED"),
                                set("counts", Document("ordersPaid", 12L)),
                                set("transactionRetries", 2)
                            )
                        )
                        throw boom
                    }
                    .inTransaction { }

                LogCapture().use { logs ->
                    val report = f.godwit.migrate(committed)

                    val outcome = report["001-committed"]
                    outcome.counts shouldBe mapOf("ordersPaid" to 12L)
                    outcome.transactionRetries shouldBe 2
                    logs.events("Migration failed").shouldBeEmpty()
                    logs.events("Applied migration").single().line.startsWith(
                        "Applied migration id=001-committed kind=ONCE steps=[OUTSIDE_TRANSACTION, IN_TRANSACTION] " +
                            "attempts=1 txRetries=2 batches=0"
                    ) shouldBe true
                }
                f.stored("001-committed")!!.containsKey("lastError") shouldBe false
            }
        }

        "a FAILED write that matches nothing because another run owns the document throws LockLostException" {
            val takeovers = listOf<Pair<String, StepScope.() -> Unit>>(
                "RUNNING by another run" to { rewriteOwnDocument(set("owner", "another-run")) },
                "APPLIED by another run" to {
                    rewriteOwnDocument(combine(set("owner", "another-run"), set("state", "APPLIED")))
                },
                "deleted by hand" to {
                    database.getCollection("godwit-history", Document::class.java).deleteOne(eq("_id", id))
                }
            )
            for ((case, takeover) in takeovers) {
                GodwitFixture().use { f ->
                    val taken = migration("001-taken").outsideTransaction {
                        takeover()
                        throw boom
                    }

                    LogCapture().use { logs ->
                        val lost = shouldThrow<LockLostException> { f.godwit.migrate(taken) }

                        lost.id shouldBe "001-taken"
                        lost.cause shouldBeSameInstanceAs boom
                        lost.message shouldBe "Lost the migration lock while running 001-taken"
                        logs.events("Migration failed").single().line.endsWith(
                            "error=java.lang.IllegalStateException: boom"
                        ) shouldBe true
                        logs.events("Lost migration lock").shouldBeEmpty()
                    }
                    if (case != "deleted by hand") f.stored("001-taken")!!.getString("owner") shouldBe "another-run"
                }
            }
        }

        "a read that fails after a FAILED write matched nothing propagates, with the step's error suppressed" {
            GodwitFixture(appName = "failure-read-fails").use { f ->
                lateinit var failPoint: AutoCloseable
                val taken = migration("001-taken").outsideTransaction {
                    rewriteOwnDocument(set("owner", "another-run"))
                    failPoint = TestMongo.failCommand(f.appName, listOf("find"), Document("times", 1), unauthorized)
                    throw boom
                }

                val read = shouldThrow<MongoCommandException> { f.godwit.migrate(taken) }
                failPoint.close()

                read.code shouldBe 13
                read.suppressed.single() shouldBeSameInstanceAs boom
            }
        }

        "the APPLIED record of an outside-only migration whose fence matches nothing throws LockLostException" {
            GodwitFixture().use { f ->
                val taken = migration("001-taken").outsideTransaction {
                    rewriteOwnDocument(set("owner", "another-run"))
                }

                LogCapture().use { logs ->
                    val lost = shouldThrow<LockLostException> { f.godwit.migrate(taken) }

                    lost.cause.shouldBeNull()
                    logs.events("Migration failed").single().line shouldBe
                        "Migration failed id=001-taken step=OUTSIDE_TRANSACTION attempts=1 " +
                        "error=godwit.core.LockLostException: Lost the migration lock while running 001-taken"
                }
                f.stored("001-taken")!!.getString("state") shouldBe "RUNNING"
            }
        }

        "an outside-only APPLIED record that fails is sent again, which records the migration APPLIED" {
            GodwitFixture(appName = "failure-record-fails-once").use { f ->
                lateinit var failPoint: AutoCloseable
                val carts = migration("002-carts").outsideTransaction {
                    count("collectionsCreated", if (ensureCollection("carts")) 1 else 0)
                    failPoint = TestMongo.failCommand(f.appName, listOf("update"), Document("times", 1), unauthorized)
                }

                LogCapture().use { logs ->
                    f.godwit.migrate(carts)["002-carts"].counts shouldBe mapOf("collectionsCreated" to 1L)
                    logs.events("Applied migration") shouldHaveSize 1
                    logs.events("Migration failed").shouldBeEmpty()
                }
                failPoint.close()

                val (first, again) = f.recorder.commands.filter { it.recordsApplied() }
                first.errorCode shouldBe 13
                again.reply!!.getInt32("n").value shouldBe 1
                val stored = f.stored("002-carts")!!
                stored.getString("state") shouldBe "APPLIED"
                stored.getInteger("attempts") shouldBe 1
            }
        }

        "an outside-only APPLIED record that fails twice propagates the first exception and leaves it RUNNING" {
            GodwitFixture(appName = "failure-record-fails").use { f ->
                var failPoint: AutoCloseable? = null
                val carts = migration("002-carts").outsideTransaction {
                    ensureCollection("carts")
                    if (failPoint == null) {
                        failPoint =
                            TestMongo.failCommand(f.appName, listOf("update"), Document("times", 2), unauthorized)
                    }
                }

                LogCapture().use { logs ->
                    val failure = shouldThrow<MongoCommandException> { f.godwit.migrate(carts) }
                    failure.code shouldBe 13
                    failure.suppressed.single().shouldBeInstanceOf<MongoCommandException>().code shouldBe 13
                    logs.events("Migration failed").shouldBeEmpty()
                }
                failPoint?.close()
                f.recorder.commands.filter { it.recordsApplied() }.map { it.errorCode } shouldBe listOf(13, 13)
                f.stored("002-carts")!!.getString("state") shouldBe "RUNNING"

                LogCapture().use { logs ->
                    f.godwit.migrate(carts)["002-carts"].attempts shouldBe 2
                    logs.events("Resuming interrupted migration").single().line shouldBe
                        "Resuming interrupted migration id=002-carts attempts=2"
                }
            }
        }

        "an APPLIED record sent again that matches another run's document propagates the first exception" {
            FailedRecordFixture("failure-record-taken", set("owner", "another-run")).use { failed ->
                val f = failed.fixture

                LogCapture().use { logs ->
                    val failure = shouldThrow<MongoCommandException> { f.godwit.migrate(failed.carts) }
                    failure.code shouldBe 13
                    failure.suppressed.toList().shouldBeEmpty()
                    logs.events("Migration failed").shouldBeEmpty()
                }
                val (_, again) = f.recorder.commands.filter { it.recordsApplied() }
                again.reply!!.getInt32("n").value shouldBe 0
                val stored = f.stored("002-carts")!!
                stored.getString("state") shouldBe "RUNNING"
                stored.getString("owner") shouldBe "another-run"
            }
        }

        "when the read after an APPLIED record sent again fails, the first record's exception carries the read's" {
            FailedRecordFixture("failure-record-read-fails", set("state", "APPLIED"), failRead = true).use { failed ->
                val f = failed.fixture

                val failure = shouldThrow<MongoCommandException> { f.godwit.migrate(failed.carts) }

                failure.code shouldBe 13
                failure.suppressed.single().shouldBeInstanceOf<MongoCommandException>().code shouldBe 13
                val (_, again) = f.recorder.commands.filter { it.recordsApplied() }
                again.reply!!.getInt32("n").value shouldBe 0
                f.recorder.commands.last { it.name == "find" }.errorCode shouldBe 13
                f.stored("002-carts")!!.getString("state") shouldBe "APPLIED"
            }
        }
    }
}
