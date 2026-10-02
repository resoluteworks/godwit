package godwit.core.internal

import ch.qos.logback.classic.Level
import com.mongodb.MongoCommandException
import com.mongodb.MongoWriteConcernException
import com.mongodb.ServerAddress
import com.mongodb.kotlin.client.MongoCollection
import godwit.core.GodwitConfig
import godwit.core.LockLostException
import godwit.core.LockTimeoutException
import godwit.core.fixtures.CommandRecorder
import godwit.core.fixtures.LogCapture
import godwit.core.fixtures.TestMongo
import godwit.core.fixtures.keyValues
import godwit.core.fixtures.line
import godwit.core.fixtures.race
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import org.awaitility.kotlin.withPollInterval
import org.bson.BsonDocument
import org.bson.Document
import org.bson.conversions.Bson
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.Date
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.toJavaDuration

private val log = LoggerFactory.getLogger(MongoLockTest::class.java)

private const val RUN_A = "0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f"
private const val RUN_B = "0199a4c2-8a40-7d12-b3c4-1e2f3a4b5c6d"
private const val DEAD_OWNER = "token-of-a-crashed-process"

/** How many missing lock documents the race test has two processes acquire at the same moment. */
private const val RACES = 50

/**
 * A fail point's reply to a write it applied: the write succeeded on the primary, and the reply reports that the
 * majority did not acknowledge it in time, an error the driver does not retry.
 */
private val APPLIED_THEN_FAILED = Document(
    "writeConcernError",
    Document("code", 64).append("errmsg", "waiting for replication timed out")
)

private fun json(text: String): BsonDocument = BsonDocument.parse(text)

/** The pause step before jitter after the refusal numbered [attempt]: 250 ms doubling, at most 5 s. */
private fun step(attempt: Int): Duration = minOf(250.milliseconds * (1 shl minOf(attempt, 5)), 5.seconds)

