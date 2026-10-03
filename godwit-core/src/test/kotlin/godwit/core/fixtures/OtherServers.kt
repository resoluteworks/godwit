package godwit.core.fixtures

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.event.CommandListener
import com.mongodb.kotlin.client.MongoClient
import org.bson.Document
import org.slf4j.LoggerFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.mongodb.MongoDBAtlasLocalContainer
import org.testcontainers.mongodb.MongoDBContainer

/** How often a container that fails to start is replaced by a new one before the fixture gives up. */
internal const val CONTAINER_START_ATTEMPTS = 3

/**
 * Starts a container from [create], replacing it with a new one when it fails to start, up to
 * [CONTAINER_START_ATTEMPTS] times: on a loaded machine a container can miss its readiness wait once. Returns the
 * started container; the last failure propagates.
 */
internal fun <T : GenericContainer<*>> startWithRetries(description: String, create: () -> T): T {
    val log = LoggerFactory.getLogger(OtherServer::class.java)
    var attempt = 1
    while (true) {
        val container = create()
        try {
            container.start()
            return container
        } catch (e: RuntimeException) {
            runCatching { container.stop() }
            if (attempt == CONTAINER_START_ATTEMPTS) throw e
            log.warn("{} failed to start on attempt {}, starting a new container", description, attempt, e)
            attempt++
        }
    }
}

/**
 * A MongoDB server other than the shared replica set, for the specs that need a deployment of another kind. The
 * container starts on first use, once per test JVM, and stops when the JVM exits.
 */
class OtherServer(private val description: String, private val create: () -> GenericContainer<*>) {
    private val log = LoggerFactory.getLogger(OtherServer::class.java)

    /** The server's connection string, with `directConnection=true`; reading it starts the container. */
    val connectionString: String by lazy {
        val begin = System.nanoTime()
        val container = startWithRetries(description, create)
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

    /**
     * As [TestMongo.failCommand], on this server: only a server started with test commands, such as [standaloneMongo],
     * accepts it. Closing the result turns the fail point off.
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

    private fun configureFailCommand(mode: Any, data: Document) {
        client("fail-points").use { admin ->
            admin.getDatabase("admin").runCommand(
                Document("configureFailPoint", "failCommand").append("mode", mode).append("data", data)
            )
        }
    }

    private companion object {
        const val MONGODB_PORT = 27017
    }
}

/**
 * A `mongod` without a replica set, ready once it logs that it waits for connections. It accepts test commands, so
 * that [OtherServer.failCommand] works on it.
 */
private fun standalone(image: String) = MongoDBContainer(image)
    .withCommand("--setParameter", "enableTestCommands=1")
    .waitingFor(Wait.forLogMessage("(?i).*waiting for connections.*", 1))

private fun image(property: String): String =
    System.getProperty(property) ?: error("System property $property is not set; the test task sets it")

/** A standalone `mongod` of the shared replica set's release: no replica set, so no transactions. */
val standaloneMongo = OtherServer("standalone mongod") { standalone(image("godwit.mongoImage")) }

/** A release from 8.3 on, where `dropIndexes` succeeds for a missing index. Standalone: DDL needs no replica set. */
val newerMongo = OtherServer("newer mongod") { standalone(image("godwit.mongoNewerImage")) }

/** The Atlas local image, which serves Atlas Search indexes. Used only by specs tagged Atlas. */
val atlasMongo = OtherServer("Atlas local") { MongoDBAtlasLocalContainer(image("godwit.atlasLocalImage")) }
