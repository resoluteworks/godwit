package com.example.shop.domain

/** A country the shop ships to. Stored in `countries` with `_id` = [code] (ISO 3166-1 alpha-2). Reference data. */
data class Country(
    val code: String,
    val name: String
)
