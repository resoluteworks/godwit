package com.example.shop.docs.testing

// region: own-cluster-spec
import com.example.shop.migrations.shopMigrations
import com.example.shop.services.CustomerService
import com.example.shop.testing.FakeIdentityProvider
import com.example.shop.testing.testConfig
import godwit.core.Godwit
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class OwnClusterSpec : StringSpec({
    "the list applies to a database on our own cluster" {
        val client = clientWithEscapeDetector(System.getenv("TEST_MONGO_URI"))
        val databaseName = "shop-test-${UUID.randomUUID()}"
        try {
            val customers = CustomerService(client.getDatabase(databaseName))
            val migrations = shopMigrations(testConfig, customers, FakeIdentityProvider())

            Godwit(client, databaseName).migrate(migrations).ran.map { it.id } shouldBe migrations.map { it.id }
        } finally {
            client.getDatabase(databaseName).drop()
            client.close()
        }
    }
})
// endregion
