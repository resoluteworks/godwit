package com.example.shop.docs.testing

// region: convention-spec
import com.example.shop.migrations.shopMigrations
import com.example.shop.testing.FakeIdentityProvider
import com.example.shop.testing.testConfig
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk

/** Applied by the whole-list spec, which is all a plain DDL migration needs. */
private val coveredByTheWholeListRun = setOf("001-initial-setup", "002-carts", "003-file-store", "bootstrap-customers")

/** Each of these has a case of its own. Adding a migration to the list fails the spec below until it is named here. */
private val coveredByOwnCase = setOf(
    "004-order-status",
    "005-customer-external-ids",
    "006-order-totals",
    "reference-countries"
)

class EveryMigrationHasATestSpec : StringSpec({
    "every migration in the list is covered by a test" {
        val ids = shopMigrations(testConfig, mockk(), FakeIdentityProvider()).map { it.id }.toSet()

        coveredByTheWholeListRun + coveredByOwnCase shouldBe ids
    }

    "no migration is claimed by both groups" {
        (coveredByTheWholeListRun intersect coveredByOwnCase) shouldBe emptySet()
    }
})
// endregion
