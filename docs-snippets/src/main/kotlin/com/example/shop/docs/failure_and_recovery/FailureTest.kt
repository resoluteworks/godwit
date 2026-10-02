package com.example.shop.docs.failure_and_recovery

import com.example.shop.migrations.carts
import godwit.core.InvalidMigrationsException
import godwit.core.validateMigrations
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize

class MergeTest : StringSpec({
    "two branches that both add 007 fail validation, before any database is involved" {
        val e = shouldThrow<InvalidMigrationsException> {
            validateMigrations(listOf(carts, productSlugs, cartCurrencyClashing))
        }
        e.problems shouldHaveSize 1
    }
})
