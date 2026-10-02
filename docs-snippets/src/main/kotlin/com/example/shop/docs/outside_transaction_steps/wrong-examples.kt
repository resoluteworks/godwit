package com.example.shop.docs.outside_transaction_steps

// Examples of what not to do. Each compiles, and each is wrong on a retry; the doc explains why.

import com.example.shop.services.EmailSender
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.Updates.inc
import godwit.core.Migration
import godwit.core.migration
import org.bson.Document

private val rawDdl = migration("raw-ddl")
    .outsideTransaction {
        database.createCollection("carts")
        collection("products").createSearchIndex("product-search", Document("mappings", Document("dynamic", true)))
        collection("orders").dropIndex("status_1")
    }

private val helperDdl = migration("helper-ddl")
    .outsideTransaction {
        ensureCollection("carts")
        ensureSearchIndex("products", name = "product-search", definition = Document("mappings", Document("dynamic", true)))
        dropIndexIfExists("orders", "status_1")
    }

/** Wrong: a retry raises every price again. */
val productPriceRiseOutside = migration("014-product-price-rise")
    .outsideTransaction {
        collection("products").updateMany(Filters.empty(), inc("priceMinor", 100L))
    }

/** Wrong: a retry sends every email again. */
fun welcomeEmails(email: EmailSender): Migration =
    migration("016-welcome-emails")
        .outsideTransaction {
            collection("customers").find(exists("welcomedAt", false)).forEach { customer ->
                email.send(customer.getString("email"), "Welcome to the shop", "Your account is ready.")
            }
        }
