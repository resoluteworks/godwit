package godwit.test

import com.mongodb.MongoClientSettings
import com.mongodb.WriteConcern
import com.mongodb.client.model.DeleteOptions
import com.mongodb.client.model.Filters.eq
import godwit.core.Godwit
import godwit.core.GodwitConfig
import godwit.core.HistoryState
import godwit.core.Migration
import godwit.core.MigrationKind
import godwit.core.MigrationOutcome
import godwit.core.MigrationReport
import godwit.core.OutOfOrder
import godwit.core.Target
import godwit.core.UnknownApplied
import godwit.core.UntrackedDatabase
import godwit.core.validateMigrations
import org.bson.BsonDocument
import org.bson.BsonString
import org.bson.Document

// Helpers that drive the real runner (lock, history, transactions) from a test. They are extensions on Godwit, so
// they work on a Godwit built by testGodwit() or by the app's own test setup. Each one that runs migrations builds a
// Godwit on the same cluster and database, with the receiver's configuration adjusted as its KDoc states.

/**
 * Deletes the history document of [id], so the next migrate runs it again. Test-only: core has no equivalent. A
 * missing document is left missing. The delete carries the comment `{godwit: <id>}`, as godwit's own history commands
 * do, so [SessionEscapeDetector] treats it as one of them.
 */
fun Godwit.forget(id: String) {
    cluster.getDatabase(databaseName)
        .getCollection(config.historyCollection, Document::class.java)
        .withCodecRegistry(MongoClientSettings.getDefaultCodecRegistry())
        .withWriteConcern(WriteConcern.MAJORITY)
        .deleteOne(eq("_id", id), DeleteOptions().comment(BsonDocument("godwit", BsonString(id))))
}

/**
 * [forget]s [id], runs it again through the real runner path and returns its outcome. A once-only [id] runs with
 * `Target.Through(id)` and out-of-order allowed, so the migrations applied after it do not block it; a repeatable or
 * every-start [id] runs with `Target.Latest`. The untracked-database guard and adoption are off, as in [runIsolated],
 * so it also works after `runIsolated(migration)` left [id] as the only history document. Migrations listed before
 * [id] that are not applied run first, as `Target.Through` runs them; after [runIsolated], pass a list that holds only
 * that migration. Proves a migration is safe to run twice: the second run's counts are usually 0.
 *
 * The list is validated before anything is forgotten: an invalid one throws `InvalidMigrationsException`, and an [id]
 * the list does not hold throws [IllegalArgumentException], with history unchanged.
 */
fun Godwit.rerun(migrations: List<Migration>, id: String): MigrationOutcome {
    validateMigrations(migrations)
    val migration = requireNotNull(migrations.firstOrNull { it.id == id }) { "rerun: the list holds no migration $id" }
    val withoutGuards = config.copy(adoptApplied = null, untrackedDatabase = UntrackedDatabase.RUN_ALL)
    val (runConfig, target) = if (migration.kind == MigrationKind.Once) {
        withoutGuards.copy(outOfOrder = OutOfOrder.RUN) to Target.Through(id)
    } else {
        withoutGuards to Target.Latest
    }
    forget(id)
    return withConfig(runConfig).migrate(migrations, target).outcome(id)
}

/**
 * Runs [migration] alone through the real runner path and returns its outcome. The untracked-database guard and
 * adoption are off and other history ids are ignored, so a test can insert data and run one migration without its
 * predecessors.
 *
 * Other applied ids count as unknown under `UnknownApplied.WARN`, whatever the receiver's configuration says: they are
 * logged and reported, never refused. A migration that history already records as applied (a repeatable at its
 * revision included) does not run, and the call throws [AssertionError]; [forget] it first, or use [rerun].
 */
fun Godwit.runIsolated(migration: Migration): MigrationOutcome {
    val isolated = config.copy(
        adoptApplied = null,
        untrackedDatabase = UntrackedDatabase.RUN_ALL,
        unknownApplied = UnknownApplied.WARN
    )
    return withConfig(isolated).migrate(listOf(migration)).outcome(migration.id)
}

/** Throws [AssertionError] unless history records [id] as APPLIED. */
infix fun Godwit.shouldHaveApplied(id: String) {
    val entry = history().firstOrNull { it.id == id }
        ?: throw AssertionError("expected $id to be APPLIED, but history has no document for it")
    if (entry.state != HistoryState.APPLIED) {
        val lastError = entry.lastError?.let { " (lastError: ${it.type}: ${it.message})" }.orEmpty()
        throw AssertionError("expected $id to be APPLIED, but history records it ${entry.state}$lastError")
    }
}

/** A [Godwit] on the receiver's cluster and database, with [config]. */
private fun Godwit.withConfig(config: GodwitConfig) = Godwit(cluster, databaseName, config)

/** The outcome of [id] in this report, which ran or recorded it; [AssertionError] when the call did neither. */
private fun MigrationReport.outcome(id: String): MigrationOutcome =
    (ran + recorded).firstOrNull { it.id == id } ?: throw AssertionError(
        "$id did not run: history records it applied. forget(\"$id\") first to run it again, or use rerun"
    )
