package godwit.core.internal

import godwit.core.GodwitConfig
import godwit.core.HistoryState
import godwit.core.Migration
import godwit.core.MigrationKind
import godwit.core.Origin
import godwit.core.OutOfOrder
import godwit.core.Target
import godwit.core.UnknownApplied

/**
 * Whether the adoption hook can still run on a database with [history]: it is configured and every history document
 * was written by adoption, which an empty history satisfies. The plan made before the lock and `status()` pass it to
 * [plan] as `adopting`; `markApplied` refuses while it holds.
 */
internal fun adoptionCanRun(config: GodwitConfig, history: List<HistoryRecord>): Boolean =
    config.adoptApplied != null && history.all { it.origin == Origin.ADOPTED }

/**
 * Decides what a call does, from data alone: the validated list [migrations], every [history] document, the [target]
 * (rule 9 already checked) and the policies in [config].
 *
 * - Due: a once-only migration whose document is missing or not APPLIED; a repeatable whose document is missing, not
 *   APPLIED or at another revision; an every-start migration always. Once-only migrations run in list order, then
 *   repeatable and every-start ones in list order.
 * - [Target.Before] and [Target.Through] reach the once-only migrations up to their id; what they stop before is
 *   pending, and no repeatable or every-start migration runs.
 * - A superseding migration that is not APPLIED is recorded when every id it replaces is APPLIED, runs when none is,
 *   and is a partial-supersede conflict when some are. Once APPLIED, its list is not evaluated.
 * - A due once-only migration listed before an applied once-only migration (a document of kind ONCE whose id the list
 *   declares once-only, or names in the `supersedes` list of a later migration that is not APPLIED, in that
 *   migration's place) is out of order: a conflict under [OutOfOrder.FAIL], a run with `appliedAfter` under
 *   [OutOfOrder.RUN]. Repeatable and every-start documents and unknown ids never count, and a recording is not a run.
 * - An APPLIED document is unknown when no declared id, no declared `supersedes` list and no stored `supersedes` list
 *   names it; under [UnknownApplied.FAIL] each one is a conflict. Undeclared documents in other states are ignored.
 *
 * With [adopting] (the hook can still run, see [adoptionCanRun]) the out-of-order and partial-supersede conflicts are
 * left out and their migrations stay due, because the hook, which runs under the lock, may record what they miss; the
 * plan made under the lock after the hook passes false and checks them. An empty history is untracked only when not
 * [adopting].
 */
internal fun plan(
    migrations: List<Migration>,
    history: List<HistoryRecord>,
    target: Target,
    config: GodwitConfig,
    adopting: Boolean
): Plan {
    val records = history.associateBy { it.id }
    val onceOnly = migrations.filter { it.kind == MigrationKind.Once }
    val reach = reachOf(onceOnly, target)
    val latest = target == Target.Latest

    val dueOnceOnly = mutableListOf<DueMigration>()
    val dueRerunnable = mutableListOf<DueMigration>()
    val superseded = mutableListOf<Migration>()
    val pending = mutableListOf<String>()
    val upToDate = mutableListOf<String>()
    val conflicts = mutableListOf<String>()
    var position = 0

    for (migration in migrations) {
        val record = records[migration.id]
        when (val kind = migration.kind) {
            MigrationKind.Once -> {
                // The position among once-only migrations, which the target's reach counts.
                val index = position++
                if (record.isApplied()) {
                    upToDate += migration.id
                    continue
                }
                val replaced = migration.supersedes.filter { records[it].isApplied() }
                val allReplaced = replaced.isNotEmpty() && replaced.size == migration.supersedes.size
                when {
                    index >= reach -> pending += migration.id

                    allReplaced -> superseded += migration

                    replaced.isNotEmpty() -> {
                        // Neither recorded nor run: a conflict of its own, never out of order, which RUN cannot resolve.
                        dueOnceOnly += DueMigration(migration)
                        if (!adopting) conflicts += partialSupersede(migration, replaced)
                    }

                    else -> {
                        // A later migration that is not APPLIED stands for the ids its supersedes list names, so a gap
                        // before a squash or a rename about to be recorded is as visible as a gap before the old ids.
                        val appliedAfter = onceOnly.drop(index + 1)
                            .flatMap { if (records[it.id].isApplied()) listOf(it.id) else it.supersedes }
                            .filter { records[it].isAppliedOnce() }
                        dueOnceOnly += DueMigration(migration, appliedAfter)
                        if (appliedAfter.isNotEmpty() && config.outOfOrder == OutOfOrder.FAIL && !adopting) {
                            conflicts += outOfOrder(migration.id, appliedAfter.first())
                        }
                    }
                }
            }

            is MigrationKind.Repeatable ->
                if (record != null && record.state == HistoryState.APPLIED && record.revision == kind.revision) {
                    upToDate += migration.id
                } else if (latest) {
                    dueRerunnable += DueMigration(migration)
                }

            MigrationKind.EveryStart -> if (latest) dueRerunnable += DueMigration(migration)
        }
    }

    val known = migrations.flatMap { listOf(it.id) + it.supersedes }.toSet() + history.flatMap { it.supersedes }
    val unknownApplied = history.filter { it.state == HistoryState.APPLIED && it.id !in known }.map { it.id }.sorted()
    if (config.unknownApplied == UnknownApplied.FAIL) conflicts += unknownApplied.map(::unknown)

    return Plan(
        due = dueOnceOnly + dueRerunnable,
        superseded = superseded,
        pending = pending,
        upToDate = upToDate,
        conflicts = conflicts,
        unknownApplied = unknownApplied,
        untracked = history.isEmpty() && !adopting
    )
}

/** How many once-only migrations, from the start of the list, [target] reaches. */
private fun reachOf(onceOnly: List<Migration>, target: Target): Int = when (target) {
    Target.Latest -> onceOnly.size
    is Target.Before -> positionOf(onceOnly, target.id)
    is Target.Through -> positionOf(onceOnly, target.id) + 1
}

private fun positionOf(onceOnly: List<Migration>, id: String): Int {
    val position = onceOnly.indexOfFirst { it.id == id }
    require(position >= 0) { "the target names $id, which is not a once-only migration in the list" }
    return position
}

private fun HistoryRecord?.isApplied(): Boolean = this?.state == HistoryState.APPLIED

private fun HistoryRecord?.isAppliedOnce(): Boolean =
    this != null && state == HistoryState.APPLIED && kind == StoredKind.ONCE

private fun outOfOrder(id: String, other: String): String =
    "$id is pending, but $other, listed after it, is applied (out of order; OutOfOrder.RUN runs it)"

private fun partialSupersede(migration: Migration, applied: List<String>): String =
    "${migration.id} supersedes ${migration.supersedes.size} migrations, but only ${applied.joinToString(", ")} " +
        "are applied. Deploy the previous release first."

private fun unknown(id: String): String = "$id is applied, but the list does not declare it (UnknownApplied.FAIL)"
