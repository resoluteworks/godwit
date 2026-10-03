package godwit.core.fixtures

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.MongoCommandException
import com.mongodb.MongoException
import com.mongodb.WriteConcern
import com.mongodb.event.CommandListener
import com.mongodb.kotlin.client.MongoClient
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.ignoreExceptionsInstanceOf
import org.awaitility.kotlin.until
import org.awaitility.kotlin.withPollInterval
import org.bson.Document
import org.slf4j.LoggerFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private const val SET_NAME = "godwit-two"

private const val PRIMARY_PORT = 27017

private const val SECONDARY_PORT = 27018

private const val ALREADY_INITIALIZED = 23

/**
 * A replica set of two members in one container, for the specs that need the majority to fall behind the primary:
 * member 0 is the primary, member 1 a secondary with priority 0, so it never takes over. With two members the
 * majority is both, so [stallReplication] on the secondary holds the majority commit point back while the primary
 * keeps applying writes, as a lagging majority does. Both `mongod`s accept test commands. The container starts on first
 * use, once per test JVM, and stops when the JVM exits.
 */
object TwoMemberReplicaSet {
    private val log = LoggerFactory.getLogger(TwoMemberReplicaSet::class.java)

    private class Started(val primary: String, val secondary: String)

    private val started: Started by lazy { start() }

    /**
     * A new client on the primary named [appName], with the client-side operation timeout [timeout] when it is not
     * null and [listeners] observing its commands. The caller closes it.
     */
    fun client(appName: String, timeout: Duration?, vararg listeners: CommandListener): MongoClient =
        MongoClient.create(
            MongoClientSettings.builder()
                .applyConnectionString(ConnectionString(started.primary))
                .applicationName(appName)
                .apply { timeout?.let { timeout(it.inWholeMilliseconds, TimeUnit.MILLISECONDS) } }
                .apply { listeners.forEach { addCommandListener(it) } }
                .build()
        )

    /**
     * Stops the secondary's replication until the result is closed: from the moment this returns, a write applies on
     * the primary and its majority wait lasts until the close. It turns on the secondary's `stopReplProducer` fail
     * point, ends the secondary's wait for its next batch with a `w:1` write on the primary, and waits until the
     * secondary has met the fail point, so no write after the return reaches it. Closing it twice is harmless.
     */
    fun stallReplication(): AutoCloseable {
        MongoClient.create(started.secondary).use { secondary ->
            val admin = secondary.getDatabase("admin")
            val on = Document("configureFailPoint", "stopReplProducer").append("mode", "alwaysOn")
            val entered = admin.runCommand(on).get("count", Number::class.java).toLong()
            MongoClient.create(started.primary).use { primary ->
                primary.getDatabase("godwit-fixture").getCollection("wake", Document::class.java)
                    .withWriteConcern(WriteConcern.W1)
                    .insertOne(Document())
            }
            admin.runCommand(
                Document("waitForFailPoint", "stopReplProducer")
                    .append("timesEntered", entered + 1)
                    .append("maxTimeMS", 30_000)
            )
        }
        return AutoCloseable {
            MongoClient.create(started.secondary).use { secondary ->
                secondary.getDatabase("admin")
                    .runCommand(Document("configureFailPoint", "stopReplProducer").append("mode", "off"))
            }
        }
    }

    private fun start(): Started {
        val image = System.getProperty("godwit.mongoImage")
            ?: error("System property godwit.mongoImage is not set; the test task sets it from the mongoImage property")
        val script = listOf(PRIMARY_PORT, SECONDARY_PORT).joinToString(" && ") { port ->
            "mkdir -p /data/m$port && mongod --replSet $SET_NAME --port $port --bind_ip_all --dbpath /data/m$port " +
                "--setParameter enableTestCommands=1 --fork --logpath /data/m$port.log"
        } + " && tail -f /dev/null"
        val container = GenericContainer(DockerImageName.parse(image))
            .withExposedPorts(PRIMARY_PORT, SECONDARY_PORT)
            .withCommand("bash", "-c", script)
        val begin = System.nanoTime()
        container.start()
        Runtime.getRuntime().addShutdownHook(Thread { container.stop() })
        fun direct(port: Int) = "mongodb://${container.host}:${container.getMappedPort(port)}/?directConnection=true"
        val members = listOf(
            Document("_id", 0).append("host", "localhost:$PRIMARY_PORT"),
            Document("_id", 1).append("host", "localhost:$SECONDARY_PORT").append("priority", 0)
        )
        MongoClient.create(direct(PRIMARY_PORT)).use { primary ->
            // A freshly started mongod can time out the first handshake on a loaded machine, so the initiate is sent
            // until it succeeds; a member that an earlier attempt already initiated answers AlreadyInitialized (23).
            await withPollInterval 500.milliseconds atMost 2.minutes ignoreExceptionsInstanceOf
                MongoException::class until { initiate(primary, members) }
            MongoClient.create(direct(SECONDARY_PORT)).use { secondary ->
                // The members close their connections as they change state, so a hello in between can fail.
                await withPollInterval 200.milliseconds atMost 2.minutes ignoreExceptionsInstanceOf
                    MongoException::class until {
                        hello(primary).getBoolean("isWritablePrimary", false) &&
                            hello(secondary).getBoolean("secondary", false)
                    }
            }
        }
        val startupMs = (System.nanoTime() - begin) / 1_000_000
        log.info("two-member replica set started image={} startupMs={}", image, startupMs)
        return Started(direct(PRIMARY_PORT), direct(SECONDARY_PORT))
    }

    private fun hello(client: MongoClient): Document = client.getDatabase("admin").runCommand(Document("hello", 1))

    private fun initiate(primary: MongoClient, members: List<Document>): Boolean = try {
        primary.getDatabase("admin")
            .runCommand(Document("replSetInitiate", Document("_id", SET_NAME).append("members", members)))
        true
    } catch (e: MongoCommandException) {
        if (e.errorCode == ALREADY_INITIALIZED) true else throw e
    }
}
