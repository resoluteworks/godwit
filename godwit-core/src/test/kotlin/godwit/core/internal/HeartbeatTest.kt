package godwit.core.internal

import ch.qos.logback.classic.Level
import godwit.core.LockLostException
import godwit.core.LockTimeoutException
import godwit.core.fixtures.CommandRecorder
import godwit.core.fixtures.LogCapture
import godwit.core.fixtures.TestMongo
import godwit.core.fixtures.keyValues
import godwit.core.fixtures.line
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import org.awaitility.kotlin.withPollInterval
import org.bson.Document
import org.slf4j.LoggerFactory
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

private val log = LoggerFactory.getLogger(HeartbeatTest::class.java)

private const val RUN_A = "0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f"
private const val RUN_B = "0199a4c2-8a40-7d12-b3c4-1e2f3a4b5c6d"
private const val HOLDER = "shop-7f9c4/1"

/** `lease - safetyMargin` of the test timings: how long after a renewal was sent the run trusts the lock. */
private val window = testTimings.lease - testTimings.safetyMargin

/** A heartbeat on a virtual clock whose renewals [renew] answers; its thread is never started. */
private fun virtualHeartbeat(clock: TestTimeSource, renew: () -> Boolean) =
    Heartbeat(testTimings.heartbeat, window, clock.markNow(), clock, RUN_A, HOLDER, renew)

/** A heartbeat on the real clock that renews every 10 ms through [renew] and trusts the lock for a minute. */
private fun realHeartbeat(renew: () -> Boolean) =
    Heartbeat(10.milliseconds, 1.minutes, TimeSource.Monotonic.markNow(), TimeSource.Monotonic, RUN_A, HOLDER, renew)

private fun LogCapture.lost() = events("Lost migration lock")

