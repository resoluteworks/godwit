package godwit.core

import godwit.core.fixtures.GodwitFixture
import godwit.core.fixtures.LogCapture
import godwit.core.fixtures.historyDocument
import godwit.core.fixtures.keyValues
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

private fun runs(id: String): Migration = migration(id).outsideTransaction { count("runs", 1) }

private val shop = listOf(
    runs("001-initial-setup"),
    runs("002-carts"),
    runs("003-file-store"),
    migration("004-order-status").inTransaction { count("ordersPaid", 0) },
    repeatable("reference-countries", "2026-10-01").outsideTransaction { },
    everyStart("bootstrap-customers").outsideTransaction { }
)

/**
 * `Target.Before` and `Target.Through` stop a run at a once-only migration: only the once-only migrations they reach
 * run, `report.pending` lists the ones they stopped before that are due, and no repeatable or every-start migration runs.
 */
class TargetTest : StringSpec() {
    init {
        "Target.Before runs what is listed before the id; pending lists the once-only ids it stopped before" {
            GodwitFixture().use { f ->
                LogCapture().use { logs ->
                    val report = f.godwit.migrate(shop, Target.Before("003-file-store"))

                    report.ran.map { it.id } shouldBe listOf("001-initial-setup", "002-carts")
                    report.pending shouldBe listOf("003-file-store", "004-order-status")
                    report.upToDate.shouldBeEmpty()
                    report.lockWait.shouldNotBeNull()
                    logs.events("Migrations complete").single().keyValues["ran"] shouldBe 2
                }
                f.godwit.history().map { it.id } shouldBe listOf("001-initial-setup", "002-carts")
            }
        }

        "Target.Through runs the id too, and still no repeatable or every-start migration" {
            GodwitFixture().use { f ->
                f.godwit.migrate(shop, Target.Before("003-file-store"))

                val report = f.godwit.migrate(shop, Target.Through("003-file-store"))

                report.ran.map { it.id } shouldBe listOf("003-file-store")
                report.upToDate shouldBe listOf("001-initial-setup", "002-carts")
                report.pending shouldBe listOf("004-order-status")
                f.godwit.history().map { it.id } shouldBe listOf("001-initial-setup", "002-carts", "003-file-store")
            }
        }

        "Target.Latest then runs the rest, repeatable and every-start migrations last; pending is empty" {
            GodwitFixture().use { f ->
                f.godwit.migrate(shop, Target.Through("002-carts"))

                val report = f.godwit.migrate(shop)

                report.ran.map { it.id } shouldBe
                    listOf("003-file-store", "004-order-status", "reference-countries", "bootstrap-customers")
                report.pending.shouldBeEmpty()
            }
        }

        "a target with nothing due before it takes the fast path and still reports what it stopped before" {
            GodwitFixture().use { f ->
                f.godwit.migrate(shop, Target.Through("002-carts"))

                val report = f.godwit.migrate(shop, Target.Before("003-file-store"))

                report.lockWait.shouldBeNull()
                report.ran.shouldBeEmpty()
                report.upToDate shouldBe listOf("001-initial-setup", "002-carts")
                report.pending shouldBe listOf("003-file-store", "004-order-status")
            }
        }

        "a target stops before a squash to record too: it is pending, and nothing is recorded" {
            GodwitFixture().use { f ->
                f.history.insertMany(listOf(historyDocument("000-old-a"), historyDocument("000-old-b")))
                val baseline = migration("001-baseline", supersedes = listOf("000-old-a", "000-old-b"))
                    .outsideTransaction { error("recorded only") }
                val list = listOf(baseline, runs("002-carts"))

                val before = f.godwit.migrate(list, Target.Before("001-baseline"))

                before.lockWait.shouldBeNull()
                before.recorded.shouldBeEmpty()
                before.pending shouldBe listOf("001-baseline", "002-carts")
                f.stored("001-baseline").shouldBeNull()

                val through = f.godwit.migrate(list, Target.Through("001-baseline"))

                through.recorded.map { it.id } shouldBe listOf("001-baseline")
                through.ran.shouldBeEmpty()
                through.pending shouldBe listOf("002-carts")
            }
        }
    }
}
