package com.example.shop.docs.history_and_reports

import godwit.core.Godwit
import godwit.core.Migration
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("shop")

/** Migrates and logs one line per migration that ran or was recorded, plus any history ids this release does not know. */
fun migrateAndSummarise(godwit: Godwit, migrations: List<Migration>) {
    val report = godwit.migrate(migrations)

    report.ran.forEach { outcome ->
        log.info(
            "{} ran in {} (attempt {}, {} transaction retries, {} batches): {}",
            outcome.id, outcome.duration, outcome.attempts, outcome.transactionRetries, outcome.batches, outcome.counts
        )
    }
    report.recorded.forEach { outcome ->
        log.info("{} recorded as {} without running", outcome.id, outcome.origin)
    }
    if (report.unknownApplied.isNotEmpty()) {
        log.warn("History holds migrations this release does not declare: {}", report.unknownApplied)
    }
    log.info(
        "run {}: {} up to date, lock wait {}, total {}",
        report.runId, report.upToDate.size, report.lockWait ?: "none (nothing was due)", report.duration
    )
}
