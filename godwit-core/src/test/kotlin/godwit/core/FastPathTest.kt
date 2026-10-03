package godwit.core

import godwit.core.fixtures.GodwitFixture
import godwit.core.fixtures.LogCapture
import godwit.core.fixtures.keyValues
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.bson.BsonDocument
import org.bson.Document
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

private val log = LoggerFactory.getLogger(FastPathTest::class.java)

/** How many timed calls the latency check makes, after as many untimed ones to warm the client up. */
private const val CALLS = 100

private val carts = migration("002-carts").outsideTransaction { ensureCollection("carts") }

private val orderStatus = migration("004-order-status").inTransaction {
    collection("orders").insertOne(session, Document("status", "PAID"))
}

/** A start with nothing due: one history read, no lock. */
class FastPathTest : StringSpec() {
    init {
        "nothing due: exactly one command, a find on godwit-history, none on godwit-lock, and no lock wait" {
            GodwitFixture().use { f ->
                f.godwit.migrate(carts, orderStatus)
                f.recorder.clear()

                LogCapture().use { logs ->
                    val report = f.godwit.migrate(carts, orderStatus)

                    report.lockWait.shouldBeNull()
                    report.ran.shouldBeEmpty()
                    report.upToDate shouldBe listOf("002-carts", "004-order-status")
                    val up = logs.events.single()
                    up.message shouldBe "Migrations up to date"
                    up.level.toString() shouldBe "INFO"
                    up.keyValues["runId"] shouldBe report.runId
                    up.keyValues["checked"] shouldBe 2
                    up.keyValues["durationMs"] shouldBe report.duration.inWholeMilliseconds
                }
                val find = f.recorder.commands.single()
                find.name shouldBe "find"
                find.command.getString("find").value shouldBe "godwit-history"
                find.command.getDocument("filter", BsonDocument()) shouldBe BsonDocument()
                find.command.getDocument("readConcern").getString("level").value shouldBe "majority"
            }
        }

        "a history longer than the server's first batch of 101 documents still comes back in one find" {
            GodwitFixture().use { f ->
                val many = (1..150).map { migration("%03d-noop".format(it)).outsideTransaction { } }
                f.godwit.migrate(many).ran shouldHaveSize 150
                f.recorder.clear()

                f.godwit.migrate(many).upToDate shouldHaveSize 150

                val find = f.recorder.commands.single()
                find.name shouldBe "find"
                val cursor = find.reply!!.getDocument("cursor")
                cursor.getArray("firstBatch") shouldHaveSize 150
                cursor.getNumber("id").longValue() shouldBe 0L
            }
        }

        "a targeted call with nothing due takes the fast path and reports what the target left pending" {
            GodwitFixture().use { f ->
                f.godwit.migrate(listOf(carts, orderStatus), Target.Before("004-order-status"))
                f.recorder.clear()

                val report = f.godwit.migrate(listOf(carts, orderStatus), Target.Before("004-order-status"))

                report.lockWait.shouldBeNull()
                report.pending shouldBe listOf("004-order-status")
                f.recorder.commands.map { it.name } shouldBe listOf("find")
            }
        }

        "the fast path's median call takes at most 20 ms against the local container" {
            GodwitFixture().use { f ->
                val migrations = listOf(carts, orderStatus)
                f.godwit.migrate(migrations)
                repeat(CALLS) { f.godwit.migrate(migrations) }

                val calls = (1..CALLS).map {
                    val started = System.nanoTime()
                    f.godwit.migrate(migrations).lockWait.shouldBeNull()
                    (System.nanoTime() - started).nanoseconds
                }.sorted()

                val p50 = calls[CALLS / 2 - 1]
                val p95 = calls[CALLS * 95 / 100 - 1]
                log.info("fastPath calls={} p50Ms={} p95Ms={}", CALLS, p50.inWholeMilliseconds, p95.inWholeMilliseconds)
                p50 shouldBeLessThanOrEqualTo 20.milliseconds
            }
        }
    }
}
