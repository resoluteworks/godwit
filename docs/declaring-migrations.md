# Declaring migrations

This page covers how a migration is written: the three factories, the step shapes and the compile errors that rule
out every other shape, how the outside step hands a typed value to the transaction, counters, ids, how files are laid
out, how the list is built, and how a unit test checks the list without a database. It ends with the reasoning behind
the declaration style. For what each step guarantees at run time, read [concepts](concepts.md) first.

## The factories

A declaration starts with one of three functions from `godwit.core`. Each returns a `MigrationDraft`, which is not a
`Migration`: a draft becomes one only when a step is added.

| Factory | Kind | Parameters |
|---|---|---|
| `migration(id, description = null, supersedes = emptyList())` | once-only | `supersedes` names the ids a squash replaces; see [squashing migrations](squashing-migrations.md) |
| `repeatable(id, revision, description = null)` | repeatable | `revision` is any non-blank string the app changes when the migration's code changes |
| `everyStart(id, description = null)` | every-start | none beyond the id |

`description` is free text, stored in history and printed in logs. It can change at any time; the id cannot.

## Steps

A draft gets an optional outside step and then an optional transactional step, at least one of the two:

| Shape | Result type | Shop example |
|---|---|---|
| `outsideTransaction { }` | `OutsideTransactionMigration<T>`, a `Migration` | `001-initial-setup`, `002-carts`, `003-file-store` |
| `inTransaction { }` | `Migration` | `004-order-status`, `reference-countries` |
| `inBatches(collection, pending, batchSize) { docs -> }` | `Migration` | `007-customer-email-lower` (below) |
| `outsideTransaction { }.inTransaction { prepared -> }` | `Migration` | `005-customer-external-ids`, `bootstrap-customers` |
| `outsideTransaction { }.inBatches(collection, pending, batchSize) { docs -> }` | `Migration` | `006-order-totals` |

Repeatable and every-start migrations take every shape except the two with `inBatches`; `validateMigrations` reports
that one, because the compiler cannot tell the kinds apart. Inside a step body the receiver is the step's scope:

| Member | `outsideTransaction` | `inTransaction`, `inBatches` |
|---|---|---|
| `id`, `database`, `collection(name)` | yes | yes |
| `count(name, n)`, `checkLock()` | yes | yes |
| `ensureCollection`, `ensureSearchIndex`, `dropIndexIfExists` | yes | no: DDL fails in a transaction |
| `session`, `attempt` | no: there is no transaction | yes |

### Outside step only

DDL belongs here. Every call must be idempotent, because a retry runs the step again from the start:

```kotlin
package com.example.shop.migrations

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

godwit writes `APPLIED` after the step returns. See [outside-transaction steps](outside-transaction-steps.md) for which
driver calls are idempotent.

### One transaction

A data change that fits in one transaction. Pass `session` to every driver call and every service call:

```kotlin
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

The updates and the `APPLIED` record commit in the same transaction. See
[transactions and sessions](transactions-and-sessions.md).

### Pages of documents

A data change too large for one transaction. godwit reads the documents that match `pending` in `_id` order, one page
of `batchSize` (500 by default) per transaction, and commits each page's writes with a checkpoint. The shop's `007`
uses pages of 1000:

```kotlin
/**
 * Stores `emailLower` on every customer, for case-insensitive sign-in. Computed in Kotlin because the server's
 * `$toLower` is defined for ASCII only. Customers without an email are counted and left alone; the `_id` paging moves
 * past them, so they do not stop the run.
 */
val customerEmailLower = migration("007-customer-email-lower")
    .inBatches("customers", pending = exists("emailLower", false), batchSize = 1000) { customers ->
        val updates = customers.mapNotNull { customer ->
            val email = customer.getString("email") ?: return@mapNotNull null
            UpdateOneModel<Document>(eq("_id", customer["_id"]), set("emailLower", email.trim().lowercase(Locale.ROOT)))
        }
        if (updates.isNotEmpty()) collection("customers").bulkWrite(session, updates)
        count("customersUpdated", updates.size)
        count("customersWithoutEmail", customers.size - updates.size)
    }
```

See [batched backfills](batched-backfills.md).

### Outside step, then one transaction: the prepared value

