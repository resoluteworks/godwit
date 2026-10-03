package godwit.core.internal

import godwit.core.BatchCheckpoint
import godwit.core.HistoryEntry
import godwit.core.HistoryState
import godwit.core.LastError
import godwit.core.MigrationKind
import godwit.core.Origin
import godwit.core.StepKind
import org.bson.Document
import org.bson.codecs.configuration.CodecRegistry
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

/**
 * A history document that [codecs] decoded, as `history()` returns it. Fields that are absent read as null, an absent
 * number as 0, an absent list or `counts` as empty, an absent `outOfOrder` as false. A repeatable that has never
 * applied has no stored revision, so its kind carries an empty one. The checkpoint's `lastId` keeps its BSON type
 * ([checkpoint]).
 */
internal fun Document.toHistoryEntry(codecs: CodecRegistry): HistoryEntry = HistoryEntry(
    id = getString("_id"),
    kind = when (StoredKind.valueOf(getString("kind"))) {
        StoredKind.ONCE -> MigrationKind.Once
        StoredKind.EVERY_START -> MigrationKind.EveryStart
        StoredKind.REPEATABLE -> MigrationKind.Repeatable(getString("revision").orEmpty())
    },
    state = HistoryState.valueOf(getString("state")),
    origin = Origin.valueOf(getString("origin")),
    description = getString("description"),
    steps = strings("steps").map(StepKind::valueOf),
    attempts = number("attempts").toInt(),
    transactionRetries = number("transactionRetries").toInt(),
    counts = counts(),
    duration = numberOrNull("durationMs")?.toLong()?.milliseconds,
    startedAt = instant("startedAt"),
    finishedAt = instant("finishedAt"),
    lastError = document("lastError")?.toLastError(),
    checkpoint = checkpoint(codecs)?.let { BatchCheckpoint(it.lastId, it.batches) },
    runCount = numberOrNull("runCount")?.toLong(),
    lastRunAt = instant("lastRunAt"),
    supersedes = strings("supersedes"),
    outOfOrder = getBoolean("outOfOrder", false),
    reason = getString("reason"),
    holder = getString("holder"),
    runId = getString("runId"),
    godwitVersion = getString("godwitVersion")
)

/** The `counts` document as counters; godwit stores them as longs, and any number reads as one. */
internal fun Document.counts(): Map<String, Long> =
    document("counts")?.mapValues { (it.value as Number).toLong() } ?: emptyMap()

private fun Document.toLastError() = LastError(
    type = getString("type"),
    message = getString("message"),
    stack = getString("stack"),
    step = getString("step")?.let(StepKind::valueOf),
    at = getDate("at").toInstant()
)

private fun Document.document(key: String): Document? = get(key, Document::class.java)

private fun Document.strings(key: String): List<String> = getList(key, String::class.java) ?: emptyList()

private fun Document.numberOrNull(key: String): Number? = get(key, Number::class.java)

private fun Document.number(key: String): Number = numberOrNull(key) ?: 0

private fun Document.instant(key: String): Instant? = getDate(key)?.toInstant()
