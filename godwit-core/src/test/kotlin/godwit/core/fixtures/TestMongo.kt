package godwit.core.fixtures

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.event.CommandListener
import com.mongodb.kotlin.client.MongoClient
import com.mongodb.kotlin.client.MongoDatabase
import org.bson.Document
import org.slf4j.LoggerFactory
import org.testcontainers.mongodb.MongoDBContainer
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

private const val REPLICA_SET_NAME = "docker-rs"

/**
 * A database of its own on the shared replica set, with the client that reaches it. Closing it closes the client.
 */
class TestDatabase internal constructor(val client: MongoClient, val name: String) : AutoCloseable {
    val database: MongoDatabase get() = client.getDatabase(name)

    override fun close() = client.close()
}

/**
 * The MongoDB replica set that the tests of this module run against. The container starts on first use and is shared
 * by every test in the JVM; each call to [database] gives a test its own client and a database named by a random UUID,
 * so tests never see each other's data. The server accepts test commands, which [failCommand] needs.
 */
object TestMongo {
    private val log = LoggerFactory.getLogger(TestMongo::class.java)

    /** The image of the container, set by the test task from the `mongoImage` Gradle property. */
    val image: String = System.getProperty("godwit.mongoImage")
        ?: error("System property godwit.mongoImage is not set; the test task sets it from the mongoImage property")

    private class Started(val container: MongoDBContainer, val startupMs: Long)

    private val started: Started by lazy { start() }

    val container: MongoDBContainer get() = started.container

    /** The time the container took from the start request until the replica set had a primary. */
    val startupMs: Long get() = started.startupMs

    val connectionString: String get() = container.connectionString

    /** A new client on the replica set. The caller closes it. */
    fun client(): MongoClient = MongoClient.create(connectionString)

    /**
     * A new client named [appName], so that a fail point can target its commands alone, with [listeners] observing
     * them. The caller closes it.
     */
    fun client(appName: String, vararg listeners: CommandListener): MongoClient = client(appName, null, *listeners)

    /**
     * [client] with a client-side operation timeout (`timeoutMS`), which bounds every operation of the client and each
     * `withTransaction` as a whole, when [timeout] is not null.
     */
    fun client(appName: String, timeout: Duration?, vararg listeners: CommandListener): MongoClient =
        MongoClient.create(
            MongoClientSettings.builder()
                .applyConnectionString(ConnectionString(connectionString))
                .applicationName(appName)
                .apply { timeout?.let { timeout(it.inWholeMilliseconds, TimeUnit.MILLISECONDS) } }
                .apply { listeners.forEach { addCommandListener(it) } }
                .build()
        )

    /** A new client and a new, empty database. The caller closes the result. */
    fun database(): TestDatabase = TestDatabase(client(), UUID.randomUUID().toString())

    /**
     * Turns on the server's `failCommand` fail point for the [commands] of the clients named [appName], in [mode]
     * (`"alwaysOn"`, `Document("times", n)` or `Document("skip", n)`), with the rest of its [data] (`errorCode`,
     * `blockConnection`, `blockTimeMS`, `namespace`). Closing the result turns it off.
     *
     * A fail point on `find` without a `namespace` also fails the first retryable write of a session the server has
     * not seen: the server reads that session's state from `config.transactions` with a `find` of its own, under the
     * client's name. A `namespace` (`"<database>.<collection>"`) keeps it to the reads meant.
     */
    fun failCommand(appName: String, commands: List<String>, mode: Any, data: Document): AutoCloseable {
        configureFailCommand(
            mode,
            Document("failCommands", commands).append("appName", appName).also {
                it.putAll(data)
            }
        )
        return AutoCloseable { configureFailCommand("off", Document()) }
    }

    private fun configureFailCommand(mode: Any, data: Document) = configureFailPoint("failCommand", mode, data)

    /**
     * Turns on the server's fail point [name] in [mode] with [data], such as `hangBeforeWaitingForWriteConcern`, which
     * holds every write's reply before its write concern wait. Closing the result turns it off.
     */
    fun failPoint(name: String, mode: Any, data: Document = Document()): AutoCloseable {
        configureFailPoint(name, mode, data)
        return AutoCloseable { configureFailPoint(name, "off", Document()) }
    }

    private fun configureFailPoint(name: String, mode: Any, data: Document) {
        client().use { admin ->
            admin.getDatabase("admin").runCommand(
                Document("configureFailPoint", name).append("mode", mode).append("data", data)
            )
        }
    }

    /**
     * Starts the replica set, replacing the container when it fails to start: the container's own wait for the replica
     * set's primary is about 6 s, which a loaded machine can miss.
     */
    private fun start(): Started {
        val begin = System.nanoTime()
        val container = startWithRetries("test replica set") {
            MongoDBContainer(image)
                .withReplicaSet()
                .withCommand("--replSet", REPLICA_SET_NAME, "--setParameter", "enableTestCommands=1")
        }
        val startupMs = (System.nanoTime() - begin) / 1_000_000
        Runtime.getRuntime().addShutdownHook(Thread { container.stop() })
        log.debug("started image={} startupMs={}", image, startupMs)
        return Started(container, startupMs)
    }
}
