package godwit.core.docs

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.PatternLayout
import ch.qos.logback.classic.spi.ILoggingEvent
import org.bson.BsonArray
import org.bson.BsonDocument
import org.bson.BsonString
import org.bson.BsonValue
import org.slf4j.LoggerFactory
import java.io.File

/** The repository: the test task of every module runs in the module's directory. */
val repositoryRoot: File = File("..").canonicalFile

/**
 * The published docs: README.md and every Markdown file under docs/, as paths from the repository root, in path
 * order. docs/development is left out: it describes how godwit is built, and its traces are the test suite's output.
 */
fun publishedDocs(): List<String> = listOf("README.md") +
    File(repositoryRoot, "docs").walk()
        .filter { it.isFile && it.extension == "md" }
        .map { it.relativeTo(repositoryRoot).invariantSeparatorsPath }
        .filterNot { it.startsWith("docs/development/") }
        .sorted()
        .toList()

/**
 * A fenced code block of a published doc. [ordinal] counts from 1 among the doc's blocks in a language other than
 * kotlin, and is 0 for a kotlin block. [lines] are the block's content without the fence's indentation; [before] is the
 * last non-blank line above the opening fence.
 */
class FencedBlock(
    val doc: String,
    val ordinal: Int,
    val line: Int,
    val language: String,
    val lines: List<String>,
    val before: String
) {
    val text: String get() = lines.joinToString("\n")

    override fun toString() = "$doc block $ordinal (line $line, $language)"
}

private val OPENING_FENCE = Regex("""^(\s*)```(\S*)\s*$""")

private val CLOSING_FENCE = Regex("""^\s*```\s*$""")

/** Every fenced block of [doc], kotlin blocks included, in order. */
fun fencedBlocks(doc: String): List<FencedBlock> {
    val text = File(repositoryRoot, doc).readLines()
    val blocks = mutableListOf<FencedBlock>()
    var ordinal = 0
    var i = 0
    while (i < text.size) {
        val opening = OPENING_FENCE.matchEntire(text[i])
        if (opening == null) {
            i++
            continue
        }
        val indent = opening.groupValues[1].length
        val language = opening.groupValues[2]
        var j = i + 1
        while (!CLOSING_FENCE.matches(text[j])) j++
        val lines = text.subList(i + 1, j).map { it.drop(minOf(indent, it.length - it.trimStart().length)) }
        val before = text.subList(0, i).lastOrNull { it.isNotBlank() }?.trim().orEmpty()
        val number = if (language == "kotlin") 0 else ++ordinal
        blocks += FencedBlock(doc, number, i + 1, language, lines, before)
        i = j + 1
    }
    return blocks
}

/** Every fenced block of the published docs in a language other than kotlin. */
fun nonKotlinBlocks(): List<FencedBlock> = publishedDocs().flatMap { doc ->
    fencedBlocks(doc).filter { it.ordinal > 0 }
}

/** An inline code span of a published doc's prose that quotes an output: a log line, an exception or a problem line. */
data class InlineQuote(val doc: String, val line: Int, val text: String) {
    override fun toString() = "$doc line $line: `$text`"
}

private val catalogue: List<String> by lazy {
    val row = Regex("""^ \* \| (?:INFO|WARN|DEBUG|ERROR)\s*\| (.+?)\s*\| .+\|$""")
    File(repositoryRoot, "godwit-core/src/main/kotlin/godwit/core/Godwit.kt").readLines()
        .mapNotNull { row.find(it)?.groupValues?.get(1) }
}

/** The messages of godwit's log catalogue, from the table in the `Godwit` KDoc. */
fun catalogueMessages(): List<String> = catalogue

