package godwit.core.docs

import ch.qos.logback.classic.spi.ILoggingEvent
import godwit.core.fixtures.LogCapture
import godwit.core.fixtures.keyValues
import org.bson.BsonDocument
import java.util.concurrent.ConcurrentHashMap

/**
 * What a scenario produced, each output under a name: the log events of a process or a call, exceptions, history and
 * lock documents as stored, and other printed lines.
 */
class Produced {
    private val logs = ConcurrentHashMap<String, List<ILoggingEvent>>()
    private val errors = ConcurrentHashMap<String, Throwable>()
    private val documents = ConcurrentHashMap<String, BsonDocument>()
    private val texts = ConcurrentHashMap<String, List<String>>()

    fun logs(name: String): List<ILoggingEvent> = logs[name] ?: error("the scenario recorded no log stream $name")

    fun exception(name: String): Throwable = errors[name] ?: error("the scenario recorded no exception $name")

    fun document(name: String): BsonDocument = documents[name] ?: error("the scenario recorded no document $name")

    fun text(name: String): List<String> = texts[name] ?: error("the scenario recorded no text $name")

    fun logs(name: String, events: List<ILoggingEvent>) {
        logs[name] = events
    }

    fun exception(name: String, thrown: Throwable) {
        errors[name] = thrown
    }

    fun document(name: String, document: BsonDocument?) {
        documents[name] = document ?: error("no document to record as $name")
    }

    fun text(name: String, lines: List<String>) {
        texts[name] = lines
    }

    /** Runs [block] and records every godwit log event it caused as [name]. */
    fun <T> logging(name: String, block: () -> T): T = LogCapture().use { capture ->
        try {
            block()
        } finally {
            logs(name, capture.events)
        }
    }

    /** Runs [block], records the exception it throws as [name], and the log events as [name] too. */
    fun failing(name: String, block: () -> Unit) {
        val thrown = runCatching { logging(name, block) }.exceptionOrNull()
            ?: error("$name was expected to throw, and returned")
        exception(name, thrown)
    }
}

/**
 * The scenario behind one or more quoted outputs. It runs once, when a quote first needs it, and every quote reads
 * what it produced; a scenario that fails fails every quote that reads it, with its exception. The databases it
 * created on [DocsMongo] are dropped once it has run.
 */
class Scenario(val name: String, private val run: Produced.() -> Unit) {
    private val result: Result<Produced> by lazy {
        val before = DocsMongo.databases()
        runCatching { Produced().apply(run) }.also { DocsMongo.drop(DocsMongo.databases() - before) }
    }

    val produced: Produced get() = result.getOrThrow()

    override fun toString() = name
}

/**
 * The events of one process among [this], when several processes ran on threads of their own: the events logged on
 * [thread], and those of the heartbeat threads of the lock acquisitions made there, named after the run id.
 */
fun List<ILoggingEvent>.ofThread(thread: String): List<ILoggingEvent> {
    val runIds = filter { it.threadName == thread && it.message == "Acquired migration lock" }
        .map { "godwit-heartbeat-${it.keyValues["runId"]}" }
        .toSet()
    return filter { it.threadName == thread || it.threadName in runIds }
}
