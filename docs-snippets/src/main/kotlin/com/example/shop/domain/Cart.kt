package com.example.shop.domain

import org.bson.types.ObjectId
import java.time.Instant

data class CartItem(
    val productId: ObjectId,
    val quantity: Int
)

/** A customer's cart. Stored in `carts`, one per customer; the server removes it 30 days after [updatedAt]. */
data class Cart(
    val id: ObjectId,
    val customerId: ObjectId,
    val items: List<CartItem>,
    val updatedAt: Instant
)
