# Repeatable migrations

Most migrations run once per database. Two kinds run again. An every-start migration, declared with `everyStart(id)`,
runs on every `migrate` call. A repeatable migration, declared with `repeatable(id, revision)`, runs again whenever its
revision changes. Otherwise both are ordinary migrations: the same steps, the same history document, the same lock and
the same transactions. Use them for state that the code defines and the database must match: reference data, seed
accounts, and state outside MongoDB that can drift between deploys.

## The two kinds

| | `repeatable(id, revision)` | `everyStart(id)` |
|---|---|---|
| Due when | Its history document is missing, not APPLIED or another kind's, or its stored revision differs from `revision` | Always, on every `migrate` with `Target.Latest` |
| A start with nothing else due | Skips it: one history read, no lock (the fast path) | Takes the lock and runs it |
| `status()` | Pending while due | Never pending |
| Use for | Data the code versions by hand: reference data, lookup tables, configuration documents | State that can change without a deploy: users at an external identity provider, seed data that comes from each environment's configuration |
| History | `kind: REPEATABLE`, `revision`, `runCount`, `lastRunAt` | `kind: EVERY_START`, `runCount`, `lastRunAt` |

Rules shared by both:

- They are listed after every once-only migration; `validateMigrations` rejects a list that breaks this.
- They run after every pending once-only migration, in list order, under the lock.
- They never run under `Target.Before` or `Target.Through`.
- They cannot use `inBatches`; `validateMigrations` rejects it.

## A repeatable: reference data by revision

The countries the shop ships to are a list in the code, applied once per revision:

```kotlin
package com.example.shop.migrations

import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.nin
import com.mongodb.client.model.ReplaceOptions
import godwit.core.repeatable
import org.bson.Document

/** The countries the shop ships to. Change the revision below in the same commit as this list. */
private val COUNTRIES = listOf(
    "GB" to "United Kingdom",
    "IE" to "Ireland",
    "FR" to "France",
    "DE" to "Germany"
)

/**
 * Applied once per revision, after every pending once-only migration. A start at the same revision skips it without
 * taking the lock.
 */
val referenceCountries = repeatable("reference-countries", revision = "2026-10-01")
    .inTransaction {
        val countries = collection("countries")
        COUNTRIES.forEach { (code, name) ->
            countries.replaceOne(
                session,
                eq("_id", code),
                Document("_id", code).append("name", name),
                ReplaceOptions().upsert(true)
            )
        }
        count("countriesRemoved", countries.deleteMany(session, nin("_id", COUNTRIES.map { it.first })).deletedCount)
    }
```

The revision is any non-blank string the app chooses: a date, a counter. godwit compares it with the stored one for
equality only; it never orders revisions.

On a fresh database, `reference-countries` runs after `001-initial-setup` to `006-order-totals`, and its history
document records `revision: "2026-10-01"` and `runCount: 1`. On every later start at the same revision it is up to
date. Because `everyStart` takes the lock anyway, a shop start still holds the lock, but `reference-countries` does not
run:

```text
INFO  godwit - Acquired migration lock runId=0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f lockWaitMs=4
INFO  godwit - Running migration id=bootstrap-customers kind=EVERY_START steps=[OUTSIDE_TRANSACTION, IN_TRANSACTION] attempt=1
INFO  godwit - Applied migration id=bootstrap-customers kind=EVERY_START steps=[OUTSIDE_TRANSACTION, IN_TRANSACTION] attempts=1 txRetries=0 batches=0 durationMs=388 customersCreated=0
INFO  godwit - Migrations complete runId=0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f ran=1 recorded=0 upToDate=7 lockWaitMs=4 durationMs=402
```

`upToDate=7` is the six applied once-only migrations and `reference-countries` at its current revision. A list
without an every-start migration takes the fast path instead: one history read, no lock, and a single "Migrations up to
date" line.

### Write the end state

