package godwit.core.docs

import ch.qos.logback.classic.spi.ILoggingEvent
import godwit.core.InvalidMigrationsException
import godwit.core.PlanConflictException
import godwit.core.fixtures.keyValues
import org.bson.BsonDocument
import java.io.File

/** Where the scenario behind a quoted output runs. */
enum class Runner {
    /** DocsFidelityTest, in godwit-core's `test` task. */
    CORE,

    /** DocsFidelityAtlasTest, in godwit-core's `atlasTest` task: the scenario needs Atlas Search. */
    ATLAS,

    /** godwit-test's DocsFidelityTest: the scenario needs the test kit's `SessionEscapeDetector`. */
    TEST_KIT
}

/** How a quoted block or inline span is checked. */
sealed interface Check

/** Not an output of godwit: a command, a build file, a sketch of a rejected design. [reason] says what it is. */
class NotOutput(val reason: String) : Check

/**
 * A compiler error the docs show after a block marked "This does not compile:". The `neg/` snippet that holds the
 * block expects it in full on its first line, and scripts/neg-check.sh proves the compiler prints it.
 */
data object CompilerError : Check

/**
 * An output of [scenario], run by [runner]: [verify] gets the quoted text, as lines, and what the scenario produced,
 * and throws [AssertionError] when they differ. A [Runner.TEST_KIT] check has no scenario here: godwit-test's spec
 * holds it.
 */
class Output(
    val runner: Runner,
    val scenario: Scenario?,
    val verify: (quoted: List<String>, produced: Produced) -> Unit
) : Check

private fun fail(message: String): Nothing = throw AssertionError(message)

/** Log lines of the stream [stream] of [scenario]: every quoted line, in order, or with [exact] the whole stream. */
fun logs(scenario: Scenario, stream: String, exact: Boolean = false, runner: Runner = Runner.CORE) =
    Output(runner, scenario) { quoted, produced ->
        logMismatch(quoted, produced.logs(stream), exact)?.let(::fail)
    }

/** The head of the printout of the exception [name] of [scenario]: its class and message, then its causes. */
fun exception(scenario: Scenario, name: String, runner: Runner = Runner.CORE) =
    Output(runner, scenario) { quoted, produced ->
        exceptionMismatch(quoted, produced.exception(name))?.let(::fail)
    }

/** Log lines of [name], then the head of the printout of the exception [name]: the block shows both, in that order. */
fun logsAndException(scenario: Scenario, name: String) = Output(Runner.CORE, scenario) { quoted, produced ->
    val logLines = quoted.takeWhile(::isLogLine)
    logMismatch(logLines, produced.logs(name), exact = false)?.let(::fail)
    exceptionMismatch(quoted.drop(logLines.size), produced.exception(name))?.let(::fail)
}

/** The message of the exception [name], without its class. */
fun message(scenario: Scenario, name: String) = Output(Runner.CORE, scenario) { quoted, produced ->
    val actual = produced.exception(name).message.orEmpty()
    if (!quotes(quoted.joinToString("\n"), actual)) fail("quoted: ${quoted.joinToString("\n")}\n  actual: $actual")
}

/** The stored document [name]; with [abbreviated], the quoted fields only. */
fun document(scenario: Scenario, name: String, abbreviated: Boolean = false, runner: Runner = Runner.CORE) =
    Output(runner, scenario) { quoted, produced ->
        val actual = produced.document(name)
        val differences = documentDifferences(BsonDocument.parse(quoted.joinToString("\n")), actual, abbreviated)
        if (differences.isNotEmpty()) fail(differences.joinToString("\n") + "\n  stored: ${actual.toJson()}")
    }

/** The lines the scenario printed as [name], line for line. */
fun printedLines(scenario: Scenario, name: String) = Output(Runner.CORE, scenario) { quoted, produced ->
    val actual = produced.text(name)
    val unmatched = quoted.indices.firstOrNull { i -> i >= actual.size || !quotes(quoted[i], actual[i]) }
    if (unmatched != null || actual.size != quoted.size) {
        fail("printed:\n" + actual.joinToString("\n") { "    $it" })
    }
}

/** A quoted output of godwit-test's scenarios, which godwit-test's DocsFidelityTest checks. */
val testKit = Output(Runner.TEST_KIT, null) { _, _ -> fail("godwit-test's DocsFidelityTest checks this quote") }

