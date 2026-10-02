package com.example.shop.docs.testing

// region: imports
import com.example.shop.migrations.orderTotals
import com.example.shop.migrations.shopMigrations
import com.example.shop.services.CustomerService
import com.example.shop.testing.FakeIdentityProvider
import com.example.shop.testing.migrationsFor
import com.example.shop.testing.testConfig
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.exists
import godwit.core.InvalidMigrationsException
import godwit.core.PlanConflictException
import godwit.core.Target
import godwit.core.migration
import godwit.core.validateMigrations
import godwit.test.forget
import godwit.test.rerun
import godwit.test.runIsolated
import godwit.test.shouldHaveApplied
import godwit.test.testGodwit
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.bson.Document
import java.util.Date
// endregion

// region: list-spec
class MigrationListSpec : StringSpec({
    "the migration list is valid" {
        validateMigrations(shopMigrations(testConfig, mockk(), FakeIdentityProvider()))
    }

    "two branches that both add 007 are caught without a database" {
        val merged = listOf(
            migration("007-product-slugs").inTransaction { },
            migration("007-cart-currency").inTransaction { }
        )

        shouldThrow<InvalidMigrationsException> { validateMigrations(merged) }.problems shouldHaveSize 1
    }
})
// endregion

// region: whole-list-spec
class EmptyDatabaseSpec : StringSpec({
    "every migration applies to an empty database, and the next start runs only the every-start one" {
        val db = testGodwit(atlasSearch = true)
        val migrations = migrationsFor(db)

        db.godwit.migrate(migrations).ran.map { it.id } shouldBe migrations.map { it.id }
        db.godwit.migrate(migrations).ran.map { it.id } shouldBe listOf("bootstrap-customers")
        db.godwit.status(migrations).isUpToDate shouldBe true
    }
})
// endregion

// region: mid-history-spec
class MidHistorySpec : StringSpec({
    "004 marks orders with paidAt PAID and keeps the date, and a second run changes nothing" {
        val db = testGodwit(atlasSearch = true)
        val migrations = migrationsFor(db)
        db.godwit.migrate(migrations, target = Target.Before("004-order-status"))

        val orders = db.database.getCollection("orders", Document::class.java)
        orders.insertMany(listOf(Document("_id", "paid").append("paidAt", Date(0)), Document("_id", "open")))

        val outcome = db.godwit.migrate(migrations, target = Target.Through("004-order-status"))["004-order-status"]

        outcome.count("ordersPaid") shouldBe 1L
        outcome.count("ordersPending") shouldBe 1L
        orders.find(eq("_id", "paid")).first().getDate("paidAt") shouldBe Date(0)
        orders.find(eq("_id", "open")).first()["status"] shouldBe "PENDING"
        db.godwit shouldHaveApplied "004-order-status"
        db.godwit.rerun(migrations, "004-order-status").count("ordersPaid") shouldBe 0L
    }

    "005 links the customers that have no identity provider user, and a second run links nobody" {
        val db = testGodwit(atlasSearch = true)
        val migrations = migrationsFor(db)
        db.godwit.migrate(migrations, target = Target.Before("005-customer-external-ids"))

        val customers = db.database.getCollection("customers", Document::class.java)
        customers.insertMany(
            listOf(
                Document("email", "ann@example.com").append("name", "Ann"),
                Document("email", "bob@example.com").append("name", "Bob").append("externalUserId", "user-bob")
            )
        )

        val outcome = db.godwit.migrate(migrations, target = Target.Through("005-customer-external-ids"))
            .get("005-customer-external-ids")

        outcome.count("customersLinked") shouldBe 1L
        CustomerService(db.database).findByEmail("ann@example.com")?.externalUserId shouldBe "user-ann@example.com"
        db.godwit.rerun(migrations, "005-customer-external-ids").count("customersLinked") shouldBe 0L
    }
})
// endregion

// region: isolated-spec
class IsolatedMigrationSpec : StringSpec({
    "006 totals every order in pages of 500, on its own" {
        val db = testGodwit()
        val orders = db.database.getCollection("orders", Document::class.java)
        orders.insertMany(
            List(1203) { Document("lines", listOf(Document("quantity", 2).append("unitPriceMinor", 1250L))) }
        )

        val outcome = db.godwit.runIsolated(orderTotals)

        outcome.count("ordersUpdated") shouldBe 1203L
        outcome.batches shouldBe 3
        orders.countDocuments(exists("totalMinor", false)) shouldBe 0L
        db.godwit shouldHaveApplied "006-order-totals"
    }
})
// endregion

// region: forget-spec
class ForgetSpec : StringSpec({
    "reference-countries removes a country that is not in the list when it runs again" {
        val db = testGodwit(atlasSearch = true)
        val migrations = migrationsFor(db)
        db.godwit.migrate(migrations)
        val countries = db.database.getCollection("countries", Document::class.java)
        countries.insertOne(Document("_id", "XX").append("name", "Nowhere"))

        db.godwit.forget("reference-countries")
        val outcome = db.godwit.migrate(migrations)["reference-countries"]

        outcome.count("countriesRemoved") shouldBe 1L
        countries.find(eq("_id", "XX")).firstOrNull() shouldBe null
        countries.countDocuments() shouldBe 4L
    }

    "a forgotten migration in the middle of the list is out of order for a plain migrate" {
        val db = testGodwit(atlasSearch = true)
        val migrations = migrationsFor(db)
        db.godwit.migrate(migrations)

        db.godwit.forget("005-customer-external-ids")

        shouldThrow<PlanConflictException> { db.godwit.migrate(migrations) }
        db.godwit.rerun(migrations, "005-customer-external-ids").count("customersLinked") shouldBe 0L
    }
})
// endregion
