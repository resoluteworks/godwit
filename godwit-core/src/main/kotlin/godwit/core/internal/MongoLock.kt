package godwit.core.internal

import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import godwit.core.LockConfig
import godwit.core.LockHolder
import godwit.core.LockLostException
import godwit.core.LockTimeoutException
import org.bson.Document
import java.util.UUID
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

/** The first pause between two acquire attempts; each later one doubles, up to [MAX_POLL]. */
private val FIRST_POLL = 250.milliseconds

private val MAX_POLL = 5.seconds

/** How often a waiting process logs the holder. */
private val WAIT_LOG_INTERVAL = 10.seconds

/**
 * The migration lock: one leased document in the lock collection, `_id` the history collection's name, judged by the
 * server's clock (`$$NOW`) and fenced by an owner token per acquisition.
 *
 * [acquire] polls until it holds the lock or [LockConfig.waitTimeout] passes; the [HeldLock] it returns renews the
 * lease from a [Heartbeat] thread, answers [HeldLock.checkLock] without I/O and releases the lock. Every operation has
 * the lock collection's 5 s client-side timeout; driver exceptions other than a duplicate key on the acquire, which
 * means "held", propagate unchanged, and [acquire] does not poll again after one.
 *
 * [timeSource], [sleep] and [random] are the monotonic clock, the pause between attempts and the jitter; a test
 * replaces them to wait in virtual time.
 */
