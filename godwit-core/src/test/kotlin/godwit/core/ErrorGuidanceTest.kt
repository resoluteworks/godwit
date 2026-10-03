package godwit.core

import com.mongodb.MongoCommandException
import com.mongodb.MongoSocketReadException
import com.mongodb.MongoWriteException
import com.mongodb.ServerAddress
import com.mongodb.WriteError
import com.mongodb.client.model.Indexes.ascending
import godwit.core.fixtures.GodwitFixture
import godwit.core.fixtures.TestMongo
import godwit.core.fixtures.probe
import godwit.core.fixtures.seeded
import godwit.core.internal.DDL_GUIDANCE
import godwit.core.internal.LIFETIME_GUIDANCE
import godwit.core.internal.OTHER_CLIENT_GUIDANCE
import godwit.core.internal.PAGE_LIFETIME_GUIDANCE
import godwit.core.internal.PAGE_TOO_LARGE_GUIDANCE
import godwit.core.internal.TOO_LARGE_GUIDANCE
import godwit.core.internal.Tuning
import godwit.core.internal.guidance
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.bson.BsonDocument
import org.bson.Document
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger(ErrorGuidanceTest::class.java)

private fun serverError(code: Int, codeName: String, message: String = "x") = MongoCommandException(
    BsonDocument.parse("""{"ok": 0, "code": $code, "codeName": "$codeName", "errmsg": "$message"}"""),
    ServerAddress()
)

/** The server's refusal of `createIndexes` in one of godwit's snapshot transactions, as MongoDB 8.0 words it. */
private val readConcernRefusal = serverError(
    72,
    "InvalidOptions",
    "Command createIndexes does not support this transaction's { readConcern: { level: \\\"snapshot\\\" } } :: " +
        "caused by :: read concern not supported"
)

/**
 * A server error on the first `insert` of the client named [appName], without the labels the server would attach,
 * so the driver does not run the body again.
 */
private fun failInsert(appName: String, code: Int): AutoCloseable = TestMongo.failCommand(
    appName,
    listOf("insert"),
    Document("times", 1),
    Document("errorCode", code).append("errorLabels", emptyList<String>())
)

