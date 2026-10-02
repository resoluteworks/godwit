package com.example.shop.services

import com.example.shop.domain.Order
import com.example.shop.domain.OrderLine
import com.example.shop.domain.OrderStatus
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Sorts.descending
import com.mongodb.client.model.Updates.combine
import com.mongodb.client.model.Updates.set
import com.mongodb.kotlin.client.ClientSession
import com.mongodb.kotlin.client.MongoCollection
import com.mongodb.kotlin.client.MongoDatabase
import org.bson.Document
import org.bson.types.ObjectId
import java.time.Instant
import java.util.Date

/** Reads and writes `orders`. Every write takes the caller's [ClientSession]. */
class OrderService(private val database: MongoDatabase) {
    private val orders: MongoCollection<Document>
        get() = database.getCollection("orders", Document::class.java)

    /** The customer's orders, newest first. */
    fun findByCustomer(customerId: ObjectId): List<Order> =
        orders.find(eq("customerId", customerId)).sort(descending("placedAt")).map(::orderOf).toList()

    fun place(session: ClientSession, order: Order) {
        orders.insertOne(session, documentOf(order))
    }

    fun markPaid(session: ClientSession, orderId: ObjectId, paymentId: String, paidAt: Instant) {
        orders.updateOne(
            session,
            eq("_id", orderId),
            combine(set("status", OrderStatus.PAID.name), set("paymentId", paymentId), set("paidAt", Date.from(paidAt)))
        )
    }

    fun cancel(session: ClientSession, orderId: ObjectId) {
        orders.updateOne(session, eq("_id", orderId), set("status", OrderStatus.CANCELLED.name))
    }
}

private fun documentOf(order: Order): Document = Document("_id", order.id)
    .append("customerId", order.customerId)
    .append("status", order.status.name)
    .append(
        "lines",
        order.lines.map { line ->
            Document("productId", line.productId)
                .append("quantity", line.quantity)
                .append("unitPriceMinor", line.unitPriceMinor)
        }
    )
    .append("totalMinor", order.totalMinor)
    .append("currency", order.currency)
    .append("placedAt", Date.from(order.placedAt))
    .append("paidAt", order.paidAt?.let(Date::from))
    .append("paymentId", order.paymentId)

private fun orderOf(document: Document): Order = Order(
    id = document.getObjectId("_id"),
    customerId = document.getObjectId("customerId"),
    status = OrderStatus.valueOf(document.getString("status")),
    lines = document.getList("lines", Document::class.java).orEmpty().map { line ->
        OrderLine(line.getObjectId("productId"), line.getInteger("quantity"), line.getLong("unitPriceMinor"))
    },
    totalMinor = document.getLong("totalMinor"),
    currency = document.getString("currency"),
    placedAt = document.getDate("placedAt").toInstant(),
    paidAt = document.getDate("paidAt")?.toInstant(),
    paymentId = document.getString("paymentId")
)
