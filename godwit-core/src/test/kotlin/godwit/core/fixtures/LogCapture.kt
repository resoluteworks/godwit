package godwit.core.fixtures

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/**
 * Records every event logged to the `godwit` logger while it is open, from any thread. Tests assert on godwit's log
 * lines through it.
 */
class LogCapture : AutoCloseable {
    private val logger = LoggerFactory.getLogger("godwit") as Logger
    private val appender = ListAppender<ILoggingEvent>().apply { start() }

    init {
        logger.addAppender(appender)
    }

    /** Every event so far, in the order they were logged. */
    val events: List<ILoggingEvent>
        get() = synchronized(appender) { appender.list.toList() }

    /** The events whose message is [message]. */
    fun events(message: String): List<ILoggingEvent> = events.filter { it.message == message }

    override fun close() {
        logger.detachAppender(appender)
        appender.stop()
    }
}

/** The event's key-value pairs, in the order they were added. */
val ILoggingEvent.keyValues: Map<String, Any?>
    get() = (keyValuePairs ?: emptyList()).associate { it.key to it.value }

/** The event as the docs print it: the message, then each `key=value`. */
val ILoggingEvent.line: String
    get() = (listOf(message) + keyValues.map { (key, value) -> "$key=$value" }).joinToString(" ")
