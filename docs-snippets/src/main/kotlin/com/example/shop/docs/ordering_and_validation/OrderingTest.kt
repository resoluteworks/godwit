package com.example.shop.docs.ordering_and_validation

import com.example.shop.migrations.shopMigrations
import com.example.shop.services.CustomerService
import com.example.shop.testing.FakeIdentityProvider
import com.example.shop.testing.testConfig
import godwit.core.Godwit
import godwit.core.GodwitConfig
import godwit.core.OutOfOrder
import godwit.core.PlanConflictException
import godwit.core.validateMigrations
import godwit.test.testGodwit
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk

class OrderingTest : StringSpec({
    "the migration list is valid" {
        validateMigrations(shopMigrations(testConfig, mockk(), FakeIdentityProvider()))
    }

    "staging ran 008 from its branch, so main's 007 is out of order there" {
        val db = testGodwit(atlasSearch = true)
        val customers = CustomerService(db.database)
        db.godwit.migrate(cartCurrencyBranchMigrations(testConfig, customers, FakeIdentityProvider()))

        val main = release14Migrations(testConfig, customers, FakeIdentityProvider())
        shouldThrow<PlanConflictException> { db.godwit.migrate(main) }

        val staging = Godwit(db.client, db.databaseName, GodwitConfig(outOfOrder = OutOfOrder.RUN))
        staging.migrate(main)["007-product-slugs"].outOfOrder shouldBe true
    }

    "the previous release still starts on a database this release migrated" {
        val db = testGodwit(atlasSearch = true)
        val customers = CustomerService(db.database)
        db.godwit.migrate(release14Migrations(testConfig, customers, FakeIdentityProvider()))

        val release13 = shopMigrations(testConfig, customers, FakeIdentityProvider())
        db.godwit.migrate(release13).unknownApplied shouldBe listOf("007-product-slugs", "008-cart-currency")
    }
})