The outside step's last expression is the **prepared value**. Its type `T` is inferred, and `inTransaction` receives it
as a typed parameter:

```kotlin
fun customerExternalIds(customers: CustomerService, identity: IdentityProvider): Migration =
    migration("005-customer-external-ids")
        .outsideTransaction {
            customers.withoutExternalUserId().associate { customer ->
                checkLock()
                customer.id to identity.findOrCreateUser(customer.email).id
            }
        }
        .inTransaction { externalIds ->
            externalIds.forEach { (customerId, externalUserId) ->
                customers.setExternalUserId(session, customerId, externalUserId)
            }
            count("customersLinked", externalIds.size)
        }
```

Here `T` is `Map<ObjectId, String>`. The rules of the prepared value:

| Rule | Consequence |
|---|---|
| It is held in memory between the two steps of one run, and never stored | after a failure, the next run's outside step runs again and computes a fresh value |
| The driver's retries of the transaction body reuse it | the HTTP calls that produced it do not repeat when the transaction does |
| Its type is whatever the outside lambda's last expression is | when the transaction does not need it, ignore the parameter |
| `inBatches` does not receive it | `006-order-totals`' outside step returns the index name from `createIndex`, and nothing reads it |
| Every driver retry of the body gets the same instance | make it fully materialised and do not change it: call `.toList()` on a cursor or a `Sequence` in the outside step, and never remove entries from it in the body, or a retry finds it empty or half used |

### Outside step, then pages

An index first, then a paged backfill that uses it:

```kotlin
val orderTotals = migration("006-order-totals")
    .outsideTransaction {
        collection("orders").createIndex(compoundIndex(ascending("customerId"), descending("totalMinor")))
    }
    .inBatches("orders", pending = exists("totalMinor", false), batchSize = 500) { orders ->
        collection("orders").bulkWrite(
            session,
            orders.map { order -> UpdateOneModel<Document>(eq("_id", order["_id"]), set("totalMinor", totalOf(order))) }
        )
        count("ordersUpdated", orders.size)
    }
```

### Every-start and repeatable

The same steps, after a different factory. `COUNTRIES` is a private list in the same file:

```kotlin
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

See [repeatable migrations](repeatable-migrations.md).

## What does not compile

The types make every other shape a compile error. Each snippet below is compiled by the docs build and fails with the
error shown.

A draft without a step is not a `Migration`, so it cannot go in the list or be passed to `migrate`.

This does not compile:

```kotlin
val productSlugs = migration("007-product-slugs", description = "URL slugs for products")

val migrations: List<Migration> = listOf(productSlugs)
```

```text
e: Initializer type mismatch: expected 'List<Migration>', actual 'List<MigrationDraft>'.
```

`godwit.migrate(migration("007-product-slugs"))` fails the same way: `Argument type mismatch: actual type is
'MigrationDraft', but 'Migration' was expected.`

A migration has one transactional step. Nothing can follow `inTransaction` or `inBatches`.

This does not compile:

```kotlin
val defaultCurrency = migration("024-default-currency")
    .inTransaction {
        collection("orders").updateMany(session, exists("currency", false), set("currency", "GBP"))
    }
    .inTransaction {
        collection("carts").updateMany(session, exists("currency", false), set("currency", "GBP"))
    }
```

```text
e: Unresolved reference 'inTransaction' on receiver of type 'Migration'.
```

Write both updates in one `inTransaction` body; a transaction can span collections. When the two must commit
separately, they are two migrations.

The DDL helpers are members of the outside step's scope only.

This does not compile:

```kotlin
val productSlugs = migration("007-product-slugs")
    .inTransaction {
        ensureCollection("product-slugs")
        collection("product-slugs").insertOne(session, Document("_id", "red-shoes").append("sku", "SHOE-RED"))
    }
