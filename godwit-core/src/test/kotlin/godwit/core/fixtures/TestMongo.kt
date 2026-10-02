package godwit.core.fixtures

import com.mongodb.kotlin.client.MongoClient
import com.mongodb.kotlin.client.MongoDatabase
import org.slf4j.LoggerFactory
import org.testcontainers.mongodb.MongoDBContainer
import java.util.UUID

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
 * so tests never see each other's data. The server accepts test commands, which the fail points of later phases need.
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

    /** A new client and a new, empty database. The caller closes the result. */
    fun database(): TestDatabase = TestDatabase(client(), UUID.randomUUID().toString())

    private fun start(): Started {
        val container = MongoDBContainer(image)
            .withReplicaSet()
            .withCommand("--replSet", REPLICA_SET_NAME, "--setParameter", "enableTestCommands=1")
        val begin = System.nanoTime()
        container.start()
        val startupMs = (System.nanoTime() - begin) / 1_000_000
        Runtime.getRuntime().addShutdownHook(Thread { container.stop() })
        log.debug("started image={} startupMs={}", image, startupMs)
        return Started(container, startupMs)
    }
}
