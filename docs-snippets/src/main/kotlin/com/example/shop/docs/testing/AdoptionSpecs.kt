package com.example.shop.docs.testing

// region: adoption-imports
import com.example.shop.migrations.appliedBeforeGodwit
import com.example.shop.migrations.carts
import com.example.shop.testing.migrationsFor
import godwit.core.GodwitConfig
import godwit.core.Origin
import godwit.core.OutOfOrder
import godwit.core.PlanConflictException
import godwit.core.Target
import godwit.core.UntrackedDatabase
import godwit.core.UntrackedDatabaseException
import godwit.test.TestGodwit
import godwit.test.testGodwit
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.bson.Document
import java.util.Date
// endregion

// region: adoption-spec
class AdoptionSpec : StringSpec({
    fun TestGodwit.seedSchemaLog(vararg versions: String) {
        database.getCollection("schema-log", Document::class.java)
            .insertMany(versions.map { version -> Document("version", version).append("appliedAt", Date()) })
    }

    "a database migrated by hand adopts what schema-log records and runs only what is left" {
        val db = testGodwit(GodwitConfig(adoptApplied = ::appliedBeforeGodwit))
        val migrations = migrationsFor(db)
        db.seedSchemaLog("001-initial-setup", "002-carts", "003-file-store")
        db.database.getCollection("orders", Document::class.java).insertOne(Document("paidAt", Date()))

        val report = db.godwit.migrate(migrations)

        report.recorded.map { it.id } shouldContainExactlyInAnyOrder
            listOf("001-initial-setup", "002-carts", "003-file-store")
        report.recorded.map { it.origin }.toSet() shouldBe setOf(Origin.ADOPTED)
        report.ran.map { it.id } shouldContainExactly listOf(
            "004-order-status",
            "005-customer-external-ids",
            "006-order-totals",
            "reference-countries",
            "bootstrap-customers"
        )
        db.godwit.status(migrations).isUpToDate shouldBe true
    }

    "an id that the list does not declare is ignored" {
        val db = testGodwit(GodwitConfig(adoptApplied = ::appliedBeforeGodwit))
        db.seedSchemaLog("001-initial-setup", "cleanup-temp-data")

        val report = db.godwit.migrate(migrationsFor(db), target = Target.Through("002-carts"))

        report.recorded.map { it.id } shouldContainExactly listOf("001-initial-setup")
        report.ran.map { it.id } shouldContainExactly listOf("002-carts")
        db.godwit.history().map { it.id } shouldContainExactlyInAnyOrder listOf("001-initial-setup", "002-carts")
    }

    "a gap in the adopted ids is refused, and the adopted records stay" {
        val db = testGodwit(GodwitConfig(adoptApplied = ::appliedBeforeGodwit))
        db.seedSchemaLog("001-initial-setup", "003-file-store")

        shouldThrow<PlanConflictException> { db.godwit.migrate(migrationsFor(db)) }

        db.godwit.history().map { it.origin }.toSet() shouldBe setOf(Origin.ADOPTED)
    }

    "a gap closed in the old record is adopted on the next start" {
        val db = testGodwit(GodwitConfig(adoptApplied = ::appliedBeforeGodwit))
        db.seedSchemaLog("001-initial-setup", "003-file-store")
        shouldThrow<PlanConflictException> { db.godwit.migrate(migrationsFor(db)) }

        db.seedSchemaLog("002-carts")
        val report = db.godwit.migrate(migrationsFor(db), target = Target.Through("003-file-store"))

        report.recorded.map { it.id } shouldContainExactly listOf("002-carts")
        report.ran.shouldBeEmpty()
    }

    "OutOfOrder.RUN runs the missing migration and records it as out of order" {
        val config = GodwitConfig(adoptApplied = ::appliedBeforeGodwit, outOfOrder = OutOfOrder.RUN)
        val db = testGodwit(config)
        db.seedSchemaLog("001-initial-setup", "003-file-store")

        val report = db.godwit.migrate(migrationsFor(db), target = Target.Through("003-file-store"))

        report["002-carts"].outOfOrder shouldBe true
    }

    "a new database runs everything while the hook stays configured" {
        val db = testGodwit(GodwitConfig(adoptApplied = ::appliedBeforeGodwit), atlasSearch = true)

        val report = db.godwit.migrate(migrationsFor(db))

        report.recorded.shouldBeEmpty()
        report.ran.map { it.id } shouldBe migrationsFor(db).map { it.id }
    }
})
// endregion

// region: untracked-spec
class UntrackedDatabaseSpec : StringSpec({
    "a database with data, no history and nothing adopted is refused" {
        val db = testGodwit()
        db.database.getCollection("orders", Document::class.java).insertOne(Document("status", "PAID"))

        val refused = shouldThrow<UntrackedDatabaseException> { db.godwit.migrate(listOf(carts)) }

        refused.collections shouldContainExactly listOf("orders")
        db.godwit.history().shouldBeEmpty()
    }

    "a collection that the app created before the first migrate makes a new database untracked" {
        val db = testGodwit()
        db.database.createCollection("sessions")

        shouldThrow<UntrackedDatabaseException> { db.godwit.migrate(listOf(carts)) }
            .collections shouldContainExactly listOf("sessions")
    }

    "RUN_ALL runs the migrations on a database that has data" {
        val db = testGodwit(GodwitConfig(untrackedDatabase = UntrackedDatabase.RUN_ALL))
        db.database.getCollection("orders", Document::class.java).insertOne(Document("status", "PAID"))

        db.godwit.migrate(listOf(carts)).ran.map { it.id } shouldContainExactly listOf("002-carts")
    }
})
// endregion
