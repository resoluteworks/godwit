package godwit.core.crash

import com.mongodb.client.model.Filters.eq
import godwit.core.fixtures.GodwitFixture
import godwit.core.fixtures.LogCapture
import godwit.core.fixtures.TestMongo
import godwit.core.fixtures.line
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.bson.Document

/** What the next start after a crash at a point finds and does. */
private class Expected(
    /** The crashed run left `001-crash` RUNNING, so the next start resumes it. */
    val resumed: Boolean,
    /** Runs of `001-crash`'s outside step over both processes. */
    val outsideRuns: Long
)

private val windows = mapOf(
    CrashPoint.AFTER_MARKER to Expected(resumed = true, outsideRuns = 1),
    CrashPoint.MID_OUTSIDE_STEP to Expected(resumed = true, outsideRuns = 2),
    CrashPoint.AFTER_OUTSIDE_STEP to Expected(resumed = true, outsideRuns = 2),
    CrashPoint.IN_TRANSACTION to Expected(resumed = true, outsideRuns = 2),
    CrashPoint.AFTER_COMMIT to Expected(resumed = false, outsideRuns = 1)
)

/**
 * A child JVM runs the migrations and is killed at each crash window; the next start, in this JVM, finishes them. Every
 * transactional effect (an `$inc` probe) ends at exactly 1.
 */
class CrashWindowTest : StringSpec() {
    init {
        for ((point, expected) in windows) {
            "killed at $point: the next start ${if (expected.resumed) "resumes the migration" else "runs the rest"}" {
                TestMongo.database().use { db ->
                    val child = CrashHarness.crashAt(point, TestMongo.connectionString, db.name)
                    child.exitCode shouldNotBe 0
                    TestMongo.client().use { CrashHarness.abortOpenTransaction(it, child) }
                    withClue("an open transaction only at IN_TRANSACTION") {
                        (child.openTransactionSession != null) shouldBe (point == CrashPoint.IN_TRANSACTION)
                    }
                    val crashed = db.database.getCollection("godwit-history", Document::class.java)
                        .find(eq("_id", "001-crash")).first()
                    crashed.getString("state") shouldBe if (expected.resumed) "RUNNING" else "APPLIED"
                    crashed.getString("holder") shouldBe "crash-child/1"

                    GodwitFixture(appName = "crash-parent", config = crashConfig("crash-parent/1"), db = db).use { f ->
                        LogCapture().use { logs ->
                            val report = f.godwit.migrate(crashScenario())

                            val resumed = logs.events("Resuming interrupted migration")
                            if (expected.resumed) {
                                resumed.single().line shouldBe "Resuming interrupted migration id=001-crash attempts=2"
                                report.ran.map { it.id } shouldBe listOf("001-crash", "002-after")
                                report["001-crash"].attempts shouldBe 2
                            } else {
                                resumed.shouldBeEmpty()
                                report.ran.map { it.id } shouldBe listOf("002-after")
                                report.upToDate shouldBe listOf("001-crash")
                            }
                            report.lockWait.shouldNotBeNull()
                        }
                        val applied = f.stored("001-crash").shouldNotBeNull()
                        applied.getString("state") shouldBe "APPLIED"
                        applied.getInteger("attempts") shouldBe if (expected.resumed) 2 else 1
                        f.stored("002-after")!!.getInteger("attempts") shouldBe 1
                        f.collection("probes").find().toList().associate {
                            it.getString("_id") to it.getInteger("n")
                        } shouldBe
                            mapOf("001-crash" to 1, "002-after" to 1)
                        f.collection("outside-runs").countDocuments() shouldBe expected.outsideRuns
                        f.godwit.migrate(crashScenario()).lockWait.shouldBeNull()
                    }
                }
            }
        }

        "every crash point has its window" {
            windows.keys shouldBe CrashPoint.entries.toSet()
        }
    }
}
