package godwit.core.fixtures

import com.mongodb.client.model.Filters.eq
import com.mongodb.kotlin.client.MongoClient
import com.mongodb.kotlin.client.MongoCollection
import com.mongodb.kotlin.client.MongoDatabase
import godwit.core.Godwit
import godwit.core.GodwitConfig
import godwit.core.internal.Tuning
import org.bson.Document
import kotlin.time.Duration

/**
 * One process running migrations in a test: a client of its own named [appName], so that a fail point can target its
 * commands alone and [recorder] sees them, and a [Godwit] on [db] with [config] and the holder `<appName>/1`. Several
 * fixtures on one [db] are several processes on one database; the one that created [db] closes it.
 */
internal class GodwitFixture(
    val appName: String = "godwit-runner",
    config: GodwitConfig = GodwitConfig(),
    tuning: Tuning = Tuning(),
    val recorder: CommandRecorder = CommandRecorder(),
    db: TestDatabase? = null,
    /** The client's `timeoutMS`, when it has one. */
    timeout: Duration? = null
) : AutoCloseable {
    private val ownsDatabase = db == null

    val db: TestDatabase = db ?: TestMongo.database()

    val client: MongoClient = TestMongo.client(appName, timeout, recorder)

    val config: GodwitConfig = config.copy(holder = "$appName/1")

    val godwit = Godwit(client, this.db.name, this.config, tuning)

    /** The database through the test's own client, which no fail point targets. */
    val database: MongoDatabase get() = db.database

    val history: MongoCollection<Document> get() = collection(config.historyCollection)

    fun collection(name: String): MongoCollection<Document> = database.getCollection(name, Document::class.java)

    /** The history document of [id], as it is now. */
    fun stored(id: String): Document? = history.find(eq("_id", id)).firstOrNull()

    /** Another process on the same database. */
    fun process(
        appName: String,
        config: GodwitConfig = GodwitConfig(),
        tuning: Tuning = Tuning(),
        recorder: CommandRecorder = CommandRecorder()
    ) = GodwitFixture(appName, config, tuning, recorder, db)

    override fun close() {
        client.close()
        if (ownsDatabase) db.close()
    }
}
