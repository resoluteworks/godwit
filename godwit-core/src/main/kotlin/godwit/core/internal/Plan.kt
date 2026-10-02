package godwit.core.internal

import godwit.core.Migration
import godwit.core.MigrationKind
import godwit.core.StepKind

/**
 * What one call does to the database, decided by [plan] from the list, the history, the target, the configuration and
 * whether adoption can still run. Every field is a decision; carrying them out is the runner's job.
 */
internal data class Plan(
    /** The migrations to run, in run order: once-only ones in list order, then repeatable and every-start ones. */
    val due: List<DueMigration>,
    /** Superseding migrations to record SUPERSEDED without running (every id they replace is applied), in list order. */
    val superseded: List<Migration>,
    /** Once-only ids still due or to be recorded that the target stops before, in list order. */
    val pending: List<String>,
    /** Ids found applied, and repeatables found at their current revision, in list order. */
    val upToDate: List<String>,
    /** One problem line per conflict, as [godwit.core.PlanConflictException] lists them. */
    val conflicts: List<String>,
    /** APPLIED history ids the list does not know, sorted by id. */
    val unknownApplied: List<String>,
    /** History is empty and adoption cannot run: the untracked-database guard decides under the lock. */
    val untracked: Boolean
) {
    /** A due migration has a step that runs in a transaction, so the server must support transactions. */
    val needsTransactions: Boolean
        get() = due.any { due -> due.migration.steps.any { it != StepKind.OUTSIDE_TRANSACTION } }

    /** Nothing to run or record: the call returns without the lock. */
    val nothingDue: Boolean get() = due.isEmpty() && superseded.isEmpty()

    /**
     * What [godwit.core.Godwit.status] reports as pending: the due ids in run order, every-start ones left out. A squash
     * to record is in [superseded], not due, so it is not pending: recording changes no data.
     */
    val statusPending: List<String>
        get() = due.filter { it.migration.kind != MigrationKind.EveryStart }.map { it.migration.id }
}

/**
 * A migration the call runs. [appliedAfter] lists the applied once-only migrations listed after it, each applied id
 * that a later migration's `supersedes` list names standing in that migration's place while it is not APPLIED; when
 * it is not empty the migration runs out of order, which only [godwit.core.OutOfOrder.RUN] allows.
 */
internal data class DueMigration(val migration: Migration, val appliedAfter: List<String> = emptyList()) {
    val outOfOrder: Boolean get() = appliedAfter.isNotEmpty()
}
