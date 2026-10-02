package com.example.shop.docs.squashing_migrations

// region: ready-checks
import godwit.core.Godwit
import godwit.core.HistoryState

/** The ids in [superseded] that this database has not applied. Empty: the database is ready for the squash release. */
fun notYetApplied(godwit: Godwit, superseded: List<String>): List<String> {
    val applied = godwit.history().filter { it.state == HistoryState.APPLIED }.map { it.id }.toSet()
    return superseded.filter { it !in applied }
}

/** True when history holds the baseline with its stored `supersedes` list, so the list in code is no longer needed. */
fun hasRecordedBaseline(godwit: Godwit, baselineId: String): Boolean =
    godwit.history().any { it.id == baselineId && it.state == HistoryState.APPLIED && it.supersedes.isNotEmpty() }
// endregion
