import org.testcontainers.mongodb.MongoDBAtlasLocalContainer
import java.util.Properties

// Testcontainers on the build's classpath starts the cluster OwnClusterSpec runs against (OwnCluster, below), in the
// version the root build tests with.
buildscript {
    val rootProperties = java.util.Properties().apply { file("../gradle.properties").inputStream().use { load(it) } }
    repositories { mavenCentral() }
    dependencies {
        classpath("org.testcontainers:testcontainers-mongodb:${rootProperties.getProperty("testContainersVersion")}")
    }
}

// The root project is the example app (com.example.shop, com.example.filestore) and every docs snippet
// (src/main/kotlin/com/example/shop/docs/<doc_slug>/). It sees godwit only through the public API of the two
// library modules, exactly as an app does.
plugins {
    kotlin("jvm") version "2.4.20"
}

allprojects {
    repositories { mavenCentral() }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> { jvmToolchain(21) }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation("works.resolute:godwit-test")
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("io.kotest:kotest-runner-junit5:6.2.5")
    implementation("io.kotest:kotest-assertions-core:6.2.5")
    implementation("io.mockk:mockk:1.14.9")

    // The specs print godwit's log lines through the configuration that configuration.md shows; Logback is on the
    // test runtime only, so the snippets compile against exactly the libraries DOMAIN.md lists.
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.32")
}

/** A property of the root build's `gradle.properties`, which holds every version and image godwit tests against. */
fun rootProperty(name: String): String = Properties()
    .apply { layout.projectDirectory.file("../gradle.properties").asFile.inputStream().use { load(it) } }
    .getProperty(name) ?: error("$name missing from ../gradle.properties")

/**
 * The cluster OwnClusterSpec runs against when `TEST_MONGO_URI` is not set: the Atlas local image (a single-node
 * replica set that serves Atlas Search, which `001-initial-setup` needs), started when the test task first asks for
 * it and stopped when the build ends, as an app's tests would use a cluster they manage themselves. A container that
 * fails to start is replaced by a new one, for up to three attempts in all.
 */
abstract class OwnCluster : BuildService<OwnCluster.Parameters>, AutoCloseable {
    interface Parameters : BuildServiceParameters {
        val image: Property<String>
    }

    private val container: MongoDBAtlasLocalContainer = start(attempt = 1)

    val connectionString: String get() = container.connectionString

    private fun start(attempt: Int): MongoDBAtlasLocalContainer {
        val container = MongoDBAtlasLocalContainer(parameters.image.get())
        return try {
            container.apply { start() }
        } catch (e: RuntimeException) {
            runCatching { container.stop() }
            if (attempt == 3) throw e
            start(attempt + 1)
        }
    }

    override fun close() = container.stop()
}

val ownCluster = gradle.sharedServices.registerIfAbsent("ownCluster", OwnCluster::class) {
    parameters.image = rootProperty("atlasLocalImage")
}

// The Kotest specs among the snippets live in the main source set, as the docs show them; this task runs them for
// real, against the containers testGodwit() starts and, for OwnClusterSpec, the cluster in TEST_MONGO_URI.
tasks.test {
    useJUnitPlatform()
    testClassesDirs = sourceSets.main.get().output.classesDirs
    usesService(ownCluster)
    doFirst {
        environment("TEST_MONGO_URI", System.getenv("TEST_MONGO_URI") ?: ownCluster.get().connectionString)
    }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
