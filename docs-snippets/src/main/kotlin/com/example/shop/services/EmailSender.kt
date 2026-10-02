package com.example.shop.services

/**
 * Sends email through the external email service. Not idempotent: a repeated call sends the email again, so it
 * belongs neither in a transaction (the driver may repeat the body) nor in an outside step (a retry repeats it).
 */
interface EmailSender {
    fun send(to: String, subject: String, body: String)
}
