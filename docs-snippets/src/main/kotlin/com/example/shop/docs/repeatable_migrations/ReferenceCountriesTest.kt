package com.example.shop.docs.repeatable_migrations

import godwit.test.runIsolated
import godwit.test.testGodwit
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bson.Document
import com.example.shop.migrations.referenceCountries as referenceCountriesOctober

class ReferenceCountriesTest : StringSpec({
    "reference-countries removes the countries the shop no longer ships to" {
        val db = testGodwit()
        db.database.getCollection("countries", Document::class.java)
            .insertOne(Document("_id", "XX").append("name", "Nowhere"))

        db.godwit.runIsolated(referenceCountriesOctober).count("countriesRemoved") shouldBe 1L
    }

    "a start at the same revision skips it without the lock, a new revision runs it again" {
        val db = testGodwit()
        db.godwit.migrate(referenceCountriesOctober).ran.map { it.id } shouldBe listOf("reference-countries")

        val sameRevision = db.godwit.migrate(referenceCountriesOctober)
        sameRevision.ran shouldBe emptyList()
        sameRevision.lockWait shouldBe null

        db.godwit.migrate(referenceCountries).ran.map { it.id } shouldBe listOf("reference-countries")
        db.database.getCollection("countries", Document::class.java).countDocuments() shouldBe 5L
    }
})
