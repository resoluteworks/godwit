package godwit.test.internal

import ch.qos.logback.classic.Level
import com.github.dockerjava.api.exception.NotFoundException
import com.mongodb.kotlin.client.MongoClient
import godwit.core.fixtures.LogCapture
import godwit.core.fixtures.TestMongo
import godwit.core.fixtures.keyValues
import godwit.core.fixtures.line
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import org.testcontainers.DockerClientFactory
import org.testcontainers.lifecycle.Startable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** A container whose start fails while [failures] lasts, with the error [error]; it records its starts and stops. */
private class FakeContainer(private val failures: Int, private val error: (Int) -> RuntimeException) : Startable {
    var starts = 0
    var stops = 0

    override fun start() {
        starts++
        if (starts <= failures) throw error(starts)
    }

    override fun stop() {
        stops++
    }
}

/** A container whose start waits until [release] opens. */
private class GatedContainer(private val release: CountDownLatch) : Startable {
    override fun start() = release.await()

    override fun stop() = Unit
}

/** How long the child JVM of the exit test may take to start its container and exit, on a loaded machine. */
private const val CHILD_TIMEOUT_MINUTES = 5L

/**
 * Runs the `main` of ContainerExitMain.kt in a child JVM on this JVM's classpath and returns the id of the container it
 * started, once the child has exited. Testcontainers' reaper (Ryuk) removes a JVM's containers only some seconds after
 * that JVM is gone, so a container that is gone as the child exits was stopped by the child's own shutdown hook.
 */
private fun containerOfAnExitedJvm(): String {
    val java = ProcessHandle.current().info().command().orElseThrow()
    val main = "godwit.test.internal.ContainerExitMainKt"
    val process = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), main)
        .redirectErrorStream(true)
        .start()
    val output = CopyOnWriteArrayList<String>()
    val reader = thread(name = "container-exit-output", isDaemon = true) {
        process.inputStream.bufferedReader().forEachLine { output += it }
    }
    try {
        check(process.waitFor(CHILD_TIMEOUT_MINUTES, TimeUnit.MINUTES)) { "the child did not exit: $output" }
    } finally {
        process.destroyForcibly()
    }
    reader.join()
    check(process.exitValue() == 0) { "the child exited with ${process.exitValue()}: $output" }
    return output.single { it.startsWith(CONTAINER_ID_LINE) }.substringAfter("$CONTAINER_ID_LINE ")
}

class SharedContainersTest : StringSpec() {
    init {
        "concurrent first reads start one container, share one client and log one start line" {
            LogCapture().use { logs ->
                val release = CountDownLatch(1)
                val created = AtomicInteger()
                val shared = SharedContainer("mongo:concurrent", { _ ->
                    created.incrementAndGet()
                    GatedContainer(release)
                }) { TestMongo.connectionString }
                val clients = CopyOnWriteArrayList<MongoClient>()

                val readers = List(8) { thread(name = "first-read-$it") { clients += shared.client } }
                // Every reader is inside the start, waiting for the gate, or blocked on the first read's lock.
                await atMost 60.seconds.toJavaDuration() until {
                    readers.all { it.state == Thread.State.WAITING || it.state == Thread.State.BLOCKED }
                }
                release.countDown()
                readers.forEach { it.join() }

                created.get() shouldBe 1
                clients shouldHaveSize 8
                clients.toSet().single() shouldBeSameInstanceAs shared.client
                val started = logs.events("godwit-test container started").single()
                started.loggerName shouldBe "godwit.test"
                started.level shouldBe Level.INFO
                started.keyValues.keys.toList() shouldBe listOf("image", "startupMs")
                started.keyValues["image"] shouldBe "mongo:concurrent"
                started.keyValues["startupMs"].shouldBeInstanceOf<Long>() shouldBeGreaterThanOrEqual 0L
                shared.client.close()
            }
        }

        "a start that fails on every attempt propagates, and the next read starts a container again" {
            val containers = mutableListOf<FakeContainer>()
            val shared = SharedContainer("mongo:retry", { _ ->
                val failing = containers.size < CONTAINER_START_ATTEMPTS
                FakeContainer(if (failing) 1 else 0) { IllegalStateException("not ready ${containers.size}") }
                    .also { containers += it }
            }) { TestMongo.connectionString }

            shouldThrow<IllegalStateException> { shared.client }.message shouldBe "not ready $CONTAINER_START_ATTEMPTS"
            containers shouldHaveSize CONTAINER_START_ATTEMPTS

            val client = shared.client

            containers shouldHaveSize CONTAINER_START_ATTEMPTS + 1
            shared.container shouldBeSameInstanceAs containers.last()
            shared.client shouldBeSameInstanceAs client
            client.close()
        }

        "the shared container stops when the JVM that started it exits" {
            val id = containerOfAnExitedJvm()

            val docker = DockerClientFactory.instance().client()
            val inspected = runCatching { docker.inspectContainerCmd(id).exec() }
            try {
                inspected.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
            } finally {
                if (inspected.isSuccess) docker.removeContainerCmd(id).withForce(true).exec()
            }
        }

        "the images are the ones godwit's own tests run against, from gradle.properties" {
            Images.mongo shouldBe System.getProperty("godwit.mongoImage")
            Images.atlasLocal shouldBe System.getProperty("godwit.atlasLocalImage")
        }

        "a container that starts is returned at once, without a warning" {
            LogCapture().use { logs ->
                val containers = mutableListOf<FakeContainer>()

                val started = startWithRetries("mongo:test", 3) {
                    FakeContainer(0) { IllegalStateException() }.also { containers += it }
                }

                started shouldBeSameInstanceAs containers.single()
                started.starts shouldBe 1
                started.stops shouldBe 0
                logs.events("godwit-test container failed to start").shouldBeEmpty()
            }
        }

        "a container that fails to start is stopped and replaced by a new one, with a warning per failure" {
            LogCapture().use { logs ->
                val containers = mutableListOf<FakeContainer>()

                val started = startWithRetries("mongo:test", 3) {
                    val failing = containers.size < 2
                    FakeContainer(if (failing) 1 else 0) { IllegalStateException("not ready ${containers.size}") }
                        .also { containers += it }
                }

                started shouldBeSameInstanceAs containers[2]
                containers.map { it.stops } shouldBe listOf(1, 1, 0)
                logs.events("godwit-test container failed to start").map { it.line } shouldBe listOf(
                    "godwit-test container failed to start image=mongo:test attempt=1 " +
                        "error=java.lang.IllegalStateException: not ready 1",
                    "godwit-test container failed to start image=mongo:test attempt=2 " +
                        "error=java.lang.IllegalStateException: not ready 2"
                )
                logs.events("godwit-test container failed to start").map { it.level.toString() }.toSet() shouldBe
                    setOf("WARN")
            }
        }

        "when every attempt fails, the last failure propagates and every container is stopped" {
            val containers = mutableListOf<FakeContainer>()

            val failure = shouldThrow<IllegalStateException> {
                startWithRetries("mongo:test", 3) {
                    FakeContainer(1) { IllegalStateException("attempt ${containers.size}") }.also { containers += it }
                }
            }

            failure.message shouldBe "attempt 3"
            containers.map { it.stops } shouldBe listOf(1, 1, 1)
        }

        "a stop that throws after a failed start does not hide the start's failure" {
            val failure = shouldThrow<IllegalStateException> {
                startWithRetries("mongo:test", 1) {
                    object : Startable {
                        override fun start() = throw IllegalStateException("start failed")

                        override fun stop() = throw IllegalArgumentException("stop failed")
                    }
                }
            }

            failure.message shouldBe "start failed"
        }
    }
}
