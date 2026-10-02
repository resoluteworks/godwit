package com.example.shop.docs.outside_transaction_steps

import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.Updates.set
import godwit.core.migration

/**
 * Marks every customer who never got a welcome email. The shop's mailer sends the emails and clears the flag; the
 * migration only changes data, so it commits once with its history record.
 */
val welcomeEmailsDue = migration("016-welcome-emails-due")
    .inTransaction {
        val result = collection("customers").updateMany(session, exists("welcomedAt", false), set("welcomeEmailDue", true))
        count("customersFlagged", result.modifiedCount)
    }
