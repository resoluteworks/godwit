package com.example.shop.docs.dependencies

import com.example.shop.BootstrapSettings
import com.example.shop.IdentitySettings
import com.example.shop.MongoSettings
import com.example.shop.ShopConfig
import com.example.shop.migrations.customerExternalIds
import com.example.shop.migrations.orderPaymentStatus
import com.example.shop.services.CustomerService
import com.example.shop.services.ExternalUser
import com.example.shop.services.IdentityProvider
import com.example.shop.services.PaymentGateway
import com.example.shop.services.PaymentStatus
import com.mongodb.client.model.Filters.eq
import godwit.core.validateMigrations
import godwit.test.runIsolated
import godwit.test.testGodwit
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bson.Document
import org.bson.types.ObjectId

private val testConfig = ShopConfig(
    mongo = MongoSettings(uri = "unused", database = "unused", searchIndexWait = null),
    identity = IdentitySettings(baseUrl = "unused", apiKey = "unused"),
    bootstrap = BootstrapSettings(customers = emptyList())
)

class DependenciesTest : StringSpec({
    "the migration list is valid" {
        validateMigrations(shopMigrations(testConfig, mockk(), mockk(), mockk()))
    }

    "005 links an unlinked customer through the identity provider" {
        val db = testGodwit()
        val customers = CustomerService(db.database)
        db.database.getCollection("customers", Document::class.java)
            .insertOne(Document("email", "ada@example.com").append("name", "Ada"))
        val identity = mockk<IdentityProvider> {
            every { findOrCreateUser("ada@example.com") } returns ExternalUser("user-1", "ada@example.com")
        }

        db.godwit.runIsolated(customerExternalIds(customers, identity)).count("customersLinked") shouldBe 1L
        customers.findByEmail("ada@example.com")?.externalUserId shouldBe "user-1"
    }

    "009 asks the gateway about orders with a payment only" {
        val db = testGodwit()
        val orders = db.database.getCollection("orders", Document::class.java)
        val paid = ObjectId()
        orders.insertMany(
            listOf(
                Document("_id", paid).append("status", "PAID").append("paymentId", "pay-1"),
                Document("_id", ObjectId()).append("status", "PENDING").append("paymentId", null)
            )
        )
        val gateway = mockk<PaymentGateway> {
            every { paymentStatus("pay-1") } returns PaymentStatus.CAPTURED
        }

        db.godwit.runIsolated(orderPaymentStatus(gateway)).count("ordersUpdated") shouldBe 1L
        orders.find(eq("_id", paid)).first()["paymentStatus"] shouldBe "CAPTURED"
        verify(exactly = 1) { gateway.paymentStatus(any()) }
    }
})