class MongoLockTest : StringSpec() {
    init {
        "acquire inserts the lock document with server times, a new owner token and the holder, and logs it" {
            TestMongo.database().use { db ->
                LogCapture().use { logs ->
                    val recorder = CommandRecorder()
                    LockProcess(db, "lock-acquire", recorder = recorder).use { p ->
                        val held = p.lock.acquire(RUN_A)
                        try {
                            val doc = db.lockDocument().shouldNotBeNull()
                            UUID.fromString(held.owner).toString() shouldBe held.owner
                            doc.getString("owner") shouldBe held.owner
                            doc.getString("holder") shouldBe "lock-acquire/1"
                            doc.getString("runId") shouldBe RUN_A
                            doc.getDate("refreshedAt") shouldBe doc.getDate("acquiredAt")
                            doc.getDate("expiresAt").time - doc.getDate("acquiredAt").time shouldBe 3000L
                            doc shouldNotContainKey "releasedAt"
                            held.runId shouldBe RUN_A
                            held.isLost() shouldBe false

                            val acquired = logs.events("Acquired migration lock").single()
                            acquired.level shouldBe Level.INFO
                            acquired.keyValues shouldBe
                                mapOf("runId" to RUN_A, "lockWaitMs" to held.lockWait.inWholeMilliseconds)

                            val command = recorder.commands("findAndModify").single().command
                            command.getString("findAndModify").value shouldBe "godwit-lock"
                            command.getDocument("query") shouldBe json("""{"_id": "godwit-history"}""")
                            command.getArray("update").map { it.asDocument() } shouldBe listOf(
                                json(
                                    $$$"""
                                    {
                                      "$replaceWith": {
                                        "$cond": [
                                          {"$lte": ["$expiresAt", "$$NOW"]},
                                          {
                                            "_id": "$_id", "owner": {"$literal": "$$${held.owner}"},
                                            "holder": {"$literal": "lock-acquire/1"}, "runId": {"$literal": "$$$RUN_A"},
                                            "acquiredAt": "$$NOW", "refreshedAt": "$$NOW",
                                            "expiresAt": {"$add": ["$$NOW", {"$numberLong": "3000"}]}
                                          },
                                          "$$ROOT"
                                        ]
                                      }
                                    }
                                    """
                                )
                            )
                            command.getBoolean("upsert").value shouldBe true
                            command.getBoolean("new").value shouldBe true
                            command.getDocument("writeConcern") shouldBe json("""{"w": "majority"}""")
                            command.getNumber("maxTimeMS").longValue() shouldBeInRange 1L..5000L
                        } finally {
                            held.release()
                        }
                    }
                }
            }
        }

        "on a database with no lock document, two concurrent acquires: the loser finds the lock held and polls" {
            TestMongo.database().use { db ->
                val recorders = listOf(CommandRecorder(), CommandRecorder())
                val processes = listOf("lock-race-a", "lock-race-b").zip(recorders) { name, recorder ->
                    LockProcess(db, name, testTimings.copy(waitTimeout = 1.minutes), recorder)
                }
                val pool = Executors.newFixedThreadPool(2)
                try {
                    val barrier = CyclicBarrier(2)
                    val acquires = processes.map { p ->
                        pool.submit(
                            Callable {
                                barrier.await()
                                p.lock.acquire(p.name)
                            }
                        )
                    }
                    fun attempts(index: Int) = recorders[index].commands("findAndModify")

                    await atMost 10.seconds.toJavaDuration() until { acquires.count { it.isDone } == 1 }
                    val winner = acquires.indexOfFirst { it.isDone }
                    val loser = 1 - winner
                    await atMost 10.seconds.toJavaDuration() until { attempts(loser).count { it.finished } >= 2 }
                    acquires[loser].isDone shouldBe false
                    val first = acquires[winner].get()
                    attempts(winner) shouldHaveSize 1
                    // Each refused attempt returns the winner's document: the server retried the losing insert of the
                    // first race as an update, so no duplicate key reached either process.
                    attempts(loser).filter { it.finished }.forEach { refused ->
                        refused.errorCode.shouldBeNull()
                        refused.reply!!.getDocument("value").getString("owner").value shouldBe first.owner
                    }

                    first.release()
                    val second = acquires[loser].get(10, TimeUnit.SECONDS)

                    second.lockWait shouldBeGreaterThan 0.seconds
                    db.lockDocument()!!.getString("owner") shouldBe second.owner
                    second.release()
                } finally {
                    pool.shutdownNow()
                    processes.forEach { it.close() }
                }
            }
        }

        "an acquire with the holder's own token reads acquired and leaves the lease as it is; another is refused" {
            TestMongo.database().use { db ->
                LockProcess(db, "lock-retry").use { p ->
                    p.lock.tryAcquire("owner-1", RUN_A) shouldBe true
                    val first = db.lockDocument().shouldNotBeNull()

                    p.lock.tryAcquire("owner-1", RUN_A) shouldBe true
                    p.lock.tryAcquire("owner-2", RUN_B) shouldBe false

                    db.lockDocument() shouldBe first
                }
            }
        }

        "an acquire whose connection drops is sent again by the driver, as the same retryable write, and acquires" {
            TestMongo.database().use { db ->
                LogCapture().use { logs ->
                    val recorder = CommandRecorder()
                    LockProcess(db, "lock-dropped", recorder = recorder).use { p ->
                        val held = TestMongo.failCommand(
                            "lock-dropped",
                            listOf("findAndModify"),
                            Document("times", 1),
                            Document("closeConnection", true)
                        ).use { p.lock.acquire(RUN_A) }

                        val attempts = recorder.commands("findAndModify")
                        attempts shouldHaveSize 2
                        attempts[1].command.getInt64("txnNumber") shouldBe attempts[0].command.getInt64("txnNumber")
                        attempts[1].reply!!.getDocument("value").getString("owner").value shouldBe held.owner
                        db.lockDocument()!!.getString("owner") shouldBe held.owner
                        logs.events.map { it.message } shouldBe listOf("Acquired migration lock")
                        held.release()
                    }
                }
            }
        }

        "an acquire that took the lock but whose reply failed throws, is not polled again, and leaves its lease" {
            TestMongo.database().use { db ->
                LogCapture().use { logs ->
                    val recorder = CommandRecorder()
                    val longLease = testTimings.copy(lease = 1.minutes)
                    LockProcess(db, "lock-reply-failed", longLease, recorder).use { p ->
                        TestMongo.failCommand(
                            "lock-reply-failed",
                            listOf("findAndModify"),
                            Document("times", 1),
                            APPLIED_THEN_FAILED
                        ).use {
                            shouldThrow<MongoWriteConcernException> { p.lock.acquire(RUN_A) }.code shouldBe 64
                        }

                        recorder.commands("findAndModify") shouldHaveSize 1
                        logs.events.shouldBeEmpty()
                        val orphaned = db.lockDocument().shouldNotBeNull()
                        orphaned.getString("runId") shouldBe RUN_A
                        orphaned.getDate("expiresAt").time - orphaned.getDate("acquiredAt").time shouldBe 60_000L
                        // The lease it took holds every other start off until it ends, at most one lease later.
                        val zero = testTimings.copy(waitTimeout = Duration.ZERO)
                        LockProcess(db, "lock-reply-failed-next", zero).use { next ->
                            shouldThrow<LockTimeoutException> { next.lock.acquire(RUN_B) }.holder!!.runId shouldBe RUN_A
                        }
                    }
                }
            }
        }

        "races on missing lock documents: one winner each, and the server retries every losing insert as an update" {
            TestMongo.database().use { db ->
                val recorder = CommandRecorder()
                val clients = listOf("lock-races-a", "lock-races-b").map { TestMongo.client(it, recorder) }
                try {
                    val locks = clients.map { client ->
                        (0 until RACES).map { round ->
                            val config = GodwitConfig(historyCollection = "race-$round", lock = testTimings)
                            MongoLock(Bookkeeping(client, db.name, config), testTimings, "lock-races/1")
                        }
                    }
                    val owners = listOf("owner-a-", "owner-b-")

                    val won = race(
                        RACES,
                        { locks[0][it].tryAcquire(owners[0] + it, RUN_A) },
                        { locks[1][it].tryAcquire(owners[1] + it, RUN_B) }
                    )

                    won.forEachIndexed { round, (first, second) ->
                        withClue("race-$round") {
                            (first xor second) shouldBe true
                            val winner = owners[if (first) 0 else 1] + round
                            db.lockDocument("race-$round")!!.getString("owner") shouldBe winner
                        }
                    }
                    val attempts = recorder.commands("findAndModify")
                    attempts shouldHaveSize 2 * RACES
                    attempts.forEach { attempt ->
                        attempt.errorCode.shouldBeNull()
                        // Winner or loser, each attempt returns the winner's document.
                        val round = attempt.command.getDocument("query").getString("_id").value
                        attempt.reply!!.getDocument("value").getString("owner").value shouldBe
                            db.lockDocument(round)!!.getString("owner")
                    }
                } finally {
                    clients.forEach { it.close() }
                }
            }
        }

        "an expired lease and a released lock are taken at once, and the release time is cleared" {
            TestMongo.database().use { db ->
                val recorder = CommandRecorder()
                LockProcess(db, "lock-expired", recorder = recorder).use { p ->
                    db.plantLease(DEAD_OWNER, "shop-dead1/1", RUN_B, -1000)

                    val held = p.lock.acquire(RUN_A)
                    db.lockDocument()!!.getString("owner") shouldBe held.owner
                    recorder.commands("findAndModify") shouldHaveSize 1
                    held.release()
                    db.lockDocument()!!.getDate("releasedAt").shouldNotBeNull()

                    val again = p.lock.acquire(RUN_B)
                    val doc = db.lockDocument().shouldNotBeNull()
                    doc.getString("owner") shouldBe again.owner
                    doc shouldNotContainKey "releasedAt"
                    recorder.commands("findAndModify") shouldHaveSize 2
                    again.release()
                }
            }
        }

        "after a holder dies, another process acquires within the lease plus one poll interval" {
            TestMongo.database().use { db ->
                LockProcess(db, "lock-takeover", testTimings.copy(waitTimeout = 1.minutes)).use { p ->
                    db.plantLease(DEAD_OWNER, "shop-dead1/1", RUN_B, testTimings.lease.inWholeMilliseconds)
                    val deadExpiry = db.lockDocument()!!.getDate("expiresAt")

                    val held = p.lock.acquire(RUN_A)

                    held.lockWait shouldBeGreaterThan 2.5.seconds
                    held.lockWait shouldBeLessThanOrEqualTo testTimings.lease + 5.seconds
                    db.lockDocument()!!.getDate("acquiredAt") shouldBeGreaterThanOrEqualTo deadExpiry
                    log.info(
                        "crashed holder taken over leaseMs={} lockWaitMs={}",
                        testTimings.lease.inWholeMilliseconds,
                        held.lockWait.inWholeMilliseconds
                    )
                    held.release()
                }
            }
        }

        "release ends the lease at the server's time, fenced on the owner token, and the lock is free at once" {
            TestMongo.database().use { db ->
                val recorder = CommandRecorder()
                val nextRecorder = CommandRecorder()
                LockProcess(db, "lock-release", recorder = recorder).use { p ->
                    LockProcess(db, "lock-release-next", recorder = nextRecorder).use { next ->
                        val held = p.lock.acquire(RUN_A)
                        val before = db.lockDocument().shouldNotBeNull()

                        held.release()

                        val doc = db.lockDocument().shouldNotBeNull()
                        doc.getDate("releasedAt").shouldNotBeNull()
                        doc.getDate("expiresAt") shouldBe doc.getDate("releasedAt")
                        doc.getString("owner") shouldBe held.owner
                        doc.getDate("acquiredAt") shouldBe before.getDate("acquiredAt")
                        val release = recorder.commands("update").last().command
                        val update = release.getArray("updates")[0].asDocument()
                        update.getDocument("q") shouldBe json("""{"_id": "godwit-history", "owner": "${held.owner}"}""")
                        update.getArray("u").map { it.asDocument() } shouldBe
                            listOf(json($$$"""{"$set": {"expiresAt": "$$NOW", "releasedAt": "$$NOW"}}"""))
                        release.getDocument("writeConcern") shouldBe json("""{"w": "majority"}""")
                        release.getNumber("maxTimeMS").longValue() shouldBeInRange 1L..5000L

                        val nextHeld = next.lock.acquire(RUN_B)
                        nextRecorder.commands("findAndModify") shouldHaveSize 1
                        nextHeld.release()
                    }
                }
            }
        }

        "a renewal that reaches the server after the release matches nothing, so the lease stays ended" {
            TestMongo.database().use { db ->
                val recorder = CommandRecorder()
                LockProcess(db, "lock-late-renewal", recorder = recorder).use { p ->
                    val held = p.lock.acquire(RUN_A)
                    held.release()
                    val released = db.lockDocument().shouldNotBeNull()
                    recorder.clear()

                    p.lock.renew(held.owner) shouldBe false

                    db.lockDocument() shouldBe released
                    val renewal = recorder.commands("update").single().command.getArray("updates")[0].asDocument()
                    renewal.getDocument("q") shouldBe json(
                        $$"""{"_id": "godwit-history", "owner": "$${held.owner}", "releasedAt": {"$exists": false}}"""
                    )
                }
            }
        }

        "a release by a stale owner changes nothing" {
            TestMongo.database().use { db ->
                LockProcess(db, "lock-stale-a").use { a ->
                    LockProcess(db, "lock-stale-b").use { b ->
                        a.lock.tryAcquire("stale-owner", RUN_A) shouldBe true
                        db.endLease()
                        val held = b.lock.acquire(RUN_B)
                        val taken = db.lockDocument()

                        a.lock.release("stale-owner")

                        db.lockDocument() shouldBe taken
                        held.isLost() shouldBe false
                        held.release()
                    }
                }
            }
        }

        "a release that throws logs Lock release failed, is not retried, and the lease ends on its own" {
            TestMongo.database().use { db ->
                LogCapture().use { logs ->
                    val recorder = CommandRecorder()
                    LockProcess(db, "lock-release-fails", recorder = recorder).use { a ->
                        LockProcess(db, "lock-release-fails-next", testTimings.copy(waitTimeout = 1.minutes)).use { b ->
                            val held = a.lock.acquire(RUN_A)
                            TestMongo.failCommand(
                                "lock-release-fails",
                                listOf("update"),
                                "alwaysOn",
                                Document("errorCode", 13)
                            ).use {
                                recorder.clear()
                                held.release()
                            }

                            val failed = logs.events("Lock release failed").single()
                            failed.level shouldBe Level.WARN
                            failed.keyValues.keys.toList() shouldBe listOf("runId", "holder", "error")
                            failed.keyValues["runId"] shouldBe RUN_A
                            failed.keyValues["holder"] shouldBe "lock-release-fails/1"
                            failed.keyValues["error"].toString() shouldStartWith
                                "com.mongodb.MongoCommandException: Command execution failed on MongoDB server with error 13"
                            recorder.commands("update").filter { it.errorCode == 13 } shouldHaveSize 1
                            val left = db.lockDocument().shouldNotBeNull()
                            left.getString("owner") shouldBe held.owner
                            left shouldNotContainKey "releasedAt"

                            val next = b.lock.acquire(RUN_B)
                            db.lockDocument()!!.getDate("acquiredAt") shouldBeGreaterThanOrEqualTo
                                left.getDate("expiresAt")
                            next.release()
                        }
                    }
                }
            }
        }

        "waitTimeout passes while another run holds the lock: LockTimeoutException names the holder, nothing written" {
            TestMongo.database().use { db ->
                LockProcess(db, "lock-timeout-a").use { a ->
                    LockProcess(db, "lock-timeout-b", testTimings.copy(waitTimeout = 1.seconds)).use { b ->
                        val held = a.lock.acquire(RUN_A)
                        try {
                            val e = shouldThrow<LockTimeoutException> { b.lock.acquire(RUN_B) }

                            e.waited shouldBeGreaterThanOrEqualTo 1.seconds
                            e.waited shouldBeLessThan 1.seconds + LOCK_OPERATION_TIMEOUT
                            val holder = e.holder.shouldNotBeNull()
                            val doc = db.lockDocument().shouldNotBeNull()
                            holder.holder shouldBe "lock-timeout-a/1"
                            holder.runId shouldBe RUN_A
                            holder.acquiredAt shouldBe doc.getDate("acquiredAt").toInstant()
                            holder.expiresAt shouldBeLessThanOrEqualTo doc.getDate("expiresAt").toInstant()
                            e.message shouldBe "Waited ${e.waited} for the migration lock, held by lock-timeout-a/1"
                            doc.getString("owner") shouldBe held.owner
                        } finally {
                            held.release()
                        }
                    }
                }
            }
        }

        "Duration.ZERO fails at the first refusal and takes a free lock" {
            TestMongo.database().use { db ->
                val recorder = CommandRecorder()
                LockProcess(db, "lock-zero-a").use { a ->
                    LockProcess(db, "lock-zero-b", testTimings.copy(waitTimeout = Duration.ZERO), recorder).use { b ->
                        val held = a.lock.acquire(RUN_A)

                        val e = shouldThrow<LockTimeoutException> { b.lock.acquire(RUN_B) }

                        e.holder!!.runId shouldBe RUN_A
                        recorder.commands("findAndModify") shouldHaveSize 1
                        held.release()

                        val free = b.lock.acquire(RUN_B)
                        db.lockDocument()!!.getString("owner") shouldBe free.owner
                        free.release()
                    }
                }
            }
        }

        "while it waits, it pauses 250 ms doubling up to 5 s with jitter and logs the holder every 10 s" {
            TestMongo.database().use { db ->
                LogCapture().use { logs ->
                    db.plantLease(DEAD_OWNER, "shop-7f9c4/1", RUN_A, 1.minutes.inWholeMilliseconds)
                    val planted = db.lockDocument().shouldNotBeNull()
                    val clock = TestTimeSource()
                    val pauses = mutableListOf<Duration>()
                    LockProcess(
                        db,
                        "lock-wait-virtual",
                        testTimings.copy(waitTimeout = 25.seconds),
                        wait = VirtualWait(clock, Random(42)) {
                            pauses += it
                            clock += it
                        }
                    ).use { p ->
                        val e = shouldThrow<LockTimeoutException> { p.lock.acquire(RUN_B) }

                        e.waited shouldBe 25.seconds
                        e.holder!!.holder shouldBe "shop-7f9c4/1"
                        pauses.fold(Duration.ZERO, Duration::plus) shouldBe 25.seconds
                        pauses.dropLast(1).forEachIndexed { attempt, pause ->
                            pause shouldBeGreaterThanOrEqualTo step(attempt) / 2
                            pause shouldBeLessThanOrEqualTo step(attempt)
                        }
                        pauses.last() shouldBeLessThanOrEqualTo step(pauses.size - 1)
                        pauses.drop(5).toSet().size shouldBeGreaterThan 1

                        val waiting = logs.events("Waiting for migration lock")
                        waiting shouldHaveSize 2
                        waiting.forEach { event ->
                            event.level shouldBe Level.INFO
                            event.keyValues.keys.toList() shouldBe
                                listOf("holder", "holderRunId", "expiresAt", "waitedMs")
                            event.keyValues["holder"] shouldBe "shop-7f9c4/1"
                            event.keyValues["holderRunId"] shouldBe RUN_A
                            event.keyValues["expiresAt"] shouldBe logged(planted.getDate("expiresAt"))
                        }
                        (waiting[0].keyValues["waitedMs"] as Long) shouldBeInRange 10_000L..14_999L
                        (waiting[1].keyValues["waitedMs"] as Long) shouldBeInRange 20_000L..24_999L
                        logs.events("Acquired migration lock").shouldBeEmpty()
                    }
                }
            }
        }

        "a waiting process logs the holder every 10 s, then Acquired migration lock once the lease ends" {
            TestMongo.database().use { db ->
                LogCapture().use { logs ->
                    db.plantLease(DEAD_OWNER, "shop-7f9c4/1", RUN_A, 1.minutes.inWholeMilliseconds)
                    val clock = TestTimeSource()
                    var ended = false
                    LockProcess(
                        db,
                        "lock-wait-then-acquire",
                        testTimings.copy(waitTimeout = 1.minutes),
                        wait = VirtualWait(clock) { pause ->
                            clock += pause
                            // The lease ends once the holder has been logged twice, 10 s apart.
                            if (!ended && logs.events("Waiting for migration lock").size == 2) {
                                db.endLease()
                                ended = true
                            }
                        }
                    ).use { p ->
                        val held = p.lock.acquire(RUN_B)

                        held.lockWait shouldBeGreaterThan 20.seconds
                        held.lockWait shouldBeLessThan 30.seconds
                        logs.events.map { it.message } shouldBe
                            listOf(
                                "Waiting for migration lock",
                                "Waiting for migration lock",
                                "Acquired migration lock"
                            )
                        logs.events.last().keyValues shouldBe
                            mapOf("runId" to RUN_B, "lockWaitMs" to held.lockWait.inWholeMilliseconds)
                        logs.events.forEach { log.info("trace: {}", it.line) }
                        held.release()
                    }
                }
            }
        }

        "a lease that ends between the refusal and the holder read: no waiting line, and a null holder at the timeout" {
            TestMongo.database().use { db ->
                LogCapture().use { logs ->
                    var endAfterRefusal = { false }
                    val recorder = CommandRecorder(onSucceeded = { command ->
                        val returned = command.reply?.getDocument("value", null)?.getString("owner")?.value
                        if (command.name == "findAndModify" && returned == DEAD_OWNER &&
                            endAfterRefusal()
                        ) {
                            db.endLease()
                        }
                    })
                    db.plantLease(DEAD_OWNER, "shop-7f9c4/1", RUN_A, 1.minutes.inWholeMilliseconds)
                    LockProcess(db, "lock-ended-zero", testTimings.copy(waitTimeout = Duration.ZERO), recorder).use {
                        endAfterRefusal = { true }

                        shouldThrow<LockTimeoutException> { it.lock.acquire(RUN_B) }.holder.shouldBeNull()
                    }

                    db.plantLease(DEAD_OWNER, "shop-7f9c4/1", RUN_A, 1.minutes.inWholeMilliseconds)
                    val clock = TestTimeSource()
                    val start = clock.markNow()
                    endAfterRefusal = { start.elapsedNow() >= 10.seconds }
                    LockProcess(
                        db,
                        "lock-ended-wait",
                        testTimings.copy(waitTimeout = 1.minutes),
                        recorder,
                        wait = VirtualWait(clock)
                    ).use { p ->
                        val held = p.lock.acquire(RUN_B)

                        held.lockWait shouldBeGreaterThanOrEqualTo 10.seconds
                        logs.events("Waiting for migration lock").shouldBeEmpty()
                        held.release()
                    }
                }
            }
        }

        "a lock document that lacks a field godwit writes names no holder: no waiting line, a null holder" {
            TestMongo.database().use { db ->
                LogCapture().use { logs ->
                    // The document a test can plant by hand for a crashed holder: no acquiredAt or refreshedAt.
                    val planted = Document("_id", "godwit-history")
                        .append("owner", DEAD_OWNER)
                        .append("holder", "shop-dead1/1")
                        .append("runId", RUN_B)
                        .append("expiresAt", Date.from(Instant.now().plusSeconds(60)))
                    db.lockCollection.insertOne(planted)
                    LockProcess(db, "lock-hand-zero", testTimings.copy(waitTimeout = Duration.ZERO)).use { p ->
                        val e = shouldThrow<LockTimeoutException> { p.lock.acquire(RUN_A) }

                        e.holder.shouldBeNull()
                        e.message shouldBe "Waited ${e.waited} for the migration lock, held by nobody"
                    }

                    // A holder that is not a string, and a wait long enough to log the holder twice.
                    db.lockCollection.replaceOne(
                        Document("_id", "godwit-history"),
                        Document(planted).append("holder", 7).append("acquiredAt", Date())
                    )
                    LockProcess(
                        db,
                        "lock-hand-wait",
                        testTimings.copy(waitTimeout = 25.seconds),
                        wait = VirtualWait(TestTimeSource())
                    ).use { p ->
                        shouldThrow<LockTimeoutException> { p.lock.acquire(RUN_A) }.holder.shouldBeNull()
                    }

                    logs.events.shouldBeEmpty()
                    db.lockDocument()!!.getString("owner") shouldBe DEAD_OWNER
                }
            }
        }

        "a lock document deleted by hand: the next renewal loses the lock (NOT_OWNER), the next acquire recreates it" {
            TestMongo.database().use { db ->
                LogCapture().use { logs ->
                    LockProcess(db, "lock-deleted-a").use { a ->
                        LockProcess(db, "lock-deleted-b", testTimings.copy(waitTimeout = Duration.ZERO)).use { b ->
                            val held = a.lock.acquire(RUN_A)

                            db.lockCollection.deleteOne(Document("_id", "godwit-history"))

                            await atMost 5.seconds.toJavaDuration() withPollInterval
                                20.milliseconds.toJavaDuration() until { held.isLost() }
                            val lost = logs.events("Lost migration lock").single()
                            lost.level shouldBe Level.WARN
                            lost.keyValues shouldBe
                                mapOf("runId" to RUN_A, "holder" to "lock-deleted-a/1", "reason" to "NOT_OWNER")
                            shouldThrow<LockLostException> { held.checkLock("004-order-status") }.message shouldBe
                                "Lost the migration lock while running 004-order-status"
                            shouldThrow<LockLostException> { held.checkLock(null) }.message shouldBe
                                "Lost the migration lock"

                            val next = b.lock.acquire(RUN_B)
                            val recreated = db.lockDocument().shouldNotBeNull()
                            recreated.getString("owner") shouldBe next.owner
                            held.release()
                            db.lockDocument() shouldBe recreated
                            next.release()
                            logs.events("Lost migration lock") shouldHaveSize 1
                        }
                    }
                }
            }
        }

        "a driver error other than a duplicate key propagates unchanged from the acquire" {
            TestMongo.database().use { db ->
                LogCapture().use { logs ->
                    LockProcess(db, "lock-unauthorized").use { p ->
                        TestMongo.failCommand(
                            "lock-unauthorized",
                            listOf("findAndModify"),
                            "alwaysOn",
                            Document("errorCode", 13)
                        ).use {
                            shouldThrow<MongoCommandException> { p.lock.acquire(RUN_A) }.code shouldBe 13
                        }
                        db.lockDocument().shouldBeNull()
                        logs.events.shouldBeEmpty()
                    }
                }
            }
        }

        "a duplicate key that reaches the acquire counts as held" {
            val collection = mockk<MongoCollection<Document>>()
            every { collection.findOneAndUpdate(any<Bson>(), any<List<Bson>>(), any()) } throws
                MongoCommandException(
                    json("""{"ok": 0, "code": 11000, "errmsg": "E11000 duplicate key"}"""),
                    ServerAddress()
                )
            val bookkeeping = mockk<Bookkeeping> {
                every { lock } returns collection
                every { lockId } returns "godwit-history"
            }

            MongoLock(bookkeeping, testTimings, "shop-7f9c4/1").tryAcquire("owner-1", RUN_A) shouldBe false
        }

        "two history collections have two locks" {
            TestMongo.database().use { db ->
                val zero = testTimings.copy(waitTimeout = Duration.ZERO)
                LockProcess(db, "lock-shop", zero, historyCollection = "godwit-history").use { shop ->
                    LockProcess(db, "lock-admin", zero, historyCollection = "admin-history").use { admin ->
                        val shopLock = shop.lock.acquire(RUN_A)
                        val adminLock = admin.lock.acquire(RUN_B)

                        db.lockDocument("godwit-history")!!.getString("owner") shouldBe shopLock.owner
                        db.lockDocument("admin-history")!!.getString("owner") shouldBe adminLock.owner
                        shopLock.release()
                        adminLock.release()
                    }
                }
            }
        }
    }
}
