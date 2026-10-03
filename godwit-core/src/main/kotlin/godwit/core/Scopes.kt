package godwit.core

import com.mongodb.WriteConcern
import com.mongodb.client.model.CreateCollectionOptions
import com.mongodb.kotlin.client.ClientSession
import com.mongodb.kotlin.client.MongoCollection
import com.mongodb.kotlin.client.MongoDatabase
import godwit.core.internal.SEARCH_INDEX_POLL
import godwit.core.internal.StepContext
import godwit.core.internal.ensureSearchIndex
import org.bson.Document
import org.bson.conversions.Bson
import kotlin.time.Duration

/**
 * What every step body can use. A step body is a lambda with a [StepScope] subclass as its receiver, so these members
 * are called unqualified: `collection("orders")`, `count("ordersUpdated", n)`, `checkLock()`.
 */
sealed class StepScope {
    /** The id of the migration this step belongs to. */
    abstract val id: String

    /**
     * The database being migrated, obtained from the cluster passed to [Godwit], so it carries that cluster's codec
     * registry and settings, with one exception: in an outside step its write concern is majority
     * ([OutsideTransactionScope.database]).
     */
    abstract val database: MongoDatabase

    /**
     * A collection of [database] as raw [Document]s, with [database]'s settings: majority write concern in an outside
     * step. In a transactional step the transaction's majority commit carries every write, whatever the collection's
     * write concern.
     */
    fun collection(name: String): MongoCollection<Document> = database.getCollection(name, Document::class.java)

    /**
     * Adds [n] to the counter [name]. Counters are stored in the history document, logged on the "Applied migration"
     * line and returned in [MigrationOutcome.counts]; the same name used in both steps adds up. A transactional step's
     * counters reset when the driver runs its body again, so they count committed work only.
     */
    abstract fun count(name: String, n: Long)

    /** [count] for an `Int`, such as a list size. */
    fun count(name: String, n: Int): Unit = count(name, n.toLong())

    /**
     * Throws [LockLostException] when this run no longer holds the migration lock. godwit calls it before and after
     * every step and before every commit; a long loop in a step calls it once per item, so a run that lost the lock
     * stops early.
     */
    abstract fun checkLock()
}

/**
 * The receiver of an `outsideTransaction` step. There is no session and no transaction: every write commits on its
 * own, and the step runs again from the start on a retry, so every call in it must be idempotent.
 *
 * [database] and [collection] write with majority write concern, whatever the app's client sets, so what the step
 * writes through them, DDL included, is majority-committed before godwit records the migration APPLIED. An app service
 * the step calls keeps its own settings, and its writes here need majority write concern too: `w=majority` on its
 * client, or `withWriteConcern(WriteConcern.MAJORITY)` on its collection. A client that sets no write concern writes
 * with the server's default: the cluster-wide default when one is set; otherwise majority on most replica sets from
 * MongoDB 5.0, and `w:1` before 5.0 and on a primary-secondary-arbiter replica set.
 *
 * The DDL helpers are members of this scope only. `ensureCollection(...)` does not resolve inside `inTransaction` or
 * `inBatches`, where DDL fails at runtime.
 */
class OutsideTransactionScope internal constructor(
    override val id: String,
    appDatabase: MongoDatabase,
    private val context: StepContext
) : StepScope() {
    /**
     * The database being migrated, from the cluster passed to [Godwit], with majority write concern. Every other
     * setting is the app database's: codec registry, read preference, read concern and timeout. Majority replaces the
     * client's whole write concern, so its `wtimeoutMS` and `journal` do not apply here; the client's `timeoutMS` is
     * what bounds the majority wait.
     *
     * A failover can roll back a write the primary acknowledged before a majority had it. Written with `w:1`, a
     * step's write could be rolled back after godwit has recorded the migration APPLIED with majority, and the step
     * would never run again to redo it; with majority, it survives every failover the APPLIED record survives. Calls on
     * it that take a write concern carry majority, DDL included; [MongoDatabase.runCommand] sends its command as
     * written, so a command that writes puts `writeConcern` in its document. A write concern the step sets itself
     * (`database.withWriteConcern(...)`) replaces majority.
     */
    override val database: MongoDatabase = appDatabase.withWriteConcern(WriteConcern.MAJORITY)

    override fun count(name: String, n: Long): Unit = context.count(name, n)

    override fun checkLock(): Unit = context.checkLock()

    /** [MongoDatabase.ensureCollection] on [database], so the create carries majority write concern. */
    fun ensureCollection(name: String, options: CreateCollectionOptions = CreateCollectionOptions()): Boolean =
        database.ensureCollection(name, options)

    /**
     * [MongoCollection.ensureSearchIndex] on [collection], calling [checkLock] between polls while it waits. The
     * search index commands take no write concern, so the create carries none.
     */
    fun ensureSearchIndex(collection: String, name: String, definition: Bson, awaitReady: Duration? = null): Boolean =
        ensureSearchIndex(collection(collection), name, definition, awaitReady, SEARCH_INDEX_POLL, ::checkLock)

    /** [MongoCollection.dropIndexIfExists] on [collection], so the drop carries majority write concern. */
    fun dropIndexIfExists(collection: String, indexName: String): Boolean =
        collection(collection).dropIndexIfExists(indexName)
}

/**
 * The receiver of an `inTransaction` or `inBatches` step. Its operations run in one transaction (snapshot read
 * concern, majority write concern, primary reads) that also commits godwit's history write.
 *
 * Pass [session] to every driver call and every app service the step calls. An operation without it runs outside the
 * transaction: it is not rolled back, and it can block on the transaction's own writes until the transaction times
 * out. `SessionEscapeDetector` in godwit-test fails a test that forgets it. The session belongs to the cluster passed
 * to [Godwit], so app services must use a database from that same client.
 *
 * The driver runs the body again on a transient transaction error (for up to 120 s), so the body must not call
 * external services or keep state outside the transaction. godwit pauses before each run after the first (5 ms,
 * growing by half each time to at most 500 ms, with jitter). DDL is not allowed in a transaction: index builds on
 * existing collections, `drop`, `dropIndexes`, `renameCollection` and `collMod` fail; they belong in
 * `outsideTransaction`.
 */
class TransactionScope internal constructor(
    override val id: String,
    override val database: MongoDatabase,
    /** The session that carries the transaction. */
    val session: ClientSession,
    /** 1 on the first run of the body, plus one per driver retry after a transient error. Per page in `inBatches`. */
    val attempt: Int,
    private val context: StepContext
) : StepScope() {
    override fun count(name: String, n: Long): Unit = context.count(name, n)

    override fun checkLock(): Unit = context.checkLock()
}
