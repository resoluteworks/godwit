# Testing migrations

`godwit-test` runs your migrations through the real runner: the same lock, history, transactions and session handling as production, against a real MongoDB. A test needs no mocks of the driver, no cleanup and no special entry point into your migrations. The kit has seven parts: `testGodwit()` for a fresh database per test, `Target.Before` and `Target.Through` (in `godwit-core`) to stop mid-history, `rerun` to probe idempotency, `runIsolated` to run one migration alone, `forget` to run one again, `shouldHaveApplied` to assert on history, and `SessionEscapeDetector` to catch a forgotten `session`. `validateMigrations` needs no database at all.

## Setup

`godwit-test` brings `godwit-core` and Testcontainers. It does not bring a test framework: its helpers throw `AssertionError`, so they work with any. The examples use Kotest `StringSpec` and MockK, with the versions the docs are compiled against. Docker must be available where the tests run. `godwitVersion` is set in `gradle.properties`, as in the [README](../README.md#add-the-dependencies).

```kts
val godwitVersion: String by project

dependencies {
    testImplementation("works.resolute:godwit-test:$godwitVersion")
    testImplementation("io.kotest:kotest-runner-junit5:6.2.5")
    testImplementation("io.kotest:kotest-assertions-core:6.2.5")
    testImplementation("io.mockk:mockk:1.14.9")
}

tasks.test {
    useJUnitPlatform()
}
```

### What `testGodwit()` gives you

`testGodwit(config = GodwitConfig(), atlasSearch = false)` returns a `TestGodwit`:

| Member | What it is |
|---|---|
| `client` | A `MongoClient` on a MongoDB container that the whole test JVM shares. A `SessionEscapeDetector` is installed on it. |
| `databaseName` | A new database name, a random UUID, different on every call. |
| `database` | `client.getDatabase(databaseName)`. |
| `godwit` | `Godwit(client, databaseName, config)`, ready to migrate. |

- The container is a single-node replica set, so transactions work. It starts on the first call and stops when the JVM exits.
- Every call returns an empty database, so tests never see each other's data, may run in parallel and need no cleanup.
- `atlasSearch = true` starts the Atlas local image instead, which also serves Atlas Search indexes. It starts more slowly, and each image starts at most once per JVM. Any test that runs `001-initial-setup` needs it, because that migration creates a search index.
- `config` is the `GodwitConfig` under test: pass `adoptApplied`, `outOfOrder` and the rest here (see [configuration.md](configuration.md)).

### Fixtures

A test configuration, a fake for the external service, and a function that builds the list from the services of one test database. The shop keeps them in `src/test/kotlin/com/example/shop/testing/TestFixtures.kt`:

```kotlin
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
```

The services are built from `db.database`, so they use the client that godwit uses. The session godwit opens for a transactional step is valid only with that client. Building the services on a second client fails the first transactional step that passes the session (see "A service on another client").

The specs from here to the adoption section share one set of imports:

```kotlin
import com.example.shop.migrations.orderTotals
import com.example.shop.migrations.shopMigrations
import com.example.shop.services.CustomerService
import com.example.shop.testing.FakeIdentityProvider
import com.example.shop.testing.migrationsFor
import com.example.shop.testing.testConfig
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.exists
import godwit.core.InvalidMigrationsException
import godwit.core.PlanConflictException
import godwit.core.Target
import godwit.core.migration
import godwit.core.validateMigrations
import godwit.test.forget
import godwit.test.rerun
import godwit.test.runIsolated
import godwit.test.shouldHaveApplied
import godwit.test.testGodwit
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.bson.Document
import java.util.Date
```

## The list is valid, without a database

`validateMigrations` is pure: it checks ids, duplicates, numeric order, the placement of repeatables and batch sizes, and reports every problem at once. `migrate`, `status` and `requireUpToDate` run it before any I/O; a unit test runs it in milliseconds, before a container exists.

The list is a function of the services the migrations need, so the test passes stand-ins that are never called. `mockk()` is enough for a service the migrations only store.

```kotlin
class MigrationListSpec : StringSpec({
    "the migration list is valid" {
        validateMigrations(shopMigrations(testConfig, mockk(), FakeIdentityProvider()))
    }

    "two branches that both add 007 are caught without a database" {
        val merged = listOf(
            migration("007-product-slugs").inTransaction { },
            migration("007-cart-currency").inTransaction { }
        )

        shouldThrow<InvalidMigrationsException> { validateMigrations(merged) }.problems shouldHaveSize 1
    }
})
```

The second case is the merge conflict that the numbering rule catches: two branches that both took the next number. See [ordering-and-validation.md](ordering-and-validation.md).

## The whole list, on an empty database, twice

The first test to write. It applies every migration to an empty database and starts the application a second time:

```kotlin
class EmptyDatabaseSpec : StringSpec({
    "every migration applies to an empty database, and the next start runs only the every-start one" {
        val db = testGodwit(atlasSearch = true)
        val migrations = migrationsFor(db)

        db.godwit.migrate(migrations).ran.map { it.id } shouldBe migrations.map { it.id }
        db.godwit.migrate(migrations).ran.map { it.id } shouldBe listOf("bootstrap-customers")
        db.godwit.status(migrations).isUpToDate shouldBe true
    }
})
```

- The first run applies every migration; `ran` equals the list.
- The second run runs only the every-start migration, so a repeatable at the same revision and every once-only migration are skipped.
- `status(migrations).isUpToDate` is true when nothing is pending and nothing conflicts.

This test fails when a migration throws on an empty database, when the second start repeats work it should skip, and when a start leaves the database not up to date.

## A migration in the middle of history

Real data has a shape that earlier migrations produced. To test a backfill, stop just before it, insert data in the old shape, then run it through the real runner:

- `Target.Before(id)` runs every once-only migration listed before `id` and nothing from `id` on. Repeatable and every-start migrations do not run.
- `Target.Through(id)` runs the same set plus `id` itself.
- `id` must be a once-only id in the list, or `InvalidMigrationsException` is thrown.

```kotlin
class MidHistorySpec : StringSpec({
    "004 marks orders with paidAt PAID and keeps the date, and a second run changes nothing" {
        val db = testGodwit(atlasSearch = true)
        val migrations = migrationsFor(db)
        db.godwit.migrate(migrations, target = Target.Before("004-order-status"))

        val orders = db.database.getCollection("orders", Document::class.java)
        orders.insertMany(listOf(Document("_id", "paid").append("paidAt", Date(0)), Document("_id", "open")))

        val outcome = db.godwit.migrate(migrations, target = Target.Through("004-order-status"))["004-order-status"]

        outcome.count("ordersPaid") shouldBe 1L
        outcome.count("ordersPending") shouldBe 1L
        orders.find(eq("_id", "paid")).first().getDate("paidAt") shouldBe Date(0)
        orders.find(eq("_id", "open")).first()["status"] shouldBe "PENDING"
        db.godwit shouldHaveApplied "004-order-status"
        db.godwit.rerun(migrations, "004-order-status").count("ordersPaid") shouldBe 0L
    }

    "005 links the customers that have no identity provider user, and a second run links nobody" {
        val db = testGodwit(atlasSearch = true)
        val migrations = migrationsFor(db)
        db.godwit.migrate(migrations, target = Target.Before("005-customer-external-ids"))

        val customers = db.database.getCollection("customers", Document::class.java)
        customers.insertMany(
            listOf(
                Document("email", "ann@example.com").append("name", "Ann"),
                Document("email", "bob@example.com").append("name", "Bob").append("externalUserId", "user-bob")
            )
        )

        val outcome = db.godwit.migrate(migrations, target = Target.Through("005-customer-external-ids"))
            .get("005-customer-external-ids")

        outcome.count("customersLinked") shouldBe 1L
        CustomerService(db.database).findByEmail("ann@example.com")?.externalUserId shouldBe "user-ann@example.com"
        db.godwit.rerun(migrations, "005-customer-external-ids").count("customersLinked") shouldBe 0L
    }
})
```

The pattern is `Before`, insert, `Through`, assert. Insert after the schema migrations have run, so that indexes exist and do not reject the data (see "Seeding data before the schema exists").

- **004** inserts one order with `paidAt` and one without, and checks the status each one gets.
- **005** has an outside step, so the test uses the fake identity provider and checks that the typed value reached the transaction: `ann@example.com` has `user-ann@example.com` in `externalUserId`, and the customer that already had an id is untouched.

### `rerun`: the idempotency probe

`rerun(migrations, id)` forgets `id`, runs it again through the real runner and returns its outcome. Both specs above end with it. A migration is safe to run twice when the second outcome's counts are 0:

```kotlin
db.godwit.rerun(migrations, "004-order-status").count("ordersPaid") shouldBe 0L
```

A once-only `id` runs with `Target.Through(id)` and out-of-order allowed, so migrations applied after it do not block it. A repeatable or every-start `id` runs with `Target.Latest`. The outside step runs again too, which is how a test shows that it is idempotent.

Like `runIsolated`, `rerun` turns the untracked-database guard and adoption off, so it works on a database whose only history document is the one it forgets. `Target.Through(id)` also runs the migrations listed before `id` that are not applied; after `runIsolated(migration)`, pass a list that holds only that migration: `rerun(listOf(orderTotals), "006-order-totals")`.

## One migration on its own

`runIsolated(migration)` runs a single migration through the real runner, without its predecessors. The untracked-database guard and adoption are off for that call, and other history ids are ignored. Use it for a migration that does not need the schema of earlier ones, and for tests that should not pay for a search index.

```kotlin
class IsolatedMigrationSpec : StringSpec({
    "006 totals every order in pages of 500, on its own" {
        val db = testGodwit()
        val orders = db.database.getCollection("orders", Document::class.java)
        orders.insertMany(
            List(1203) { Document("lines", listOf(Document("quantity", 2).append("unitPriceMinor", 1250L))) }
        )

        val outcome = db.godwit.runIsolated(orderTotals)

        outcome.count("ordersUpdated") shouldBe 1203L
        outcome.batches shouldBe 3
        orders.countDocuments(exists("totalMinor", false)) shouldBe 0L
        db.godwit shouldHaveApplied "006-order-totals"
    }
})
```

`006-order-totals` has 1,203 orders to process in pages of 500, so the outcome shows three batches: `batches` counts the committed pages, `count("ordersUpdated")` the total. The run is recorded in history like any other.

## `forget`: run a migration again

`forget(id)` deletes the history document of `id`, so the next `migrate` runs it again. It exists only in `godwit-test`: production code has no way to un-apply a migration.

```kotlin
class ForgetSpec : StringSpec({
    "reference-countries removes a country that is not in the list when it runs again" {
        val db = testGodwit(atlasSearch = true)
        val migrations = migrationsFor(db)
        db.godwit.migrate(migrations)
        val countries = db.database.getCollection("countries", Document::class.java)
        countries.insertOne(Document("_id", "XX").append("name", "Nowhere"))

        db.godwit.forget("reference-countries")
        val outcome = db.godwit.migrate(migrations)["reference-countries"]

        outcome.count("countriesRemoved") shouldBe 1L
        countries.find(eq("_id", "XX")).firstOrNull() shouldBe null
        countries.countDocuments() shouldBe 4L
    }

    "a forgotten migration in the middle of the list is out of order for a plain migrate" {
        val db = testGodwit(atlasSearch = true)
        val migrations = migrationsFor(db)
        db.godwit.migrate(migrations)

        db.godwit.forget("005-customer-external-ids")

        shouldThrow<PlanConflictException> { db.godwit.migrate(migrations) }
        db.godwit.rerun(migrations, "005-customer-external-ids").count("customersLinked") shouldBe 0L
    }
})
```

- A repeatable or the last once-only migration can be forgotten and run by a plain `migrate`.
- A once-only migration in the middle of the list cannot. Forgotten, it is pending before applied once-only migrations, which is out of order, and the default policy refuses. Use `rerun`, which allows it.

## `shouldHaveApplied`

`db.godwit shouldHaveApplied "004-order-status"` throws `AssertionError` unless history records the id as `APPLIED`. It reads history only; use `outcome.count(...)` for effects.

## Adoption and the untracked guard

A database migrated by another tool or by hand is tested by seeding the old record and running the real adoption. The hook is `appliedBeforeGodwit` from [adopting-an-existing-database.md](adopting-an-existing-database.md).

The adoption specs share these imports:

```kotlin
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
```

```kotlin
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
```

- The first case is the normal rollout: three ids adopted, the rest run, the database up to date. It seeds a shorter `schema-log` than the walkthrough in [adopting-an-existing-database.md](adopting-an-existing-database.md), which adopts `001` to `004`, so that `004` runs on the order the test inserts.
- The second shows an id that the list does not declare being ignored.
- The third, fourth and fifth are the gap. The third pins that it is refused by default and that the adopted records stay. The fourth closes the gap in `schema-log`: history still holds only `ADOPTED` documents, so the next start calls the hook again and records `002-carts`, and nothing runs. The fifth runs the missing migration under `OutOfOrder.RUN`.
- The last shows that a new database runs everything while the hook is still configured.

The guard has its own cases:

```kotlin
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
```

## A squash

A squash is tested on two databases: one built by the previous release and then the squash release, one built by the squash release alone. The full equivalence test is in [squashing-migrations.md](squashing-migrations.md). The smallest useful case shows that a migrated database records the baseline without running it:

```kotlin
import com.example.shop.migrations.squashedMigrations
import com.example.shop.services.CustomerService
import com.example.shop.testing.FakeIdentityProvider
import com.example.shop.testing.migrationsFor
import com.example.shop.testing.testConfig
import godwit.core.Origin
import godwit.test.testGodwit
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class SquashRecordedSpec : StringSpec({
    "a database migrated before the squash records the baseline without running it" {
        val db = testGodwit(atlasSearch = true)
        db.godwit.migrate(migrationsFor(db))
        val squashed = squashedMigrations(testConfig, CustomerService(db.database), FakeIdentityProvider())

        val report = db.godwit.migrate(squashed)

        report["100-baseline"].origin shouldBe Origin.SUPERSEDED
        report.ran.map { it.id } shouldContainExactly listOf("bootstrap-customers")
    }
})
```

## `SessionEscapeDetector`

Inside `inTransaction` and `inBatches`, every driver call and every service call must receive `session`. Forgetting it compiles and runs, but the call happens outside the transaction: it is not rolled back, and it can wait for the transaction's own locks. The detector turns that into a test failure.

`014-product-price-rise` from [outside-transaction steps](outside-transaction-steps.md#large-server-side-updates), written without its `session`:

```kotlin
// compiles, and the detector fails it: the update is missing `session`
val productPriceRiseWithoutSession = migration("014-product-price-rise")
    .inTransaction {
        collection("products").updateMany(Filters.empty(), inc("priceMinor", 100L))
    }
```

With the session passed, the detector has nothing to report:

```kotlin
/** Every price goes up by 1.00. `$inc` is not idempotent, so the update commits with the history record. */
val productPriceRise = migration("014-product-price-rise")
    .inTransaction {
        val result = collection("products").updateMany(session, Filters.empty(), inc("priceMinor", 100L))
        count("productsRepriced", result.modifiedCount)
    }
```

How it works. The sync driver calls command listeners on the thread that runs the command. godwit opens the transaction with its own command before the step body runs, and the detector records that command's session id (`lsid`). Until a commit or abort of that session ends the transaction, every command on that thread must carry the same `lsid` and `autocommit: false`, which the driver adds to commands sent with the session. A command without a session, or with another session (a service that starts a session and a transaction of its own), makes the detector throw `SessionEscapeError` (an `AssertionError`) before the command is sent. The commit of a service's own transaction does not end the tracked one.

```text
update on products ran without the step's session, outside the transaction. Pass `session` to the driver call or the service method.
```

The step fails and the test fails with that message, naming the command and the collection.

What it does not cover:

- Commands on other threads. A step that fans out to a thread pool is not checked.
- Outside steps. They have no session and no transaction.
- Paths the tests do not run. A migration branch that no test reaches is not checked.

`testGodwit()` installs the detector. A test that builds its own client installs it too:

```kotlin
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.kotlin.client.MongoClient
import godwit.test.SessionEscapeDetector

/**
 * A client for a cluster that the tests manage themselves. The detector fails any transactional step that runs a
 * command without its session.
 */
fun clientWithEscapeDetector(uri: String): MongoClient = MongoClient.create(
    MongoClientSettings.builder()
        .applyConnectionString(ConnectionString(uri))
        .addCommandListener(SessionEscapeDetector())
        .build()
)
```

The client can then back a `Godwit` for a cluster that the tests manage themselves, for example in an environment without Docker:

```kotlin
import com.example.shop.migrations.shopMigrations
import com.example.shop.services.CustomerService
import com.example.shop.testing.FakeIdentityProvider
import com.example.shop.testing.testConfig
import godwit.core.Godwit
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class OwnClusterSpec : StringSpec({
    "the list applies to a database on our own cluster" {
        val client = clientWithEscapeDetector(System.getenv("TEST_MONGO_URI"))
        val databaseName = "shop-test-${UUID.randomUUID()}"
        try {
            val customers = CustomerService(client.getDatabase(databaseName))
            val migrations = shopMigrations(testConfig, customers, FakeIdentityProvider())

            Godwit(client, databaseName).migrate(migrations).ran.map { it.id } shouldBe migrations.map { it.id }
        } finally {
            client.getDatabase(databaseName).drop()
            client.close()
        }
    }
})
```

## Every migration has a test

A convention test turns "every migration is tested" into a failing build. It does not prove a test is good; it makes adding a migration without touching the tests impossible.

```kotlin
import com.example.shop.migrations.shopMigrations
import com.example.shop.testing.FakeIdentityProvider
import com.example.shop.testing.testConfig
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk

/** Applied by the whole-list spec, which is all a plain DDL migration needs. */
private val coveredByTheWholeListRun = setOf("001-initial-setup", "002-carts", "003-file-store", "bootstrap-customers")

/** Each of these has a case of its own. Adding a migration to the list fails the spec below until it is named here. */
private val coveredByOwnCase = setOf(
    "004-order-status",
    "005-customer-external-ids",
    "006-order-totals",
    "reference-countries"
)

class EveryMigrationHasATestSpec : StringSpec({
    "every migration in the list is covered by a test" {
        val ids = shopMigrations(testConfig, mockk(), FakeIdentityProvider()).map { it.id }.toSet()

        coveredByTheWholeListRun + coveredByOwnCase shouldBe ids
    }

    "no migration is claimed by both groups" {
        (coveredByTheWholeListRun intersect coveredByOwnCase) shouldBe emptySet()
    }
})
```

Adding `007-product-slugs` to the list fails the first case with the set difference. Naming it in one of the two sets, and writing the case, makes it pass.

## Edge cases

Each case gives the state, what godwit does, and what you do.

### Docker is not available

- **State:** the machine running the tests has no Docker daemon.
- **godwit:** the first `testGodwit()` fails with the Testcontainers error, and so does every test that calls it. There is no fallback to a local `mongod`.
- **You:** provide Docker in CI. Where that is impossible, point the tests at your own cluster with `clientWithEscapeDetector` and build `Godwit` yourself, as in `OwnClusterSpec`. That cluster must be a replica set, and your tests must clean up the databases they create.

### A service on another client

- **State:** a test builds `CustomerService` on a second `MongoClient`, then runs `005-customer-external-ids`.
- **godwit:** the transactional step passes godwit's session to the service. The driver throws `IllegalStateException` whose message contains `ClientSession from same MongoClient`. godwit records `005` as `FAILED` and throws `MigrationFailedException` with the guidance line "The step passed godwit's session to an operation on another MongoClient. Build Godwit and the services the migrations call from the same MongoClient."
- **You:** build the services from `db.database`, as `migrationsFor` does.

### Atlas Search is needed and the plain container is running

- **State:** `testGodwit()` without `atlasSearch`, and the list runs `001-initial-setup`.
- **godwit:** `ensureSearchIndex` fails on a deployment that does not serve Atlas Search. `001` is recorded `FAILED` and `MigrationFailedException` is thrown.
- **You:** use `testGodwit(atlasSearch = true)` for tests that run `001`, and `runIsolated` or `Target.Through` past it for tests that do not need the index. Setting `searchIndexWait` to null skips waiting for the index, not creating it.

### Seeding data before the schema exists

- **State:** a test inserts two customers with the same email, then runs `Target.Before("004-order-status")`.
- **godwit:** `001-initial-setup` builds the unique index on `email` over the existing documents and fails with a duplicate key error. `001` is `FAILED`.
- **You:** run `Target.Before(...)` first and insert afterwards. Seed data in the old shape only after the schema migrations before it have run.

### `forget` on a migration in the middle of the list

- **State:** the whole list is applied. The test calls `forget("005-customer-external-ids")` and then `migrate(migrations)`.
- **godwit:** `005` is pending and listed before the applied `006`, which is out of order. Under the default `OutOfOrder.FAIL`, `PlanConflictException`.
- **You:** call `rerun(migrations, "005-customer-external-ids")`, which runs it with out-of-order allowed. The `ForgetSpec` above shows both.

### A target that is not a once-only id

- **State:** `migrate(migrations, target = Target.Before("reference-countries"))`.
- **godwit:** `InvalidMigrationsException`: a target must be a once-only id in the list. Repeatable and every-start migrations never run under a target.
- **You:** run `Target.Latest` and assert on `report["reference-countries"]`, or use `rerun`.

### `rerun` finds a migration that is not idempotent

- **State:** a backfill has no filter: `updateMany(Filters.empty(), Updates.inc("revision", 1))`. The test calls `rerun`.
- **godwit:** the second run is an ordinary run, and it changes every order again. The outcome's count is the number of all orders, not 0, and the assertion fails.
- **You:** change the filter to select only what still needs the change (`exists("revision", false)` with a `set`). The failing `rerun` is the signal this probe exists to give.

### A test that asserts nothing

- **State:** `runIsolated(customerExternalIds(customers, FakeIdentityProvider()))` on a database where the test inserted no customers.
- **godwit:** `005` finds no customer without an `externalUserId`, links none, and applies. `count("customersLinked")` is 0, and an assertion on "no exception" passes.
- **You:** insert the data the migration acts on and assert a non-zero count, as the `005` case above does. A migration test that never gives the migration anything to do proves only that it compiles.

### A step that fans out to other threads

- **State:** an `inTransaction` step uses a parallel stream and one of its threads calls `collection("orders").updateOne(filter, update)` without a session.
- **godwit:** the detector checks only the thread that runs the step, so the call is not caught in a test. In production it runs outside the transaction.
- **You:** do the work on the step's thread, and pass `session` to every call. Do not parallelise inside a transaction: a `ClientSession` is not thread-safe either.

### A client without the detector

- **State:** a spec builds its own client with `MongoClient.create(uri)` and does not call `testGodwit()`.
- **godwit:** nothing watches the commands. A forgotten `session` passes the test and fails, or silently escapes, in production.
- **You:** build the client with `clientWithEscapeDetector`.

### A test that mocks the driver

- **State:** a test replaces `MongoCollection` and `ClientSession` with mocks to run a migration body.
- **godwit:** is not involved. The body runs against mocks that accept every call, so the test cannot show that the update ran inside the transaction, that a unique index rejects the data, or that the history record committed with the effect.
- **You:** run the migration through the runner against the container. Use `mockk()` only for services that the list needs and the test never calls.

### A database shared between tests

- **State:** a spec stores `testGodwit()` in a top-level `val` and several cases use it, in parallel.
- **godwit:** all cases share one database, one history and one lock. They see each other's data, and a case that expects a migration to run finds it applied.
- **You:** call `testGodwit()` inside each case. A call is cheap once the container runs.

### The test configuration drifts from production

- **State:** the tests pass `searchIndexWait = null` and a different seed list, and production waits five minutes for the search index.
- **godwit:** the tests prove the migrations, not the wait. A timeout in `ensureSearchIndex` shows up only in production.
- **You:** keep the differences to values that migrations take as parameters, as `initialSetup(searchIndexWait)` does. Everything else in the test run is the production code path.

## Design decisions

### The real runner against a real server

**Chosen:** tests run the list through `Godwit.migrate` against a MongoDB container.

**Alternatives:**

- An in-memory fake of the driver.
- Extract each step body into a function and test the function.

```text
// the extract-to-function workaround, which godwit makes unnecessary
internal fun backfillOrderStatus(session: ClientSession, orders: MongoCollection<Document>) { /* ... */ }

val orderStatus = migration("004-order-status")
    .inTransaction { backfillOrderStatus(session, collection("orders")) }   // the test never reaches this line
```

**Why:** what goes wrong with migrations is the runner's territory: the history flip in the same transaction, the retry of a body, DDL inside a transaction, the unique index that rejects the data, a service on the wrong client. None of it exists in a fake, and a function that the test calls directly skips it. A container costs seconds once per JVM.

### One container, one database per call

**Chosen:** a single replica-set container per JVM, and `testGodwit()` returns a database named by a UUID.

**Alternatives:** a container per spec, or one database that tests clean between cases.

**Why:** the container is the expensive part, and a UUID database makes isolation free: no cleanup that can be forgotten, no ordering between cases, parallel specs for free. Databases disappear with the container when the JVM exits.

### Helpers are extensions on `Godwit`, and `testGodwit` is a plain function

**Chosen:** `rerun`, `runIsolated`, `forget` and `shouldHaveApplied` extend `Godwit`; `testGodwit` takes no framework receiver; failures are `AssertionError`.

**Alternative:** helpers on a Kotest spec, with the container as a spec extension.

**Why:** the helpers work with any `Godwit`, including one that an application built in its own test setup. `godwit-test` has no test framework dependency, so Kotest, JUnit and a plain `main` all work, and no framework version constrains yours.

### `Target` in the core, `forget` in the test kit

**Chosen:** `Target.Before` and `Target.Through` are part of `godwit-core`; `forget` exists only in `godwit-test`.

**Alternative:** a test-only runner.

**Why:** running to a point is a real operation (a staged release, a test). Un-applying one is not: godwit rolls forward only, and a production method that deletes history would be a footgun. Tests that need it get it in a module that production does not depend on.

### The detector is a test-time check

**Chosen:** `SessionEscapeDetector`, a command listener, installed by `testGodwit()`.

**Alternative:** a session-bound wrapper around `MongoCollection`, so the compiler rejects a missing session.

```text
// not godwit API: a wrapper that binds the session
inTransaction {
    tx.collection("orders").updateMany(exists("status", false), set("status", "PENDING"))   // no session parameter
}
```

**Why:** a wrapper mirrors the driver, and the application's own services take `session` as a parameter and would bypass it. The detector sees every command from every code path that a test runs, including services. The cost is that it checks only what the tests execute.

### A convention test for coverage

**Chosen:** a spec that compares the list's ids with the ids that the tests claim.

**Alternative:** rely on code coverage tools, or on review.

**Why:** line coverage of a migration lambda says it ran, not that a test chose data for it. A named id in a test is a deliberate decision, and the test fails the moment the list grows.

## See also

- [declaring-migrations.md](declaring-migrations.md): the shapes of migrations that these tests exercise.
- [dependencies.md](dependencies.md): building the list in tests with fakes.
- [transactions-and-sessions.md](transactions-and-sessions.md): `session`, and what the detector protects.
- [outside-transaction-steps.md](outside-transaction-steps.md): idempotency, which `rerun` proves.
- [batched-backfills.md](batched-backfills.md): the batches that `runIsolated(orderTotals)` shows.
- [adopting-an-existing-database.md](adopting-an-existing-database.md): the hook under test.
- [squashing-migrations.md](squashing-migrations.md): the equivalence test for a squash.
- [ordering-and-validation.md](ordering-and-validation.md): what `validateMigrations` checks.
- [configuration.md](configuration.md): the `GodwitConfig` that `testGodwit` takes.
- [concepts.md](concepts.md): the model that the tests exercise.
- [design-decisions.md](design-decisions.md): the index of all decisions.
- [../README.md](../README.md): the project overview.
