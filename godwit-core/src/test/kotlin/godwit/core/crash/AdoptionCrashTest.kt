package godwit.core.crash

import com.mongodb.kotlin.client.MongoCollection
import godwit.core.MigrationReport
import godwit.core.OutOfOrder
import godwit.core.fixtures.CountingHook
import godwit.core.fixtures.GodwitFixture
import godwit.core.fixtures.LogCapture
import godwit.core.fixtures.StandaloneProcess
import godwit.core.fixtures.TestMongo
import godwit.core.fixtures.line
import godwit.core.fixtures.origins
import godwit.core.fixtures.standaloneMongo
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.bson.Document
import java.util.UUID

/** The ids the child recorded before it died, and what the next start then records. */
private class AdoptionExpected(val leftByChild: List<String>, val adoptedByNextStart: List<String>)

private val replicaSetCases = mapOf(
    CrashPoint.ADOPTION_IN_TRANSACTION to AdoptionExpected(emptyList(), adoptedBeforeGodwit.sorted()),
    CrashPoint.ADOPTION_COMMITTED to AdoptionExpected(adoptedBeforeGodwit.sorted(), emptyList())
)

/** On a standalone server the writes go last-listed first: two writes leave 002 and 003 and miss 001. */
private val standalonePartial = AdoptionExpected(listOf("002-adopted", "003-adopted"), listOf("001-adopted"))

private fun MongoCollection<Document>.holders(): Set<String> = find().map { it.getString("holder") }.toList().toSet()

/**
 * Checks the start after the crash: it called the hook once, recorded [expected]'s missing ids and nothing else, ran
 * `004-left` once and nothing out of order, and left every adopted id ADOPTED.
 */
private fun MigrationReport.shouldComplete(expected: AdoptionExpected, hook: CountingHook, logs: LogCapture) {
    hook.calls shouldBe 1
    recorded.map { it.id } shouldBe expected.adoptedByNextStart
    ran.map { it.id } shouldBe listOf("004-left")
    ran.single().outOfOrder shouldBe false
    logs.events("Adopted applied migrations").single().line shouldBe
        "Adopted applied migrations adopted=[${expected.adoptedByNextStart.joinToString(", ")}] ignored=[]"
    logs.events("Running out-of-order migration").shouldBeEmpty()
}

/**
 * A child JVM (CrashMain) adopts a database and is killed while it records the adopted ids; the next start, in this
 * JVM, calls the hook again and records what is missing, without a conflict and without running an adopted id.
 */
class AdoptionCrashTest : StringSpec() {
    init {
        for ((point, expected) in replicaSetCases) {
            "killed at $point on a replica set: the next start finds all or none recorded and records the rest" {
                TestMongo.database().use { db ->
                    val child = CrashHarness.crashAt(point, TestMongo.connectionString, db.name)
                    child.exitCode shouldNotBe 0
                    TestMongo.client().use { CrashHarness.abortOpenTransaction(it, child) }
                    withClue("an open transaction only at ADOPTION_IN_TRANSACTION") {
                        (child.openTransactionSession != null) shouldBe (point == CrashPoint.ADOPTION_IN_TRANSACTION)
                    }
                    val history = db.database.getCollection("godwit-history", Document::class.java)
                    history.origins() shouldBe expected.leftByChild.map { "$it ADOPTED" }

                    val hook = CountingHook(*adoptedBeforeGodwit.toTypedArray())
                    GodwitFixture(appName = "crash-parent", config = adoptionConfig("crash-parent/1", hook), db = db)
                        .use { f ->
                            LogCapture().use { logs ->
                                f.godwit.migrate(adoptionScenario()).shouldComplete(expected, hook, logs)
                            }
                            f.history.origins() shouldBe adoptedBeforeGodwit.sorted().map { "$it ADOPTED" } +
                                "004-left RAN"
                            f.collection("outside-runs").countDocuments() shouldBe 1L
                        }
                }
            }
        }

        for (outOfOrder in OutOfOrder.entries) {
            "killed between two writes on a standalone server: the next start completes it, OutOfOrder.$outOfOrder" {
                val databaseName = UUID.randomUUID().toString()
                val point = CrashPoint.ADOPTION_TWO_WRITES
                val child = CrashHarness.crashAt(point, standaloneMongo.connectionString, databaseName)
                child.exitCode shouldNotBe 0
                child.openTransactionSession shouldBe null

                val hook = CountingHook(*adoptedBeforeGodwit.toTypedArray())
                val config = adoptionConfig("crash-parent/1", hook).copy(outOfOrder = outOfOrder)
                StandaloneProcess("crash-parent", config, databaseName = databaseName).use { p ->
                    p.history.origins() shouldBe standalonePartial.leftByChild.map { "$it ADOPTED" }
                    p.history.holders() shouldBe setOf("crash-child/1")

                    LogCapture().use { logs ->
                        p.godwit.migrate(adoptionScenario()).shouldComplete(standalonePartial, hook, logs)
                    }
                    p.history.origins() shouldBe adoptedBeforeGodwit.sorted().map { "$it ADOPTED" } + "004-left RAN"
                    p.database.getCollection("outside-runs", Document::class.java).countDocuments() shouldBe 1L
                }
            }
        }

        "every adoption crash point has its case" {
            (replicaSetCases.keys + CrashPoint.ADOPTION_TWO_WRITES) shouldBe
                CrashPoint.entries.filter { it.scenario == CrashScenario.ADOPTION }.toSet()
        }
    }
}
