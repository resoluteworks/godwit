# godwit

**Status: pre-release.** No version is published yet. The API on this page and in [docs](docs/) is designed and
compile-checked; the implementation follows the [implementation plan](docs/development/implementation-plan.md).

godwit runs MongoDB schema and data migrations for Kotlin JVM applications. A migration is a plain Kotlin value, built
by a chain of calls, and an application lists its migrations in a plain `List<Migration>`: the list is the registry
and the run order. There are no annotations, no reflection, no classpath scanning, no code generation and no
dependency injection: a migration that needs a service is a function that takes it as a parameter. Each step's name
states its guarantee: `outsideTransaction` runs at least once and must be idempotent, `inTransaction` commits exactly
once together with its history record, `inBatches` commits each page exactly once with a checkpoint. A lease lock
serialises every process that shares the database, a history collection holds one document per migration, and godwit
rolls forward only. godwit-core has two runtime dependencies: the MongoDB Kotlin sync driver and slf4j-api.

| Principle | What it means in code |
|---|---|
| Migrations are values | `migration("004-order-status").inTransaction { ... }` is an expression; nothing is discovered or registered |
| The list is explicit | `listOf(carts, orderStatus)` is the run order; a migration that is not listed never runs |
| Dependencies are parameters | `fun customerExternalIds(customers: CustomerService, identity: IdentityProvider): Migration` |
| Step names state the guarantee | `outsideTransaction` (at least once), `inTransaction` (exactly once), `inBatches` (exactly once per page) |
| Built in | the lock (`godwit-lock`), the history (`godwit-history`), transactions with the history record inside them |
| Roll forward only | no down migrations; `markApplied(id, reason)` is the audited way to skip one |
| Two runtime dependencies | `org.mongodb:mongodb-driver-kotlin-sync` and `org.slf4j:slf4j-api`; no framework integrations |

## Requirements

