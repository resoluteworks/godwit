package com.example.shop.domain

import org.bson.types.ObjectId

/** A product in the catalogue. Stored in `products`, unique by [sku]. */
data class Product(
    val id: ObjectId,
    val sku: String,
    val name: String,
    val description: String,
    val priceMinor: Long,
    val currency: String
)
