package com.example.shop

import com.example.shop.services.OrderService
import com.example.shop.services.PaymentGateway

/** Signs in to the payment gateway: slow, and it throws while the gateway is down. */
fun connectPaymentGateway(): PaymentGateway = TODO("the gateway's client library")

/** The background worker's job: the rest of the worker process. */
fun processPaidOrders(orders: OrderService): Unit = TODO("the worker's loop")