A repeatable runs again on every revision, against whatever the previous revisions left. Its step describes the state
the data must end in, so that running it on any earlier state converges: `reference-countries` upserts every listed
country and deletes every other one. A step that applies a change relative to the data (`$inc`, `$push`, an insert
without a key) applies it again on every revision.

### Bumping the revision

The next release ships to Spain. The list and the revision change in one commit:

```kotlin
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.nin
import com.mongodb.client.model.ReplaceOptions
import godwit.core.repeatable
import org.bson.Document

/** The countries the shop ships to. Change the revision below in the same commit as this list. */
private val COUNTRIES = listOf(
    "GB" to "United Kingdom",
    "IE" to "Ireland",
    "FR" to "France",
    "DE" to "Germany",
    "ES" to "Spain"
)

/**
 * Applied once per revision, after every pending once-only migration. A start at the same revision skips it without
 * taking the lock.
 */
val referenceCountries = repeatable("reference-countries", revision = "2026-11-15")
    .inTransaction {
        val countries = collection("countries")
        COUNTRIES.forEach { (code, name) ->
            countries.replaceOne(
                session,
                eq("_id", code),
                Document("_id", code).append("name", name),
                ReplaceOptions().upsert(true)
            )
        }
        count("countriesRemoved", countries.deleteMany(session, nin("_id", COUNTRIES.map { it.first })).deletedCount)
    }
```

On the first start of the new release, every database finds `2026-10-01` stored and `2026-11-15` declared, so
`reference-countries` is due. godwit takes the lock, runs any pending once-only migrations, then runs it. Its history
document, updated in place:

```json
{
  "_id": "reference-countries",
  "kind": "REPEATABLE",
  "revision": "2026-11-15",
  "steps": ["IN_TRANSACTION"],
  "state": "APPLIED",
  "origin": "RAN",
  "attempts": 1,
  "transactionRetries": 0,
  "counts": { "countriesRemoved": 0 },
  "durationMs": 27,
  "startedAt": { "$date": "2026-11-15T09:02:11.391Z" },
  "finishedAt": { "$date": "2026-11-15T09:02:11.418Z" },
  "runCount": 2,
  "lastRunAt": { "$date": "2026-11-15T09:02:11.418Z" },
  "holder": "shop-7f9c4/1",
  "owner": "9e2d7a10-4c3b-4f8e-a1d2-6b5c4d3e2f10",
  "runId": "019a8b31-2c4d-7e5f-8a6b-1c2d3e4f5a6b",
  "godwitVersion": "0.1.0",
  "v": 1
}
```

| Field | For repeatable and every-start migrations |
|---|---|
| `kind` | `REPEATABLE` or `EVERY_START` |
| `revision` | The revision of the last run (repeatables only) |
| `runCount` | Successful runs, over the life of the database |
| `lastRunAt` | When the last successful run finished |
| `state` | APPLIED between runs; RUNNING during one; FAILED after a failed one |
| `attempts` | Runs started since the last APPLIED: 1 after a clean run, so it starts again with each run |
| `counts`, `transactionRetries` | Of the last successful run |
| `durationMs`, `finishedAt` | Of the last run, a failed one included |

The [history reference](history-and-reports.md) has every field.

## An every-start migration: syncing to an external service

Each environment's configuration lists the accounts it needs (`SEED_CUSTOMERS`): staff accounts in production, demo
accounts in staging. The accounts live at the identity provider as well as in `customers`, and someone can delete one
in the provider's console at any time. `bootstrap-customers` puts them back on every start:

```kotlin
package com.example.shop.migrations

import com.example.shop.SeedCustomer
import com.example.shop.services.CustomerService
import com.example.shop.services.IdentityProvider
import godwit.core.Migration
import godwit.core.everyStart

/**
 * Makes sure the configured seed customers exist, on every start: each environment's configuration lists the
 * accounts it needs. The identity provider calls run outside any transaction because the driver may run a
 * transaction body again; findOrCreateUser keeps them idempotent. The inserts are one transaction.
 */
fun bootstrapCustomers(seed: List<SeedCustomer>, customers: CustomerService, identity: IdentityProvider): Migration =
    everyStart("bootstrap-customers", description = "Seed customers from configuration")
        .outsideTransaction {
            seed.associate { customer -> customer.email to identity.findOrCreateUser(customer.email).id }
        }
        .inTransaction { externalIds ->
            val created = seed.count { customer ->
                customers.ensureCustomer(session, customer, externalIds.getValue(customer.email))
            }
            count("customersCreated", created)
        }
```

The shape is the usual one for external state ([transactions and sessions](transactions-and-sessions.md#external-services-the-outside-step-and-the-hand-off)):

- The outside step makes one `findOrCreateUser` call per seed customer. The call finds the user by email first, so
  every start returns the same users, and a deleted one is created again.
- It returns a `Map<String, String>` from email to the provider's user id, which the transaction receives as
  `externalIds`.
- The transaction calls `CustomerService.ensureCustomer` with the step's `session`: an upsert by email that inserts
  only missing customers. `customersCreated` is 0 on a start where nothing was missing.

The seed list reaches the migration as a function parameter, from `config.bootstrap.customers`
([dependencies](dependencies.md)).

Every start pays for it: a lock acquire and release, one HTTP call per seed customer, two history writes (the RUNNING
marker and the APPLIED record). In a rollout of six pods, the six starts run it one after another, each waiting for the
lock while the one before runs. Prefer `repeatable` unless the work must happen on every start: here it must, because
the drift happens at the provider, where no revision can see it.

## Placement and run order

Every repeatable and every-start migration is listed after every once-only migration. This compiles and is wrong:

```kotlin
/** Wrong: a repeatable listed before a once-only migration. */
val misordered: List<Migration> = listOf(initialSetup(searchIndexWait = null), referenceCountries, carts)
```

`validateMigrations(misordered)` throws `InvalidMigrationsException` naming `reference-countries`, and `migrate`,
`status` and `requireUpToDate` throw the same before any I/O. The unit test that validates the app's list catches it
([ordering and validation](ordering-and-validation.md)).

They run after every pending once-only migration, so they always see the schema of the release that runs them. A
fresh database runs `001-initial-setup` to `006-order-totals`, then `reference-countries`; a production database where
only `007-customer-email-lower` is pending runs `007`, then `reference-countries`, if its revision changed. Both end
with the same data on the same schema.

### Never under `Target.Before` or `Target.Through`

`Target.Before(id)` and `Target.Through(id)` stop at a once-only migration, for tests that insert data in the shape that
migration expects. They run no repeatable or every-start migration, which belong to the latest schema. The shop's
`004-order-status` test runs `Target.Through("004-order-status")`: `001-initial-setup` to `004-order-status` run,
`reference-countries` and `bootstrap-customers` do not. A test that needs them runs `Target.Latest`, or runs one alone:

```kotlin
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
```

The second test runs the October revision, starts again at the same revision (nothing runs, and `lockWait` is null
because the lock was never taken), then runs the November revision from this page.

## Locks and the fast path

- A repeatable at its current revision is up to date: when nothing else is due, `migrate` reads history once and
  returns without the lock.
- A due repeatable takes the lock like any due migration, and runs once per new revision per database. A process that
  read history before another one applied the new revision waits for the lock, reads history again under it, finds the
  revision applied and releases the lock without running it. Two races with a run that has lost the lock are the
  exceptions, and each runs the revision once more: that run's commit lands just after the new holder read history
  under the lock and before the new holder's marker, so the new holder runs the revision again; or that run's marker
  lands after the new holder applied the revision and reopens it, and the next start runs it again
  ([architecture](architecture.md#edge-cases)).
- An every-start migration is always due, so a list that contains one takes the lock and writes history on every
  start; such a list never takes the fast path.

Under the lock, godwit marks a repeatable or every-start migration RUNNING with an unconditional upsert on its `_id`.
A once-only migration's marker only matches a document that is not APPLIED, which is how a once-only migration is
never run twice; a repeatable's APPLIED document has to go back to RUNNING for its next run
([architecture](architecture.md)).

## No `inBatches` in repeatables

`validateMigrations` rejects `inBatches` in a repeatable or every-start migration. This compiles and is rejected
(`searchTextOf(product)` is a helper of the shop's that joins a product's name, description and SKU in lower case):

```kotlin
/** Wrong: a repeatable cannot page through a collection. */
val productSearchTextEveryRevision = repeatable("product-search-text", revision = "3")
    .inBatches("products", pending = Filters.empty(), batchSize = 500) { products ->
        collection("products").bulkWrite(
            session,
            products.map { product -> UpdateOneModel<Document>(eq("_id", product["_id"]), set("searchText", searchTextOf(product))) }
        )
    }
```

A checkpoint marks progress through one run; on a migration that runs again, godwit could not tell a checkpoint of
this run from one of the last. Write each version of a collection-wide change as a once-only migration, with a field
that records which version a document has:

```kotlin
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.ne
import com.mongodb.client.model.UpdateOneModel
import com.mongodb.client.model.Updates.combine
import com.mongodb.client.model.Updates.set
import godwit.core.migration
import org.bson.Document

/**
 * The third version of the product search text. A once-only migration per version: `searchTextVersion` marks the
 * products already done, so the pages resume and the next version selects every product again.
 */
val productSearchTextV3 = migration("020-product-search-text-v3")
    .inBatches("products", pending = ne("searchTextVersion", 3), batchSize = 500) { products ->
        collection("products").bulkWrite(
            session,
            products.map { product ->
                UpdateOneModel<Document>(
                    eq("_id", product["_id"]),
                    combine(set("searchText", searchTextOf(product)), set("searchTextVersion", 3))
                )
            }
        )
        count("productsUpdated", products.size)
    }
```

Version 4 is `021-product-search-text-v4` with `pending = ne("searchTextVersion", 4)`. See
[batched backfills](batched-backfills.md).

## Edge cases

### A repeatable fails

The `2026-11-15` revision of `reference-countries` throws: a bug in the new list.

godwit: the transaction rolls back, so `countries` is unchanged. The history document is FAILED with `lastError`;
`migrate` throws `MigrationFailedException` and the app does not start. The once-only migrations that ran before it in
the same call stay applied. The next start runs it again: a document that is not APPLIED is due, whatever its
revision.

You: fix the list and deploy, or roll back. A rolled-back release declares `2026-10-01`, finds a document that is not
APPLIED, and runs its own revision, which applies. `markApplied` does not help here: it refuses a repeatable or
every-start migration, because the migration would be due again on the next start whatever its document says.

### An every-start migration fails because a service is down

The identity provider is down, and `findOrCreateUser` throws in the outside step of `bootstrap-customers`.

godwit: the migration is FAILED and `migrate` throws `MigrationFailedException` on every start until the provider is
back. No process of the shop starts in the meantime.

You: decide whether the shop should start without the provider. A migration is work the app cannot start without; when
the seed sync is not, move it out of the migration list into a job of the app's own that retries in the background.

### The revision was not bumped

The list gains `"ES" to "Spain"`, and the revision stays `2026-10-01`.

godwit: the stored revision matches, so `reference-countries` does not run, and no database ships to Spain.

You: bump the revision. To make the bump impossible to forget, derive the revision from the data, with a digest of
the list:

```kotlin
/** The first 16 hex digits of the SHA-256 of the list: changes whenever an entry changes. */
private fun digestOf(entries: List<Pair<String, String>>): String =
    MessageDigest.getInstance("SHA-256")
        .digest(entries.joinToString("\n") { (code, name) -> "$code=$name" }.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(16)
```

The same migration with the derived revision, in the file that holds `COUNTRIES` and `digestOf`:

```kotlin
private val referenceCountriesDerived = repeatable("reference-countries", revision = "v2-" + digestOf(COUNTRIES))
    .inTransaction {
        val countries = collection("countries")
        COUNTRIES.forEach { (code, name) ->
            countries.replaceOne(session, eq("_id", code), Document("_id", code).append("name", name), ReplaceOptions().upsert(true))
        }
        count("countriesRemoved", countries.deleteMany(session, nin("_id", COUNTRIES.map { it.first })).deletedCount)
    }
```

A derived revision changes whenever an entry changes, and not when only the step's code changes; the `v2-` prefix is
the part to change by hand for that. A reordered list also changes the digest, which runs the step once more, harmlessly.

### An older release starts after a newer one

A rollback deploys the release with `2026-10-01` after `2026-11-15` has applied. Or, during a rolling deploy, a pod
still on the old release crashes and restarts after new pods have applied `2026-11-15`.

godwit: revisions are compared for equality, so the old release finds a different revision stored and runs its own:
`reference-countries` deletes Spain. A new-release process that starts later finds `2026-10-01` stored and runs
`2026-11-15` again. The data follows whichever release started last.

You: for a rollback this is what you want: the old release gets the data it was written for. For a rolling deploy,
know that a restarted old process undoes the new revision until the next new process starts. A step that only upserts
never removes what a newer revision added; one that deletes everything it does not list, as `reference-countries`
does, removes it until the newer release runs again. Every-start migrations behave the same way: each process applies
its own release's version on its own start.

### Several processes start at once

A rollout starts six pods, each with `bootstrap-customers`.

godwit: the lock serialises them. Each pod runs `bootstrap-customers` once, after the pod before it releases the lock;
five of the six runs create nothing (`customersCreated=0`). A pod waits at most `LockConfig.waitTimeout` for the lock.

You: keep every-start steps short; six slow runs in a row add up to the last pod's start time. In a list without an
every-start migration, a new revision of a repeatable runs on the first pod alone. The pods that read history before it
applied wait for the lock, find the revision applied when they read history again under it, and release the lock
without running anything (`Migrations complete ... ran=0`); a pod that starts after it has applied takes the fast path.

### A worker that must not migrate

A worker process calls `godwit.requireUpToDate(migrations)` and never `migrate`.

godwit: every-start migrations are never pending, so `bootstrap-customers` does not hold the worker back. A due
repeatable is pending: a worker deployed with `2026-11-15` before any app process has run it throws
`PendingMigrationsException` naming `reference-countries`.

You: deploy the app first, or let the worker retry until the app has migrated.

### Deleting a repeatable or every-start migration

The shop stops keeping countries in the database and deletes `reference-countries` from the list.

godwit: the migration never runs again, and its history document stays APPLIED. godwit no longer knows the id, so every
start logs `Unknown applied migrations ids=[reference-countries]` (WARN) and returns it in
`MigrationReport.unknownApplied`. Under `UnknownApplied.FAIL`, `migrate` throws `PlanConflictException` instead. The
data it wrote stays.

You: remove the data in a once-only migration of its own (`collection("countries").drop()` in an outside step is safe to
repeat). When no deployed release lists the migration any more, delete its history document by hand:
`db.getCollection("godwit-history").deleteOne({ _id: "reference-countries" })` in `mongosh`. A release that still lists
it and starts afterwards runs it again, because its document is missing. When `adoptApplied` is configured and every
other history document is `ADOPTED`, the deletion reopens adoption: the next start with work due calls the hook again.
Renaming the id of a repeatable is the same as deleting one and adding another: the new id runs, the old one becomes
unknown.

### Changing a migration's kind

`reference-countries` was declared with `everyStart`, and the next release declares it with
`repeatable("reference-countries", revision = "2026-10-01")`, so that a start with nothing else due takes the fast path
again. Later a rollback deploys the `everyStart` release for a while, and the `repeatable` release rolls forward.

godwit: the history document belongs to the id, and each run writes its own `kind` into it. A repeatable is up to date
only when a repeatable's run applied its revision: the document is APPLIED, has `kind: REPEATABLE` and the same
`revision`. An every-start or once-only run that applies removes `revision`. So the first start of the `repeatable`
release runs it, every start of the rolled-back release runs its every-start version, and the next start of the
`repeatable` release runs it again at `2026-10-01`, with `attempts: 1`. The data follows whichever release started
last, as it does across revisions. `runCount` counts the runs of both kinds. A once-only migration whose id has an
APPLIED document of another kind counts as applied and does not run.

You: nothing, between repeatable and every-start. A once-only migration that must run gets an id of its own.

### A blank revision

`repeatable("reference-countries", revision = "")`, for example from an unset environment variable.

godwit: `validateMigrations` reports it, and `migrate` throws `InvalidMigrationsException` before any I/O.

You: give it a value. A revision read from configuration ties the data to each environment's configuration rather than
to the release; prefer one in the code.

## Design decisions

### Two kinds, named where the migration is written

Chosen: `everyStart(id)` and `repeatable(id, revision)` start the declaration, the same way `migration(id)` does, so
the kind is on the first line of the migration.

Considered: one factory with a flag (`migration(id, rerun = ...)`), and kinds decided by where a migration sits in the
list. A flag puts the difference that decides cost and correctness in an argument that is easy to miss; a placement
rule hides it from the migration's own file. Two names also carry two different costs: one says "this takes the lock
on every start".

### A revision chosen by the app, not a checksum

Chosen: the app sets `revision`, and changes it in the same commit as the code.

Considered: a checksum of the migration's code. A Kotlin lambda has no stable source text at runtime, and its bytecode
changes with the compiler version or an unrelated refactor, so a checksum would rerun migrations on upgrades that
changed nothing and miss changes in data the step reads from elsewhere. A revision states the intent; a derived revision
(above) is available when the data is the whole of it.

### Run last, listed last

Chosen: repeatable and every-start migrations are listed after every once-only migration, and run after every pending
one.

Considered: running them at their position in the list. A fresh database would then run a repeatable against the
schema of an earlier migration, while a production database runs it against the latest; the same code would see two
schemas. Running last means both see the release's schema. Listing them last, enforced by validation, makes the list
read in run order.

### The lock on every start for `everyStart`

Chosen: an every-start migration runs under the lock, like every other migration.

Considered: running it without the lock, to keep the fast path. Several processes starting together would then run it
at the same time, and the guarantees it relies on (one run at a time, the fenced history write, `runCount`) would not
hold. The cost is the lock on every start, which is why `repeatable` exists and why this page recommends it first.

### No `inBatches`, no targets

Chosen: repeatables cannot use `inBatches`, and `Target.Before` and `Target.Through` never run them.

A checkpoint belongs to one run of a once-only change; a versioned once-only migration does the same job without
ambiguity. Targets reconstruct an earlier schema for a test; repeatables describe the latest data, which may depend on
the latest schema, so running them at an earlier target would test a state no database is ever in.

## See also

- [Concepts](concepts.md): the three kinds and the run lifecycle
- [Declaring migrations](declaring-migrations.md): `migration`, `everyStart` and `repeatable`
- [Ordering and validation](ordering-and-validation.md): the placement rule and the other validation rules
- [Dependencies](dependencies.md): passing the seed list and the services to `bootstrapCustomers`
- [Transactions and sessions](transactions-and-sessions.md): the outside step and the typed hand-off
- [Batched backfills](batched-backfills.md): versioned once-only backfills
- [Locking](locking.md): the fast path and waiting for the lock
- [History and reports](history-and-reports.md): `runCount`, `lastRunAt`, `revision`, `unknownApplied`
- [Testing](testing.md): targets, `runIsolated` and `rerun`
- [Configuration](configuration.md): `unknownApplied` and the lock settings
- [Design decisions](design-decisions.md): every decision in one index
- [README](../README.md)