/**
 * An inline log line: an event of [stream] with the span's level (when it has one) and message, whose key-value pairs
 * include the span's, in order. Inline mentions name the keys that matter and leave the others out.
 */
fun inlineLog(scenario: Scenario, stream: String) = Output(Runner.CORE, scenario) { quoted, produced ->
    val span = quoted.single()
    val events = produced.logs(stream)
    if (events.none { mentions(span, it) }) {
        fail("no event matches\n  logged:\n" + events.joinToString("\n") { "    ${it.printed()}" })
    }
}

/** An inline `key=value`: a pair of an event of [stream]. */
fun inlineValue(scenario: Scenario, stream: String) = Output(Runner.CORE, scenario) { quoted, produced ->
    val (key, value) = quoted.single().split('=', limit = 2)
    val events = produced.logs(stream)
    if (events.none { event -> event.keyValues[key]?.let { quotes("$key=$value", "$key=$it") } == true }) {
        fail("no event has it\n  logged:\n" + events.joinToString("\n") { "    ${it.printed()}" })
    }
}

/** An inline exception: the first line of the exception [name]'s printout. */
fun inlineException(scenario: Scenario, name: String) = Output(Runner.CORE, scenario) { quoted, produced ->
    val actual = produced.exception(name).printed().first()
    if (!quotes(quoted.single(), actual)) fail("actual: $actual")
}

/** An inline problem line: one of the problems of the exceptions [names], or of every invalid list when none. */
fun inlineProblem(scenario: Scenario, vararg names: String) = Output(Runner.CORE, scenario) { quoted, produced ->
    val problems = if (names.isEmpty()) produced.problemLines() else names.flatMap { produced.problems(it) }
    if (problems.none { quotes(quoted.single(), it) }) fail("problems:\n" + problems.joinToString("\n") { "    $it" })
}

/** An inline field value: the string at [path] (`lastError.type`) of the stored document [name]. */
fun inlineField(scenario: Scenario, name: String, path: String) = Output(Runner.CORE, scenario) { quoted, produced ->
    val actual = path.split('.').fold(produced.document(name) as org.bson.BsonValue) { value, key ->
        value.asDocument().getValue(key)
    }.asString().value
    if (!quotes(quoted.single(), actual)) fail("actual: $actual")
}

private fun Produced.problems(name: String): List<String> = when (val error = exception(name)) {
    is InvalidMigrationsException -> error.problems
    is PlanConflictException -> error.problems
    else -> fail("$name is ${error.javaClass.name}, not a list of problems")
}

private val LEVEL = Regex("""^(INFO|WARN|ERROR|DEBUG)\s+""")

private val PAIR_START = Regex("""\s(?=[A-Za-z]+=)""")

/**
 * Whether [event] is the log line an inline [span] mentions: the same level when the span has one, the same message,
 * and the span's `key=value` pairs among the event's, in order. A lone `...` in the span stands for pairs left out.
 */
private fun mentions(span: String, event: ILoggingEvent): Boolean {
    val level = LEVEL.find(span)?.groupValues?.get(1)
    val rest = span.removePrefix(LEVEL.find(span)?.value.orEmpty())
    val message = catalogueMessages().filter { rest == it || rest.startsWith("$it ") }.maxByOrNull { it.length }
        ?: return false
    if (event.message != message || (level != null && event.level.toString() != level)) return false
    val pairs = rest.removePrefix(message).trim().split(PAIR_START).map { it.trim() }
        .filter { it.isNotEmpty() && it != "..." }
        .map { it.removePrefix("... ").split('=', limit = 2) }
    val actual = event.keyValues.entries.toList()
    var next = 0
    for ((key, value) in pairs) {
        val found = (next until actual.size).firstOrNull { i ->
            actual[i].key == key && quotes("$key=$value", "$key=${actual[i].value}")
        } ?: return false
        next = found + 1
    }
    return true
}

/** The first line of the `neg/` snippet that holds [block], the kotlin block a compiler error follows. */
fun negExpectation(block: FencedBlock): String {
    fun normalised(lines: List<String>) = lines.map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() }
    val wanted = normalised(block.lines)
    val snippet = File(repositoryRoot, "docs-snippets/neg").listFiles()!!.sorted().firstOrNull { file ->
        val lines = normalised(file.readLines())
        lines.windowed(wanted.size).any { it == wanted }
    } ?: fail("no neg/ snippet holds the block at line ${block.line}")
    return snippet.readLines().first().removePrefix("// expect: ")
}
