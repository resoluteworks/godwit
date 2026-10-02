package com.example.shop

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The shop's configuration. Migrations take only the parts they need, never the whole config. */
data class ShopConfig(
    val mongo: MongoSettings,
    val identity: IdentitySettings,
    val bootstrap: BootstrapSettings
)

data class MongoSettings(
    val uri: String,
    val database: String,
    /** How long 001-initial-setup waits for the product search index to become queryable. Null: do not wait. */
    val searchIndexWait: Duration?
)

data class IdentitySettings(
    val baseUrl: String,
    val apiKey: String
)

/** Data every environment must contain, such as staff and demo accounts. */
data class BootstrapSettings(
    val customers: List<SeedCustomer>
)

data class SeedCustomer(
    val email: String,
    val name: String
)

/** Reads the configuration from environment variables. */
fun loadShopConfig(env: Map<String, String> = System.getenv()): ShopConfig = ShopConfig(
    mongo = MongoSettings(
        uri = env.getValue("MONGO_URI"),
        database = env["MONGO_DATABASE"] ?: "shop",
        searchIndexWait = env["SEARCH_INDEX_WAIT_SECONDS"]?.toLong()?.seconds
    ),
    identity = IdentitySettings(
        baseUrl = env.getValue("IDENTITY_URL"),
        apiKey = env.getValue("IDENTITY_API_KEY")
    ),
    bootstrap = BootstrapSettings(
        customers = env["SEED_CUSTOMERS"].orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { email -> SeedCustomer(email = email, name = email.substringBefore('@')) }
    )
)
