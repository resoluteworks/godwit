package godwit.core.internal

import com.mongodb.ErrorCategory
import com.mongodb.MongoClientSettings
import com.mongodb.MongoException
import com.mongodb.ReadConcern
import com.mongodb.ReadPreference
import com.mongodb.TransactionOptions
import com.mongodb.WriteConcern
import com.mongodb.kotlin.client.ClientSession
import com.mongodb.kotlin.client.MongoCluster
import com.mongodb.kotlin.client.MongoCollection
import godwit.core.GodwitConfig
import godwit.core.MigrationKind
import org.bson.BsonDocument
import org.bson.BsonString
import org.bson.Document
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/** The godwit release that writes history documents, stored as `godwitVersion`. The build fills in the resource. */
internal val GODWIT_VERSION: String = Bookkeeping::class.java.getResource("godwit-version.txt")!!.readText().trim()

/** The format of the history documents this release writes, stored as `v`. */
internal const val DOCUMENT_FORMAT = 1

/** The client-side timeout of every lock operation: a stalled majority must not hold the heartbeat past the lease. */
internal val LOCK_OPERATION_TIMEOUT = 5.seconds

/**
 * The options of every transaction godwit runs: snapshot reads, a majority commit, the primary. The history record and
 * the data it describes commit atomically only with these.
 */
internal val TRANSACTION_OPTIONS: TransactionOptions = TransactionOptions.builder()
    .readConcern(ReadConcern.SNAPSHOT)
    .writeConcern(WriteConcern.MAJORITY)
    .readPreference(ReadPreference.primary())
    .build()

/**
 * godwit's two collections in one database, as the history store and the lock use them.
 *
 * Both handles use the driver's default codec registry, majority read and write concern and primary reads, whatever
 * the app's client is configured with: a lock acquired with `w:1` can be rolled back by a failover and leave two
 * holders, a history read below majority can see an APPLIED record that is rolled back later, and the app's codecs
 * must not change how godwit reads its own documents. The driver applies the client's `uuidRepresentation` to the
 * default registry all the same, which only a checkpoint's `lastId` can meet ([checkpoint]). The lock handle adds
 * [LOCK_OPERATION_TIMEOUT] to every operation. The lock document's `_id` is the history collection's name, so two
 * configurations with different history collections have different locks.
 */
internal class Bookkeeping(private val cluster: MongoCluster, databaseName: String, config: GodwitConfig) {
    private val database = cluster.getDatabase(databaseName)
        .withCodecRegistry(MongoClientSettings.getDefaultCodecRegistry())
        .withReadConcern(ReadConcern.MAJORITY)
        .withWriteConcern(WriteConcern.MAJORITY)
        .withReadPreference(ReadPreference.primary())

    /** The `_id` of the lock document. */
    val lockId: String = config.historyCollection

    val history: MongoCollection<Document> = database.getCollection(config.historyCollection, Document::class.java)

    val lock: MongoCollection<Document> = database.getCollection(config.lockCollection, Document::class.java)
        .withTimeout(LOCK_OPERATION_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)

    /** A causally consistent session on the cluster the app passed to `Godwit`. The caller closes it. */
    fun startSession(): ClientSession = cluster.startSession()
}

/** The [godwitComment] subject of a read of the whole history collection. */
internal const val HISTORY_SUBJECT = "history"

/** The [godwitComment] subject of every command on the lock collection. */
internal const val LOCK_SUBJECT = "lock"

/**
 * `{godwit: <subject>}`: the `comment` of every command godwit sends to its history and lock collections, and of the
 * read that opens each transaction of a transactional step ([HistoryStore.openStepTransaction], the page read of
 * [Pages]). The subject is the migration id for a command about one migration, [HISTORY_SUBJECT] for a read of the
 * whole history and [LOCK_SUBJECT] for the lock's commands.
 *
 * godwit-test's `SessionEscapeDetector` reads it. A command with it that starts a transaction opens godwit's
 * transaction, which the detector then checks every command on the thread against. One outside the transaction it
 * tracks tells it that transaction has ended: godwit sends its own commands outside a step's transaction only once that
 * transaction is over, so a window whose commit or abort the detector never saw (an interrupted thread, an abort that
 * found no server) ends there instead of failing godwit's own command. The server's logs and profiler show it too.
 */
internal fun godwitComment(subject: String): BsonDocument = BsonDocument("godwit", BsonString(subject))

/** The `kind` a migration of this kind stores in history. */
internal val MigrationKind.stored: StoredKind
    get() = when (this) {
        MigrationKind.Once -> StoredKind.ONCE
        MigrationKind.EveryStart -> StoredKind.EVERY_START
        is MigrationKind.Repeatable -> StoredKind.REPEATABLE
    }

/**
 * Runs [write] and returns its result, or null when the server refuses it with a duplicate key, which godwit's upserts
 * read as an answer: the lock's acquire as "held", the history's conditional upserts as "APPLIED, or inserted by a
 * concurrent write" ([HistoryStore]). Every other driver exception propagates unchanged.
 */
internal fun <T> nullOnDuplicateKey(write: () -> T): T? = try {
    write()
} catch (e: MongoException) {
    if (ErrorCategory.fromErrorCode(e.code) != ErrorCategory.DUPLICATE_KEY) throw e
    null
}

/** `{ $literal: value }`: a pipeline update stores [value] as it is, even when it starts with `$`. */
internal fun literal(value: Any): Document = Document("\$literal", value)
