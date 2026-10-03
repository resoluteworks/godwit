package godwit.core.docs

import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import com.mongodb.event.CommandSucceededEvent
import godwit.core.fixtures.LogCapture
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import org.awaitility.kotlin.withPollInterval
import org.bson.BsonDocument
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/** Waits until [capture] holds at least [count] events with [message], for up to [timeout]. */
fun LogCapture.awaitLogged(message: String, count: Int = 1, timeout: Duration = 2.minutes) {
    await atMost timeout.toJavaDuration() withPollInterval 20.milliseconds.toJavaDuration() until
        { events(message).size >= count }
}

/** A point a scenario's process stops at until the scenario opens it. */
class Gate {
    private val latch = CountDownLatch(1)

    fun open() = latch.countDown()

    /** Blocks the calling thread until [open], for up to 5 minutes. */
    fun pass() = check(latch.await(5, TimeUnit.MINUTES)) { "the gate was never opened" }
}

/**
 * Runs [action] once, for the [nth] command that [matches] (counting from 1), on the thread that sends it: before the
 * command is sent, or with [onSucceeded] after its reply. The sync driver calls listeners on that thread, so the run
 * waits while [action] runs: how a scenario acts at an exact point of a run whose code it does not change, such as
 * the page of a backfill that a network partition cuts off.
 */
class AtCommand(
    private val nth: Int = 1,
    private val onSucceeded: Boolean = false,
    private val matches: (name: String, command: BsonDocument) -> Boolean,
    private val action: () -> Unit
) : CommandListener {
    private val seen = AtomicInteger()
    private val fired = AtomicBoolean()
    private val awaitingReply = ConcurrentHashMap.newKeySet<Int>()

    override fun commandStarted(event: CommandStartedEvent) {
        if (!matches(event.commandName, event.command)) return
        if (seen.incrementAndGet() != nth) return
        if (onSucceeded) {
            awaitingReply += event.requestId
        } else if (fired.compareAndSet(false, true)) {
            action()
        }
    }

    override fun commandSucceeded(event: CommandSucceededEvent) {
        if (awaitingReply.remove(event.requestId) && fired.compareAndSet(false, true)) action()
    }
}

/** A command that reads a page of an `inBatches` step of [id]: the find that opens the page's transaction. */
fun isPageRead(collection: String, id: String): (String, BsonDocument) -> Boolean = { name, command ->
    name == "find" && command.getString("find", null)?.value == collection &&
        command.getDocument("comment", null)?.getString("godwit", null)?.value == id
}

/** Runs [block] on a new thread named [name] and returns the thread; its exception, if any, is in [failures]. */
fun background(name: String, failures: MutableMap<String, Throwable>, block: () -> Unit): Thread = thread(name = name) {
    try {
        block()
    } catch (e: Throwable) {
        failures[name] = e
    }
}

/** Waits for [thread] to end, for up to 5 minutes. */
fun Thread.awaitEnd() {
    join(TimeUnit.MINUTES.toMillis(5))
    check(!isAlive) { "$name did not end" }
}
