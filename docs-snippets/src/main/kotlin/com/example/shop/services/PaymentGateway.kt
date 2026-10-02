package com.example.shop.services

enum class PaymentStatus { AUTHORISED, CAPTURED, REFUNDED, FAILED }

/** The external payment gateway. Every call is an HTTP request: never inside a transaction. */
interface PaymentGateway {
    /** The current status of payment [paymentId] at the gateway. Read-only, so safe to repeat. */
    fun paymentStatus(paymentId: String): PaymentStatus
}
