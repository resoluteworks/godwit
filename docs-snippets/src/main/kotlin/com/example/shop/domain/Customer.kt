package com.example.shop.domain

import org.bson.types.ObjectId
import java.time.Instant

/** A shop customer. Stored in `customers`, unique by [email]. [externalUserId] links it to the identity provider. */
data class Customer(
    val id: ObjectId,
    val email: String,
    val name: String,
    val externalUserId: String?,
    val createdAt: Instant
)
