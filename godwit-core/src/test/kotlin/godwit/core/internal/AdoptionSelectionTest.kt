package godwit.core.internal

import godwit.core.HistoryState
import godwit.core.Migration
import godwit.core.Origin
import godwit.core.everyStart
import godwit.core.migration
import godwit.core.repeatable
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

private fun once(id: String, supersedes: List<String> = emptyList()): Migration =
    migration(id, supersedes = supersedes).outsideTransaction { }

private val list = listOf(
    once("001-initial-setup"),
    once("006-baseline", supersedes = listOf("002-carts", "003-file-store")),
    once("007-customer-email-lower"),
    repeatable("reference-countries", "2026-10-01").outsideTransaction { },
    everyStart("bootstrap-customers").outsideTransaction { }
)

private fun adopted(vararg ids: String) =
    ids.map { HistoryRecord(it, StoredKind.ONCE, HistoryState.APPLIED, Origin.ADOPTED) }

/** The pure half of adoption: which returned ids are recorded, in which order, and which are ignored. */
class AdoptionSelectionTest : StringSpec() {
    init {
        "adoptable ids: once-only migrations in list order, each after the ids its supersedes list names" {
            adoptableIds(list) shouldBe listOf(
                "001-initial-setup",
                "002-carts",
                "003-file-store",
                "006-baseline",
                "007-customer-email-lower"
            )
            adoptableIds(emptyList()).shouldBeEmpty()
        }

        "the returned adoptable ids history lacks are recorded in list order; the rest are ignored, sorted" {
            val returned = setOf(
                "zz-cleanup",
                "007-customer-email-lower",
                "reference-countries",
                "003-file-store",
                "bootstrap-customers",
                "001-initial-setup",
                "002-Carts"
            )

            selectAdopted(list, returned, emptyList()) shouldBe AdoptionSelection(
                toRecord = listOf("001-initial-setup", "003-file-store", "007-customer-email-lower"),
                ignored = listOf("002-Carts", "bootstrap-customers", "reference-countries", "zz-cleanup")
            )
        }

        "ids history holds already are neither recorded again nor ignored" {
            val returned = setOf("001-initial-setup", "002-carts", "cleanup-temp-data")

            selectAdopted(list, returned, adopted("001-initial-setup", "003-file-store")) shouldBe
                AdoptionSelection(toRecord = listOf("002-carts"), ignored = listOf("cleanup-temp-data"))
            selectAdopted(list, setOf("001-initial-setup"), adopted("001-initial-setup")) shouldBe
                AdoptionSelection(emptyList(), emptyList())
            selectAdopted(list, emptySet(), emptyList()) shouldBe AdoptionSelection(emptyList(), emptyList())
        }
    }
}