/** The guidance line [MigrationFailedException] adds for the causes godwit recognises. */
class ErrorGuidanceTest : StringSpec() {
    init {
        "each recognised cause gets its guidance line, from the cause or from one of its own causes" {
            val long = 61.seconds
            val threshold = 60.seconds
            val step = StepKind.IN_TRANSACTION
            guidance(serverError(251, "NoSuchTransaction"), step, long, threshold) shouldBe LIFETIME_GUIDANCE
            guidance(serverError(290, "TransactionExceededLifetimeLimitSeconds"), step, 60.seconds, threshold) shouldBe
                LIFETIME_GUIDANCE
            guidance(serverError(388, "TransactionTooLargeForCache"), step, Duration.ZERO, threshold) shouldBe
                TOO_LARGE_GUIDANCE
            guidance(serverError(263, "OperationNotSupportedInTransaction"), step, Duration.ZERO, threshold) shouldBe
                DDL_GUIDANCE
            guidance(readConcernRefusal, step, Duration.ZERO, threshold) shouldBe DDL_GUIDANCE
            val otherClient = IllegalStateException("state should be: ClientSession from same MongoClient")
            guidance(otherClient, step, Duration.ZERO, threshold) shouldBe OTHER_CLIENT_GUIDANCE
            val wrapped = RuntimeException("the service failed", serverError(388, "TransactionTooLargeForCache"))
            guidance(wrapped, step, Duration.ZERO, threshold) shouldBe TOO_LARGE_GUIDANCE
        }

        "an inBatches page past its lifetime or too large gets the page lines, which lower batchSize" {
            val threshold = 60.seconds
            val pages = StepKind.IN_BATCHES
            guidance(serverError(251, "NoSuchTransaction"), pages, 61.seconds, threshold) shouldBe
                PAGE_LIFETIME_GUIDANCE
            guidance(serverError(290, "TransactionExceededLifetimeLimitSeconds"), pages, 60.seconds, threshold) shouldBe
                PAGE_LIFETIME_GUIDANCE
            guidance(serverError(251, "NoSuchTransaction"), pages, 59.seconds, threshold).shouldBeNull()
            guidance(serverError(388, "TransactionTooLargeForCache"), pages, Duration.ZERO, threshold) shouldBe
                PAGE_TOO_LARGE_GUIDANCE
            guidance(serverError(263, "OperationNotSupportedInTransaction"), pages, Duration.ZERO, threshold) shouldBe
                DDL_GUIDANCE
            for (step in listOf(StepKind.OUTSIDE_TRANSACTION, StepKind.IN_TRANSACTION)) {
                withClue(step) {
                    guidance(serverError(388, "TransactionTooLargeForCache"), step, Duration.ZERO, threshold) shouldBe
                        TOO_LARGE_GUIDANCE
                }
            }
        }

        "a cause godwit does not recognise gets no guidance" {
            val threshold = 60.seconds
            val unrecognised = listOf(
                serverError(251, "NoSuchTransaction") to 59.seconds,
                serverError(72, "InvalidOptions", "BSON field 'unique' is an unknown field") to Duration.ZERO,
                serverError(112, "WriteConflict") to Duration.ZERO,
                MongoSocketReadException("closed", ServerAddress()) to Duration.ZERO,
                IllegalStateException("Transaction already in progress") to Duration.ZERO,
                IllegalStateException() to Duration.ZERO,
                IllegalArgumentException("ClientSession from same MongoClient") to Duration.ZERO
            )
            unrecognised.forEachIndexed { index, (error, longest) ->
                withClue("case $index: $error") {
                    guidance(error, StepKind.IN_TRANSACTION, longest, threshold).shouldBeNull()
                }
            }
            val writeError = MongoWriteException(WriteError(72, "invalid", BsonDocument()), ServerAddress(), emptySet())
            guidance(writeError, StepKind.IN_TRANSACTION, Duration.ZERO, threshold).shouldBeNull()
        }

        "godwit looks eight causes deep for one it recognises" {
            fun wrapped(times: Int): Throwable =
                (1..times).fold<Int, Throwable>(serverError(388, "TransactionTooLargeForCache")) { cause, n ->
                    RuntimeException("wrapper $n", cause)
                }

            guidance(wrapped(7), StepKind.IN_TRANSACTION, Duration.ZERO, 1.minutes) shouldBe TOO_LARGE_GUIDANCE
            guidance(wrapped(8), StepKind.IN_TRANSACTION, Duration.ZERO, 1.minutes).shouldBeNull()
        }

        "251 and 290 after an attempt at least as long as the lifetime threshold get the lifetime guidance" {
            for ((code, name) in listOf(251 to "NoSuchTransaction", 290 to "TransactionExceededLifetimeLimitSeconds")) {
                withClue(name) {
                    GodwitFixture(
                        appName = "guidance-lifetime-$code",
                        tuning = Tuning(lifetimeGuidanceAfter = 200.milliseconds)
                    ).use { f ->
                        val long = migration("007-customer-email-lower").inTransaction {
                            Thread.sleep(250)
                            collection("customers").insertOne(session, Document("email", "a@example.com"))
                        }

                        val failure = failInsert(f.appName, code).use {
                            shouldThrow<MigrationFailedException> { f.godwit.migrate(long) }
                        }

                        failure.cause.shouldBeInstanceOf<MongoCommandException>().code shouldBe code
                        failure.message shouldEndWith "\n$LIFETIME_GUIDANCE"
                    }
                }
            }
        }

        "a page past the lifetime threshold gets the page's guidance, timed on the failed page's own transaction" {
            GodwitFixture(
                appName = "guidance-page-lifetime",
                config = seeded,
                tuning = Tuning(lifetimeGuidanceAfter = 200.milliseconds)
            ).use { f ->
                f.collection("orders").insertMany((1..5).map { Document("_id", it) })
                var failing: AutoCloseable? = null
                // The second page outlasts the threshold, then its first write fails with 251: the first page's
                // transaction was short, so only the second page's can earn the guidance.
                val totals = migration("006-order-totals").inBatches("orders", Document(), batchSize = 2) { page ->
                    if (page.first()["_id"] == 3) {
                        Thread.sleep(250)
                        failing = TestMongo.failCommand(
                            f.appName,
                            listOf("update"),
                            Document("times", 1),
                            Document("errorCode", 251)
                                .append("errorLabels", emptyList<String>())
                                .append("namespace", "${f.db.name}.orders")
                        )
                    }
                    probe("orders", page)
                }

                val failure = try {
                    shouldThrow<MigrationFailedException> { f.godwit.migrate(totals) }
                } finally {
                    failing?.close()
                }

                failure.step shouldBe StepKind.IN_BATCHES
                failure.cause.shouldBeInstanceOf<MongoCommandException>().code shouldBe 251
                failure.message shouldEndWith "\n$PAGE_LIFETIME_GUIDANCE"
                f.stored("006-order-totals")!!.get("checkpoint", Document::class.java).getInteger("batches") shouldBe 1
            }
        }

        "251 after a short attempt gets no guidance" {
            GodwitFixture(appName = "guidance-short").use { f ->
                val short = migration("004-order-status").inTransaction {
                    collection("orders").insertOne(session, Document("status", "PAID"))
                }

                val failure = failInsert(f.appName, 251).use {
                    shouldThrow<MigrationFailedException> { f.godwit.migrate(short) }
                }

                failure.message!!.lines().size shouldBe 1
            }
        }

        "TransactionTooLargeForCache gets the size guidance" {
            GodwitFixture(appName = "guidance-too-large").use { f ->
                val large = migration("006-order-totals").inTransaction {
                    collection("orders").insertOne(session, Document("totalMinor", 1))
                }

                val failure = failInsert(f.appName, 388).use {
                    shouldThrow<MigrationFailedException> { f.godwit.migrate(large) }
                }

                failure.message shouldEndWith "\n$TOO_LARGE_GUIDANCE"
            }
        }

        "a drop in a transaction (263) and an index build on an existing collection get the DDL guidance" {
            GodwitFixture().use { f ->
                val drop = migration("012-drop").outsideTransaction { ensureCollection("orders") }
                    .inTransaction { collection("orders").drop(session) }

                val dropped = shouldThrow<MigrationFailedException> { f.godwit.migrate(drop) }
                dropped.cause.shouldBeInstanceOf<MongoCommandException>().code shouldBe 263
                dropped.message shouldEndWith "\n$DDL_GUIDANCE"
            }
            GodwitFixture().use { f ->
                val index = migration("008-customer-email-lower-index")
                    .outsideTransaction { ensureCollection("customers") }
                    .inTransaction { collection("customers").createIndex(session, ascending("emailLower")) }

                val indexed = shouldThrow<MigrationFailedException> { f.godwit.migrate(index) }
                val refusal = indexed.cause.shouldBeInstanceOf<MongoCommandException>()
                refusal.code shouldBe 72
                refusal.errorMessage shouldStartWith "Command createIndexes does not support this transaction's"
                indexed.message shouldEndWith "\n$DDL_GUIDANCE"
                log.info("index build in a transaction: {}", indexed.message)
            }
        }

        "godwit's session passed to an operation on another client gets the same-client guidance" {
            GodwitFixture().use { f ->
                TestMongo.client().use { other ->
                    val customers = other.getDatabase(f.db.name).getCollection("customers", Document::class.java)
                    val service = migration("bootstrap-customers-once").inTransaction {
                        customers.insertOne(session, Document("email", "seed@example.com"))
                    }

                    val failure = shouldThrow<MigrationFailedException> { f.godwit.migrate(service) }

                    failure.cause.shouldBeInstanceOf<IllegalStateException>().message shouldBe
                        "state should be: ClientSession from same MongoClient"
                    failure.message shouldBe
                        "Migration bootstrap-customers-once failed in IN_TRANSACTION: state should " +
                        "be: ClientSession from same MongoClient\n$OTHER_CLIENT_GUIDANCE"
                }
            }
        }

        "the MigrationFailedException KDoc and architecture.md's Error guidance table quote every guidance line" {
            val kdoc = File("src/main/kotlin/godwit/core/Exceptions.kt").readText()
            val table = File("../docs/architecture.md").readText().substringAfter("\n## Error guidance")
                .substringBefore("\n## ")
            val lines = listOf(
                LIFETIME_GUIDANCE,
                TOO_LARGE_GUIDANCE,
                PAGE_LIFETIME_GUIDANCE,
                PAGE_TOO_LARGE_GUIDANCE,
                DDL_GUIDANCE,
                OTHER_CLIENT_GUIDANCE
            )
            for (line in lines) {
                withClue(line) {
                    kdoc shouldContain " | `$line` |\n"
                    table shouldContain " | `$line` |\n"
                }
            }
        }

        "an unrecognised cause adds no line to the message" {
            GodwitFixture().use { f ->
                val failing = migration("001-failing").outsideTransaction { error("not a known cause") }

                shouldThrow<MigrationFailedException> { f.godwit.migrate(failing) }.message shouldBe
                    "Migration 001-failing failed in OUTSIDE_TRANSACTION: not a known cause"
            }
        }
    }
}