/** The phrases of godwit's problem lines and exception messages, which mark an inline span as a quoted output. */
private val OUTPUT_PHRASES = Regex(
    listOf(
        "is pending, but", "Invalid migrations", "cannot run against", "migrations, but only",
        "is applied, but the list",
        "has a blank revision", "numeric prefix", "named in the supersedes", "named twice", "which the list declares",
        "names no once-only", "batchSize is 1 to", "uses inBatches, which", "ids match", "duplicate id",
        "is once-only but listed", "but no godwit history", "not up to date", "adoption has not ended",
        "ran without the step", "Lost the migration lock", "for the migration lock", "need transactions",
        "was not queryable", "failed in OUTSIDE_TRANSACTION", "failed in IN_TRANSACTION", "failed in IN_BATCHES"
    ).joinToString("|") { Regex.escape(it) }
)

private val EXCEPTION = Regex("""^(?:[a-z]+\.)+[A-Z]\w*(?:Exception|Error)(?::|$)""")

/**
 * Every inline code span of the published docs' prose that quotes an output: a log line with at least one value
 * (`Lost migration lock runId=... reason=DEADLINE_PASSED`, with or without its level), a value of a log line's `error`
 * (`error=WriteConflict (112)`), an exception (`com.mongodb.MongoTimeoutException: ...`, or an exception class alone),
 * or a problem line or exception message (it contains one of [OUTPUT_PHRASES]). Spans are read per paragraph and per
 * table row, so a span that wraps onto the next line is read whole.
 */
fun inlineQuotes(): List<InlineQuote> {
    val messages = catalogueMessages().sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
    val logLine = Regex("""^(?:(?:INFO|WARN|ERROR|DEBUG)\s+)?(?:$messages)(?: |$)""")
    val quotes = mutableListOf<InlineQuote>()
    for (doc in publishedDocs()) {
        val text = File(repositoryRoot, doc).readLines()
        var fenced = false
        val paragraph = mutableListOf<Pair<Int, String>>()
        fun flush() {
            if (paragraph.isEmpty()) return
            val joined = paragraph.joinToString(" ") { it.second.trim() }
            Regex("`([^`]+)`").findAll(joined).map { it.groupValues[1] }.forEach { span ->
                val quoted = (logLine.containsMatchIn(span) && '=' in span) || EXCEPTION.containsMatchIn(span) ||
                    span.startsWith("error=") || OUTPUT_PHRASES.containsMatchIn(span)
                if (quoted) quotes += InlineQuote(doc, paragraph.first().first, span)
            }
            paragraph.clear()
        }
        text.forEachIndexed { index, line ->
            when {
                OPENING_FENCE.matches(line) || (fenced && CLOSING_FENCE.matches(line)) -> {
                    flush()
                    fenced = !fenced
                }

                fenced -> Unit

                line.isBlank() -> flush()

                line.trimStart().startsWith("|") -> {
                    flush()
                    paragraph += (index + 1) to line
                    flush()
                }

                else -> paragraph += (index + 1) to line
            }
        }
        flush()
    }
    return quotes
}

private val UUID = Regex("""\b[0-9a-f]{8}-(?:[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b|\.\.\.)""")

private val INSTANT = Regex("""(?:\d{4}-\d{2}-\d{2}T)?\d{2}:\d{2}:\d{2}(?:\.\d+)?Z""")

private val DURATION_KEY = Regex("""\b(durationMs|lockWaitMs|waitedMs)=\d+""")

/** A Kotlin `Duration` as `toString` prints it: `2m 0.214s`, `5m`, `1ms`. */
private const val DURATION = """(?:\d+d )?(?:\d+h )?(?:\d+m )?(?:\d+(?:\.\d+)?(?:s|ms|us|ns)|\d+[dhm])"""

private val WAITED = Regex("""Waited $DURATION for the migration lock""")

private val NOT_QUERYABLE = Regex("""was not queryable after $DURATION""")

private val TXN_NUMBER = Regex("""txnNumber: \d+""")

private val CLUSTER_TIME = Regex("""Timestamp\(\d+, \d+\)""")

/**
 * [text] with what differs from run to run replaced by a placeholder: run ids, owner tokens and other UUIDs (an
 * abbreviated one, `0199a4c2-...`, too), times, durations (`durationMs`, `lockWaitMs`, `waitedMs`, a `Duration` in a
 * message), a session's transaction number and the server's cluster time. Applied to the docs and to what the
 * implementation produced alike.
 */
