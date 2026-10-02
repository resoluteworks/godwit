package com.example.shop.services

/** A user account at the external identity provider. */
data class ExternalUser(
    val id: String,
    val email: String
)

/** The external identity provider customers sign in with. Every call is an HTTP request: never inside a transaction. */
interface IdentityProvider {
    /** The user with [email], created when absent. Idempotent: the same email always returns the same user. */
    fun findOrCreateUser(email: String): ExternalUser
}

/** [IdentityProvider] over the provider's HTTP API. */
class HttpIdentityProvider(
    private val baseUrl: String,
    private val apiKey: String
) : IdentityProvider {
    override fun findOrCreateUser(email: String): ExternalUser = TODO("POST $baseUrl/users with the API key")
}
