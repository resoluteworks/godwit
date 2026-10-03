package godwit.core

import com.mongodb.MongoOperationTimeoutException
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates.inc
import godwit.core.fixtures.CommandRecorder
import godwit.core.fixtures.GodwitFixture
import godwit.core.fixtures.LogCapture
import godwit.core.fixtures.RecordedCommand
import godwit.core.fixtures.TestMongo
import godwit.core.fixtures.keyValues
import godwit.core.fixtures.line
import godwit.core.internal.Tuning
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.bson.Document
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

/** `{_id: id, n: 1}` incremented in `probes` on the transaction's session: an effect that must commit exactly once. */
private fun TransactionScope.probe() {
    collection("probes").updateOne(session, eq("_id", id), inc("n", 1), UpdateOptions().upsert(true))
}

/** A transient write conflict, as the server labels one in a transaction. */
private val writeConflict =
    Document("errorCode", 112).append("errorLabels", listOf("TransientTransactionError"))

/** The client named [appName]'s [commands] fail with [data], [times] times. */
private fun fail(appName: String, commands: List<String>, times: Int, data: Document): AutoCloseable =
    TestMongo.failCommand(appName, commands, Document("times", times), data)

/** The update of the history document that records it APPLIED. */
private fun RecordedCommand.isAppliedRecord(): Boolean {
    if (name != "update" || command.getString("update").value != "godwit-history") return false
    val set = command.getArray("updates")[0].asDocument().getDocument("u").getDocument("\$set", null)
    return set?.getString("state", null)?.value == "APPLIED"
}

/**
 * Holds the reply of the first write [isTarget] picks before its majority acknowledgement, after the write applied,
 * until the client gives up on it: a commit or record that applies on the server and times out on a client with
 * `timeoutMS`. A later write [isTarget] picks runs as usual.
 */
private class HeldAcknowledgement(private val isTarget: (RecordedCommand) -> Boolean) {
    @Volatile
    private var held: RecordedCommand? = null

    @Volatile
    private var hang: AutoCloseable? = null

    val recorder = CommandRecorder(
        onStarted = {
            if (held == null && isTarget(it)) {
                held = it
                hang = TestMongo.failPoint("hangBeforeWaitingForWriteConcern", "alwaysOn")
            }
        },
        onFailed = { if (it === held) hang?.close() }
    )
}

