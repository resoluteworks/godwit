package com.example.shop.docs.transactions_and_sessions

import com.example.shop.migrations.orderPaymentStatus
import com.example.shop.services.PaymentGateway
import com.example.shop.services.PaymentStatus
import godwit.core.MigrationFailedException
import godwit.test.SessionEscapeError
import godwit.test.runIsolated
import godwit.test.testGodwit
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.bson.Document

private class FakePaymentGateway(private val status: PaymentStatus) : PaymentGateway {
    override fun paymentStatus(paymentId: String) = status
}

class OrderPaymentStatusTest : StringSpec({
    "009 stores the gateway status of every paid order" {
        val db = testGodwit()
        val orders = db.database.getCollection("orders", Document::class.java)
        orders.insertOne(Document("paymentId", "pay-1"))

        val outcome = db.godwit.runIsolated(orderPaymentStatus(FakePaymentGateway(PaymentStatus.CAPTURED)))

        outcome.count("ordersUpdated") shouldBe 1L
        orders.find().first()["paymentStatus"] shouldBe "CAPTURED"
    }

    "a step that forgets the session fails the test" {
        val db = testGodwit()
        db.database.getCollection("orders", Document::class.java).insertOne(Document("paymentId", "pay-1"))

        val failure = shouldThrow<MigrationFailedException> {
            db.godwit.runIsolated(orderPaymentStatusEscaping(FakePaymentGateway(PaymentStatus.CAPTURED)))
        }
        failure.cause.shouldBeInstanceOf<SessionEscapeError>()
    }
})