fun normalise(text: String): String = text
    .replace(UUID, "<uuid>")
    .replace(INSTANT, "<time>")
    .replace(DURATION_KEY, "$1=<ms>")
    .replace(WAITED, "Waited <duration> for the migration lock")
    .replace(NOT_QUERYABLE, "was not queryable after <duration>")
    .replace(TXN_NUMBER, "txnNumber: <n>")
    .replace(CLUSTER_TIME, "Timestamp(<time>)")

/** A placeholder the docs write for any value, such as `<id>` in a problem template. */
private val PLACEHOLDER = Regex("""<[a-z][a-zA-Z ]*>""")

private const val ANY_VALUE = '\u0001'

/**
 * The pattern a quoted text stands for: the text, [normalise]d, where `...` stands for any text (also none) and a
 * placeholder the docs write for a value, such as `<id>`, for one or more characters.
 */
fun quotedPattern(quoted: String): Regex {
    val marked = normalise(quoted.replace(PLACEHOLDER, ANY_VALUE.toString()))
    val pattern = StringBuilder()
    var i = 0
    var literal = StringBuilder()
    fun flush() {
        if (literal.isNotEmpty()) pattern.append(Regex.escape(literal.toString()))
        literal = StringBuilder()
    }
    while (i < marked.length) {
        when {
            marked.startsWith("...", i) -> {
                flush()
                pattern.append(".*")
                i += 3
            }

            marked[i] == ANY_VALUE -> {
                flush()
                pattern.append(".+?")
                i++
            }

            else -> literal.append(marked[i++])
        }
    }
    flush()
    return Regex(pattern.toString(), RegexOption.DOT_MATCHES_ALL)
}

/** Whether [actual] is what [quoted] shows. */
fun quotes(quoted: String, actual: String): Boolean = quotedPattern(quoted).matches(normalise(actual))

/** The Logback pattern of the configuration that configuration.md shows. */
val documentedLogPattern: String by lazy {
    val xml = fencedBlocks("docs/configuration.md").single { it.language == "xml" }.text
    Regex("<pattern>(.+)</pattern>").find(xml)!!.groupValues[1]
}

private val documentedLayout: PatternLayout by lazy {
    PatternLayout().apply {
        context = LoggerFactory.getILoggerFactory() as LoggerContext
        pattern = documentedLogPattern
        start()
    }
}

/**
 * [this] event's line as the documented Logback configuration prints it: its first line, without the stack trace of
 * an exception the event carries, which Logback prints on the lines after it.
 */
fun ILoggingEvent.printed(): String = documentedLayout.doLayout(this).lineSequence().first()

/** A line of a quoted log block: it starts with a level and the logger. */
private val LOG_LINE = Regex("""^(TRACE|DEBUG|INFO|WARN|ERROR)\s+godwit - """)

fun isLogLine(line: String): Boolean = LOG_LINE.containsMatchIn(line)

/** A line of a quoted log block that annotates the lines around it: `...`, or a remark in parentheses. */
fun isAnnotation(line: String): Boolean = line.trim() == "..." || (line.startsWith("(") && line.endsWith(")"))

/**
 * Why [quoted] log lines are not [actual]'s, or null when they are. With [exact], the quoted lines are every actual line
 * at INFO and above, in order. Otherwise each quoted line is an actual line, in order, with any number of actual lines
 * between them: a doc shows the lines that matter for its point. Annotation lines are skipped.
 */