```

```text
e: Unresolved reference 'ensureCollection'.
```

With `import godwit.core.*` the public extension `MongoDatabase.ensureCollection` is in scope, and the error becomes
`... is inapplicable because of a receiver type mismatch`. The compiler cannot stop every DDL call:
`database.ensureCollection(...)` compiles inside a transaction and runs without the session, outside the transaction,
and a raw `createIndex(session, ...)` on an existing collection compiles and fails at run time. See
[transactions and sessions](transactions-and-sessions.md).

The outside step comes first. Schema after data does not compile.

This does not compile:

```kotlin
val customerEmailLower = migration("007-customer-email-lower")
    .inTransaction {
        collection("customers").updateMany(
            session,
            exists("emailLower", false),
            listOf(Aggregates.set(Field("emailLower", Document("\$toLower", "\$email"))))
        )
    }
    .outsideTransaction {
        collection("customers").createIndex(ascending("emailLower"), IndexOptions().unique(true))
    }
```

```text
e: Unresolved reference 'outsideTransaction' on receiver of type 'Migration'.
```

Data then schema is two migrations; see [fixed two-phase shape](#a-fixed-two-phase-shape).

An outside step has no session.

This does not compile:

```kotlin
val productSlugs = migration("007-product-slugs")
    .outsideTransaction {
        collection("products").updateMany(
            session,
            exists("slug", false),
            listOf(Aggregates.set(Field("slug", Document("\$toLower", "\$sku"))))
        )
    }
```

```text
e: Unresolved reference 'session'.
```

Drop `session` to run the update outside any transaction (it must then be idempotent, as this filter makes it), or move
it to `inTransaction`.

## Counters

`count(name, n)` adds `n` to the counter `name`. Both steps can call it; `n` is a `Long` or an `Int`.

| Where the counter goes | Example for `004-order-status` |
|---|---|
| the history document's `counts` | `"counts": { "ordersPaid": 1200, "ordersPending": 37 }` |
| the `Applied migration` log line, as keys | `ordersPaid=1200 ordersPending=37` |
| `MigrationOutcome.counts` and `MigrationOutcome.count(name)` | `report["004-order-status"].count("ordersPaid")` is 1200 |

```text
INFO  godwit - Applied migration id=004-order-status kind=ONCE steps=[IN_TRANSACTION] attempts=1 txRetries=0 batches=0 durationMs=84 ordersPaid=1200 ordersPending=37
```

| Rule | Detail |
|---|---|
| The same name in both steps adds up | an outside step that counts `customersLinked` 3 and a transaction that counts it 5 report 8 |
| A transactional step counts committed work only | when the driver runs the body again, the step's counters start again from 0 |
| An `inBatches` step's counts survive a crash | each page commits the counts so far with its checkpoint, and a resumed run continues from them |
| `count(name)` on an outcome returns 0 for a name never counted | a test can assert 0 on a second run |

## Ids

| Rule | Detail |
|---|---|
| Characters | `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`: 1–128 characters, a letter or digit first |
| Unique | within the list, counting the ids named in `supersedes` lists |
| Permanent | the id is the history document's `_id`; changing it makes a different migration |
| Numbered | a numeric prefix (`^\d+[-_.]`) is compared as a number and must strictly increase among once-only migrations |
| Scope | flat and global within the database; there is nothing like a group or a module in an id |

The shop's convention:

| Kind | Id | Example |
|---|---|---|
| once-only | three zero-padded digits, a dash, what it does in kebab case | `004-order-status`, `006-order-totals` |
| repeatable, every-start | what it maintains, in kebab case, no number | `reference-countries`, `bootstrap-customers` |
| squash | above every id it replaces when it replaces the whole history; the number of the last id it replaces when later migrations stay ([rules](squashing-migrations.md#rules)) | `100-baseline`, `006-baseline` |

Gaps in the numbering are fine. The numbers make the order visible in a file listing and let `validateMigrations` catch
two branches that took the same number; see [ordering and validation](ordering-and-validation.md).

The examples in these docs follow one numbering. `001` to `006` and the two repeatable and every-start migrations are
the shop's list; `007-customer-email-lower`, `008-customer-email-lower-index` and `009-order-payment-status` come next;
every other example takes its own number from `010` on. Two kinds of example share a number on purpose: the two-branch
examples (`007-product-slugs`, `007-cart-currency`, `008-cart-currency`, `007-cart-totals`), which show a clash and an
out-of-order merge, and a wrong version of a migration shown next to its fix. A page that adds a migration to the list
shows the list at that later release, and says so (the shop's list once `009` is added, release 1.4).

## Files and names

The shop keeps one file per migration, named after its id, next to the list:

```text
src/main/kotlin/com/example/shop/migrations/
  001-initial-setup.kt           fun initialSetup(searchIndexWait: Duration?): Migration
  002-carts.kt                   val carts
  003-file-store.kt              val fileStore
  004-order-status.kt            val orderStatus
  005-customer-external-ids.kt   fun customerExternalIds(customers: CustomerService, identity: IdentityProvider): Migration
  006-order-totals.kt            val orderTotals
  reference-countries.kt         val referenceCountries
  bootstrap-customers.kt         fun bootstrapCustomers(seed: List<SeedCustomer>, customers: CustomerService, identity: IdentityProvider): Migration
  ShopMigrations.kt              fun shopMigrations(config: ShopConfig, customers: CustomerService, identity: IdentityProvider): List<Migration>
