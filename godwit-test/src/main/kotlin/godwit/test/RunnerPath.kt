package godwit.test

import godwit.core.Godwit
import godwit.core.Migration
import godwit.core.MigrationOutcome

// Helpers that drive the real runner (lock, history, transactions) from a test. They are extensions on Godwit, so
// they work on a Godwit built by testGodwit() or by the app's own test setup.

/** Deletes the history document of [id], so the next migrate runs it again. Test-only: core has no equivalent. */
fun Godwit.forget(id: String): Unit = throw NotImplementedError("P7")

/**
 * [forget]s [id], runs it again through the real runner path and returns its outcome. A once-only [id] runs with
 * `Target.Through(id)` and out-of-order allowed, so the migrations applied after it do not block it; a repeatable or
 * every-start [id] runs with `Target.Latest`. The untracked-database guard and adoption are off, as in [runIsolated],
 * so it also works after `runIsolated(migration)` left [id] as the only history document. Migrations listed before
 * [id] that are not applied run first, as `Target.Through` runs them; after [runIsolated], pass a list that holds only
 * that migration. Proves a migration is safe to run twice: the second run's counts are usually 0.
 */
fun Godwit.rerun(migrations: List<Migration>, id: String): MigrationOutcome = throw NotImplementedError("P7")

/**
 * Runs [migration] alone through the real runner path and returns its outcome. The untracked-database guard and
 * adoption are off and other history ids are ignored, so a test can insert data and run one migration without its
 * predecessors.
 */
fun Godwit.runIsolated(migration: Migration): MigrationOutcome = throw NotImplementedError("P7")

/** Throws [AssertionError] unless history records [id] as APPLIED. */
infix fun Godwit.shouldHaveApplied(id: String): Unit = throw NotImplementedError("P7")
