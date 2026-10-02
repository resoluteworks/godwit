package godwit.core.internal

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.TimeSource

/** Why a run gave up the lock, the `reason` of "Lost migration lock". */
internal enum class LossReason {
    /** A renewal matched no lock document with this run's owner token: another run holds it, or it was deleted. */
    NOT_OWNER,

    /**
     * No renewal succeeded within `lease - safetyMargin` of the last successful one being sent, or of the acquire being
     * sent when none has succeeded yet.
     */
    DEADLINE_PASSED
}

/**
 * Keeps one acquisition of the lock alive and judges, without I/O, whether it still holds.
 *
 * A single daemon thread calls [tick] every [interval]. Each tick checks the local deadline first and, once it has
 * passed, stops without renewing: a renewal sent after a long pause could extend a lease the run has already given
 * up. Otherwise it sends one renewal ([renew]), recording when it was sent; a renewal that matches the lock document
 * moves the deadline to that time plus [window] (`lease - safetyMargin`), unless the deadline passed while the renewal
 * was in flight. A renewal that matches nothing marks the lock lost (`NOT_OWNER`); one that throws logs "Lock renewal
 * failed" and the next tick tries again, the deadline deciding. Once lost, the lock stays lost: "Lost migration lock"
 * is logged once, by the heartbeat thread or by the caller of [isLost], whichever notices first.
 *
 * The deadline and the lost flag are read by the run's thread and written by the heartbeat thread, so both are safe
 * to share. The heartbeat never touches the run's session; [renew] issues its own operation.
 */
internal class Heartbeat(
    private val interval: Duration,
    private val window: Duration,
    /** When the acquire that took the lock was sent: the first deadline counts from it. */
    acquireSent: ComparableTimeMark,
    private val timeSource: TimeSource.WithComparableMarks,
    private val runId: String,
    private val holder: String,
    /** Sends one renewal; true when it matched the lock document with this run's owner token. */
    private val renew: () -> Boolean
) {
    @Volatile
    private var deadline: ComparableTimeMark = acquireSent + window

    private val lost = AtomicReference<LossReason?>(null)

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "godwit-heartbeat-$runId").apply { isDaemon = true }
    }

    /** Starts the heartbeat thread: the first renewal goes out one [interval] from now. */
    fun start() {
        val nanos = interval.inWholeNanoseconds
        executor.scheduleAtFixedRate({ if (!tick()) executor.shutdown() }, nanos, nanos, TimeUnit.NANOSECONDS)
    }

    /**
     * Stops the heartbeat and waits for a renewal in flight to return, so that the release is the last lock write the
     * run sends. The lock operation timeout bounds that wait. A renewal that still reaches the server after the release
     * (the client gave up on it, and the network delivered it late) matches nothing: the renewal is fenced on
     * `releasedAt` being absent.
     */
    fun stop() {
        executor.shutdown()
        executor.awaitTermination(2 * LOCK_OPERATION_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    }

    /** True once the lock is lost or the local deadline has passed; the first caller to see it logs it. */
    fun isLost(): Boolean {
        if (lost.get() == null && deadline.hasPassedNow()) markLost(LossReason.DEADLINE_PASSED)
        return lost.get() != null
    }

    /**
     * One heartbeat: checks the deadline, then renews. Returns false when the lock is lost and the heartbeat should
     * stop. Catches every [Throwable], because an exception escaping a scheduled task cancels every later run of it.
     */
    internal fun tick(): Boolean = try {
        if (isLost()) {
            false
        } else {
            val sent = timeSource.markNow()
            if (renew()) {
                renewed(sent)
            } else {
                markLost(LossReason.NOT_OWNER)
                false
            }
        }
    } catch (e: Throwable) {
        Log.lockRenewalFailed(runId, holder, e)
        true
    }

    /** A renewal sent at [sent] matched. When the deadline passed while it was in flight, the lock stays lost. */
    private fun renewed(sent: ComparableTimeMark): Boolean {
        if (isLost()) return false
        deadline = sent + window
        return true
    }

    private fun markLost(reason: LossReason) {
        if (lost.compareAndSet(null, reason)) Log.lostMigrationLock(runId, holder, reason)
    }
}
