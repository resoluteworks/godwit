package godwit.core.fixtures

import com.mongodb.kotlin.client.MongoClient
import com.mongodb.kotlin.client.MongoCollection
import com.mongodb.kotlin.client.MongoDatabase
import godwit.core.Godwit
import godwit.core.GodwitConfig
import godwit.core.HistoryState
import godwit.core.Origin
import org.bson.Document
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * A stand-in for an app's adoption hook: it returns [ids] as they are at each call, so a test can correct "the old
 * record" between two starts, and counts its calls. [onCall] runs inside each call, before it returns, on the thread
 * that holds the lock.
 */
class CountingHook(vararg ids: String, private val onCall: () -> Unit = {}) : (MongoDatabase) -> Set<String> {
    @Volatile
    var ids: Set<String> = ids.toSet()

    private val counter = AtomicInteger()

    /** The calls so far. */
    val calls: Int get() = counter.get()

    /** The name of the database of the last call. */
    @Volatile
    var database: String? = null
        private set

    override fun invoke(database: MongoDatabase): Set<String> {
        counter.incrementAndGet()
        this.database = database.name
        onCall()
        return ids
    }
}

/**
 * A history document as an earlier run, adoption or mark left it, with the fields the planner reads and the ones a
 * run writes. [kind] is the stored kind: `ONCE`, `REPEATABLE` or `EVERY_START`.
 */
fun historyDocument(
    id: String,
    state: HistoryState = HistoryState.APPLIED,
    origin: Origin = Origin.RAN,
    kind: String = "ONCE"
): Document = Document("_id", id).append("kind", kind).append("steps", emptyList<String>())
    .append("state", state.name).append("origin", origin.name).append("attempts", 1)
    .append("owner", "token-of-an-earlier-run").append("holder", "shop-earlier/1").append("runId", "run-earlier")

/** Plants the ids as ADOPTED documents, as an earlier adoption left them. */
fun MongoCollection<Document>.plantAdopted(vararg ids: String) {
    insertMany(ids.map { historyDocument(it, origin = Origin.ADOPTED) })
}

/** The ids of the documents in this history collection, in id order, each with its origin: `001-a ADOPTED`. */
fun MongoCollection<Document>.origins(): List<String> =
    find().sort(Document("_id", 1)).map { "${it.getString("_id")} ${it.getString("origin")}" }.toList()

/**
 * One process on a database of [standaloneMongo], which has no transactions: a client of its own named [appName],
 * whose commands [recorder] sees and which [OtherServer.failCommand] can target, and a [Godwit] with [config] and the
 * holder `<appName>/1`. [process] adds another process on the same database.
 */
class StandaloneProcess(
    val appName: String,
    config: GodwitConfig,
    val recorder: CommandRecorder = CommandRecorder(),
    val databaseName: String = UUID.randomUUID().toString()
) : AutoCloseable {
    val client: MongoClient = standaloneMongo.client(appName, recorder)

    val config: GodwitConfig = config.copy(holder = "$appName/1")

    val godwit = Godwit(client, databaseName, this.config)

    val database: MongoDatabase get() = client.getDatabase(databaseName)

    val history: MongoCollection<Document> get() = database.getCollection(
        config.historyCollection,
        Document::class.java
    )

    fun process(appName: String, config: GodwitConfig, recorder: CommandRecorder = CommandRecorder()) =
        StandaloneProcess(appName, config, recorder, databaseName)

    /** A fail point on this process's [commands] that write to its history collection only. */
    fun failHistoryWrites(commands: List<String>, mode: Any, data: Document): AutoCloseable =
        standaloneMongo.failCommand(appName, commands, mode, Document(data).append("namespace", historyNamespace))

    private val historyNamespace get() = "$databaseName.${config.historyCollection}"

    override fun close() = client.close()
}

/** A fail point on this process's [commands] that write to its history collection only, on the shared replica set. */
internal fun GodwitFixture.failHistoryWrites(commands: List<String>, mode: Any, data: Document): AutoCloseable =
    TestMongo.failCommand(
        appName,
        commands,
        mode,
        Document(data).append("namespace", "${db.name}.${config.historyCollection}")
    )

/** The `_id` each `update` command this recorder saw on [collection] filtered on, in the order they were sent. */
fun CommandRecorder.updatedIds(collection: String = "godwit-history"): List<String> = commands("update")
    .filter { it.collection == collection }
    .map { it.command.getArray("updates")[0].asDocument().getDocument("q").getString("_id").value }
