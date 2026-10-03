package godwit.core.internal

import com.mongodb.kotlin.client.MongoDatabase
import godwit.core.Migration
import godwit.core.MigrationKind
import java.time.Instant

/**
 * The ids adoption can record, in list order: the once-only migrations in list order, each preceded by the ids its
 * `supersedes` list names, in that list's order. A standalone server gets them last-listed first.
 */
internal fun adoptableIds(migrations: List<Migration>): List<String> =
    migrations.filter { it.kind == MigrationKind.Once }.flatMap { it.supersedes + it.id }

/**
 * What one call of the hook adds to history: [toRecord], the adoptable ids it returned that history does not hold
 * yet, in list order; and [ignored], the ids it returned that the list neither declares as once-only nor names in a
 * `supersedes` list, sorted. An adoptable id that history holds already is neither.
 */
internal data class AdoptionSelection(val toRecord: List<String>, val ignored: List<String>)

/** Selects from [returned], the hook's answer, against [migrations] and the [history] read under the lock. */
internal fun selectAdopted(
    migrations: List<Migration>,
    returned: Set<String>,
    history: List<HistoryRecord>
): AdoptionSelection {
    val adoptable = adoptableIds(migrations)
    val held = history.map { it.id }.toSet()
    return AdoptionSelection(
        toRecord = adoptable.filter { it in returned && it !in held },
        ignored = (returned - adoptable.toSet()).sorted()
    )
}

/**
 * Adoption, under the lock, on a start whose history holds nothing but ADOPTED documents (or none): calls the app's
 * [hook] on the app's [database], checks the lock, and records the adoptable ids history lacks as APPLIED with origin
 * ADOPTED, with the history store's insert-only upserts. The records go in one transaction when the server runs
 * transactions, and one at a time, last-listed first, when it does not; [topology] answers which, and only when there
 * is an id to record.
 *
 * Every call whose hook returns while the lock is held logs "Adopted applied migrations" with the ids it recorded, also
 * when recording them fails: on a standalone server the ids written before the failure stay recorded, and the line is
 * their only trace in the log. An exception from the hook, a lost lock and a driver exception from the records
 * propagate unchanged: the caller releases the lock, and the next start calls the hook again and records what is
 * missing. A lock lost while the hook runs records nothing and logs no line.
 */
internal class Adoption(
    private val hook: (MongoDatabase) -> Set<String>,
    private val database: MongoDatabase,
    private val store: HistoryStore,
    private val topology: Topology
) {
    /**
     * Runs one adoption for [migrations] against [history], the history read under [lock], and returns the ids this
     * call recorded, in list order. [writer] is the run that records them.
     */
    fun adopt(migrations: List<Migration>, history: List<HistoryRecord>, lock: HeldLock, writer: Writer): List<String> {
        val returned = hook(database)
        lock.checkLock(null)
        val selection = selectAdopted(migrations, returned, history)
        val inserted = mutableSetOf<String>()
        try {
            if (selection.toRecord.isNotEmpty()) {
                val transactions = topology.supportsTransactions()
                store.recordAdopted(
                    selection.toRecord,
                    writer,
                    Instant.now(),
                    transactions,
                    checkLock = { lock.checkLock(null) },
                    recorded = { inserted += it }
                )
            }
        } finally {
            Log.adoptedAppliedMigrations(selection.toRecord.filter { it in inserted }, selection.ignored)
        }
        return selection.toRecord.filter { it in inserted }
    }
}
