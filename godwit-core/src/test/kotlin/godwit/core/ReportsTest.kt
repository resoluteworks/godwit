package godwit.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private fun outcome(id: String, origin: Origin = Origin.RAN, counts: Map<String, Long> = emptyMap()) = MigrationOutcome(
    id,
    MigrationKind.Once,
    origin,
    if (origin == Origin.RAN) listOf(StepKind.IN_TRANSACTION) else emptyList(),
    1,
    0,
    0,
    counts,
    false,
    84.milliseconds
)

class ReportsTest : StringSpec() {
    init {
        "an outcome's counter is 0 when the steps never counted it" {
            val outcome = outcome("004-order-status", counts = mapOf("ordersPaid" to 1200L))
            outcome.count("ordersPaid") shouldBe 1200L
            outcome.count("ordersRefunded") shouldBe 0L
        }

        "report[id] finds a migration that ran or was recorded, and throws for one that did neither" {
            val ran = outcome("bootstrap-customers")
            val recorded = outcome("100-baseline", Origin.SUPERSEDED)
            val report = MigrationReport(
                "run",
                listOf(ran),
                listOf(recorded),
                listOf("reference-countries"),
                emptyList(),
                emptyList(),
                212.milliseconds,
                Duration.ZERO
            )
            report["bootstrap-customers"] shouldBeSameInstanceAs ran
            report["100-baseline"] shouldBeSameInstanceAs recorded
            shouldThrow<NoSuchElementException> { report["reference-countries"] }.message shouldBe
                "reference-countries neither ran nor was recorded"
        }

        "a status is up to date only with nothing pending and no problem" {
            MigrationStatus(emptyList(), emptyList(), listOf("009-gone")).isUpToDate shouldBe true
            MigrationStatus(listOf("007-a"), emptyList(), emptyList()).isUpToDate shouldBe false
            MigrationStatus(emptyList(), listOf("a conflict"), emptyList()).isUpToDate shouldBe false
        }
    }
}
