package com.example.shop

import com.mongodb.client.model.Filters.eq
import godwit.core.Target
import godwit.core.validateMigrations
import godwit.test.rerun
import godwit.test.shouldHaveApplied
import godwit.test.testGodwit
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bson.Document
import java.util.Date

class MigrationsTest : StringSpec({
    "the list is valid" {
        validateMigrations(migrations)
    }

    "004 marks orders with paidAt PAID and the rest PENDING, and a second run changes nothing" {
        val db = testGodwit()
        db.godwit.migrate(migrations, target = Target.Before("004-order-status"))
        val orders = db.database.getCollection("orders", Document::class.java)
        orders.insertMany(listOf(Document("_id", "paid").append("paidAt", Date()), Document("_id", "open")))

        val outcome = db.godwit.migrate(migrations)["004-order-status"]

        outcome.count("ordersPaid") shouldBe 1L
        outcome.count("ordersPending") shouldBe 1L
        orders.find(eq("_id", "open")).first()["status"] shouldBe "PENDING"
        db.godwit shouldHaveApplied "004-order-status"
        db.godwit.rerun(migrations, "004-order-status").count("ordersPaid") shouldBe 0L
    }
})
