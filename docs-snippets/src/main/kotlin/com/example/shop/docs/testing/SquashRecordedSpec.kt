package com.example.shop.docs.testing

// region: squash-recorded-spec
import com.example.shop.migrations.squashedMigrations
import com.example.shop.services.CustomerService
import com.example.shop.testing.FakeIdentityProvider
import com.example.shop.testing.migrationsFor
import com.example.shop.testing.testConfig
import godwit.core.Origin
import godwit.test.testGodwit
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class SquashRecordedSpec : StringSpec({
    "a database migrated before the squash records the baseline without running it" {
        val db = testGodwit(atlasSearch = true)
        db.godwit.migrate(migrationsFor(db))
        val squashed = squashedMigrations(testConfig, CustomerService(db.database), FakeIdentityProvider())

        val report = db.godwit.migrate(squashed)

        report["100-baseline"].origin shouldBe Origin.SUPERSEDED
        report.ran.map { it.id } shouldContainExactly listOf("bootstrap-customers")
    }
})
// endregion
