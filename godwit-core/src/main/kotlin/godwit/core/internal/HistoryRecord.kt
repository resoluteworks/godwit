package godwit.core.internal

import godwit.core.HistoryState
import godwit.core.Origin

/** The `kind` field of a history document, as stored. */
internal enum class StoredKind {
    ONCE,
    EVERY_START,
    REPEATABLE
}

/**
 * One history document as plain Kotlin data: the fields the planner decides from. The history store reads every
 * document into one of these.
 *
 * [revision] is the revision of a repeatable's last applied run, null before its first. [supersedes] is the stored
 * `supersedes` list of a superseding migration, empty for every other document.
 */
internal data class HistoryRecord(
    val id: String,
    val kind: StoredKind,
    val state: HistoryState,
    val origin: Origin,
    val revision: String? = null,
    val supersedes: List<String> = emptyList()
)