class TransactionRetryTest : StringSpec() {
    init {
        "a TransientTransactionError runs the body again after a pause, with attempt 2, a new scope and new counters" {
            val pauses = CopyOnWriteArrayList<Duration>()
            val tuning = Tuning(sleep = { pauses += it })
            GodwitFixture(appName = "retry-transient", tuning = tuning).use { f ->
                val scopes = mutableListOf<TransactionScope>()
                val orderStatus = migration("004-order-status").inTransaction {
                    scopes += this
                    count("ordersPaid", 1)
                    collection("orders").insertOne(session, Document("status", "PAID"))
                    probe()
                }

                val report = LogCapture().use { logs ->
                    val report = fail(f.appName, listOf("insert"), 1, writeConflict).use {
                        f.godwit.migrate(orderStatus)
                    }
                    logs.events("Retrying transaction").single().line shouldBe
                        "Retrying transaction id=004-order-status attempt=2 error=WriteConflict (112)"
                    logs.events("Retrying transaction").single().level.toString() shouldBe "WARN"
                    logs.events("Applied migration").single().keyValues["txRetries"] shouldBe 1
                    report
                }

                scopes.map { it.attempt } shouldBe listOf(1, 2)
                scopes[1] shouldNotBe scopes[0]
                pauses.single() shouldBeGreaterThanOrEqualTo 2.5.milliseconds
                pauses.single() shouldBeLessThanOrEqualTo 5.milliseconds
                val outcome = report["004-order-status"]
                outcome.counts shouldBe mapOf("ordersPaid" to 1L)
                outcome.transactionRetries shouldBe 1
                outcome.attempts shouldBe 1
                f.stored("004-order-status")!!.getInteger("transactionRetries") shouldBe 1
                f.stored("004-order-status")!!["counts"] shouldBe Document("ordersPaid", 1L)
                f.collection("orders").countDocuments() shouldBe 1L
                f.collection("probes").find().first().getInteger("n") shouldBe 1
            }
        }

        "an UnknownTransactionCommitResult makes the driver retry the commit only" {
            GodwitFixture(appName = "retry-commit-unknown").use { f ->
                val attempts = mutableListOf<Int>()
                val orderStatus = migration("004-order-status").inTransaction {
                    attempts += attempt
                    probe()
                }
                val unknown = Document("errorCode", 8).append("errorLabels", listOf("UnknownTransactionCommitResult"))

                LogCapture().use { logs ->
                    fail(f.appName, listOf("commitTransaction"), 1, unknown).use { f.godwit.migrate(orderStatus) }
                    logs.events("Retrying transaction").shouldBeEmpty()
                }

                attempts shouldBe listOf(1)
                f.recorder.commands("commitTransaction") shouldHaveSize 2
                f.stored("004-order-status")!!.getInteger("transactionRetries") shouldBe 0
                f.collection("probes").find().first().getInteger("n") shouldBe 1
            }
        }

        "a transient error on the commit runs the body again and logs error=commit" {
            GodwitFixture(appName = "retry-commit-transient").use { f ->
                val attempts = mutableListOf<Int>()
                val orderStatus = migration("004-order-status").inTransaction {
                    attempts += attempt
                    count("tries", 1)
                    probe()
                }

                val outcome = LogCapture().use { logs ->
                    val report =
                        fail(f.appName, listOf("commitTransaction"), 1, writeConflict).use {
                            f.godwit.migrate(orderStatus)
                        }
                    logs.events("Retrying transaction").single().line shouldBe
                        "Retrying transaction id=004-order-status attempt=2 error=commit"
                    report["004-order-status"]
                }

                attempts shouldBe listOf(1, 2)
                outcome.counts shouldBe mapOf("tries" to 1L)
                outcome.transactionRetries shouldBe 1
                f.collection("probes").find().first().getInteger("n") shouldBe 1
            }
        }

        "a body that conflicts for 5 s logs Retrying transaction once per 10 s and counts every retry" {
            GodwitFixture(appName = "retry-conflict-5s").use { f ->
                val conflict = TestMongo.failCommand(f.appName, listOf("insert"), "alwaysOn", writeConflict)
                val started = TimeSource.Monotonic.markNow()
                val busy = migration("004-order-status").inTransaction {
                    if (started.elapsedNow() >= 5.seconds) conflict.close()
                    collection("orders").insertOne(session, Document("status", "PAID"))
                }

                val outcome = LogCapture().use { logs ->
                    val outcome = conflict.use { f.godwit.migrate(busy)["004-order-status"] }
                    logs.events("Retrying transaction") shouldHaveSize 1
                    logs.events("Applied migration").single().keyValues["txRetries"] shouldBe outcome.transactionRetries
                    outcome
                }

                val conflicts = f.recorder.commands("insert").count { it.errorCode == 112 }
                conflicts shouldBeGreaterThan 10
                outcome.transactionRetries shouldBe conflicts
                f.stored("004-order-status")!!.getInteger("transactionRetries") shouldBe conflicts
            }
        }

        "after the first retry, Retrying transaction is logged again once the interval has passed" {
            val tuning = Tuning(retryLogInterval = 1.seconds)
            GodwitFixture(appName = "retry-conflict-interval", tuning = tuning).use { f ->
                val conflict = TestMongo.failCommand(f.appName, listOf("insert"), "alwaysOn", writeConflict)
                val started = TimeSource.Monotonic.markNow()
                val busy = migration("004-order-status").inTransaction {
                    if (started.elapsedNow() >= 2500.milliseconds) conflict.close()
                    collection("orders").insertOne(session, Document("status", "PAID"))
                }

                LogCapture().use { logs ->
                    conflict.use { f.godwit.migrate(busy) }

                    val retries = logs.events("Retrying transaction")
                    retries.size shouldBeGreaterThanOrEqual 2
                    retries.zipWithNext().forEach { (earlier, later) ->
                        withClue("${earlier.line} then ${later.line}") {
                            (later.timeStamp - earlier.timeStamp) shouldBeGreaterThanOrEqual 1000L
                        }
                    }
                }
            }
        }

        "an attempt slower than slowTransactionWarning logs Slow transaction" {
            GodwitFixture(config = GodwitConfig(slowTransactionWarning = 100.milliseconds)).use { f ->
                val slow = migration("007-customer-email-lower").inTransaction {
                    Thread.sleep(150.milliseconds.toJavaDuration())
                    probe()
                }

                LogCapture().use { logs ->
                    f.godwit.migrate(slow)

                    val line = logs.events("Slow transaction").single()
                    line.level.toString() shouldBe "WARN"
                    line.keyValues["id"] shouldBe "007-customer-email-lower"
                    line.keyValues["attempt"] shouldBe 1
                    (line.keyValues["durationMs"] as Long) shouldBeGreaterThanOrEqual 150L
                }
            }
        }

        "a commit that applies and then times out on the client ends APPLIED, not FAILED, with its effect once" {
            val held = HeldAcknowledgement { it.name == "commitTransaction" }
            GodwitFixture(appName = "retry-commit-timeout", recorder = held.recorder, timeout = 2.seconds).use { f ->
                val orderStatus = migration("004-order-status").inTransaction {
                    count("ordersPaid", 1)
                    probe()
                }

                LogCapture().use { logs ->
                    val report = f.godwit.migrate(orderStatus)

                    report["004-order-status"].counts shouldBe mapOf("ordersPaid" to 1L)
                    logs.events("Migration failed").shouldBeEmpty()
                    logs.events("Applied migration") shouldHaveSize 1
                }

                val commit = f.recorder.commands("commitTransaction").single()
                commit.finished shouldBe true
                commit.reply shouldBe null
                val stored = f.stored("004-order-status")!!
                stored.getString("state") shouldBe "APPLIED"
                stored.containsKey("lastError") shouldBe false
                f.collection("probes").find().first().getInteger("n") shouldBe 1
                f.recorder.commands("update").filter { it.command.getString("update").value == "godwit-history" }
                    .map { it.isAppliedRecord() } shouldBe listOf(true, false)
            }
        }

        "an outside-only APPLIED record that applies and then times out on the client ends APPLIED too" {
            val held = HeldAcknowledgement { it.isAppliedRecord() }
            GodwitFixture(appName = "retry-record-timeout", recorder = held.recorder, timeout = 2.seconds).use { f ->
                val runs = mutableListOf<Int>()
                val carts = migration("002-carts").outsideTransaction {
                    runs += 1
                    count("collectionsCreated", if (ensureCollection("carts")) 1 else 0)
                }

                val report = f.godwit.migrate(carts)

                report["002-carts"].counts shouldBe mapOf("collectionsCreated" to 1L)
                val (record, again) = f.recorder.commands.filter { it.isAppliedRecord() }
                record.finished shouldBe true
                record.reply shouldBe null
                again.reply!!.getInt32("n").value shouldBe 0
                f.stored("002-carts")!!.getString("state") shouldBe "APPLIED"
                f.godwit.migrate(carts).ran.shouldBeEmpty()
                runs shouldBe listOf(1)
            }
        }

        "withTransaction does not retry a client-side timeout: the step fails with MongoOperationTimeoutException" {
            GodwitFixture(appName = "retry-timeout-not-retried", timeout = 500.milliseconds).use { f ->
                val attempts = mutableListOf<Int>()
                val blocked = migration("004-order-status").inTransaction {
                    attempts += attempt
                    collection("orders").insertOne(session, Document("status", "PAID"))
                }
                val block = Document("blockConnection", true).append("blockTimeMS", 1000)

                val failure = fail(f.appName, listOf("insert"), 1, block).use {
                    shouldThrow<MigrationFailedException> { f.godwit.migrate(blocked) }
                }

                attempts shouldBe listOf(1)
                failure.cause.shouldBeInstanceOf<MongoOperationTimeoutException>()
                f.stored("004-order-status")!!.getString("state") shouldBe "FAILED"
            }
        }
    }
}
