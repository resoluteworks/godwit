package godwit.core.fixtures

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.event.CommandListener
import com.mongodb.kotlin.client.MongoClient
import org.slf4j.LoggerFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.mongodb.MongoDBAtlasLocalContainer
import org.testcontainers.mongodb.MongoDBContainer

/**
 * A MongoDB server other than the shared replica set, for the specs that need a deployment of another kind. The
 * container starts on first use, once per test JVM, and stops when the JVM exits.
 */
class OtherServer(private val description: String, private val create: () -> GenericContainer<*>) {
    private val log = LoggerFactory.getLogger(OtherServer::class.java)

    private val connectionString: String by lazy {
        val container = create()
        val begin = System.nanoTime()
        container.start()
        Runtime.getRuntime().addShutdownHook(Thread { container.stop() })
        val startupMs = (System.nanoTime() - begin) / 1_000_000
        log.info("{} started image={} startupMs={}", description, container.dockerImageName, startupMs)
        val port = container.getMappedPort(MONGODB_PORT)
        "mongodb://${container.host}:$port/?directConnection=true"
    }

    /** A new client named [appName], with [listeners] observing its commands. The caller closes it. */
    fun client(appName: String, vararg listeners: CommandListener): MongoClient = MongoClient.create(
        MongoClientSettings.builder()
            .applyConnectionString(ConnectionString(connectionString))
            .applicationName(appName)
            .apply { listeners.forEach { addCommandListener(it) } }
            .build()
    )

    private companion object {
        const val MONGODB_PORT = 27017
    }
}

/** A `mongod` without a replica set, ready once it logs that it waits for connections. */
private fun standalone(image: String) =
    MongoDBContainer(image).waitingFor(Wait.forLogMessage("(?i).*waiting for connections.*", 1))

private fun image(property: String): String =
    System.getProperty(property) ?: error("System property $property is not set; the test task sets it")

/** A standalone `mongod` of the shared replica set's release: no replica set, so no transactions. */
val standaloneMongo = OtherServer("standalone mongod") { standalone(image("godwit.mongoImage")) }

/** A release from 8.3 on, where `dropIndexes` succeeds for a missing index. Standalone: DDL needs no replica set. */
val newerMongo = OtherServer("newer mongod") { standalone(image("godwit.mongoNewerImage")) }

/** The Atlas local image, which serves Atlas Search indexes. Used only by specs tagged Atlas. */
val atlasMongo = OtherServer("Atlas local") { MongoDBAtlasLocalContainer(image("godwit.atlasLocalImage")) }