```

| Convention | Why |
|---|---|
| File name = id | a file listing reads in run order, and a review sees the new file and its list line together |
| `val` when the migration needs nothing, `fun` when it takes dependencies | the signature lists what the migration uses; see [dependencies](dependencies.md) |
| Kotlin name = what it does, in camel case (`orderStatus`) | the list reads as a table of contents |
| A KDoc on each declaration saying what and why | the history document stores the id and description, not the reasoning |
| Private helpers in the same file (`totalOf` in `006-order-totals.kt`) | a migration's code stays together and never changes after it is applied |

Grouping several migrations in one file, such as all reference data in `reference-data.kt`, compiles and runs the
same: godwit only sees the list. It suits a few small repeatable migrations that change together. It costs the
one-file-one-id overview, and a diff that touches the file no longer says which migration changed.

## Building the list

The list is a `List<Migration>` built by a function of what the migrations need:

```kotlin
fun shopMigrations(
    config: ShopConfig,
    customers: CustomerService,
    identity: IdentityProvider
): List<Migration> = listOf(
    initialSetup(searchIndexWait = config.mongo.searchIndexWait),
    carts,
    fileStore,
    orderStatus,
    customerExternalIds(customers, identity),
    orderTotals,
    referenceCountries,
    bootstrapCustomers(config.bootstrap.customers, customers, identity)
)
```

| Rule | Detail |
|---|---|
| List order is run order | once-only migrations run in list order; repeatable and every-start ones run after them, in list order |
| Once-only first | every repeatable and every-start migration is listed after every once-only one |
| Building it is cheap | the function only creates values; no step runs until `migrate` |
| One list per database | every module's and every library's schema changes go through this one list; see [libraries and modules](libraries-and-modules.md) |

`Godwit.migrate(vararg migrations)` takes migrations written inline, for scripts and tests.

## Checking the list in a unit test

`validateMigrations(list)` runs the same checks `migrate`, `status` and `requireUpToDate` run before any I/O, and
throws `InvalidMigrationsException` listing every problem. It needs no database, and the services can be mocks because
no step runs. `testConfig` and `FakeIdentityProvider` are the shop's test fixtures
([testing](testing.md#fixtures)):

```kotlin
"the migration list is valid" {
    validateMigrations(shopMigrations(testConfig, mockk(), FakeIdentityProvider()))
}
```

This test fails in CI when two branches both add `007-...`. Every rule and its message is in
[ordering and validation](ordering-and-validation.md).

## Edge cases

### The data must change before the schema

State: customers need a unique index on a lower-case copy of their email, and the copy does not exist yet. One
migration cannot do it: its outside step runs before its transactional step, and putting the index after the data does
not compile (above). What you do: write two migrations, data first. See
[a fixed two-phase shape](#a-fixed-two-phase-shape) for the code and why.

### The code of an applied migration changes

State: carts should expire after 60 days instead of 30, and someone edits `002-carts` to say `60L`. godwit stores no
checksum of a migration's code, so it notices nothing: every database where `002-carts` is `APPLIED` keeps 30 days,
and only fresh databases get 60. The schema now depends on when a database was created. What you do: leave `002-carts`
as it is and add a migration that changes every database:

```kotlin
/** Carts expire 60 days after their last update. 002-carts keeps its 30 days: it has run on every database. */
val cartExpiry60Days = migration("010-cart-expiry-60-days")
    .outsideTransaction {
        database.runCommand(
            Document("collMod", "carts").append(
                "index",
                Document("keyPattern", Document("updatedAt", 1)).append("expireAfterSeconds", 60L * 24 * 60 * 60)
            )
        )
    }
