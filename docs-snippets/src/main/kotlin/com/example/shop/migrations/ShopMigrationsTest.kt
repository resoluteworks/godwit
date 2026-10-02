package com.example.shop.migrations

import com.example.shop.BootstrapSettings
import com.example.shop.IdentitySettings
import com.example.shop.MongoSettings
import com.example.shop.SeedCustomer
import com.example.shop.ShopConfig
import com.example.shop.services.CustomerService
import com.example.shop.services.ExternalUser
import com.example.shop.services.IdentityProvider
import com.mongodb.client.model.Filters.eq
import godwit.core.Target
import godwit.core.validateMigrations
import godwit.test.rerun
import godwit.test.runIsolated
import godwit.test.shouldHaveApplied
import godwit.test.testGodwit
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.bson.Document
import java.util.Date

private val testConfig = ShopConfig(
    mongo = MongoSettings(uri = "unused", database = "unused", searchIndexWait = null),
    identity = IdentitySettings(baseUrl = "unused", apiKey = "unused"),
    bootstrap = BootstrapSettings(customers = listOf(SeedCustomer(email = "staff@example.com", name = "Staff")))
)

private class FakeIdentityProvider : IdentityProvider {
    override fun findOrCreateUser(email: String) = ExternalUser(id = "user-$email", email = email)
}

class ShopMigrationsTest : StringSpec({
    "the migration list is valid" {
        validateMigrations(shopMigrations(testConfig, mockk(), FakeIdentityProvider()))
    }

    "every migration applies to an empty database, and the next start runs only the every-start one" {
        val db = testGodwit(atlasSearch = true)
        val migrations = shopMigrations(testConfig, CustomerService(db.database), FakeIdentityProvider())

        db.godwit.migrate(migrations).ran.map { it.id } shouldBe migrations.map { it.id }
        db.godwit.migrate(migrations).ran.map { it.id } shouldBe listOf("bootstrap-customers")
        db.godwit.status(migrations).isUpToDate shouldBe true
    }

    "004 marks orders with paidAt PAID and the rest PENDING, and a second run changes nothing" {
        val db = testGodwit(atlasSearch = true)
        val migrations = shopMigrations(testConfig, CustomerService(db.database), FakeIdentityProvider())
        db.godwit.migrate(migrations, target = Target.Before("004-order-status"))

        val orders = db.database.getCollection("orders", Document::class.java)
        orders.insertMany(listOf(Document("_id", "paid").append("paidAt", Date()), Document("_id", "open")))

        val outcome = db.godwit.migrate(migrations, target = Target.Through("004-order-status"))["004-order-status"]
        outcome.count("ordersPaid") shouldBe 1L
        outcome.count("ordersPending") shouldBe 1L
        orders.find(eq("_id", "open")).first()["status"] shouldBe "PENDING"
        db.godwit shouldHaveApplied "004-order-status"
        db.godwit.rerun(migrations, "004-order-status").count("ordersPaid") shouldBe 0L
    }

    "006 totals every order, on its own" {
        val db = testGodwit()
        db.database.getCollection("orders", Document::class.java).insertOne(
            Document("lines", listOf(Document("quantity", 2).append("unitPriceMinor", 1250L)))
        )

        db.godwit.runIsolated(orderTotals).count("ordersUpdated") shouldBe 1L
    }
})
