package godwit.core.internal

import godwit.core.InvalidMigrationsException
import godwit.core.Migration
import godwit.core.MigrationKind
import godwit.core.Target
import java.math.BigInteger

/** The id format of rule 1, as the problem line quotes it. */
private const val ID_FORMAT = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}"

private val idPattern = Regex(ID_FORMAT)

/** Digits followed by `-`, `_` or `.` at the start of an id: the numeric prefix of rule 4. */
private val numericPrefix = Regex("^([0-9]+)[-_.]")

/** The largest `inBatches` batch size of rule 8. */
private const val MAX_BATCH_SIZE = 10_000

/**
 * Every problem with [migrations], rules 1 to 8 of the ordering-and-validation page, grouped by rule in rule order and,
 * within a rule, in list order. Empty for a valid list. Positions count from 1.
 */
internal fun listProblems(migrations: List<Migration>): List<String> = (
    invalidIds(migrations) +
        duplicateIds(migrations) +
        supersededDeclaredIds(migrations) +
        decreasingPrefixes(migrations) +
        misplacedOnceOnly(migrations) +
        blankRevisions(migrations) +
        batchedRerunnables(migrations) +
        batchSizes(migrations)
    ).distinct()

/** Rule 9: the problem with [target] for [migrations], if any. `Before` and `Through` name a once-only id. */
internal fun targetProblems(migrations: List<Migration>, target: Target): List<String> {
    val (name, id) = when (target) {
        Target.Latest -> return emptyList()
        is Target.Before -> "Before" to target.id
        is Target.Through -> "Through" to target.id
    }
    val named = migrations.any { it.kind == MigrationKind.Once && it.id == id }
    return if (named) emptyList() else listOf("Target.$name(\"$id\") names no once-only migration in the list")
}

/** The check [godwit.core.Godwit.migrate] runs before any I/O: the list's rules, then the target's. */
internal fun validateCall(migrations: List<Migration>, target: Target): Unit =
    requireValid(listProblems(migrations) + targetProblems(migrations, target))

/** Throws [InvalidMigrationsException] with [problems] unless there are none. */
internal fun requireValid(problems: List<String>) {
    if (problems.isNotEmpty()) throw InvalidMigrationsException(problems)
}

/** How a problem line names the kind of a repeatable or every-start migration. */
private fun MigrationKind.rerunnableLabel(): String =
    if (this is MigrationKind.Repeatable) "repeatable" else "every-start"

/**
 * Rule 1: each declared id, then the ids its `supersedes` list names. A named id becomes a history document's `_id`
 * when adoption records it, so it follows the same format.
 */
private fun invalidIds(migrations: List<Migration>): List<String> = migrations
    .flatMap { listOf(it.id) + it.supersedes }
    .filterNot { idPattern.matches(it) }
    .map { "invalid id \"$it\": ids match $ID_FORMAT" }

/**
 * Rule 2: a declared id at a second position, and an id named in two `supersedes` lists or twice in one, each at the
 * migration where it repeats, so the lines follow list order.
 */
private fun duplicateIds(migrations: List<Migration>): List<String> {
    val problems = mutableListOf<String>()
    val firstPosition = mutableMapOf<String, Int>()
    val firstNamer = mutableMapOf<String, Int>()
    for ((index, migration) in migrations.withIndex()) {
        firstPosition.putIfAbsent(migration.id, index)?.let { first ->
            problems += "duplicate id ${migration.id} at positions ${first + 1} and ${index + 1}"
        }
        for (id in migration.supersedes) {
            val namer = firstNamer.putIfAbsent(id, index) ?: continue
            problems += if (namer == index) {
                "duplicate id $id: named twice in the supersedes list of ${migration.id}"
            } else {
                "duplicate id $id: named in the supersedes lists of ${migrations[namer].id} and ${migration.id}"
            }
        }
    }
    return problems
}

/** Rule 3. */
private fun supersededDeclaredIds(migrations: List<Migration>): List<String> {
    val declared = migrations.map { it.id }.toSet()
    return migrations.flatMap { migration ->
        migration.supersedes.filter { it in declared }.map { "${migration.id} supersedes $it, which the list declares" }
    }
}

/** Rule 4: each numbered once-only id against the numbered once-only id listed before it. */
private fun decreasingPrefixes(migrations: List<Migration>): List<String> = migrations
    .filter { it.kind == MigrationKind.Once }
    .mapNotNull { migration -> numericPrefix.find(migration.id)?.let { migration.id to BigInteger(it.groupValues[1]) } }
    .zipWithNext()
    .filter { (previous, current) -> current.second <= previous.second }
    .map { (previous, current) ->
        "${current.first} is listed after ${previous.first}, but its numeric prefix ${current.second} " +
            "is not greater than ${previous.second}"
    }

/** Rule 5: a once-only migration after the first repeatable or every-start one. */
private fun misplacedOnceOnly(migrations: List<Migration>): List<String> {
    val firstRerunnable = migrations.indexOfFirst { it.kind != MigrationKind.Once }
    if (firstRerunnable < 0) return emptyList()
    val other = migrations[firstRerunnable]
    return migrations.drop(firstRerunnable + 1).filter { it.kind == MigrationKind.Once }.map {
        "${it.id} is once-only but listed after ${other.id} (${other.kind.rerunnableLabel()}); " +
            "list repeatable and every-start migrations after every once-only migration"
    }
}

/** Rule 6. */
private fun blankRevisions(migrations: List<Migration>): List<String> = migrations
    .filter {
        val kind = it.kind
        kind is MigrationKind.Repeatable && kind.revision.isBlank()
    }
    .map { "${it.id} has a blank revision" }

/** Rule 7. */
private fun batchedRerunnables(migrations: List<Migration>): List<String> = migrations
    .filter { it.kind != MigrationKind.Once && it.batchSize() != null }
    .map { "${it.id} is ${it.kind.rerunnableLabel()} and uses inBatches, which only once-only migrations can use" }

/** Rule 8. */
private fun batchSizes(migrations: List<Migration>): List<String> = migrations.mapNotNull { migration ->
    migration.batchSize()?.takeIf { it !in 1..MAX_BATCH_SIZE }?.let {
        "${migration.id} has batchSize $it; batchSize is 1 to $MAX_BATCH_SIZE"
    }
}

/** The batch size of the migration's `inBatches` step; null when it has none. */
private fun Migration.batchSize(): Int? = (bodies.transactional as? InBatchesStep)?.batchSize
