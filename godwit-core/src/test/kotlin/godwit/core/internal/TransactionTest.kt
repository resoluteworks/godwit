package godwit.core.internal

import com.mongodb.MongoCommandException
import com.mongodb.MongoException
import com.mongodb.MongoSocketReadException
import com.mongodb.MongoWriteException
import com.mongodb.ServerAddress
import com.mongodb.WriteError
import com.mongodb.kotlin.client.ClientSession
import godwit.core.fixtures.LogCapture
import godwit.core.fixtures.keyValues
import godwit.core.fixtures.line
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import org.bson.BsonDocument
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** A random source whose doubles are always [value]: the jitter at one end of its range. */
private class FixedRandom(private val value: Double) : Random() {
    override fun nextBits(bitCount: Int): Int = 0

    override fun nextDouble(): Double = value
}

/** A server error as the driver reports it, with [labels]. */
private fun serverError(code: Int, codeName: String, vararg labels: String): MongoCommandException {
    val labelled = labels.joinToString(", ", prefix = "[", postfix = "]") { "\"$it\"" }
    val response = """{"ok": 0, "code": $code, "codeName": "$codeName", "errmsg": "x", "errorLabels": $labelled}"""
    return MongoCommandException(BsonDocument.parse(response), ServerAddress())
}

/** What the driver's withTransaction does to the body, one entry per run: the body throws, or the commit fails. */
private sealed interface Driver {
    /** Runs the body once; the error it throws, if any, is what the driver retries after. */
    data object Body : Driver

    /** Runs the body once; it returns, and the commit fails with a transient error, so the driver runs it again. */
    data object CommitFails : Driver
}

/**
 * A session whose withTransaction behaves as [script] says, run by run, like the driver: it runs the body again after
 * a transient error from the body, or after a transient error on the commit; the last run's value is returned.
 */
private fun scriptedSession(vararg script: Driver): ClientSession {
    val session = mockk<ClientSession>()
    every { session.withTransaction(any<() -> Any>(), any()) } answers {
        val body = firstArg<() -> Any>()
        var result: Any? = null
        for (run in script) {
            result = try {
                body()
            } catch (e: MongoException) {
                if (!e.hasErrorLabel("TransientTransactionError")) throw e
                continue
            }
            if (run == Driver.CommitFails) continue
        }
        result!!
    }
    return session
}

class TransactionTest : StringSpec() {
    init {
        "the pause before a run grows from 5 ms by half each time to at most 500 ms, between half and all of it" {
            transactionPause(2, FixedRandom(0.0)) shouldBe 2.5.milliseconds
            transactionPause(2, FixedRandom(0.5)) shouldBe 3.75.milliseconds
            transactionPause(3, FixedRandom(1.0)) shouldBe 7.5.milliseconds
            transactionPause(4, FixedRandom(1.0)) shouldBe 11.25.milliseconds
            transactionPause(13, FixedRandom(1.0)) shouldBe (5 * 1.5.pow(11)).milliseconds
            transactionPause(14, FixedRandom(1.0)) shouldBe 500.milliseconds
            transactionPause(100, FixedRandom(0.0)) shouldBe 250.milliseconds
        }

        "a retry names the body's error, or commit; it is logged first, then at most once per interval, and counted" {
            val conflict = serverError(112, "WriteConflict", "TransientTransactionError")
            val pauses = mutableListOf<Duration>()
            val tuning =
                Tuning(retryLogInterval = Duration.INFINITE, sleep = { pauses += it }, random = FixedRandom(1.0))
            val transaction = Transaction("004-order-status", Duration.INFINITE, tuning)
            val session = scriptedSession(Driver.Body, Driver.CommitFails, Driver.Body)
            val attempts = mutableListOf<Int>()

            LogCapture().use { logs ->
                val result = transaction.run(session) { attempt ->
                    attempts += attempt
                    if (attempt == 1) throw conflict
                    "committed by $attempt"
                }

                result shouldBe "committed by 3"
                attempts shouldBe listOf(1, 2, 3)
                transaction.attempts shouldBe 3
                transaction.retries shouldBe 2
                pauses shouldBe listOf(5.milliseconds, 7.5.milliseconds)
                logs.events.map { it.line } shouldBe
                    listOf("Retrying transaction id=004-order-status attempt=2 error=WriteConflict (112)")
                logs.events.single().level.toString() shouldBe "WARN"
            }

            val everyRetry =
                Transaction("004-order-status", Duration.INFINITE, tuning.copy(retryLogInterval = Duration.ZERO))
            LogCapture().use { logs ->
                everyRetry.run(scriptedSession(Driver.Body, Driver.CommitFails, Driver.Body)) { attempt ->
                    if (attempt == 1) throw conflict
                }
                logs.events.map { it.keyValues["error"] } shouldBe listOf("WriteConflict (112)", "commit")
                logs.events.map { it.keyValues["attempt"] } shouldBe listOf(2, 3)
            }
        }

        "every run slower than the warning logs Slow transaction, and the longest run is kept for the guidance" {
            val transaction = Transaction("007-customer-email-lower", Duration.ZERO, Tuning(sleep = {}))
            val conflict = serverError(112, "WriteConflict", "TransientTransactionError")

            LogCapture().use { logs ->
                transaction.run(scriptedSession(Driver.Body, Driver.Body)) { attempt ->
                    Thread.sleep(5)
                    if (attempt == 1) throw conflict
                }

                logs.events("Slow transaction").map { it.keyValues["attempt"] } shouldBe listOf(1, 2)
                logs.events("Slow transaction").first().keyValues.keys.toList() shouldBe
                    listOf("id", "attempt", "durationMs")
                transaction.longestAttempt shouldBeGreaterThan 4.milliseconds
            }
            LogCapture().use { logs ->
                Transaction("004-order-status", Duration.INFINITE, Tuning()).run(scriptedSession(Driver.Body)) { }
                logs.events.shouldBeEmpty()
            }
        }

        "an error the driver does not retry propagates from run as the body threw it" {
            val fatal = serverError(263, "OperationNotSupportedInTransaction")
            val transaction = Transaction("008-index", Duration.INFINITE, Tuning())

            shouldThrow<MongoCommandException> {
                transaction.run(scriptedSession(Driver.Body)) { throw fatal }
            } shouldBeSameInstanceAs fatal
            transaction.retries shouldBe 0
            Transaction("x", Duration.INFINITE, Tuning()).retries shouldBe 0
        }

        "a retry is named by the server's code name and code, or by the exception's class and code" {
            errorName(serverError(112, "WriteConflict")) shouldBe "WriteConflict (112)"
            errorName(serverError(112, "")) shouldBe "MongoCommandException (112)"
            errorName(MongoSocketReadException("closed", ServerAddress())) shouldBe "MongoSocketReadException (-2)"
            errorName(
                MongoWriteException(WriteError(112, "conflict", BsonDocument()), ServerAddress(), emptySet())
            ) shouldBe
                "MongoWriteException (112)"
        }
    }
}
