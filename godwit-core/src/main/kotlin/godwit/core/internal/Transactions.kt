package godwit.core.internal

import com.mongodb.MongoException
import com.mongodb.kotlin.client.ClientSession
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** The pause before the second run of a transaction body; each later one is half as long again. */
private const val FIRST_PAUSE_MS = 5.0

private const val PAUSE_GROWTH = 1.5

private const val MAX_PAUSE_MS = 500.0

/**
 * The pause before run [attempt] (2 or more) of a transaction body: 5 ms before the second, growing by half each time
 * to at most 500 ms, then a random time between half and all of that.
 */
internal fun transactionPause(attempt: Int, random: Random): Duration {
    val step = minOf(FIRST_PAUSE_MS * PAUSE_GROWTH.pow(attempt - 2), MAX_PAUSE_MS)
    return (step * (0.5 + random.nextDouble() / 2)).milliseconds
}

/**
 * One transactional step's transaction, run through the driver's `ClientSession.withTransaction` with godwit's
 * options ([TRANSACTION_OPTIONS]). The driver runs the body again after a `TransientTransactionError`, from the body
 * or from the commit, and retries only the commit after an `UnknownTransactionCommitResult`, for up to 120 s. Driver
 * 5.7.0 starts the next run at once, so the wrapper around the body:
 * - counts the runs ([attempts]; [retries] is every run after the first);
 * - pauses before each run after the first ([transactionPause]);
 * - logs "Retrying transaction" for the first retry, then at most once per [Tuning.retryLogInterval], with `error`
 *   the code name and code of the error the previous run's body threw, or `commit` when that body returned and the
 *   driver ran it again after a transient error on the commit;
 * - times each run, logs "Slow transaction" for a run slower than [slowWarning], and keeps the longest run for the
 *   error guidance ([longestAttempt]).
 */
internal class Transaction(private val id: String, private val slowWarning: Duration, private val tuning: Tuning) {
    /** Runs of the body so far, the current one included. */
    var attempts: Int = 0
        private set

    /** Runs of the body after the first: the driver's retries, which history records as `transactionRetries`. */
    val retries: Int get() = maxOf(attempts - 1, 0)

    /** The longest run of the body so far, from its start until it returned or threw. */
    var longestAttempt: Duration = Duration.ZERO
        private set

    /** The error the last run of the body threw; null when it returned, so a retry was the commit's. */
    private var bodyError: MongoException? = null

    private var lastRetryLogged: ComparableTimeMark? = null

    /** Runs [body] in the transaction on [session], with the number of the run, and returns what the commit kept. */
    fun <T : Any> run(session: ClientSession, body: (attempt: Int) -> T): T =
        session.withTransaction({ attempt(body) }, TRANSACTION_OPTIONS)

    private fun <T : Any> attempt(body: (attempt: Int) -> T): T {
        val attempt = ++attempts
        if (attempt > 1) retrying(attempt)
        bodyError = null
        val started = TimeSource.Monotonic.markNow()
        try {
            return body(attempt)
        } catch (e: MongoException) {
            bodyError = e
            throw e
        } finally {
            val took = started.elapsedNow()
            longestAttempt = maxOf(longestAttempt, took)
            if (took > slowWarning) Log.slowTransaction(id, attempt, took.inWholeMilliseconds)
        }
    }

    private fun retrying(attempt: Int) {
        val logged = lastRetryLogged
        if (logged == null || logged.elapsedNow() >= tuning.retryLogInterval) {
            Log.retryingTransaction(id, attempt, bodyError?.let(::errorName) ?: "commit")
            lastRetryLogged = TimeSource.Monotonic.markNow()
        }
        tuning.sleep(transactionPause(attempt, tuning.random))
    }
}
