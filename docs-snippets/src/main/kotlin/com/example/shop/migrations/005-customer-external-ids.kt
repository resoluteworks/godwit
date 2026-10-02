package com.example.shop.migrations

import com.example.shop.services.CustomerService
import com.example.shop.services.IdentityProvider
import godwit.core.Migration
import godwit.core.migration

/**
 * Links every customer created before the identity provider integration to an identity provider user.
 *
 * The HTTP calls run outside any transaction, one per customer, and are idempotent: findOrCreateUser matches by
 * email. Their results reach the transaction as the outside step's return value, and CustomerService writes the links
 * with the step's session, so they commit together with the history record.
 */
fun customerExternalIds(customers: CustomerService, identity: IdentityProvider): Migration =
    migration("005-customer-external-ids")
        .outsideTransaction {
            customers.withoutExternalUserId().associate { customer ->
                checkLock()
                customer.id to identity.findOrCreateUser(customer.email).id
            }
        }
        .inTransaction { externalIds ->
            externalIds.forEach { (customerId, externalUserId) ->
                customers.setExternalUserId(session, customerId, externalUserId)
            }
            count("customersLinked", externalIds.size)
        }
