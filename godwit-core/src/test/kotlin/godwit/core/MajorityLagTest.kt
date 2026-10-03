package godwit.core

import com.mongodb.MongoException
import com.mongodb.ReadConcern
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates.inc
import com.mongodb.kotlin.client.MongoCollection
import godwit.core.fixtures.CommandRecorder
import godwit.core.fixtures.LogCapture
import godwit.core.fixtures.RecordedCommand
import godwit.core.fixtures.TwoMemberReplicaSet
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import org.bson.Document
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * The client-side timeout of the app's client in these tests: long enough for the secondary to catch up within one
 * operation once its replication resumes, which takes about a second.
 */
private val TIMEOUT = 3.seconds

/** The update of a history document that sets `state` to [state]: an APPLIED record or a FAILED write. */
private fun RecordedCommand.setsHistoryState(state: String): Boolean {
    if (name != "update" || command.getString("update").value != "godwit-history") return false
    val set = command.getArray("updates")[0].asDocument().getDocument("u").getDocument("\$set", null)
    return set?.getString("state", null)?.value == state
}

/** `{_id: id, n: 1}` incremented in `probes` on the transaction's session: an effect that must commit exactly once. */
private fun TransactionScope.probe() {
    collection("probes").updateOne(session, eq("_id", id), inc("n", 1), UpdateOptions().upsert(true))
}

/** The secondary's replication, stopped from [start] until [end] or [close]. */
private class Stall : AutoCloseable {
    @Volatile
    private var stalled: AutoCloseable? = null

    /** Stops replication, unless it is stopped already. */
    fun start() {
        if (stalled == null) stalled = TwoMemberReplicaSet.stallReplication()
    }

    fun end() {
        stalled?.close()
        stalled = null
    }

    override fun close() = end()
}

/**
 * One process on a database of its own on the two-member replica set: a client named [appName] with `timeoutMS`
 * [TIMEOUT] and [recorder], and a [Godwit] on it with the holder `<appName>/1`. The test reads through a client of its
 * own. Closing it ends [stall] first, whatever the test left, so that dropping the database can reach the majority.
 */
private class LagFixture(appName: String, recorder: CommandRecorder, private val stall: Stall) : AutoCloseable {
    private val databaseName = UUID.randomUUID().toString()

    private val client = TwoMemberReplicaSet.client(appName, TIMEOUT, recorder)

    private val reader = TwoMemberReplicaSet.client("$appName-reader", 30.seconds)

    val godwit = Godwit(client, databaseName, GodwitConfig(holder = "$appName/1"))

    fun collection(name: String): MongoCollection<Document> =
        reader.getDatabase(databaseName).getCollection(name, Document::class.java)

    /** The history document of [id] on the primary, as it is now. */
    fun stored(id: String): Document? = collection("godwit-history").find(eq("_id", id)).firstOrNull()

    /** Waits until the majority has [id] APPLIED, so that the next start's history read, a majority read, sees it. */
    fun awaitMajorityApplied(id: String) {
        val majority = collection("godwit-history").withReadConcern(ReadConcern.MAJORITY)
        await atMost 30.seconds until { majority.find(eq("_id", id)).firstOrNull()?.getString("state") == "APPLIED" }
    }

    override fun close() {
        stall.close()
        reader.getDatabase(databaseName).drop()
        reader.close()
        client.close()
    }
}

/**
 * The APPLIED record and the commit when the majority lags behind the primary for longer than the app's `timeoutMS`:
 * the write applies on the primary and throws on the client while it waits for the majority, and a majority read right
 * after the timeout cannot see it yet. A single-node replica set cannot show this: its writes are majority-committed as
 * soon as they apply.
 */
