package com.example.shop.migrations

import com.example.shop.SeedCustomer
import com.example.shop.services.CustomerService
import com.example.shop.services.IdentityProvider
import godwit.core.Migration
import godwit.core.everyStart

/**
 * Makes sure the configured seed customers exist, on every start: each environment's configuration lists the
 * accounts it needs. The identity provider calls run outside any transaction because the driver may run a
 * transaction body again; findOrCreateUser keeps them idempotent. The inserts are one transaction.
 */
fun bootstrapCustomers(seed: List<SeedCustomer>, customers: CustomerService, identity: IdentityProvider): Migration =
    everyStart("bootstrap-customers", description = "Seed customers from configuration")
        .outsideTransaction {
            seed.associate { customer -> customer.email to identity.findOrCreateUser(customer.email).id }
        }
        .inTransaction { externalIds ->
            val created = seed.count { customer ->
                customers.ensureCustomer(session, customer, externalIds.getValue(customer.email))
            }
            count("customersCreated", created)
        }
