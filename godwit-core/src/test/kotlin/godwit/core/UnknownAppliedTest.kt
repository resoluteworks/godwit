package godwit.core

import godwit.core.fixtures.GodwitFixture
import godwit.core.fixtures.LogCapture
import godwit.core.fixtures.historyDocument
import godwit.core.fixtures.line
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

private fun runs(id: String): Migration = migration(id).outsideTransaction { count("runs", 1) }

/** The APPLIED ids of [plantNewerRelease]'s history that a list of 001 and 002 does not know, sorted. */
private val unknown = listOf("008-cart-currency", "009-order-payment-status", "100-baseline", "zz-reindex-products")

/**
 * Unknown applied ids: APPLIED history ids that no declared id, no declared `supersedes` list and no stored
 * `supersedes` list names. They are checked before the fast path: logged and reported under `UnknownApplied.WARN`, a
 * `PlanConflictException` under `UnknownApplied.FAIL`.
 */
class UnknownAppliedTest : StringSpec() {
    /** History after a rollback: two ids from a newer release, a squash's records, and undeclared unfinished ones. */
    private fun GodwitFixture.plantNewerRelease() {
        history.insertMany(
            listOf(
                historyDocument("001-initial-setup"),
                historyDocument("zz-reindex-products"),
                historyDocument("009-order-payment-status"),
                historyDocument("008-cart-currency", kind = "REPEATABLE"),
                historyDocument("010-failed-in-newer", HistoryState.FAILED),
                historyDocument("011-running-in-newer", HistoryState.RUNNING),
                historyDocument("100-baseline").append("supersedes", listOf("000-squashed")),
                historyDocument("000-squashed")
            )
        )
    }

    init {
        "WARN: the unknown ids, sorted, in the log line and the report, and the start carries on" {
            GodwitFixture().use { f ->
                f.plantNewerRelease()
                val list = listOf(runs("001-initial-setup"), runs("002-carts"))

                LogCapture().use { logs ->
                    val report = f.godwit.migrate(list)

                    report.unknownApplied shouldBe unknown
                    val warning = logs.events("Unknown applied migrations").single()
                    warning.line shouldBe "Unknown applied migrations ids=[${unknown.joinToString(", ")}]"
                    warning.level.toString() shouldBe "WARN"
                    logs.events.first().message shouldBe "Unknown applied migrations"
                    report.ran.map { it.id } shouldBe listOf("002-carts")
                    f.godwit.status(list).unknownApplied shouldBe unknown
                }
            }
        }

        "WARN on a start with nothing due: the fast path logs and reports them without taking the lock" {
            GodwitFixture().use { f ->
                f.plantNewerRelease()

                LogCapture().use { logs ->
                    val report = f.godwit.migrate(runs("001-initial-setup"))

                    report.lockWait.shouldBeNull()
                    report.unknownApplied shouldBe unknown
                    logs.events.map { it.message } shouldBe
                        listOf("Unknown applied migrations", "Migrations up to date")
                }
            }
        }

        "FAIL on a start with nothing due: PlanConflictException, one problem per id in id order, before any lock" {
            GodwitFixture(config = GodwitConfig(unknownApplied = UnknownApplied.FAIL)).use { f ->
                f.plantNewerRelease()
                f.recorder.clear()

                val conflict = shouldThrow<PlanConflictException> { f.godwit.migrate(runs("001-initial-setup")) }

                conflict.problems shouldBe
                    unknown.map { "$it is applied, but the list does not declare it (UnknownApplied.FAIL)" }
                conflict.problems.first() shouldBe
                    "008-cart-currency is applied, but the list does not declare it (UnknownApplied.FAIL)"
                f.recorder.commands.map { it.name } shouldBe listOf("find")
                f.godwit.status(listOf(runs("001-initial-setup"))).problems shouldBe conflict.problems
            }
        }

        "ids the list declares, or names in a declared or stored supersedes list, are known" {
            GodwitFixture(config = GodwitConfig(unknownApplied = UnknownApplied.FAIL)).use { f ->
                f.plantNewerRelease()
                val baseline = migration("100-baseline", supersedes = listOf("009-order-payment-status"))
                    .outsideTransaction { }
                val list = listOf(
                    runs("001-initial-setup"),
                    baseline,
                    migration("zz-reindex-products").outsideTransaction { },
                    repeatable("008-cart-currency", "2026-10-01").outsideTransaction { }
                )

                val report = f.godwit.migrate(list)

                report.unknownApplied.shouldBeEmpty()
                report.lockWait.shouldNotBeNull()
            }
        }
    }
}
