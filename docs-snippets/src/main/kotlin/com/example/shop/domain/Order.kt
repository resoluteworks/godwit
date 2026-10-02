package com.example.shop.domain

import org.bson.types.ObjectId
import java.time.Instant

enum class OrderStatus { PENDING, PAID, SHIPPED, CANCELLED }

/** One product line of an order. Prices are in minor units (pence, cents). */
data class OrderLine(
    val productId: ObjectId,
    val quantity: Int,
    val unitPriceMinor: Long
)

/** An order. Stored in `orders`. [totalMinor] is the sum of the lines; [paymentId] is the payment gateway's id. */
data class Order(
    val id: ObjectId,
    val customerId: ObjectId,
    val status: OrderStatus,
    val lines: List<OrderLine>,
    val totalMinor: Long,
    val currency: String,
    val placedAt: Instant,
    val paidAt: Instant?,
    val paymentId: String?
)
