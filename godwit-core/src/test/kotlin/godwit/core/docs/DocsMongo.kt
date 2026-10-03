package godwit.core.docs

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.event.CommandListener
import com.mongodb.kotlin.client.MongoClient
import com.mongodb.kotlin.client.MongoCollection
import com.mongodb.kotlin.client.MongoDatabase
import godwit.core.Godwit
import godwit.core.GodwitConfig
import godwit.core.fixtures.OtherServer
import godwit.core.fixtures.TestMongo
import godwit.core.internal.Tuning
import org.bson.BsonDocument
import org.bson.Document
import org.testcontainers.mongodb.MongoDBContainer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * The replica set the docs' scenarios run on: a single-node replica set of the shared replica set's release, with test
 * commands for fail points, and its data on tmpfs, so the scenarios that page through a million orders commit their
 * pages quickly. It is a server of its own, so a scenario that changes a server parameter or cuts a client off changes
 * nothing for the other specs. It starts on first use and stops when the JVM exits.
 */
object DocsMongo {
    private val server = OtherServer("docs replica set") {
        MongoDBContainer(TestMongo.image)
            .withReplicaSet()
            .withCommand("--replSet", "docs-rs", "--setParameter", "enableTestCommands=1")
            .withTmpFs(mapOf("/data/db" to "rw"))
    }

    /** The server, `directConnection=true`. */
    val connectionString: String get() = server.connectionString

    /** The server's address, for a [Partition] in front of it. */
    val address: InetSocketAddress
        get() = ConnectionString(connectionString).hosts.single().let { host ->
            InetSocketAddress(host.substringBeforeLast(':'), host.substringAfterLast(':').toInt())
        }

    /** A new client named [appName], on [connectionString] by default, with [settings] applied last. */
    fun client(
        appName: String,
        listeners: List<CommandListener> = emptyList(),
        connectionString: String = this.connectionString,
        settings: MongoClientSettings.Builder.() -> Unit = {}
    ): MongoClient = MongoClient.create(
        MongoClientSettings.builder()
            .applyConnectionString(ConnectionString(connectionString))
            .applicationName(appName)
            .apply { listeners.forEach { addCommandListener(it) } }
            .apply(settings)
            .build()
    )

    /** As [godwit.core.fixtures.TestMongo.failCommand], on this server. Closing the result turns it off. */
    fun failCommand(appName: String, commands: List<String>, mode: Any, data: Document): AutoCloseable =
        server.failCommand(appName, commands, mode, data)

    /** Runs [command] on the admin database. */
    fun admin(command: Document): Document = client("docs-admin").use {
        it.getDatabase("admin").runCommand(command)
    }

    private val created = ConcurrentHashMap.newKeySet<String>()

    /** A new database name, for a scenario of its own. [drop] removes the database. */
    fun newDatabase(): String = UUID.randomUUID().toString().also { created += it }

    /** The databases [newDatabase] has named so far. */
    fun databases(): Set<String> = created.toSet()

    /** Drops [databases]: the orders of the large scenarios would hold on to the tmpfs's memory otherwise. */
    fun drop(databases: Set<String>) {
        if (databases.isEmpty()) return
        client("docs-cleanup").use { client -> databases.forEach { client.getDatabase(it).drop() } }
        created -= databases
    }
}

/**
 * One process of the shop: a client of its own on the docs replica set (or through [connectionString]), named after
 * [holder]'s host part so that fail points can target it, and a [Godwit] on [database] with [config] and [holder].
 */
internal class ShopProcess(
    val holder: String,
    val database: String,
    config: GodwitConfig = GodwitConfig(),
    tuning: Tuning = Tuning(),
    listeners: List<CommandListener> = emptyList(),
    connectionString: String = DocsMongo.connectionString,
    settings: MongoClientSettings.Builder.() -> Unit = {}
) : AutoCloseable {
    val appName: String = holder.substringBefore('/')

    val client: MongoClient = DocsMongo.client(appName, listeners, connectionString, settings)

    val config: GodwitConfig = config.copy(holder = holder)

    val godwit: Godwit = Godwit(client, database, this.config, tuning)

    val db: MongoDatabase get() = client.getDatabase(database)

    fun collection(name: String): MongoCollection<Document> = db.getCollection(name, Document::class.java)

    override fun close() = client.close()
}

/** How a scenario reads what godwit stored: its own client, no fail point, no partition. */
class Observer(private val database: String) : AutoCloseable {
    private val client = DocsMongo.client("docs-observer")

    val db: MongoDatabase get() = client.getDatabase(database)

    fun collection(name: String): MongoCollection<Document> = db.getCollection(name, Document::class.java)

    /** The history document of [id] as stored. */
    fun history(id: String, collection: String = "godwit-history"): BsonDocument? =
        db.getCollection(collection, BsonDocument::class.java).find(BsonDocument("_id", org.bson.BsonString(id)))
            .firstOrNull()

    /** The lock document as stored. */
    fun lock(): BsonDocument? = db.getCollection("godwit-lock", BsonDocument::class.java).find().firstOrNull()

    override fun close() = client.close()
}

/**
 * A TCP proxy in front of the docs replica set, through which one process reaches the database. [cut] cuts that
 * process off as a network partition does: the connections it has are closed, and new ones are refused. The other
 * processes, which connect to the server directly, are not affected.
 */
class Partition(private val target: InetSocketAddress) : AutoCloseable {
    private val listening = ServerSocket(0, 50, InetAddress.getLoopbackAddress())

    private val sockets = CopyOnWriteArrayList<Socket>()

    /** The proxy, `directConnection=true`: a client sees one server, the replica set's primary. */
    val connectionString: String = "mongodb://localhost:${listening.localPort}/?directConnection=true"

    init {
        thread(isDaemon = true, name = "partition-accept") {
            while (true) {
                val inbound = try {
                    listening.accept()
                } catch (_: IOException) {
                    break
                }
                val outbound = try {
                    Socket(target.address, target.port)
                } catch (_: IOException) {
                    inbound.close()
                    continue
                }
                sockets += inbound
                sockets += outbound
                pump(inbound, outbound)
                pump(outbound, inbound)
            }
        }
    }

    private fun pump(from: Socket, to: Socket) = thread(isDaemon = true, name = "partition-pump") {
        try {
            from.getInputStream().copyTo(to.getOutputStream())
        } catch (_: IOException) {
            // The partition, or the other side, closed the connection.
        } finally {
            from.close()
            to.close()
        }
    }

    /** Closes every connection through the proxy and refuses new ones. */
    fun cut() {
        listening.close()
        sockets.forEach { runCatching { it.close() } }
    }

    override fun close() = cut()
}
