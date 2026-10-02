package godwit.core

import com.mongodb.client.model.CreateCollectionOptions
import com.mongodb.kotlin.client.ClientSession
import com.mongodb.kotlin.client.MongoCollection
import com.mongodb.kotlin.client.MongoDatabase
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
     * registry and settings.
     */
    abstract val database: MongoDatabase

    /** A collection of [database] as raw [Document]s. */
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
 * The DDL helpers are members of this scope only. `ensureCollection(...)` does not resolve inside `inTransaction` or
 * `inBatches`, where DDL fails at runtime.
 */
class OutsideTransactionScope internal constructor(override val id: String, override val database: MongoDatabase) :
    StepScope() {
    override fun count(name: String, n: Long): Unit = throw NotImplementedError("P3")

    override fun checkLock(): Unit = throw NotImplementedError("P3")

    /** [MongoDatabase.ensureCollection] on [database]. */
    fun ensureCollection(name: String, options: CreateCollectionOptions = CreateCollectionOptions()): Boolean =
        throw NotImplementedError("P3")

    /** [MongoCollection.ensureSearchIndex] on [collection], calling [checkLock] between polls while it waits. */
    fun ensureSearchIndex(collection: String, name: String, definition: Bson, awaitReady: Duration? = null): Boolean =
        throw NotImplementedError("P3")

    /** [MongoCollection.dropIndexIfExists] on [collection]. */
    fun dropIndexIfExists(collection: String, indexName: String): Boolean = throw NotImplementedError("P3")
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
    val attempt: Int
) : StepScope() {
    override fun count(name: String, n: Long): Unit = throw NotImplementedError("P3")

    override fun checkLock(): Unit = throw NotImplementedError("P3")
}
