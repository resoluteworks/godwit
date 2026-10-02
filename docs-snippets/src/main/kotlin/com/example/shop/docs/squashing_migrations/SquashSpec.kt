package com.example.shop.docs.squashing_migrations

// region: squash-spec
import com.example.shop.migrations.squashedIds
import com.example.shop.migrations.squashedMigrations
import com.example.shop.services.CustomerService
import com.example.shop.testing.FakeIdentityProvider
import com.example.shop.testing.migrationsFor
import com.example.shop.testing.testConfig
import com.mongodb.kotlin.client.MongoDatabase
import godwit.core.InvalidMigrationsException
import godwit.core.Migration
import godwit.core.Origin
import godwit.core.PlanConflictException
import godwit.core.Target
import godwit.test.TestGodwit
import godwit.test.testGodwit
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.bson.Document

private fun squashedFor(db: TestGodwit): List<Migration> =
    squashedMigrations(testConfig, CustomerService(db.database), FakeIdentityProvider())

/** Every collection of the application and its indexes, without the index format version. */
private fun schemaOf(db: TestGodwit): Map<String, Set<Document>> =
    db.database.listCollectionNames().toList()
        .filterNot { name -> name in db.godwit.bookkeepingCollections }
        .associateWith { name -> indexesOf(db.database, name) }

private fun indexesOf(database: MongoDatabase, collection: String): Set<Document> =
    database.getCollection(collection, Document::class.java).listIndexes<Document>().toList()
        .map { index -> index.apply { remove("v") } }
        .toSet()

class SquashSpec : StringSpec({
    "a fresh database and a migrated one end up with the same collections and indexes" {
        val migrated = testGodwit(atlasSearch = true)
        migrated.godwit.migrate(migrationsFor(migrated))
        migrated.godwit.migrate(squashedFor(migrated))

        val fresh = testGodwit(atlasSearch = true)
        fresh.godwit.migrate(squashedFor(fresh))

        schemaOf(fresh) shouldBe schemaOf(migrated)
        fresh.godwit.history().single { it.id == "100-baseline" }.origin shouldBe Origin.RAN
        migrated.godwit.history().single { it.id == "100-baseline" }.origin shouldBe Origin.SUPERSEDED
    }

    "a database that stopped at 004 is refused until the previous release finishes the job" {
        val db = testGodwit(atlasSearch = true)
        db.godwit.migrate(migrationsFor(db), target = Target.Through("004-order-status"))
        notYetApplied(db.godwit, squashedIds) shouldContainExactly
            listOf("005-customer-external-ids", "006-order-totals")

        shouldThrow<PlanConflictException> { db.godwit.migrate(squashedFor(db)) }.problems shouldHaveSize 1
        db.godwit.history().none { it.id == "100-baseline" } shouldBe true

        db.godwit.migrate(migrationsFor(db))
        notYetApplied(db.godwit, squashedIds) shouldBe emptyList()
        db.godwit.migrate(squashedFor(db))["100-baseline"].origin shouldBe Origin.SUPERSEDED
    }

    "a target that names a superseded id is invalid in the squash release" {
        val db = testGodwit()

        shouldThrow<InvalidMigrationsException> {
            db.godwit.migrate(squashedFor(db), target = Target.Through("004-order-status"))
        }
    }
})
// endregion