fun logMismatch(quoted: List<String>, actual: List<ILoggingEvent>, exact: Boolean): String? {
    val wanted = quoted.filterNot(::isAnnotation)
    val lines = (if (exact) actual.filter { it.level.levelInt >= ch.qos.logback.classic.Level.INFO_INT } else actual)
        .map { it.printed() }
    if (exact) {
        val unmatched = wanted.indices.firstOrNull { i -> i >= lines.size || !quotes(wanted[i], lines[i]) }
        return when {
            unmatched != null -> "line ${unmatched + 1} does not match\n  quoted: ${wanted[unmatched]}\n" +
                "  actual: ${lines.getOrNull(unmatched)}\n${transcript(lines)}"

            lines.size != wanted.size ->
                "the implementation logged ${lines.size} lines, the doc shows " +
                    "${wanted.size}\n${transcript(lines)}"

            else -> null
        }
    }
    var next = 0
    for ((i, line) in wanted.withIndex()) {
        val found = (next until lines.size).firstOrNull { quotes(line, lines[it]) }
            ?: return "line ${i + 1} is not among the lines logged after the line before it\n  quoted: $line\n" +
                transcript(lines)
        next = found + 1
    }
    return null
}

private fun transcript(lines: List<String>) = "  logged:\n" + lines.joinToString("\n") { "    $it" }

/**
 * An exception as the head of its stack trace prints it: its `toString()` (the class, then the message, whose lines
 * follow), then `Caused by: ` and each cause's `toString()`, without the frames.
 */
fun Throwable.printed(): List<String> = generateSequence(this) { it.cause }
    .mapIndexed { i, error -> (if (i == 0) "" else "Caused by: ") + error.toString() }
    .flatMap { it.lines() }
    .toList()

/** Why [quoted] is not the head of [actual]'s printout, or null when it is. */
fun exceptionMismatch(quoted: List<String>, actual: Throwable): String? {
    val printed = actual.printed()
    val unmatched = quoted.indices.firstOrNull { i -> i >= printed.size || !quotes(quoted[i], printed[i]) }
        ?: return null
    return "line ${unmatched + 1} does not match\n  quoted: ${quoted[unmatched]}\n  actual: " +
        "${printed.getOrNull(unmatched)}\n  printed:\n" + printed.joinToString("\n") { "    $it" }
}

/**
 * The differences between a quoted [expected] document and the [actual] one, empty when there are none. Field order
 * does not count; array order does. Numbers compare by value whatever their BSON type. Dates match any date, a UUID
 * string any UUID string, and `durationMs` any number, as [normalise] treats them in text; a quoted string with `...`
 * stands for any text there. With [abbreviated], the quoted document may leave fields out.
 */
fun documentDifferences(
    expected: BsonDocument,
    actual: BsonDocument,
    abbreviated: Boolean,
    path: String = ""
): List<String> {
    val differences = mutableListOf<String>()
    val missing = expected.keys - actual.keys
    missing.forEach { differences += "$path$it: quoted, but the document has no such field" }
    if (!abbreviated) (actual.keys - expected.keys).forEach { differences += "$path$it: in the document, not quoted" }
    for (key in expected.keys - missing) {
        differences += valueDifferences(expected.getValue(key), actual.getValue(key), abbreviated, "$path$key")
    }
    return differences
}

private fun valueDifferences(expected: BsonValue, actual: BsonValue, abbreviated: Boolean, path: String): List<String> =
    when {
        path.endsWith("durationMs") && expected.isNumber && actual.isNumber -> emptyList()

        expected.isDateTime && actual.isDateTime -> emptyList()

        expected.isNumber && actual.isNumber ->
            if (expected.asNumber().decimal128Value() == actual.asNumber().decimal128Value()) {
                emptyList()
            } else {
                listOf("$path: quoted $expected, the document has $actual")
            }

        expected is BsonString && actual is BsonString ->
            if (quotes(expected.value, actual.value)) {
                emptyList()
            } else {
                listOf("$path: quoted \"${expected.value}\", the document has \"${actual.value}\"")
            }

        expected is BsonDocument && actual is BsonDocument ->
            documentDifferences(expected, actual, abbreviated, "$path.")

        expected is BsonArray && actual is BsonArray ->
            if (expected.size != actual.size) {
                listOf("$path: quoted $expected, the document has $actual")
            } else {
                expected.indices.flatMap { valueDifferences(expected[it], actual[it], abbreviated, "$path[$it]") }
            }

        expected == actual -> emptyList()

        else -> listOf("$path: quoted $expected, the document has $actual")
    }
