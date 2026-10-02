package godwit.core

import godwit.core.internal.listProblems
import godwit.core.internal.requireValid

/**
 * Checks [migrations] without touching a database and throws [InvalidMigrationsException] listing every problem
 * found. [Godwit.migrate], [Godwit.status] and [Godwit.requireUpToDate] run the same check before any I/O. Called
 * from a unit test, it catches a bad list (two branches that both added `007-...`) without a database.
 *
 * Rules:
 * - Every id matches `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`.
 * - No id appears twice, counting the ids named in `supersedes` lists.
 * - A `supersedes` list does not name an id the list declares.
 * - Among once-only migrations, ids with a numeric prefix (`^\d+[-_.]`) strictly increase in list order, compared as
 *   numbers. Ids without one are not ordered.
 * - Every [everyStart] and [repeatable] migration is listed after every once-only migration.
 * - A [repeatable] revision is not blank.
 * - [everyStart] and [repeatable] migrations do not use `inBatches`.
 * - Every `inBatches` batch size is between 1 and 10000.
 */
fun validateMigrations(migrations: List<Migration>): Unit = requireValid(listProblems(migrations))