```

`collMod` with the same value again succeeds, so the outside step is idempotent. On a fresh database `002-carts` creates
the 30-day index and `010-cart-expiry-60-days` changes it to 60 days a moment later. Fixing a bug in a migration that
has run nowhere yet (it failed everywhere it ran, or it is not deployed) is an ordinary code change.

### An applied id is renamed

State: someone renames `004-order-status` to `004-order-status-backfill`. On a database where `004-order-status` is
applied, the new id is pending and listed before the applied `005` and `006`, so `migrate` throws
`PlanConflictException` (out of order) and nothing runs. With `OutOfOrder.RUN` the backfill would run a second time.
Once the conflict is resolved, the old id is reported as unknown applied. What you do: keep the id and change the
`description`, which is free text. When the id must change, declare the new id as a squash of the old one:

```kotlin
/**
 * 004-order-status under a new id. Where 004-order-status is applied, godwit records this one SUPERSEDED without
 * running it; where it is not, this one runs.
 */
val orderStatusBackfill = migration("004-order-status-backfill", supersedes = listOf("004-order-status"))
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

See [squashing migrations](squashing-migrations.md).

### A migration only some environments need

State: staging needs demo customers and production must not get them. Adding a once-only migration to the list only
when an environment flag is set makes the list differ between environments: a database that ran it and a database that
did not now disagree about what applied means, and a later change of the flag runs it out of order or leaves its id
unknown. What you do: keep one list everywhere and put the difference in configuration. `bootstrap-customers` does this:
it is in every environment's list and seeds the customers that environment's `SEED_CUSTOMERS` names, which is empty in
production.

### The prepared value would be large

State: `005-customer-external-ids` on a database with 2 million unlinked customers. The outside step would hold 2
million map entries in memory, and the transaction would write 2 million documents, far past the 60-second transaction
lifetime: the step fails with guidance and fails again on every start. What you do: do the work where it fits. Write
each link in the outside step as it is fetched (each write idempotent, at least once, with `checkLock()` per customer),
or split the work into an outside-only migration that stores the external ids and an `inBatches` migration that uses
them. See [outside-transaction steps](outside-transaction-steps.md) and [batched backfills](batched-backfills.md).

### A repeatable migration with `inBatches`

State: `repeatable("product-search-text", revision = "2026-10-01").inBatches(...)` compiles, because the draft type
does not carry the kind. `validateMigrations`, and therefore `migrate`, rejects the list before any I/O:

```text
godwit.core.InvalidMigrationsException: Invalid migrations:
- product-search-text is repeatable and uses inBatches, which only once-only migrations can use
```

What you do: make it a once-only migration, or, when the work must repeat, an `inTransaction` repeatable whose data
fits one transaction. A checkpoint has no meaning across runs of a repeatable.

## Design decisions

### A chained value, not a block builder or a class per migration

Chosen: a migration is a value built by chained calls: `migration(id).outsideTransaction { }.inTransaction { prepared -> }`.

Considered, as they would read for `005-customer-external-ids` (sketches of rejected shapes, not godwit API):

```text
// A class per migration
class CustomerExternalIds(
    private val customers: CustomerService,
    private val identity: IdentityProvider
) : TransactionalMigration("005-customer-external-ids") {
    private lateinit var externalIds: Map<ObjectId, String>

    override fun prepare(ctx: MigrationContext) {
        externalIds = customers.withoutExternalUserId().associate { it.id to identity.findOrCreateUser(it.email).id }
    }

    override fun migrate(tx: TransactionContext) {
        externalIds.forEach { (id, externalId) -> customers.setExternalUserId(tx.session, id, externalId) }
    }
}

// A block builder that registers migrations as it runs
shopMigrations {
    migration("005-customer-external-ids") {
        val externalIds = mutableMapOf<ObjectId, String>()
        outside { externalIds += customers.withoutExternalUserId().associate { ... } }
        transaction { externalIds.forEach { ... } }
    }
}
```

