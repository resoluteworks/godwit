package godwit.core

import godwit.core.fixtures.GodwitFixture
import godwit.core.fixtures.LogCapture
import godwit.core.fixtures.collection
import godwit.core.fixtures.keyValues
import godwit.core.fixtures.lockOperations
import godwit.core.fixtures.referenceCountries
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.bson.Document
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(RepeatablesFastPathTest::class.java)

/** The starts each spec makes after the first. */
private const val STARTS = 5

private val setup = migration("001-initial-setup").outsideTransaction { ensureCollection("customers") }

private val orderStatus = migration("004-order-status").inTransaction {
    collection("orders").insertOne(session, Document("status", "PAID"))
}

/** What the lock costs a start: nothing for a repeatable at its revision, an acquire and a release per every-start. */
class RepeatablesFastPathTest : StringSpec() {
    init {
        "a start whose list ends with an up-to-date repeatable reads history once and sends no command to godwit-lock" {
            GodwitFixture().use { f ->
                val list = listOf(setup, orderStatus, referenceCountries("2026-10-01"))
                f.godwit.migrate(list).lockWait.shouldNotBeNull()
                f.recorder.clear()

                LogCapture().use { logs ->
                    val reports = (1..STARTS).map { f.godwit.migrate(list) }

                    reports.forEach { report ->
                        report.lockWait.shouldBeNull()
                        report.ran.shouldBeEmpty()
                        report.upToDate shouldBe listOf("001-initial-setup", "004-order-status", "reference-countries")
                    }
                    logs.events.map { it.message } shouldBe List(STARTS) { "Migrations up to date" }
                    logs.events.forEach { it.keyValues["checked"] shouldBe 3 }
                }
                val commands = f.recorder.commands
                val lockCommands = commands.count { it.collection == "godwit-lock" }
                lockCommands shouldBe 0
                commands.map { "${it.name} ${it.collection}" } shouldBe List(STARTS) { "find godwit-history" }
                log.info(
                    "repeatables fastPath starts={} commands={} lockCommands={}",
                    STARTS,
                    commands.size,
                    lockCommands
                )
            }
        }

        "an every-start migration makes every start take the lock: one acquire and one release per start" {
            GodwitFixture().use { f ->
                val bootstrap = everyStart("bootstrap-customers").outsideTransaction { count("usersChecked", 2) }
                val list = listOf(setup, referenceCountries("2026-10-01"), bootstrap)
                f.godwit.migrate(list)

                for (start in 1..STARTS) {
                    f.recorder.clear()
                    val report = f.godwit.migrate(list)

                    withClue("start $start") {
                        report.ran.map { it.id } shouldBe listOf("bootstrap-customers")
                        report.lockWait.shouldNotBeNull()
                        val commands = f.recorder.commands
                        commands.lockOperations() shouldBe listOf("acquire", "release")
                        commands.first { it.collection == "godwit-lock" }.name shouldBe "findAndModify"
                        commands.last().let { "${it.name} ${it.collection}" } shouldBe "update godwit-lock"
                    }
                }
                f.stored("bootstrap-customers")!!.getLong("runCount") shouldBe STARTS + 1L
                f.stored("reference-countries")!!.getLong("runCount") shouldBe 1L
            }
        }

        "a repeatable takes the lock only on the start that applies a new revision" {
            GodwitFixture().use { f ->
                val revisions = listOf("2026-10-01", "2026-10-01", "2026-11-15", "2026-11-15", "2026-11-15")

                val perStart = revisions.map { revision ->
                    f.recorder.clear()
                    f.godwit.migrate(setup, referenceCountries(revision))
                    f.recorder.commands.lockOperations()
                }

                perStart shouldBe listOf(
                    listOf("acquire", "release"),
                    emptyList(),
                    listOf("acquire", "release"),
                    emptyList(),
                    emptyList()
                )
            }
        }
    }
}