class MajorityLagTest : StringSpec() {
    init {
        "an outside-only APPLIED record that times out behind the majority is sent again, and the migration applies" {
            val stall = Stall()
            val records = AtomicInteger()
            val recorder = CommandRecorder(onStarted = { command ->
                if (command.setsHistoryState("APPLIED")) {
                    // The first record applies on the primary and times out waiting for the majority, which
                    // catches up while godwit sends the record again.
                    if (records.incrementAndGet() == 1) stall.start() else stall.end()
                }
            })
            LagFixture("lag-outside-confirmed", recorder, stall).use { f ->
                val runs = AtomicInteger()
                val carts = migration("002-carts").outsideTransaction {
                    runs.incrementAndGet()
                    count("collectionsCreated", if (ensureCollection("carts")) 1 else 0)
                }

                LogCapture().use { logs ->
                    val report = f.godwit.migrate(carts)

                    report["002-carts"].counts shouldBe mapOf("collectionsCreated" to 1L)
                    logs.events("Applied migration") shouldHaveSize 1
                    logs.events("Migration failed").shouldBeEmpty()
                }
                val sent = recorder.commands.filter { it.setsHistoryState("APPLIED") }
                sent shouldHaveSize 2
                sent[1].reply.shouldNotBeNull().getInt32("n").value shouldBe 0
                f.stored("002-carts")!!.getString("state") shouldBe "APPLIED"
                f.godwit.migrate(carts).ran.shouldBeEmpty()
                runs.get() shouldBe 1
            }
        }

        "an outside-only APPLIED record sent again behind the majority propagates the first exception, then applies" {
            val stall = Stall()
            val records = AtomicInteger()
            val endAfterSecond = { command: RecordedCommand ->
                if (command.setsHistoryState("APPLIED") && records.get() == 2) stall.end()
            }
            val recorder = CommandRecorder(
                onStarted = { command ->
                    if (command.setsHistoryState("APPLIED") && records.incrementAndGet() == 1) stall.start()
                },
                onSucceeded = endAfterSecond,
                onFailed = endAfterSecond
            )
            LagFixture("lag-outside-unconfirmed", recorder, stall).use { f ->
                val runs = AtomicInteger()
                val carts = migration("002-carts").outsideTransaction {
                    runs.incrementAndGet()
                    ensureCollection("carts")
                }

                LogCapture().use { logs ->
                    val failure = shouldThrow<MongoException> { f.godwit.migrate(carts) }

                    failure.suppressed.single().shouldBeInstanceOf<MongoException>()
                    logs.events("Migration failed").shouldBeEmpty()
                    logs.events("Applied migration").shouldBeEmpty()
                }
                recorder.commands.filter { it.setsHistoryState("APPLIED") } shouldHaveSize 2
                f.stored("002-carts")!!.getString("state") shouldBe "APPLIED"

                f.awaitMajorityApplied("002-carts")
                val next = f.godwit.migrate(carts)
                next.ran.shouldBeEmpty()
                next.upToDate shouldBe listOf("002-carts")
                runs.get() shouldBe 1
            }
        }

        "a commit that times out behind the majority is reported applied once the FAILED write's majority wait ends" {
            val stall = Stall()
            val recorder = CommandRecorder(onStarted = { command ->
                // The commit applies on the primary and times out waiting for the majority, which catches up while
                // the FAILED write, a no-op on the APPLIED document, waits for it.
                if (command.name == "commitTransaction") stall.start()
                if (command.setsHistoryState("FAILED")) stall.end()
            })
            LagFixture("lag-commit-confirmed", recorder, stall).use { f ->
                val orderStatus = migration("004-order-status").inTransaction {
                    count("ordersPaid", 1)
                    probe()
                }

                LogCapture().use { logs ->
                    val report = f.godwit.migrate(orderStatus)

                    report["004-order-status"].counts shouldBe mapOf("ordersPaid" to 1L)
                    logs.events("Applied migration") shouldHaveSize 1
                    logs.events("Migration failed").shouldBeEmpty()
                }
                val failedWrite = recorder.commands.single { it.setsHistoryState("FAILED") }
                failedWrite.reply.shouldNotBeNull().getInt32("n").value shouldBe 0
                val stored = f.stored("004-order-status")!!
                stored.getString("state") shouldBe "APPLIED"
                stored.containsKey("lastError") shouldBe false
                f.collection("probes").find().first().getInteger("n") shouldBe 1
            }
        }

        "a commit whose FAILED write times out behind the majority too fails the start; the next finds it applied" {
            val stall = Stall()
            val endAfterFailedWrite = { command: RecordedCommand ->
                if (command.setsHistoryState("FAILED")) stall.end()
            }
            val recorder = CommandRecorder(
                onStarted = { command -> if (command.name == "commitTransaction") stall.start() },
                onSucceeded = endAfterFailedWrite,
                onFailed = endAfterFailedWrite
            )
            LagFixture("lag-commit-unconfirmed", recorder, stall).use { f ->
                val runs = AtomicInteger()
                val orderStatus = migration("004-order-status").inTransaction {
                    runs.incrementAndGet()
                    probe()
                }

                LogCapture().use { logs ->
                    val failure = shouldThrow<MigrationFailedException> { f.godwit.migrate(orderStatus) }

                    failure.step shouldBe StepKind.IN_TRANSACTION
                    failure.cause.shouldBeInstanceOf<MongoException>()
                    failure.suppressed.single().shouldBeInstanceOf<MongoException>()
                    logs.events("Migration failed") shouldHaveSize 1
                }
                val stored = f.stored("004-order-status")!!
                stored.getString("state") shouldBe "APPLIED"
                stored.containsKey("lastError") shouldBe false

                f.awaitMajorityApplied("004-order-status")
                f.godwit.migrate(orderStatus).upToDate shouldBe listOf("004-order-status")
                runs.get() shouldBe 1
                f.collection("probes").find().first().getInteger("n") shouldBe 1
            }
        }
    }
}