Why the chained value:

| Concern | Chained value | Class per migration | Block builder |
|---|---|---|---|
| Passing the outside result to the transaction | a typed lambda parameter | a mutable field set in one method, read in another | a captured mutable variable |
| Shape checked by the compiler | a draft is not a `Migration`; nothing follows the transactional step | the base class fixes the shape, one base class per shape | the builder accepts any sequence and checks at run time |
| Ceremony per migration | one expression, one indentation level | `class`, `override fun`, `ctx.`, `tx.` | a nested block per step, a marker annotation to keep scopes apart |
| How a migration is found | it is in the list | scanning, or the list | calling the builder registers it as a side effect |
| Testing one migration | pass the value to `runIsolated` | construct the class | build a builder around it |

### Step names state the guarantee

Chosen: `outsideTransaction`, `inTransaction`, `inBatches`. Considered: names by content (`schema { }`, `data { }`),
and names by order (`prepare`, `migrate`). Content names put the boundary in the wrong place: an HTTP call is not
schema and must run outside the transaction, and a server-side `updateMany` with an idempotent filter is data and can
run outside one. Order names say nothing about what may repeat. What decides whether a migration is correct is which
code may run more than once and what commits with the history record, so the names say that.

### A fixed two-phase shape

Chosen: an optional outside step, then an optional transactional step. A retry always runs the outside step again,
then the transactional step. Considered: free sequences of steps (outside, transaction, outside, ...) with progress
stored per step.

Why: with two fixed phases a retry has one rule, the history document needs no step index, and the prepared value is
always computed in the same run that uses it. With free sequences a retry would resume at step k, the value step k-1
produced would have to be stored or recomputed, and a code change between runs could shift which step k means.

The cost is that schema after data takes two migrations. A unique index on lower-case emails needs the data first,
`007` above:

```kotlin
/**
 * The unique index on `emailLower`, after 007 has filled it in. Partial, so customers without an email (no
 * `emailLower`) do not collide with each other.
 */
val customerEmailLowerIndex = migration("008-customer-email-lower-index")
    .outsideTransaction {
        collection("customers").createIndex(
            ascending("emailLower"),
            IndexOptions().unique(true).partialFilterExpression(exists("emailLower", true))
        )
    }
```

Listed in that order, `008` runs only after `007` is `APPLIED` on that database, so the index is built over data that
already has the field. A test runs the pair the same way:

```kotlin
"007 fills emailLower, then 008 indexes it" {
    val db = testGodwit()
    val customers = db.database.getCollection("customers", Document::class.java)
    customers.insertOne(Document("email", "Ada@Example.com").append("name", "Ada"))

    db.godwit.runIsolated(customerEmailLower).count("customersUpdated") shouldBe 1L
    db.godwit.runIsolated(customerEmailLowerIndex)

    customers.find(eq("emailLower", "ada@example.com")).first()["name"] shouldBe "Ada"
}
```

### No checksums of migration code

Chosen: godwit stores no hash of a migration's code and never compares one. A lambda has no stable source text at run
time, and a hash of compiled code changes with every compiler upgrade, so a checksum would fail starts for no change at
all. The need behind it, running a migration again when its code changes, is what `repeatable(id, revision)` covers,
with the revision under the app's control.

Every decision is indexed in [design decisions](design-decisions.md).

## See also

- [Concepts](concepts.md): kinds, steps, guarantees, the run lifecycle.
- [Dependencies](dependencies.md): migrations that need services.
- [Ordering and validation](ordering-and-validation.md): every list check and its message.
- [Transactions and sessions](transactions-and-sessions.md): what `inTransaction` guarantees and requires.
- [Outside-transaction steps](outside-transaction-steps.md): idempotent DDL, external calls.
- [Batched backfills](batched-backfills.md): `inBatches` in depth.
- [Repeatable migrations](repeatable-migrations.md): `repeatable` and `everyStart`.
- [Squashing migrations](squashing-migrations.md): `supersedes`.
- [Testing](testing.md): `runIsolated`, `rerun`, `Target`.
- [Configuration](configuration.md) and the [README](../README.md).
