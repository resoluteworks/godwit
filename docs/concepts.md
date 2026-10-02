# Concepts

This page is the whole mental model of godwit on one page: what a migration is, the three kinds of migration, the
steps a migration is made of and the guarantee each step gives, what one call to `migrate` does from start to finish,
and the words the rest of the documentation uses. Every section links to the page that covers its subject in depth.
Every example uses the same online shop: customers, orders, products and carts in MongoDB, with an external identity
provider and payment gateway ([the example domain](#the-example-domain)).

## A migration is a value

A migration is an ordinary Kotlin value of type `Migration`. A factory function names its kind and its id, and one or
two step functions give it its work. Nothing is annotated, scanned, discovered, registered or injected.

```kotlin
package com.example.shop.migrations

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

The app puts its migrations in a plain `List<Migration>`. The list is the registry: a migration that is not in it never
runs. List position is run order. A migration that needs a service is a function that takes the service, so the list is
built by a function that takes everything its migrations need:

```kotlin
package com.example.shop.migrations

import com.example.shop.ShopConfig
import com.example.shop.services.CustomerService
import com.example.shop.services.IdentityProvider
import godwit.core.Migration

/**
 * Every shop migration, in run order. This list is the registry: a migration that is not listed never runs. The
 * parameters are everything the migrations need, and each migration takes only its own part.
 */
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

The app migrates its database at startup, before it serves anything. `Godwit` gets the same `MongoClient` the app's
services use, because the session godwit opens for a transactional step is valid only on that client:

```kotlin
fun main() {
    val config = loadShopConfig()
    MongoClient.create(config.mongo.uri).use { client ->
        val database = client.getDatabase(config.mongo.database)
        val customers = CustomerService(database)
        val orders = OrderService(database)
        val identity = HttpIdentityProvider(config.identity.baseUrl, config.identity.apiKey)

        Godwit(client, config.mongo.database).migrate(shopMigrations(config, customers, identity))

        startHttpServer(customers, orders)
    }
}
```

`migrate` is synchronous. It returns a `MigrationReport` (what ran, what was recorded without running, what was already
up to date, how long the lock wait took) or throws. Several processes can call it at once against the same database:
the lock serialises them. Details: [declaring migrations](declaring-migrations.md), [dependencies](dependencies.md),
[history and reports](history-and-reports.md).

## Three kinds

The factory that starts a declaration names how often the migration runs, so the kind is visible where the migration is
written.

| Kind | Declared with | Due when | History | Shop example |
|---|---|---|---|---|
| once-only | `migration(id)` | its history document is missing or not `APPLIED` | `kind: "ONCE"`; `APPLIED` is final | `004-order-status` |
| repeatable | `repeatable(id, revision)` | as once-only, or the stored `revision` differs from the declared one | `kind: "REPEATABLE"`, `revision`, `runCount`, `lastRunAt` | `reference-countries` |
| every-start | `everyStart(id)` | on every `migrate` with `Target.Latest` | `kind: "EVERY_START"`, `runCount`, `lastRunAt` | `bootstrap-customers` |

Repeatable and every-start migrations are listed after every once-only migration (`validateMigrations` rejects any
other order), and they run after every due once-only migration. A fresh database and one that is a year old therefore
reach the same schema before reference data is written. A repeatable whose revision has not changed costs nothing on a
start; an every-start migration makes every start take the lock. Details:
[repeatable migrations](repeatable-migrations.md).

## Steps and their guarantees

A migration has an optional **outside step** followed by an optional **transactional step**, and at least one of the
two. The step's name states its guarantee, because the transaction boundary is what decides whether a migration is
correct.

| Step | Declared with | Session | How often the code runs | What commits, and when |
|---|---|---|---|---|
| outside step | `outsideTransaction { }` | none | at least once: a retry runs it again from the start | each write on its own, as it happens |
| transactional step | `inTransaction { }` | `session` | once, or more when the driver retries the transaction | the step's writes and the `APPLIED` history record, in one transaction: exactly once |
| transactional step, paged | `inBatches(collection, pending, batchSize) { docs -> }` | `session` | once per page, or more when the driver retries that page | each page's writes with the page's checkpoint: each page exactly once; the last page also commits `APPLIED` |

The five valid shapes:

| Shape | Use it for | Shop example |
|---|---|---|
| `outsideTransaction { }` | DDL: collections, indexes, search indexes | `001-initial-setup`, `002-carts`, `003-file-store` |
| `inTransaction { }` | a data change that fits in one transaction | `004-order-status`, `reference-countries` |
| `inBatches(...) { }` | a data change too large for one transaction | `007-customer-email-lower` in [declaring migrations](declaring-migrations.md) |
| `outsideTransaction { }.inTransaction { prepared -> }` | slow or external work first, then an atomic write of its result | `005-customer-external-ids`, `bootstrap-customers` |
| `outsideTransaction { }.inBatches(...) { }` | an index first, then a paged backfill | `006-order-totals` |

The outside step's return value, the **prepared value**, is handed to `inTransaction` as a typed parameter. It is how
work that must not repeat inside a transaction (an HTTP call, a slow read) reaches the transaction:

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

What happens to `005-customer-external-ids` on one run:

```text
history 005           missing -> RUNNING (attempts: 1)                     own write, committed
outside step          findOrCreateUser x N over HTTP                       at least once; idempotent by email
                      returns Map<ObjectId, String>                        held in memory only
transactional step    setExternalUserId(session, ...) x N                  \
                      history 005: RUNNING -> APPLIED, counts               > one transaction: exactly once
```

**Exactly once** describes what commits, not how often the code runs. The driver runs a transaction body again on a
transient error (an election, a write conflict) for up to 120 s, and godwit rolls back every attempt but the one that
commits; counters reset with each attempt. The database ends up with the step's writes exactly once, together with the
history record that says so. No step makes an effect outside MongoDB happen exactly once: an HTTP call inside a
transaction body repeats on every driver retry, which is why external calls belong in the outside step and must be
idempotent.

Details: [declaring migrations](declaring-migrations.md), [transactions and sessions](transactions-and-sessions.md),
[outside-transaction steps](outside-transaction-steps.md), [batched backfills](batched-backfills.md).

## One call to migrate

```text
migrate(list)
  check the list (pure) ................................ InvalidMigrationsException
  read history: one query, majority read concern
  check the plan against history ....................... PlanConflictException
  nothing due? ......................................... return: the fast path, no lock, no writes
  a transactional step due on a standalone server? ..... TransactionsUnsupportedException
  take the lock, waiting up to 10 min .................. LockTimeoutException
    read history again
    adopt applied ids (while history holds only adopted documents; only what is missing)
    plan and check again ............................... PlanConflictException, UntrackedDatabaseException
    a new transactional step due on a standalone server? TransactionsUnsupportedException
    record superseded squashes
    run every due once-only migration, in list order
    run every due repeatable and every-start migration, in list order
  release the lock
  return MigrationReport ............................... MigrationFailedException at the first failure
```

1. **Check the list.** `validateMigrations` runs before any I/O: ids, duplicates, numbering, placement of repeatable
   and every-start migrations, batch sizes. See [ordering and validation](ordering-and-validation.md).
2. **Read history and plan.** A migration is due when its history document is missing or not `APPLIED`; a repeatable
   also when its stored revision differs; an every-start migration always.
3. **Check the plan.** A pending once-only migration listed before an applied once-only migration is out of order and
   fails by default (repeatable and every-start documents never make one out of order); history ids the list does not
   know are logged (or fail, under `UnknownApplied.FAIL`); a partially applied squash fails. These checks come before
   the fast path, so they also stop a start that has nothing to do. While the adoption hook can still run, the
   out-of-order and squash checks wait for it (step 7). See
   [ordering and validation](ordering-and-validation.md), [squashing migrations](squashing-migrations.md).
4. **The fast path.** When nothing is due, `migrate` logs `Migrations up to date` and returns without taking the lock.
   `report.lockWait` is null.
5. **Check the server.** Only when a transactional step is due: a standalone `mongod` has no transactions.
6. **Take the lock.** One document in `godwit-lock`, leased on server time and renewed by a heartbeat. A second process
   waits, logs the holder every 10 s, and gives up after `LockConfig.waitTimeout`. See [locking](locking.md).
7. **Plan again under the lock.** Another process may have done the work while this one waited, so godwit reads
   history again before it runs anything. While history holds nothing but `ADOPTED` documents (or nothing at all),
   this is where the adoption hook runs and records the ids history lacks, where the out-of-order and squash checks
   that waited for it run, and where a database with collections but no history is refused unless something was
   adopted. See [adopting an existing database](adopting-an-existing-database.md).
8. **Run.** For each due migration: mark it `RUNNING` (`attempts` + 1), run the outside step, run the transactional
   step with the `APPLIED` flip inside its transaction (an outside-only migration writes `APPLIED` after its step).
   The first failure records the migration `FAILED` with its error, stops the run and throws
   `MigrationFailedException`. See [failure and recovery](failure-and-recovery.md).
9. **Release the lock** and return the report.

A migration's history document moves through three states:

```text
              run starts                  every step commits
 (missing) -------------> RUNNING ---------------------------> APPLIED
                           |    ^
         a step throws     |    |  the next migrate retries it,
                           v    |  outside step first
                          FAILED

 process killed: the document stays RUNNING; the next migrate, after the lock's lease expires, resumes it
```

A repeatable or every-start migration goes from `APPLIED` back to `RUNNING` each time it is due again.

## What godwit keeps in the database

godwit owns two collections in the migrated database and keeps no state in the process between calls.

| Collection | Holds |
|---|---|
| `godwit-history` | one document per migration, `_id` = migration id, updated in place |
| `godwit-lock` | one lock document |

The history document of `004-order-status` after it ran:

```json
{
  "_id": "004-order-status",
  "kind": "ONCE",
  "steps": ["IN_TRANSACTION"],
  "state": "APPLIED",
  "origin": "RAN",
  "attempts": 1,
  "transactionRetries": 0,
  "counts": { "ordersPaid": 1200, "ordersPending": 37 },
  "durationMs": 84,
  "startedAt": { "$date": "2026-10-02T10:14:05.120Z" },
  "finishedAt": { "$date": "2026-10-02T10:14:05.204Z" },
  "holder": "shop-7f9c4/1",
  "owner": "5b0f3c6e-2a41-4f7e-9d1c-0e8a7b6c5d4f",
  "runId": "0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f",
  "godwitVersion": "0.1.0",
  "v": 1
}
```

`origin` says how the migration came to be `APPLIED`: `RAN` (godwit ran it), `ADOPTED` (the adoption hook reported it),
`SUPERSEDED` (a squash replaced it) or `MARKED` (`Godwit.markApplied`, the audited escape hatch). godwit rolls forward
only: there are no down migrations. Details: [history and reports](history-and-reports.md), [locking](locking.md).

## Edge cases

### Nothing is due

State: a database at the current release, and a list without an every-start migration. godwit reads history once,
finds nothing due, logs one line and returns. It takes no lock and writes nothing; `report.lockWait` is null and
`report.ran` is empty.

```text
INFO  godwit - Migrations up to date runId=0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f checked=7 durationMs=6
```

The shop's list contains `bootstrap-customers`, an every-start migration, so every shop start takes the lock and runs
that one migration:

```text
INFO  godwit - Acquired migration lock runId=0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f lockWaitMs=3
INFO  godwit - Running migration id=bootstrap-customers kind=EVERY_START steps=[OUTSIDE_TRANSACTION, IN_TRANSACTION] attempt=1
INFO  godwit - Applied migration id=bootstrap-customers kind=EVERY_START steps=[OUTSIDE_TRANSACTION, IN_TRANSACTION] attempts=1 txRetries=0 batches=0 durationMs=41 customersCreated=0
INFO  godwit - Migrations complete runId=0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f ran=1 recorded=0 upToDate=7 lockWaitMs=3 durationMs=58
```

What you do: use `repeatable(id, revision)` instead of `everyStart` whenever the work only has to happen when something
you version changes. See [repeatable migrations](repeatable-migrations.md).

### Two instances start at the same time

State: a rolling deploy starts `shop-7f9c4/1` and `shop-2b8d1/1` on a release that adds `006-order-totals`. The first
takes the lock and runs `006-order-totals`, which pages through every order. The second finds `006-order-totals` due,
waits for the lock and logs the holder every 10 s:

```text
INFO  godwit - Waiting for migration lock holder=shop-7f9c4/1 holderRunId=0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f expiresAt=2026-10-02T10:15:00.210Z waitedMs=10004
```

When the first releases the lock, the second acquires it, reads history again, finds `006-order-totals` applied and
runs only `bootstrap-customers`, which is due on every start. What you do: nothing, as long as
`LockConfig.waitTimeout` (10 min by default) is longer than the longest migration. See [locking](locking.md).

### The process is killed in the middle of a migration

State: the instance running `006-order-totals` is killed after committing page 40. History holds `006-order-totals`
as `RUNNING` with `checkpoint: {lastId: <last _id of page 40>, batches: 40}`, and the lock document still names the
killed process until its lease expires (60 s at most). The next start waits for the lease to expire, takes the lock and
resumes:

```text
WARN  godwit - Resuming interrupted migration id=006-order-totals attempts=2
INFO  godwit - Running migration id=006-order-totals kind=ONCE steps=[OUTSIDE_TRANSACTION, IN_BATCHES] attempt=2
```

The outside step runs again (`createIndex` with the same spec does nothing) and paging continues after the checkpoint:
no page commits twice. What you do: nothing. See [failure and recovery](failure-and-recovery.md).

### The driver retries a transaction

State: while `004-order-status` runs, a customer pays for an order the step is updating. The server aborts the
step's transaction with a write conflict (code 112, labelled `TransientTransactionError`). The driver runs the body
again; godwit logs it and resets the step's counters:

```text
WARN  godwit - Retrying transaction id=004-order-status attempt=2 error=WriteConflict (112)
INFO  godwit - Applied migration id=004-order-status kind=ONCE steps=[IN_TRANSACTION] attempts=1 txRetries=1 batches=0 durationMs=1290 ordersPaid=1200 ordersPending=37
```

The counts are those of the attempt that committed, and history records `transactionRetries: 1`. What you do: nothing,
provided the body has no effect outside MongoDB. See [transactions and sessions](transactions-and-sessions.md).

### A local `mongod` started without a replica set

State: a developer runs the shop against a fresh database on a standalone `mongod`. Every migration is due, and
`004-order-status` and four others have a transactional step. godwit checks the server before it takes the lock, so
nothing runs, not even the DDL-only `001` to `003`:

```text
godwit.core.TransactionsUnsupportedException: Migrations [004-order-status, 005-customer-external-ids, 006-order-totals, reference-countries, bootstrap-customers] need transactions, which a standalone mongod does not support. Run a single-node replica set: start mongod with --replSet rs0, then run rs.initiate() once.
```

What you do: start `mongod --replSet rs0` and run `rs.initiate()` once. A list whose due migrations are all DDL-only
runs on a standalone server. See [transactions and sessions](transactions-and-sessions.md).

### A migration that is written but not listed

State: a developer adds `007-product-slugs.kt` with `val productSlugs = migration("007-product-slugs")...` and forgets
the line in `shopMigrations`. godwit never sees the value: it does not run, nothing is logged, and `status()` does not
mention it. What you do: keep each migration in a file named after its id next to `ShopMigrations.kt`, so the review
shows the new file and the list line together; the IDE's unused-declaration inspection flags the value; a test that
checks the migration's effect fails. See [testing](testing.md).

### Several databases with the same schema

State: the shop runs one database per country. godwit migrates one database per `Godwit` instance; each database has
its own history and its own lock. Build the list per database, because the services the migrations use are bound to
one database:

```kotlin
/** One shop database per country, same schema. Each database has its own history and its own lock. */
fun migrateEveryShop(
    client: MongoClient,
    config: ShopConfig,
    identity: IdentityProvider,
    databaseNames: List<String>
): Map<String, MigrationReport> =
    databaseNames.associateWith { databaseName ->
        val customers = CustomerService(client.getDatabase(databaseName))
        Godwit(client, databaseName).migrate(shopMigrations(config, customers, identity))
    }
```

The loop stops at the first database whose migration fails; the databases after it keep their schema until the next
start.

## The example domain

The shop's own code appears in the examples next to godwit's API. None of it is part of godwit:

| Name | Package | What it is |
|---|---|---|
| `ShopConfig`, `MongoSettings`, `IdentitySettings`, `BootstrapSettings`, `SeedCustomer`, `loadShopConfig()` | `com.example.shop` | The shop's configuration, read from environment variables. `config.mongo.searchIndexWait` is a `Duration?`, `config.bootstrap.customers` a `List<SeedCustomer>` |
| `startHttpServer(customers, orders)`, `log` | `com.example.shop` | The rest of the app, and its slf4j logger |
| `CustomerService(database)`, `OrderService(database)` | `com.example.shop.services` | The shop's services. Every method that writes takes a `ClientSession` first: `setExternalUserId(session, customerId, externalUserId)`, `ensureCustomer(session, seed, externalUserId)`, `markPaid(session, orderId, paymentId, paidAt)`. Reads such as `findByEmail(email)` and `withoutExternalUserId()` take none |
| `IdentityProvider`, `ExternalUser`, `HttpIdentityProvider(baseUrl, apiKey)` | `com.example.shop.services` | An external identity provider over HTTP: `findOrCreateUser(email): ExternalUser`, idempotent by email |
| `PaymentGateway`, `PaymentStatus` | `com.example.shop.services` | An external payment gateway: `paymentStatus(paymentId): PaymentStatus`, read-only |
| `EmailSender` | `com.example.shop.services` | An external email service: `send(to, subject, body)`, not idempotent, so no migration calls it |
| `Customer`, `Order`, `Product`, `Cart` | `com.example.shop.domain` | The shop's documents as classes; migrations work on `org.bson.Document` |
| `shopMigrations(...)`, `initialSetup`, `carts`, ... | `com.example.shop.migrations` | The shop's migrations, one file per id, and the list |
| `testConfig`, `FakeIdentityProvider`, `migrationsFor(db)` | `com.example.shop.testing` | The shop's test fixtures ([testing](testing.md#fixtures)) |
| `ensureFileStoreSchema()`, `FILES_COLLECTION` | `com.example.filestore` | A small file store library the shop uses ([libraries and modules](libraries-and-modules.md)) |

## Design decisions

| Decision | Chosen | Alternatives considered | Why |
|---|---|---|---|
| What a migration is | a plain value built by a chain of calls, in an explicit `List<Migration>` | a class per migration found by scanning the classpath; migrations registered as a side effect of calling a builder | The list is the registry and the run order in one place a reviewer reads. No reflection, no scanning, no annotations. A test builds the same list the app builds. See [declaring migrations](declaring-migrations.md). |
| How migrations get services | function parameters | a DI container; a typed bag of dependencies | A missing dependency is a compile error, and godwit needs no knowledge of any container. See [dependencies](dependencies.md). |
| What organises a migration | the transaction boundary: outside step, then transactional step | steps named by content (`schema`, `data`); free sequences of steps | Whether a step may repeat is what decides correctness, so the step's name states it. See [declaring migrations](declaring-migrations.md#design-decisions). |
| Undo | roll forward only; `markApplied(id, reason)` as the audited escape hatch | down migrations | A failed transactional step rolls back on its own; an outside step converges when it runs again; a fix is a new migration. See [failure and recovery](failure-and-recovery.md). |
| Scope of one run | one database, one list, one history, one lock | several lists per database (per library, per module) | Global order needs one place. See [libraries and modules](libraries-and-modules.md). |

Every decision, with its reasoning, is indexed in [design decisions](design-decisions.md).

## Glossary

| Term | Meaning |
|---|---|
| migration | a `Migration` value: id, kind, one or two steps |
| draft | what `migration(id)`, `repeatable(...)` and `everyStart(...)` return: a `MigrationDraft`, not a `Migration` until a step is added |
| once-only migration | declared with `migration(id)`; runs once per database |
| every-start migration | declared with `everyStart(id)`; runs on every start, under the lock |
| repeatable migration | declared with `repeatable(id, revision)`; runs when the revision changes |
| outside step | the `outsideTransaction { }` step: no session, at least once, idempotent |
| transactional step | the `inTransaction { }` or `inBatches(...) { }` step: commits with the history record |
| prepared value | what the outside step returns, the parameter of the `inTransaction` lambda |
| checkpoint | the last `_id`, page count and counts an `inBatches` step committed with its last page |
| counter | a named number a step adds to with `count(name, n)`; stored in history, logged, reported |
| due | needs to run on this database now: missing or not `APPLIED`, a changed revision, or every-start |
| pending | due, as `status()` reports it; never includes every-start migrations |
| history | the `godwit-history` collection, one document per migration, `_id` = migration id |
| origin | how a history document became `APPLIED`: `RAN`, `ADOPTED`, `SUPERSEDED` or `MARKED` |
| lock | the `godwit-lock` document; one holder at a time, leased, renewed by a heartbeat |
| holder | `GodwitConfig.holder`, `<hostname>/<pid>` by default |
| fast path | nothing due: one history read, no lock |
| out of order | a pending once-only migration listed before an applied once-only migration |
| unknown applied | an `APPLIED` history id that the list does not know |
| adoption | adopting a database migrated by another tool, or by hand, through `GodwitConfig.adoptApplied` |
| squash | a superseding migration declared with `supersedes = listOf(...)` |
| untracked database | collections present, no godwit history, nothing adopted |
| target | how far `migrate` goes: `Target.Latest`, or `Target.Before(id)` / `Target.Through(id)` in tests |
| report | the `MigrationReport` a `migrate` call returns; one `MigrationOutcome` per migration it ran or recorded |

## See also

- [README](../README.md): installation and the quick start.
- [Declaring migrations](declaring-migrations.md): the factories, the step shapes, ids, files, the list.
- [Dependencies](dependencies.md): services as function parameters.
- [Ordering and validation](ordering-and-validation.md): run order, the list checks, out-of-order and unknown ids.
- [Libraries and modules](libraries-and-modules.md): why libraries ship setup functions, not migrations.
- [Transactions and sessions](transactions-and-sessions.md): `inTransaction`, `session`, retries, limits.
- [Outside-transaction steps](outside-transaction-steps.md): idempotent DDL and external calls.
- [Batched backfills](batched-backfills.md): `inBatches`, checkpoints, resuming.
- [Repeatable migrations](repeatable-migrations.md): `everyStart` and `repeatable`.
- [Locking](locking.md): the lease lock, waiting, the fast path.
- [History and reports](history-and-reports.md): the history document, reports, `status()`, logs.
- [Failure and recovery](failure-and-recovery.md): every failure and what the next start does.
- [Adopting an existing database](adopting-an-existing-database.md): `adoptApplied` and the untracked-database guard.
- [Squashing migrations](squashing-migrations.md): `supersedes`.
- [Testing](testing.md): godwit-test.
- [Configuration](configuration.md): `GodwitConfig` and `LockConfig`.
- [Architecture](architecture.md): the runner's internals.
- [Design decisions](design-decisions.md): every decision and its reasoning.