internal class MongoLock(
    bookkeeping: Bookkeeping,
    private val config: LockConfig,
    private val holder: String,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    private val sleep: (Duration) -> Unit = { Thread.sleep(it.toJavaDuration()) },
    private val random: Random = Random.Default
) {
    private val collection = bookkeeping.lock

    private val lockId = bookkeeping.lockId

    /**
     * Takes the lock for the call [runId], with a new owner token, and starts its heartbeat. While another run holds
     * it, sleeps 250 ms, doubling up to 5 s, each pause a random time between half and all of that step; logs "Waiting
     * for migration lock" with the holder every 10 s; and throws [LockTimeoutException] once [LockConfig.waitTimeout]
     * has passed, after a last attempt. [Duration.ZERO] fails at the first refusal. Logs "Acquired migration lock".
     */
    fun acquire(runId: String): HeldLock {
        val owner = UUID.randomUUID().toString()
        val start = timeSource.markNow()
        var nextLog = start + WAIT_LOG_INTERVAL
        var attempt = 0
        while (true) {
            val sent = timeSource.markNow()
            if (tryAcquire(owner, runId)) {
                val lockWait = start.elapsedNow()
                Log.acquiredMigrationLock(runId, lockWait.inWholeMilliseconds)
                val window = config.lease - config.safetyMargin
                val heartbeat = Heartbeat(config.heartbeat, window, sent, timeSource, runId, holder) { renew(owner) }
                heartbeat.start()
                return HeldLock(owner, runId, lockWait, holder, heartbeat) { release(owner) }
            }
            val waited = start.elapsedNow()
            if (waited >= config.waitTimeout) throw LockTimeoutException(readLease()?.toLockHolder(), waited)
            if (nextLog.hasPassedNow()) {
                readLease()?.let { lease ->
                    Log.waitingForMigrationLock(
                        lease.getString("holder"),
                        lease.getString("runId"),
                        lease.getDate("expiresAt").toInstant(),
                        waited.inWholeMilliseconds
                    )
                }
                nextLog += WAIT_LOG_INTERVAL
            }
            sleep(minOf(pause(attempt++), config.waitTimeout - waited))
        }
    }

    /**
     * One acquire attempt: an upsert on the lock document's `_id` whose pipeline takes the lock when it is expired,
     * released or missing (a missing `expiresAt` counts as expired), and otherwise leaves the document as it is. True
     * when the returned document carries [owner]: another owner means another run holds the lock. The server retries
     * an upsert on `_id` that hits a duplicate key, so two processes racing on a missing document see the winner's
     * document; a duplicate key that reaches godwit all the same also means held.
     */
    internal fun tryAcquire(owner: String, runId: String): Boolean {
        val free = Document("\$lte", listOf("\$expiresAt", "\$\$NOW"))
        val taken = Document("_id", "\$_id")
            .append("owner", literal(owner))
            .append("holder", literal(holder))
            .append("runId", literal(runId))
            .append("acquiredAt", "\$\$NOW")
            .append("refreshedAt", "\$\$NOW")
            .append("expiresAt", expiresIn(config.lease))
        val replace = Document("\$replaceWith", Document("\$cond", listOf(free, taken, "\$\$ROOT")))
        val after = nullOnDuplicateKey {
            collection.findOneAndUpdate(
                Document("_id", lockId),
                listOf(replace),
                FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER)
            )
        }
        return after?.getString("owner") == owner
    }

    /**
     * Moves the lease of [owner] to [LockConfig.lease] from now; false when no lock document carries [owner], or when
     * [owner] has released it. The release keeps `owner`, so the renewal is fenced on `releasedAt` being absent too: a
     * renewal the network delivers after the release, such as one the client gave up on, matches nothing instead of
     * holding a released lock for another lease. An acquire replaces the whole document, which drops `releasedAt`.
     */
    internal fun renew(owner: String): Boolean {
        val lease = Document("refreshedAt", "\$\$NOW").append("expiresAt", expiresIn(config.lease))
        val held = owned(owner).append("releasedAt", Document("\$exists", false))
        return collection.updateOne(held, listOf(Document("\$set", lease))).matchedCount == 1L
    }

    /** Ends the lease of [owner] now. Changes nothing when another run owns the lock. */
    internal fun release(owner: String) {
        val ended = Document("expiresAt", "\$\$NOW").append("releasedAt", "\$\$NOW")
        collection.updateOne(owned(owner), listOf(Document("\$set", ended)))
    }

    /**
     * The lock document while its lease lasts, by the server's clock; null when it is missing, has ended, or lacks one
     * of the fields a waiting process reads, with its type: a document godwit did not write, such as one planted by
     * hand, names no holder.
     */
    private fun readLease(): Document? {
        val live = Document("\$gt", listOf("\$expiresAt", "\$\$NOW"))
        val filter = Document("_id", lockId)
            .append("holder", Document("\$type", "string"))
            .append("runId", Document("\$type", "string"))
            .append("acquiredAt", Document("\$type", "date"))
            .append("expiresAt", Document("\$type", "date"))
            .append("\$expr", live)
        return collection.find(filter).firstOrNull()
    }

    /** `{_id, owner}`: the fence of the release, and with `releasedAt` absent, of the renewal. */
    private fun owned(owner: String) = Document("_id", lockId).append("owner", owner)

    private fun Document.toLockHolder() = LockHolder(
        getString("holder"),
        getString("runId"),
        getDate("acquiredAt").toInstant(),
        getDate("expiresAt").toInstant()
    )

    private fun expiresIn(lease: Duration) = Document("\$add", listOf("\$\$NOW", lease.inWholeMilliseconds))

    /** The pause after the refusal numbered [attempt] (from 0): 250 ms doubling up to 5 s, then jittered. */
    private fun pause(attempt: Int): Duration {
        val step = minOf(FIRST_POLL.inWholeMilliseconds shl minOf(attempt, 5), MAX_POLL.inWholeMilliseconds)
        return (step * (0.5 + random.nextDouble() / 2)).milliseconds
    }
}

/** One acquisition of the lock, held until [release]. */
internal class HeldLock(
    /** The token that fences this run's history writes. */
    val owner: String,
    val runId: String,
    /** How long the acquire waited. */
    val lockWait: Duration,
    private val holder: String,
    private val heartbeat: Heartbeat,
    /** Sends the release write, fenced on [owner]. */
    private val endLease: () -> Unit
) {
    /** True once the lock is lost or its local deadline has passed. No I/O. */
    fun isLost(): Boolean = heartbeat.isLost()

    /** Throws [LockLostException] naming the migration [id] (null outside a migration) once [isLost]. No I/O. */
    fun checkLock(id: String?) {
        if (heartbeat.isLost()) throw LockLostException(id)
    }

    /**
     * Stops the heartbeat, then ends the lease, fenced on the owner token: a lock another run has taken is left alone.
     * A release that throws, or that is interrupted while the heartbeat stops, logs "Lock release failed" and is not
     * retried; the lease then ends on its own. An interrupt stays set on the thread.
     */
    fun release() {
        try {
            heartbeat.stop()
            endLease()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.lockReleaseFailed(runId, holder, e)
        } catch (e: Exception) {
            Log.lockReleaseFailed(runId, holder, e)
        }
    }
}
