package godwit.test.internal

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.kotlin.client.MongoClient
import godwit.test.SessionEscapeDetector
import org.slf4j.LoggerFactory
import org.testcontainers.lifecycle.Startable
import org.testcontainers.mongodb.MongoDBAtlasLocalContainer
import org.testcontainers.mongodb.MongoDBContainer
import java.util.Properties
import kotlin.time.TimeSource

/** How often a container that fails to start is replaced by a new one before the first use gives up. */
internal const val CONTAINER_START_ATTEMPTS = 3

/** The logger of godwit-test's own lines, under godwit's `godwit` logger. */
private val log = LoggerFactory.getLogger("godwit.test")

/**
 * The images the containers run: the releases godwit's own tests run against, which the build writes from
 * `gradle.properties` into `images.properties`.
 */
internal object Images {
    private val properties = Properties().apply {
        Images::class.java.getResourceAsStream("images.properties")!!.use(::load)
    }

    /** The MongoDB image of the replica set, such as `mongo:8.0.17`. */
    val mongo: String = properties.getProperty("mongoImage")

    /** The Atlas local image, which also serves Atlas Search, such as `mongodb/mongodb-atlas-local:8.0`. */
    val atlasLocal: String = properties.getProperty("atlasLocalImage")
}

/**
 * Starts a container from [create], replacing it with a new one when its start fails, up to [attempts] times: on a
 * loaded machine a container can miss its readiness wait once. Each failure but the last logs
 * `godwit-test container failed to start` at WARN with `image`, `attempt` and `error`, and the failed container is
 * stopped. Returns the started container; the last failure propagates.
 */
internal fun <C : Startable> startWithRetries(image: String, attempts: Int, create: () -> C): C {
    var attempt = 1
    while (true) {
        val container = create()
        try {
            container.start()
            return container
        } catch (e: RuntimeException) {
            runCatching { container.stop() }
            if (attempt == attempts) throw e
            log.atWarn().setMessage("godwit-test container failed to start")
                .addKeyValue("image", image)
                .addKeyValue("attempt", attempt)
                .addKeyValue("error", e.toString())
                .log()
            attempt++
        }
    }
}

/**
 * A MongoDB container that every `testGodwit()` call of one kind shares within the JVM, and the one client on it,
 * with [detector] installed.
 *
 * The container starts on the first read of [client], at most once: [lazy] serialises the first reads, and a start
 * that fails after [CONTAINER_START_ATTEMPTS] attempts propagates, so the next read tries again. Once started, it logs
 * `godwit-test container started` at INFO with `image` and `startupMs` (from the first start request until the
 * container is ready), and a shutdown hook closes the client and stops the container when the JVM exits.
 */
internal class SharedContainer<C : Startable>(
    /** The image, as the trace names it. */
    val image: String,
    private val create: (image: String) -> C,
    private val connectionString: (C) -> String
) {
    private class Started<C>(val container: C, val client: MongoClient)

    private val started: Started<C> by lazy { start() }

    /** The detector on [client]. */
    val detector = SessionEscapeDetector()

    /** The container, started. */
    val container: C get() = started.container

    /** The client on the container. Every `TestGodwit` of this kind shares it, so a test must not close it. */
    val client: MongoClient get() = started.client

    private fun start(): Started<C> {
        val begin = TimeSource.Monotonic.markNow()
        val container = startWithRetries(image, CONTAINER_START_ATTEMPTS) { create(image) }
        val startupMs = begin.elapsedNow().inWholeMilliseconds
        val client = MongoClient.create(
            MongoClientSettings.builder()
                .applyConnectionString(ConnectionString(connectionString(container)))
                .addCommandListener(detector)
                .build()
        )
        Runtime.getRuntime().addShutdownHook(
            Thread({
                try {
                    client.close()
                } finally {
                    container.stop()
                }
            }, "godwit-test-container-stop")
        )
        log.atInfo().setMessage("godwit-test container started")
            .addKeyValue("image", image)
            .addKeyValue("startupMs", startupMs)
            .log()
        return Started(container, client)
    }
}

/** The two containers `testGodwit()` uses, one of each per JVM. */
internal object SharedContainers {
    /** A single-node replica set, so transactions work. */
    val replicaSet = SharedContainer(Images.mongo, { MongoDBContainer(it).withReplicaSet() }) { it.connectionString }

    /** The Atlas local image: a single-node replica set that also serves Atlas Search indexes. */
    val atlasLocal = SharedContainer(Images.atlasLocal, ::MongoDBAtlasLocalContainer) { it.connectionString }
}
