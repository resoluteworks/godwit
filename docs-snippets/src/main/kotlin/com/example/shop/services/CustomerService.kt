package com.example.shop.services

import com.example.shop.SeedCustomer
import com.example.shop.domain.Customer
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates.combine
import com.mongodb.client.model.Updates.set
import com.mongodb.client.model.Updates.setOnInsert
import com.mongodb.kotlin.client.ClientSession
import com.mongodb.kotlin.client.MongoCollection
import com.mongodb.kotlin.client.MongoDatabase
import org.bson.Document
import org.bson.types.ObjectId
import java.time.Instant
import java.util.Date

/**
 * Reads and writes `customers`. Every write takes the caller's [ClientSession], so a migration's transactional step
 * (and any other transaction) can call it inside its transaction.
 */
class CustomerService(private val database: MongoDatabase) {
    private val customers: MongoCollection<Document>
        get() = database.getCollection("customers", Document::class.java)

    fun findByEmail(email: String): Customer? = customers.find(eq("email", email)).firstOrNull()?.let(::customerOf)

    /** Customers not linked to an identity provider user yet. */
    fun withoutExternalUserId(): List<Customer> =
        customers.find(exists("externalUserId", false)).map(::customerOf).toList()

    fun setExternalUserId(session: ClientSession, customerId: ObjectId, externalUserId: String) {
        customers.updateOne(session, eq("_id", customerId), set("externalUserId", externalUserId))
    }

    /** Inserts [seed] unless a customer with its email exists. Returns true when it inserted one. */
    fun ensureCustomer(session: ClientSession, seed: SeedCustomer, externalUserId: String): Boolean {
        val result = customers.updateOne(
            session,
            eq("email", seed.email),
            combine(
                setOnInsert("name", seed.name),
                setOnInsert("externalUserId", externalUserId),
                setOnInsert("createdAt", Date())
            ),
            UpdateOptions().upsert(true)
        )
        return result.upsertedId != null
    }
}

private fun customerOf(document: Document): Customer = Customer(
    id = document.getObjectId("_id"),
    email = document.getString("email"),
    name = document.getString("name"),
    externalUserId = document.getString("externalUserId"),
    createdAt = document.getDate("createdAt")?.toInstant() ?: Instant.EPOCH
)
