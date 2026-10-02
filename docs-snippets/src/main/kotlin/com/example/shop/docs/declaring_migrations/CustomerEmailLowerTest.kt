package com.example.shop.docs.declaring_migrations

import com.example.shop.migrations.customerEmailLower
import com.example.shop.migrations.customerEmailLowerIndex
import com.mongodb.client.model.Filters.eq
import godwit.test.runIsolated
import godwit.test.testGodwit
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bson.Document

class CustomerEmailLowerTest : StringSpec({
    "007 fills emailLower, then 008 indexes it" {
        val db = testGodwit()
        val customers = db.database.getCollection("customers", Document::class.java)
        customers.insertOne(Document("email", "Ada@Example.com").append("name", "Ada"))

        db.godwit.runIsolated(customerEmailLower).count("customersUpdated") shouldBe 1L
        db.godwit.runIsolated(customerEmailLowerIndex)

        customers.find(eq("emailLower", "ada@example.com")).first()["name"] shouldBe "Ada"
    }
})