class HeartbeatTest : StringSpec() {
    init {
        "the local deadline is lease - safetyMargin after the acquire was sent, then after each renewal sent" {
            LogCapture().use { logs ->
                val clock = TestTimeSource()
                val heartbeat = virtualHeartbeat(clock) {
                    clock += 400.milliseconds
                    true
                }

                clock += 1500.milliseconds
                heartbeat.isLost() shouldBe false
                heartbeat.tick() shouldBe true
                clock += 600.milliseconds
                heartbeat.isLost() shouldBe false
                clock += 999.milliseconds
                heartbeat.isLost() shouldBe false
                clock += 1.milliseconds
                heartbeat.isLost() shouldBe true
                heartbeat.isLost() shouldBe true

                val lost = logs.lost().single()
                lost.level shouldBe Level.WARN
                lost.keyValues shouldBe mapOf("runId" to RUN_A, "holder" to HOLDER, "reason" to "DEADLINE_PASSED")
            }
        }

        "a tick after the deadline sends no renewal, marks the lock lost and stops" {
            LogCapture().use { logs ->
                val clock = TestTimeSource()
                val renewals = AtomicInteger()
                val heartbeat = virtualHeartbeat(clock) { renewals.incrementAndGet() > 0 }

                clock += window
                heartbeat.tick() shouldBe false

                renewals.get() shouldBe 0
                heartbeat.isLost() shouldBe true
                logs.lost().single().keyValues["reason"] shouldBe "DEADLINE_PASSED"
            }
        }

        "a renewal that matches nothing marks the lock lost for good with NOT_OWNER" {
            LogCapture().use { logs ->
                val clock = TestTimeSource()
                val renewals = AtomicInteger()
                val heartbeat = virtualHeartbeat(clock) { renewals.incrementAndGet() < 0 }

                heartbeat.tick() shouldBe false
                heartbeat.isLost() shouldBe true
                heartbeat.tick() shouldBe false
                clock += 1.minutes
                heartbeat.isLost() shouldBe true

                renewals.get() shouldBe 1
                logs.lost().single().keyValues shouldBe
                    mapOf("runId" to RUN_A, "holder" to HOLDER, "reason" to "NOT_OWNER")
            }
        }

        "a renewal that throws logs Lock renewal failed and leaves the deadline; the next tick tries again" {
            LogCapture().use { logs ->
                val clock = TestTimeSource()
                val renewals = AtomicInteger()
                val heartbeat = virtualHeartbeat(clock) {
                    if (renewals.incrementAndGet() == 1) throw IllegalStateException("Timed out waiting for a primary")
                    true
                }

                clock += 500.milliseconds
                heartbeat.tick() shouldBe true
                clock += 1.seconds
                heartbeat.tick() shouldBe true
                clock += 1900.milliseconds
                heartbeat.isLost() shouldBe false
                clock += 100.milliseconds
                heartbeat.isLost() shouldBe true

                val failed = logs.events("Lock renewal failed").single()
                failed.level shouldBe Level.WARN
                failed.keyValues shouldBe mapOf(
                    "runId" to RUN_A,
                    "holder" to HOLDER,
                    "error" to "java.lang.IllegalStateException: Timed out waiting for a primary"
                )
                renewals.get() shouldBe 2

                val failingClock = TestTimeSource()
                val failing = virtualHeartbeat(failingClock) { throw IllegalStateException("no primary") }
                failingClock += 500.milliseconds
                failing.tick() shouldBe true
                failingClock += 500.milliseconds
                failing.tick() shouldBe true
                failingClock += 999.milliseconds
                failing.isLost() shouldBe false
                failingClock += 1.milliseconds
                failing.tick() shouldBe false
                logs.lost().map { it.keyValues["reason"] } shouldBe listOf("DEADLINE_PASSED", "DEADLINE_PASSED")
            }
        }

        "an Error thrown by a renewal is caught as well, so the heartbeat keeps running" {
            LogCapture().use { logs ->
                val heartbeat = virtualHeartbeat(TestTimeSource()) { throw StackOverflowError() }

                heartbeat.tick() shouldBe true

                logs.events("Lock renewal failed").single().keyValues["error"] shouldBe "java.lang.StackOverflowError"
            }
        }

        "a renewal that returns after the deadline passed does not revive the lock" {
            LogCapture().use { logs ->
                val clock = TestTimeSource()
                val heartbeat = virtualHeartbeat(clock) {
                    clock += window
                    true
                }

                heartbeat.tick() shouldBe false

                heartbeat.isLost() shouldBe true
                logs.lost().single().keyValues["reason"] shouldBe "DEADLINE_PASSED"
            }
        }

        "the heartbeat and checkLock, noticing the loss together, log it once between them" {
            LogCapture().use { logs ->
                val clock = TestTimeSource()
                lateinit var heartbeat: Heartbeat
                heartbeat = virtualHeartbeat(clock) {
                    // While the renewal is in flight the deadline passes and the step's thread notices first.
                    clock += window
                    heartbeat.isLost() shouldBe true
                    false
                }

                heartbeat.tick() shouldBe false

                logs.lost().single().keyValues["reason"] shouldBe "DEADLINE_PASSED"
            }
        }

        "the release waits for a renewal in flight, so it reaches the server after the renewal" {
            val inFlight = CountDownLatch(1)
            val proceed = CountDownLatch(1)
            val order = CopyOnWriteArrayList<String>()
            val heartbeat = realHeartbeat {
                if (inFlight.count > 0) {
                    inFlight.countDown()
                    proceed.await()
                }
                order += "renewed"
                true
            }
            val held = HeldLock("owner", RUN_A, Duration.ZERO, HOLDER, heartbeat) { order += "released" }
            heartbeat.start()
            inFlight.await(10, TimeUnit.SECONDS) shouldBe true

            val releasing = thread { held.release() }
            await atMost 10.seconds.toJavaDuration() until { releasing.state == Thread.State.TIMED_WAITING }
            order.shouldBeEmpty()
            proceed.countDown()
            releasing.join(10_000)

            order shouldBe listOf("renewed", "released")
        }

        "an interrupt while the release waits for the heartbeat logs Lock release failed and stays set" {
            LogCapture().use { logs ->
                val inFlight = CountDownLatch(1)
                val proceed = CountDownLatch(1)
                val released = AtomicInteger()
                val heartbeat = realHeartbeat {
                    inFlight.countDown()
                    proceed.await()
                    true
                }
                val held = HeldLock("owner", RUN_A, Duration.ZERO, HOLDER, heartbeat) { released.incrementAndGet() }
                heartbeat.start()
                inFlight.await(10, TimeUnit.SECONDS) shouldBe true

                var interrupted = false
                val releasing = thread {
                    Thread.currentThread().interrupt()
                    held.release()
                    interrupted = Thread.currentThread().isInterrupted
                }
                releasing.join(10_000)
                proceed.countDown()

                interrupted shouldBe true
                released.get() shouldBe 0
                logs.events("Lock release failed").single().keyValues shouldBe
                    mapOf("runId" to RUN_A, "holder" to HOLDER, "error" to "java.lang.InterruptedException")
            }
        }

        "renewals keep the lock past three leases" {
            TestMongo.database().use { db ->
                LogCapture().use { logs ->
                    val recorder = CommandRecorder()
                    LockProcess(db, "hb-keeps", recorder = recorder).use { a ->
                        LockProcess(db, "hb-keeps-other", testTimings.copy(waitTimeout = Duration.ZERO)).use { b ->
                            val held = a.lock.acquire(RUN_A)
                            val acquiredAt = db.lockDocument()!!.getDate("acquiredAt").time
                            val threeLeases = 3 * testTimings.lease.inWholeMilliseconds

                            await atMost 20.seconds.toJavaDuration() withPollInterval
                                100.milliseconds.toJavaDuration() until {
                                    db.lockDocument()!!.getDate("refreshedAt").time - acquiredAt >= threeLeases
                                }

                            held.isLost() shouldBe false
                            held.checkLock("004-order-status")
                            shouldThrow<LockTimeoutException> { b.lock.acquire(RUN_B) }.holder!!.runId shouldBe RUN_A
                            val renewals = recorder.commands("update").size
                            renewals shouldBeGreaterThanOrEqual 17
                            logs.events.map { it.message } shouldBe listOf("Acquired migration lock")
                            log.info(
                                "heartbeat kept the lock leases=3 leaseMs={} renewals={}",
                                testTimings.lease.inWholeMilliseconds,
                                renewals
                            )
                            held.release()
                        }
                    }
                }
            }
        }

        "a renewal that fails once logs Lock renewal failed, and the next one keeps the lock" {
            TestMongo.database().use { db ->
                LogCapture().use { logs ->
                    LockProcess(db, "hb-fails-once").use { a ->
                        val held = a.lock.acquire(RUN_A)
                        TestMongo.failCommand(
                            "hb-fails-once",
                            listOf("update"),
                            Document("times", 1),
                            Document("errorCode", 13)
                        ).use {
                            await atMost 5.seconds.toJavaDuration() until {
                                logs.events("Lock renewal failed").isNotEmpty()
                            }
                        }
                        val refreshed = db.lockDocument()!!.getDate("refreshedAt")

                        await atMost 5.seconds.toJavaDuration() until {
                            db.lockDocument()!!.getDate("refreshedAt") > refreshed
                        }

                        held.isLost() shouldBe false
                        held.checkLock("004-order-status")
                        val failed = logs.events("Lock renewal failed").single()
                        failed.keyValues.keys.toList() shouldBe listOf("runId", "holder", "error")
                        failed.keyValues["holder"] shouldBe "hb-fails-once/1"
                        logs.lost().shouldBeEmpty()
                        held.release()
                    }
                }
            }
        }

        "a renewal blocked past the deadline: checkLock throws before another process can acquire, logged once" {
            TestMongo.database().use { db ->
                LogCapture().use { logs ->
                    LockProcess(db, "hb-blocked").use { a ->
                        LockProcess(db, "hb-blocked-next", testTimings.copy(waitTimeout = 1.minutes)).use { b ->
                            val pool = Executors.newSingleThreadExecutor()
                            val held = a.lock.acquire(RUN_A)
                            val blocked = TestMongo.failCommand(
                                "hb-blocked",
                                listOf("update"),
                                "alwaysOn",
                                Document("blockConnection", true).append("blockTimeMS", 10_000).append("errorCode", 13)
                            )
                            try {
                                val takeover = pool.submit(Callable { b.lock.acquire(RUN_B) })

                                await atMost 10.seconds.toJavaDuration() withPollInterval
                                    10.milliseconds.toJavaDuration() until { held.isLost() }

                                takeover.isDone shouldBe false
                                shouldThrow<LockLostException> { held.checkLock("004-order-status") }
                                val next = takeover.get(15, TimeUnit.SECONDS)
                                next.lockWait shouldBeGreaterThan 2.seconds
                                blocked.close()
                                held.release()

                                logs.events("Lock renewal failed").shouldNotBeEmpty()
                                val lost = logs.lost().single()
                                lost.keyValues shouldBe
                                    mapOf("runId" to RUN_A, "holder" to "hb-blocked/1", "reason" to "DEADLINE_PASSED")
                                log.info("trace: {}", lost.line)
                                next.release()
                            } finally {
                                blocked.close()
                                pool.shutdownNow()
                            }
                        }
                    }
                }
            }
        }

        "the heartbeat runs on one daemon thread named after the run, which ends with the release" {
            TestMongo.database().use { db ->
                LockProcess(db, "hb-thread").use { a ->
                    val held = a.lock.acquire(RUN_A)
                    val threads = Thread.getAllStackTraces().keys.filter { it.name == "godwit-heartbeat-$RUN_A" }
                    threads shouldHaveSize 1
                    threads.single().isDaemon shouldBe true

                    held.release()

                    await atMost 10.seconds.toJavaDuration() until { !threads.single().isAlive }
                }
            }
        }
    }
}
