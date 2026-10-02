package com.example.shop.testing

import com.example.shop.BootstrapSettings
import com.example.shop.IdentitySettings
import com.example.shop.MongoSettings
import com.example.shop.SeedCustomer
import com.example.shop.ShopConfig
import com.example.shop.migrations.shopMigrations
import com.example.shop.services.CustomerService
import com.example.shop.services.ExternalUser
import com.example.shop.services.IdentityProvider
import godwit.core.Migration
import godwit.test.TestGodwit

/** A configuration for tests: no waiting for the search index, one seed customer. */
val testConfig = ShopConfig(
    mongo = MongoSettings(uri = "unused", database = "unused", searchIndexWait = null),
    identity = IdentitySettings(baseUrl = "unused", apiKey = "unused"),
    bootstrap = BootstrapSettings(customers = listOf(SeedCustomer(email = "staff@example.com", name = "Staff")))
)

/** Answers without a network call, and gives the same answer for the same email, like the real provider. */
class FakeIdentityProvider : IdentityProvider {
    override fun findOrCreateUser(email: String) = ExternalUser(id = "user-$email", email = email)
}

/**
 * The shop's migrations wired to the services of a test database. The services use the client that godwit uses,
 * so the session godwit opens for a transactional step is valid in them.
 */
fun migrationsFor(db: TestGodwit): List<Migration> =
    shopMigrations(testConfig, CustomerService(db.database), FakeIdentityProvider())