| Requirement | Version | Notes |
|---|---|---|
| JDK | 21 or later | |
| Kotlin | 2.4 or later | godwit is built with Kotlin 2.4.20; the lowest consumer version is confirmed before the first release ([implementation plan](docs/development/implementation-plan.md#decisions-needed-before-p0)) |
| MongoDB Kotlin sync driver | 5.7.0 | `org.mongodb:mongodb-driver-kotlin-sync`, an `api` dependency of godwit-core: the driver's types are in godwit's API |
| MongoDB server | 4.4 or later | See [compatibility](docs/architecture.md#compatibility) |
| Replica set or sharded cluster | | Needed when a transactional step (`inTransaction`, `inBatches`) is due. A single-node replica set is enough; outside-only migrations also run on a standalone server |
| Atlas, or the Atlas local image | | Only for `ensureSearchIndex` |
| Docker | | Only for `godwit-test`, which starts MongoDB in a container |

## Getting started

### Add the dependencies

godwit's artifacts are on Maven Central from the first release. Set the version once in `gradle.properties`:

```properties
godwitVersion=<version>
```

and add the two artifacts in `build.gradle.kts`:

```kts
val godwitVersion: String by project

repositories {
    mavenCentral()
}

dependencies {
    implementation("works.resolute:godwit-core:$godwitVersion")
    testImplementation("works.resolute:godwit-test:$godwitVersion")
    testImplementation("io.kotest:kotest-runner-junit5:6.2.5")
    testImplementation("io.kotest:kotest-assertions-core:6.2.5")
}

tasks.test {
    useJUnitPlatform()
}
```

`godwit-core` brings the MongoDB Kotlin sync driver and slf4j-api. Bind slf4j to the logging backend you already use.
`godwit-test` brings `godwit-core` and Testcontainers, and no test framework: its helpers throw `AssertionError`. The
two Kotest lines are for the test in [Test them](#test-them), which uses Kotest; any JUnit Platform framework works.

### Write migrations

The examples here and in the docs use one online shop: customers, orders, products and carts in MongoDB
([the example domain](docs/concepts.md#the-example-domain) lists the shop's own types). Two of its migrations:
`002-carts` creates a collection and its indexes outside any transaction, and `004-order-status` backfills a field in
one transaction that also commits godwit's history record.

```kotlin
import com.mongodb.client.model.IndexModel
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes.ascending
import godwit.core.migration
import java.util.concurrent.TimeUnit

/** Carts: one per customer, removed by the server 30 days after their last update. */
val carts = migration("002-carts", description = "One cart per customer, expiring after 30 days")
    .outsideTransaction {
        ensureCollection("carts")
        collection("carts").createIndexes(
            listOf(
                IndexModel(ascending("customerId"), IndexOptions().unique(true)),
                IndexModel(ascending("updatedAt"), IndexOptions().expireAfter(30L, TimeUnit.DAYS))
            )
        )
    }
```

The outside step has no session and runs again after a failure, so every call in it is idempotent: `ensureCollection`
does nothing when the collection exists, and `createIndexes` with the same specification does nothing either.

```kotlin
import com.mongodb.client.model.Filters.and
import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.Updates.set
import godwit.core.migration

/**
 * Gives every order a status. Orders written before the field existed are PAID when they have `paidAt`, PENDING
 * otherwise. The updates and the history record commit in one transaction, so the backfill applies exactly once.
 */
val orderStatus = migration("004-order-status")
    .inTransaction {
        val orders = collection("orders")
        val paid = orders.updateMany(
            session,
            and(exists("status", false), exists("paidAt", true)),
            set("status", "PAID")
        )
        val pending = orders.updateMany(session, exists("status", false), set("status", "PENDING"))
        count("ordersPaid", paid.modifiedCount)
        count("ordersPending", pending.modifiedCount)
    }
```

Pass `session` to every driver call in a transactional step; `godwit-test` fails a test that forgets it. The driver may
run the body again on a transient error, so it calls no external service. `count` adds a counter to the history
document and the log line. One transaction must commit within the server's 60 s transaction lifetime: for a collection
too large for that, page through it with `inBatches`, one transaction per page
([batched backfills](docs/batched-backfills.md)).

### List them and run them at startup

```kotlin
import com.example.shop.migrations.carts
import com.example.shop.migrations.orderStatus
import com.mongodb.kotlin.client.MongoClient
import godwit.core.Godwit
import godwit.core.Migration

/** Every migration, in run order. The list is the registry: a migration that is not listed never runs. */
val migrations: List<Migration> = listOf(carts, orderStatus)

fun main() {
    MongoClient.create(System.getenv("MONGO_URI")).use { client ->
        Godwit(client, "shop").migrate(migrations)
        // Build the app's services from this same client, then start serving requests.
    }
}
```

Ids are flat strings; numeric prefixes must increase in list order but need not be contiguous. `migrate` checks the
list before any I/O, reads history, and returns at once when nothing is due. Otherwise it takes the lock (a second
process waits for it), runs every due migration in list order and returns a `MigrationReport`. Build the services your
migrations call from the same `MongoClient`: the session godwit opens for a transactional step is valid only on that
client.

On a database that already has collections but no godwit history, such as one migrated by hand or by another tool, the
first `migrate` throws `UntrackedDatabaseException` and runs nothing, so it cannot re-run migrations over live data.
Tell godwit what is already applied with the `adoptApplied` hook: see
[adopting an existing database](docs/adopting-an-existing-database.md).

### What it logs

The first start on a new database runs both migrations:

```text
INFO  godwit - Acquired migration lock runId=0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f lockWaitMs=4
INFO  godwit - Running migration id=002-carts kind=ONCE steps=[OUTSIDE_TRANSACTION] attempt=1
INFO  godwit - Applied migration id=002-carts kind=ONCE steps=[OUTSIDE_TRANSACTION] attempts=1 txRetries=0 batches=0 durationMs=41
INFO  godwit - Running migration id=004-order-status kind=ONCE steps=[IN_TRANSACTION] attempt=1
INFO  godwit - Applied migration id=004-order-status kind=ONCE steps=[IN_TRANSACTION] attempts=1 txRetries=0 batches=0 durationMs=19 ordersPaid=0 ordersPending=0
INFO  godwit - Migrations complete runId=0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f ran=2 recorded=0 upToDate=0 lockWaitMs=4 durationMs=96
```

Every later start finds nothing due, reads history once and takes no lock:

```text
INFO  godwit - Migrations up to date runId=0199a4c3-0a11-7c52-8d93-e4f5a6b7c8d9 checked=2 durationMs=5
```

### Test them

`testGodwit()` returns a new database on a MongoDB replica-set container shared by the test JVM, with a `Godwit` for
it. Tests run the list through the real runner: the same lock, history and transactions as production.
`Target.Before` stops just before a migration, so a test can insert data in the shape that migration expects.

```kotlin
import com.mongodb.client.model.Filters.eq
import godwit.core.Target
import godwit.core.validateMigrations
import godwit.test.rerun
import godwit.test.shouldHaveApplied
import godwit.test.testGodwit
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bson.Document
import java.util.Date

class MigrationsTest : StringSpec({
    "the list is valid" {
        validateMigrations(migrations)
    }

    "004 marks orders with paidAt PAID and the rest PENDING, and a second run changes nothing" {
        val db = testGodwit()
        db.godwit.migrate(migrations, target = Target.Before("004-order-status"))
        val orders = db.database.getCollection("orders", Document::class.java)
        orders.insertMany(listOf(Document("_id", "paid").append("paidAt", Date()), Document("_id", "open")))

        val outcome = db.godwit.migrate(migrations)["004-order-status"]

        outcome.count("ordersPaid") shouldBe 1L
        outcome.count("ordersPending") shouldBe 1L
        orders.find(eq("_id", "open")).first()["status"] shouldBe "PENDING"
        db.godwit shouldHaveApplied "004-order-status"
        db.godwit.rerun(migrations, "004-order-status").count("ordersPaid") shouldBe 0L
    }
})
```

`validateMigrations` needs no database. `rerun` runs a migration a second time through the runner, which proves it is
safe to repeat. See [testing](docs/testing.md) for the whole kit.

## Documentation

Read in this order; each page builds on the ones before it.

1. [Concepts](docs/concepts.md): the whole mental model on one page: migrations, kinds, steps and their guarantees,
   one `migrate` call, the glossary.
2. [Declaring migrations](docs/declaring-migrations.md): the factories, step shapes, the prepared value, counters, ids,
   files and the list, and what does not compile.
3. [Dependencies](docs/dependencies.md): migrations that need services, as functions of those services; wiring at
   startup and fakes in tests.
4. [Transactions and sessions](docs/transactions-and-sessions.md): the exactly-once guarantee, passing `session`,
   driver retries, the 60 s lifetime, one `MongoClient`.
5. [Outside-transaction steps](docs/outside-transaction-steps.md): DDL, external services and large idempotent
   updates; which driver calls are safe to repeat; the three DDL helpers.
6. [Batched backfills](docs/batched-backfills.md): `inBatches`, one transaction per page with a checkpoint, and resume
   after a crash.
7. [Repeatable migrations](docs/repeatable-migrations.md): `repeatable(id, revision)` for reference data and
   `everyStart(id)` for work on every start.
8. [Ordering and validation](docs/ordering-and-validation.md): run order, the checks on the list, out-of-order and
   unknown applied ids, with every message.
9. [Locking](docs/locking.md): the leased lock, waiting, the fast path, crashed holders and lost locks.
10. [History and reports](docs/history-and-reports.md): the history documents field by field, `MigrationReport`,
    `status()`, `markApplied`, the log lines and shell queries.
11. [Failure and recovery](docs/failure-and-recovery.md): what every failure leaves behind, what the next start does
    and what you do.
12. [Adopting an existing database](docs/adopting-an-existing-database.md): taking over a database migrated by
    another tool, or by hand, with the `adoptApplied` hook and the untracked-database guard.
13. [Squashing migrations](docs/squashing-migrations.md): replacing many migrations with one baseline through
    `supersedes`.
14. [Libraries and modules](docs/libraries-and-modules.md): why libraries ship setup functions instead of migrations,
    and one list in a multi-module app.
15. [Testing migrations](docs/testing.md): `testGodwit()`, `Target`, `rerun`, `runIsolated`, `forget`,
    `shouldHaveApplied` and `SessionEscapeDetector`.
16. [Configuration](docs/configuration.md): every setting of `GodwitConfig` and `LockConfig`, per-environment setup and
    logging.
17. [Architecture](docs/architecture.md): the components, the runner's algorithm, the state machine, the exact lock and
    history operations, compatibility.
18. [Design decisions](docs/design-decisions.md): every design decision, with the options considered, the reasoning
    and the consequences.

For contributors: the [implementation plan](docs/development/implementation-plan.md) describes how godwit itself is
built, phase by phase, with the tests and verification gates of each phase.

## License

Apache License 2.0.
