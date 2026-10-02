package com.example.shop.docs.history_and_reports

import godwit.core.Godwit
import godwit.core.HistoryState

/** An admin command: one line per history document, with the error and checkpoint of unfinished ones. */
fun printHistory(godwit: Godwit) {
    godwit.history().forEach { entry ->
        println("${entry.id} ${entry.state} ${entry.origin} attempts=${entry.attempts} counts=${entry.counts}")
        entry.lastError?.let { error -> println("  last error in ${error.step}: ${error.type}: ${error.message}") }
        entry.checkpoint?.let { checkpoint ->
            println("  ${checkpoint.batches} batches committed, last _id ${checkpoint.lastId}")
        }
    }
}

/** The ids whose last run failed or never finished. */
fun unfinished(godwit: Godwit): List<String> =
    godwit.history().filter { it.state != HistoryState.APPLIED }.map { it.id }

/** Records the hand-made index as the outcome of 008, which would otherwise fail on this database. */
fun markEmailIndexApplied(godwit: Godwit) {
    godwit.markApplied(
        "008-customer-email-lower-index",
        reason = "Unique index on customers.emailLower built by hand as emailLower_unique during the 2026-10-05 incident"
    )
}
