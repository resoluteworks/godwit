# Failure and recovery

godwit rolls forward only. When a migration fails, the `migrate` call stops at that migration, keeps everything that
committed before it, records the failure in history and throws. The next start retries the failed migration, outside
step first. There are no down migrations and no automatic rollback; a fix is new code, and `markApplied` is the one
audited way to skip a migration. This page lists what every kind of failure leaves behind, then walks through each one
with the state before and after, what the next start does, and what you do.

## Contents

- [Using it](#using-it)
- [Failure semantics](#failure-semantics)
- Worked examples:
  [outside step throws](#outside-step-throws) ·
  [process killed mid outside step](#process-killed-mid-outside-step) ·
  [process killed mid transaction](#process-killed-mid-transaction) ·
  [transaction body throws](#transaction-body-throws) ·
  [transient error retried](#transient-error-retried) ·
  [transaction past 60 s](#transaction-past-60-s) ·
  [DDL in a transaction](#ddl-in-a-transaction) ·
  [session from another MongoClient](#session-from-another-mongoclient) ·
  [`inBatches` page k fails](#inbatches-page-k-fails) ·
  [killed after the flip, before the release](#killed-after-the-flip-before-the-release) ·
  [lock lost](#lock-lost) ·
  [lock wait timeout](#lock-wait-timeout) ·
  [history write fails](#history-write-fails) ·
  [standalone server](#standalone-server-with-transactional-work-due) ·
  [out of order](#out-of-order) ·
  [partial supersede](#partial-supersede) ·
  [untracked database](#untracked-database) ·
  [invalid declarations](#invalid-declarations)
- [Manual repair with `markApplied`](#manual-repair-with-markapplied)
- [Fixing forward](#fixing-forward)
- [Edge cases](#edge-cases)
- [Design decisions](#design-decisions)
- [See also](#see-also)

## Using it

A failed migration must stop the start: the code that follows expects the schema the migration was about to produce.
Letting the exception propagate out of `main` does that. The shop logs what happened first, then exits non-zero so the
orchestrator restarts the process, and the restart retries the migration:

```kotlin
/**
 * The shop's start with the failure spelled out: a failed migration stops the start before the HTTP server, logs
 * what ran before it, and exits non-zero so the orchestrator restarts the process, which retries the migration.
 */
fun main() {
    val config = loadShopConfig()
    MongoClient.create(config.mongo.uri).use { client ->
        val database = client.getDatabase(config.mongo.database)
        val customers = CustomerService(database)
        val orders = OrderService(database)
        val identity = HttpIdentityProvider(config.identity.baseUrl, config.identity.apiKey)

        try {
            Godwit(client, config.mongo.database).migrate(shopMigrations(config, customers, identity))
        } catch (e: MigrationFailedException) {
            log.error("Migration {} failed in {}; this start ran {} first", e.id, e.step, e.report.ran.map { it.id }, e)
            exitProcess(1)
        }

        startHttpServer(customers, orders)
    }
}
```

`MigrationFailedException` carries the failed migration's `id`, the `step` that failed, the `report` of what this call
did before it, and the step's exception as its cause. Its message adds guidance for causes godwit recognises (DDL in a
transaction, a transaction past its lifetime or too large, a session from another client). Every other godwit
exception (`LockTimeoutException`, `LockLostException`, `PlanConflictException`, ...) also means "do not start"; they
all extend `GodwitException`.

What the stance means in practice:

- **A failure never undoes committed work.** Migrations applied earlier in the same call stay applied. Pages of an
  `inBatches` step committed before the failure stay committed. Writes of an outside step stay (they were never in a
  transaction).
- **A transactional step's work commits with its history record, or not at all.** After a failure in an
  `inTransaction` step, the database holds none of that step's writes.
- **The next start retries.** The failed migration runs again from its outside step, which is why outside steps must
  be idempotent ([outside-transaction-steps.md](outside-transaction-steps.md)). godwit never skips a migration on its
  own and never gives up after a number of attempts.
- **A fix is code.** Correct the failed migration, or add a migration that repairs what an applied one did
  ([Fixing forward](#fixing-forward)). `markApplied` records a migration as applied without running it,
  with a reason ([Manual repair](#manual-repair-with-markapplied)).

## Failure semantics

| Situation | History afterwards | This `migrate` call | The next start |
|---|---|---|---|
| Outside step throws | `FAILED`, `lastError` (step `OUTSIDE_TRANSACTION`); the step's own writes stay | `MigrationFailedException`; later migrations do not run; lock released | Runs the outside step again from the start |
| Process killed mid outside step | `RUNNING`; partial DDL stays | (process gone) | After the lease ends: `WARN Resuming interrupted migration`, outside step again |
| Process killed mid transaction | `RUNNING`; the server aborts the open transaction within 60 s, nothing of it stays | (process gone) | As above; the transaction runs from scratch |
| Transaction body throws | The step's writes and the `APPLIED` record roll back; `FAILED` and `lastError` written after the abort | `MigrationFailedException` | Outside step again, then the transaction |
| Transient error (`WriteConflict`, election) | `transactionRetries` counts it once the migration applies | The driver re-runs the body, counters reset, `WARN Retrying transaction`, for up to 120 s | Nothing to retry |
| Commit result unknown (network blip at commit) | `APPLIED` if the commit applied, else as for a body that throws | The driver retries the commit only. When it gives up although the commit applied while the lock is still held (a client-side timeout), godwit reads the document, finds it `APPLIED` by this run and reports the migration as applied. When its retries run out of the 120 s window, the lock's deadline has normally passed too: `LockLostException`, with the commit's error as its cause | Nothing to retry, or the migration again |
| Transaction past the 60 s lifetime | `FAILED`, guidance in the message | The driver retries until its 120 s window ends, then `MigrationFailedException` | Fails the same way until the code changes |
| Transaction too large (`TransactionTooLargeForCache`, 388) | `FAILED`, same guidance | Not retried by the driver | Same until the code changes |
| DDL in a transaction (263, or 72 for an index build) | `FAILED`, guidance | Not retried | Same until the code changes |
| Session from another `MongoClient` | `FAILED`, guidance | `MigrationFailedException` | Same until the wiring changes |
| `inBatches` page k fails | Pages before k and their checkpoint committed; `FAILED` | `MigrationFailedException` | Outside step again, then pages from the checkpoint |
| Killed after the `APPLIED` commit, before the release | `APPLIED` | (process gone) | Fast path if nothing else is due; otherwise waits up to one lease for the lock |
| Lock lost mid-run | `RUNNING` stays (a run that lost the lock writes nothing more); committed pages stay. When a step error coincides with the loss and no other run has taken over, `FAILED` with that error | `LockLostException`, with the step's error as its cause when there is one | The process that takes the lock resumes the migration |
| Lock wait timeout | Nothing | `LockTimeoutException` | Waits again |
| History write fails | Depends on the write ([below](#history-write-fails)) | The driver's exception, unchanged | Retries |
| Standalone server, transactional step due | Nothing | `TransactionsUnsupportedException`, before the lock | Same until the server is a replica set |
| Out of order under `OutOfOrder.FAIL` | Nothing | `PlanConflictException` | Same until resolved |
| An adoption gap under `OutOfOrder.FAIL` | The `ADOPTED` documents | `PlanConflictException`, under the lock, after the hook ran | Calls the hook again, records only what is new, and refuses while the gap remains |
| Partially superseded squash | Nothing | `PlanConflictException` | Same until the previous release is deployed |
| Unknown applied ids under `UnknownApplied.FAIL` | Nothing | `PlanConflictException` | Same |
| Untracked database | Nothing (the lock document is written) | `UntrackedDatabaseException` | Same until resolved |
| Invalid declarations | Nothing; no I/O at all | `InvalidMigrationsException` | Same until the code changes |
| The `adoptApplied` hook throws | Nothing | The hook's exception, unchanged | Calls the hook again |

## Worked examples

### Outside step throws

`001-initial-setup` waits for the product search index with `searchIndexWait = 5.minutes`. On a new Atlas cluster under
load, the index is still building after 5 minutes.

**Before.** A new, empty database.

**What happens.** The outside step creates `customers`, `orders` and `products` and their indexes, requests the
`product-search` index, and polls it. After 5 minutes `ensureSearchIndex` throws `SearchIndexNotReadyException`. godwit
records the failure, releases the lock and throws:

```text
ERROR godwit - Migration failed id=001-initial-setup step=OUTSIDE_TRANSACTION attempts=1 error=godwit.core.SearchIndexNotReadyException: Search index product-search on products was not queryable after 5m
```

```text
godwit.core.MigrationFailedException: Migration 001-initial-setup failed in OUTSIDE_TRANSACTION: Search index product-search on products was not queryable after 5m
```

**After.** The three collections, their indexes and the (still building) search index exist. Nothing else ran.

```json
{
  "_id": "001-initial-setup",
  "kind": "ONCE",
  "steps": ["OUTSIDE_TRANSACTION"],
  "state": "FAILED",
  "origin": "RAN",
  "attempts": 1,
  "durationMs": 300412,
  "lastError": {
    "type": "godwit.core.SearchIndexNotReadyException",
    "message": "Search index product-search on products was not queryable after 5m",
    "stack": "godwit.core.SearchIndexNotReadyException: Search index product-search on products was not queryable after 5m\n\tat ...",
    "step": "OUTSIDE_TRANSACTION",
    "at": { "$date": "2026-10-02T10:19:00.652Z" }
  },
  "startedAt": { "$date": "2026-10-02T10:14:00.240Z" },
  "finishedAt": { "$date": "2026-10-02T10:19:00.652Z" },
  "holder": "shop-7f9c4/1",
  "owner": "5b0f3c6e-2a41-4f7e-9d1c-0e8a7b6c5d4f",
  "runId": "0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f",
  "godwitVersion": "0.1.0",
  "v": 1
}
```

**Next start.** `001` is due. The document goes to `RUNNING` with `attempts: 2` (keeping `lastError` until it applies).
The outside step runs from the top: `ensureCollection` returns false for the three existing collections, the identical
`createIndex` calls are no-ops, `ensureSearchIndex` finds `product-search` and only polls it. The index finished
building in the meantime, so the step returns and `001` applies with `attempts: 2`. Then `002` onwards run.

**What you do.** Usually nothing: the restart retries. If it keeps failing, the wait is too short for the cluster: raise
`SEARCH_INDEX_WAIT_SECONDS`, or unset it so `001` does not wait (the app then serves product search once the index is
ready).

### Process killed mid outside step

The pod running `001-initial-setup` is killed (its node is drained) after creating `customers` and `orders` and before
creating `products`.

**Before.** A new database. **What happens.** Nothing catches a kill: the process is gone mid-step.

**After.** `customers` and `orders` exist with their indexes; `products` does not. History:

```json
{
  "_id": "001-initial-setup",
  "kind": "ONCE",
  "steps": ["OUTSIDE_TRANSACTION"],
  "state": "RUNNING",
  "origin": "RAN",
  "attempts": 1,
  "startedAt": { "$date": "2026-10-02T10:14:00.240Z" },
  "holder": "shop-7f9c4/1",
  "owner": "5b0f3c6e-2a41-4f7e-9d1c-0e8a7b6c5d4f",
  "runId": "0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f",
  "godwitVersion": "0.1.0",
  "v": 1
}
```

The lock document still names `shop-7f9c4/1`, with a lease that ends at most 60 s after its last renewal.

**Next start.** It finds `001` due and the lock held. It waits for the lease to end ([locking.md](locking.md#a-crashed-holder)),
acquires the lock, and logs:

```text
WARN  godwit - Resuming interrupted migration id=001-initial-setup attempts=2
```

The outside step runs again from the top. The steps already done are no-ops; `products`, its index and the search index
are created. `001` applies with `attempts: 2`.

**What you do.** Nothing, provided the outside step is idempotent. A step that is not (a raw `createCollection`, which
fails with `NamespaceExists` (48) when the collection exists, before MongoDB 7.0 or from 7.0 when the options differ,
or a `dropIndex`, before MongoDB 8.3) fails here on every retry. Use
`ensureCollection`, `dropIndexIfExists` and identical `createIndex` calls
([outside-transaction-steps.md](outside-transaction-steps.md)).

### Process killed mid transaction

The pod is killed while `004-order-status`'s transaction is open: both `updateMany` calls have run, the commit has not.

**After.** The server still holds the open transaction; it aborts it when the transaction's 60 s lifetime ends. Its
writes never become visible. `004` is `RUNNING`, exactly as in the previous example. App writes to the orders the
transaction touched wait for the abort (up to 60 s), or conflict and are retried by the app's own transaction logic.

**Next start.** After the lease ends: `WARN Resuming interrupted migration id=004-order-status attempts=2`. The
transaction runs from scratch, sees the orders without `status` again, and commits with the `APPLIED` record.

**What you do.** Nothing. If the kill was a startup probe giving up on a long migration, size the probe
([locking.md](locking.md#edge-cases)).

### Transaction body throws

`009-order-payment-status` fetches the gateway status of every order with a payment in its outside step, then writes
them in its transaction. This version reads the orders again inside the transaction and looks each one up in the
prepared map:

```kotlin
/**
 * Fails when an order gets a payment between the outside step and the transaction: the transaction reads it again,
 * and its status is not in [statuses].
 */
fun orderPaymentStatusFragile(gateway: PaymentGateway): Migration = migration("009-order-payment-status")
    .outsideTransaction {
        collection("orders")
            .find(and(ne("paymentId", null), exists("paymentStatus", false)))
            .map { order ->
                checkLock()
                order.getObjectId("_id") to gateway.paymentStatus(order.getString("paymentId"))
            }
            .toList()
            .toMap()
    }
    .inTransaction { statuses ->
        val orders = collection("orders")
        orders.find(session, and(ne("paymentId", null), exists("paymentStatus", false))).toList().forEach { order ->
            val status = statuses.getValue(order.getObjectId("_id"))
            orders.updateOne(session, eq("_id", order["_id"]), set("paymentStatus", status.name))
        }
        count("ordersUpdated", statuses.size)
    }
```

The outside step makes one read-only gateway call per order, which is safe to repeat on a retry.

**Before.** `001` to `008` applied. 41,000 orders with a payment; the outside step takes 7 minutes.

**What happens.** During those 7 minutes customers keep paying. In the transaction, an order paid after the outside
step read the list has no entry in `statuses`, and `getValue` throws. The transaction aborts: none of the
`paymentStatus` writes stay.

```text
godwit.core.MigrationFailedException: Migration 009-order-payment-status failed in IN_TRANSACTION: Key 66fe2b7c9b1e8a0012a4c3d7 is missing in the map.
```

**After.** `009` is `FAILED` with `lastError.type` `java.util.NoSuchElementException` and `lastError.step`
`IN_TRANSACTION`. No order has `paymentStatus`. The 41,000 gateway calls happened; they were reads.

**Next start.** The outside step runs again (7 more minutes of gateway calls) and produces a fresh map, then the
transaction runs. On a quiet database it succeeds. On a busy one, another order is paid during the 7 minutes and it fails
again, on every start.

**What you do.** Fix the code. `009` has not applied on production, so its code can change; on databases where it did
apply (a staging database with no traffic), it never runs again, and the fixed version produces the same end state. The
fix is the shop's `009` from [dependencies](dependencies.md#adding-a-migration-that-needs-a-new-service): its
transaction writes exactly what the outside step fetched, and orders paid later get their status from this release's
app code:

```kotlin
/**
 * Stores the payment gateway's status on every order that has a payment. The gateway calls run outside any
 * transaction, one per order; they only read, so a retry repeats them harmlessly. The statuses reach the transaction
 * as the outside step's value.
 */
fun orderPaymentStatus(gateway: PaymentGateway): Migration = migration("009-order-payment-status")
    .outsideTransaction {
        collection("orders")
            .find(and(ne("paymentId", null), exists("paymentStatus", false)))
            .map { order ->
                checkLock()
                order.getObjectId("_id") to gateway.paymentStatus(order.getString("paymentId"))
            }
            .toList()
            .toMap()
    }
    .inTransaction { statuses ->
        val orders = collection("orders")
        statuses.forEach { (orderId, status) ->
            orders.updateOne(session, eq("_id", orderId), set("paymentStatus", status.name))
        }
        count("ordersUpdated", statuses.size)
    }
```

The rule behind the fix: a transaction must not assume the database still looks the way the outside step saw it. Work
from the prepared value, or re-check inside the transaction and skip what does not match.

### Transient error retried

`004-order-status` updates 1237 orders while the shop's checkout updates one of them in its own transaction.

**What happens.** The server aborts `004`'s transaction with `WriteConflict` (112), labelled `TransientTransactionError`.
The driver aborts and runs the whole body again in a new transaction. godwit resets the step's counters for the new
attempt, increments `TransactionScope.attempt`, and logs:

```text
WARN  godwit - Retrying transaction id=004-order-status attempt=2 error=WriteConflict (112)
INFO  godwit - Applied migration id=004-order-status kind=ONCE steps=[IN_TRANSACTION] attempts=1 txRetries=1 batches=0 durationMs=131 ordersPaid=1200 ordersPending=37
```

**After.** `004` is `APPLIED` with `transactionRetries: 1` and the counts of the attempt that committed. `attempts` is
still 1: a driver retry is part of the same run.

**What you do.** Nothing. The driver retries transient errors (write conflicts, a primary election, an aborted
transaction) for up to 120 s. Driver 5.7.0 starts the next attempt at once, so godwit pauses before each attempt after
the first (5 ms, growing by half each time to at most 500 ms, with jitter) and logs `Retrying transaction` for the first
retry and then at most every 10 s; `transactionRetries` counts every retry. A migration that conflicts with heavy app
traffic on the same documents can still spend that window retrying; move such work to `inBatches` (smaller
transactions conflict less) or run it at a quiet time. A network blip during the commit itself
(`UnknownTransactionCommitResult`) makes the driver retry only the commit, not the body.

### Transaction past 60 s

`007-customer-email-lower` was written as one transaction over every customer:

```kotlin
/** Every customer in one transaction: fine on a test database, past the 60 s transaction lifetime on production. */
val customerEmailLowerInOneTransaction = migration("007-customer-email-lower")
    .inTransaction {
        val result = collection("customers").updateMany(
            session,
            exists("emailLower", false),
            listOf(Aggregates.set(Field("emailLower", Document("\$toLower", "\$email"))))
        )
        count("customersUpdated", result.modifiedCount)
    }
```

**Before.** It applied in tests and on staging (40,000 customers, 3 s). Production has 2.4 million customers.

**What happens.** The `updateMany` runs past the server's `transactionLifetimeLimitSeconds` (60 s). The server aborts the
transaction; the next operation fails with `NoSuchTransaction` (251), labelled transient, so the driver runs the body
again, which runs out of time too. When the driver's 120 s window ends, godwit fails the migration and adds guidance:

```text
WARN  godwit - Slow transaction id=007-customer-email-lower attempt=1 durationMs=60117
WARN  godwit - Retrying transaction id=007-customer-email-lower attempt=2 error=NoSuchTransaction (251)
WARN  godwit - Slow transaction id=007-customer-email-lower attempt=2 durationMs=60094
ERROR godwit - Migration failed id=007-customer-email-lower step=IN_TRANSACTION attempts=1 error=com.mongodb.MongoCommandException: Command execution failed on MongoDB server with error 251 (NoSuchTransaction): ...
```

```text
godwit.core.MigrationFailedException: Migration 007-customer-email-lower failed in IN_TRANSACTION: Command execution failed on MongoDB server with error 251 (NoSuchTransaction): 'Transaction with { txnNumber: 3 } has been aborted.' on server ...
The transaction ran past the server's transaction lifetime (transactionLifetimeLimitSeconds, 60 s by default). Process the documents with inBatches, or move work that needs no atomicity to outsideTransaction.
```

**After.** `007` is `FAILED`. No customer has `emailLower`: both attempts rolled back. Every start fails the same way, about
two minutes in.

**What you do.** Change `007`'s code. It applied on test and staging databases, where it never runs again; on
production it has not applied, so the new code is what runs there. Both versions leave every customer with `emailLower`,
so every database ends in the same state. Either page through the customers, one transaction per 1000, as the shop's
`007` in [batched backfills](batched-backfills.md#data-then-schema-two-migrations) does:

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

or, since setting `emailLower` needs no atomicity with the history record, run one server-side update outside any
transaction, safe to repeat because of its filter. It is correct only when every email is ASCII, because `$toLower`
lowercases ASCII letters only:

```kotlin
/**
 * No atomicity needed: one server-side update, idempotent through its filter, safe to run again after a crash.
 * Correct only when every email is ASCII, because the server's `$toLower` lowercases ASCII letters only.
 */
val customerEmailLowerOutside = migration("007-customer-email-lower")
    .outsideTransaction {
        val result = collection("customers").updateMany(
            exists("emailLower", false),
            listOf(Aggregates.set(Field("emailLower", Document("\$toLower", "\$email"))))
        )
        count("customersUpdated", result.modifiedCount)
    }
```

[batched-backfills.md](batched-backfills.md) compares the two. A transaction too large for the storage engine's cache
fails with `TransactionTooLargeForCache` (388), which the driver does not retry; godwit adds the same guidance and the
fix is the same.

### DDL in a transaction

`008-customer-email-lower-index` was written with the index build inside the transaction. It compiles, because the
driver's `createIndex` accepts a session:

```kotlin
/** Compiles, and fails at runtime: an index on an existing collection cannot be built inside a transaction. */
val customerEmailLowerIndexInTransaction = migration("008-customer-email-lower-index")
    .inTransaction {
        collection("customers").createIndex(
            session,
            ascending("emailLower"),
            IndexOptions().unique(true).partialFilterExpression(exists("emailLower", true))
        )
    }
```

**What happens.** godwit's transactions read with snapshot read concern, and the server refuses `createIndexes` in a
transaction with that read concern, on any collection (an index build in a transaction needs read concern `local`, and
fails on an existing collection even then). The error is not transient, so there is no retry:

```text
godwit.core.MigrationFailedException: Migration 008-customer-email-lower-index failed in IN_TRANSACTION: Command execution failed on MongoDB server with error 72 (InvalidOptions): 'Command createIndexes does not support this transaction's { readConcern: { level: "snapshot", afterClusterTime: Timestamp(1790842445, 7), provenance: "clientSupplied" } } :: caused by :: read concern not supported' on server ...
DDL cannot run in a transaction: index builds on existing collections, drop, dropIndexes, renameCollection and collMod belong in outsideTransaction.
```

**After.** `008` is `FAILED`; no index. It fails on every database, including the empty test database (`001` creates
`customers`), so the shop's "every migration applies to an empty database" test fails before this reaches a deploy
([testing.md](testing.md)).

**What you do.** Move the index to the outside step. `008` has applied nowhere (it cannot), so change it in place. This
is the shop's `008` from [batched backfills](batched-backfills.md#data-then-schema-two-migrations):

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

godwit's own DDL helpers (`ensureCollection`, `ensureSearchIndex`, `dropIndexIfExists`) exist only in the outside step's
scope, so calling them inside `inTransaction` does not compile. Raw driver calls cannot be stopped at compile time
([declaring-migrations.md](declaring-migrations.md)).

### Session from another MongoClient

The shop is wired with two clients: one for its services and one for godwit.

```kotlin
/** Wrong: godwit opens its sessions on migrationClient, and CustomerService runs on appClient. */
fun startWithTwoClients() {
    val config = loadShopConfig()
    val appClient = MongoClient.create(config.mongo.uri)
    val migrationClient = MongoClient.create(config.mongo.uri)
    val customers = CustomerService(appClient.getDatabase(config.mongo.database))
    val identity = HttpIdentityProvider(config.identity.baseUrl, config.identity.apiKey)

    Godwit(migrationClient, config.mongo.database).migrate(shopMigrations(config, customers, identity))
}
```

**What happens.** On a new database, `001` to `004` and `006` use only their scope's `collection(...)`, which comes from
godwit's client, so they apply. `005` has no unlinked customers on a new database, so its transaction calls no service
and applies too. `bootstrap-customers` passes `session` to `CustomerService.ensureCustomer` for each configured seed
customer, and that service's collection belongs to `appClient`; the driver refuses a session from another client:

```text
godwit.core.MigrationFailedException: Migration bootstrap-customers failed in IN_TRANSACTION: state should be: ClientSession from same MongoClient
The step passed godwit's session to an operation on another MongoClient. Build Godwit and the services the migrations call from the same MongoClient.
```

On an existing database with unlinked customers, `005` fails the same way first.

**After.** The failed migration is `FAILED`; earlier ones stay applied. Every start fails the same way.

**What you do.** One client for both, as the shop's `main` in [dependencies](dependencies.md#wiring-at-startup) does:
`Godwit(client, ...)` and `CustomerService(client.getDatabase(...))` from the same `MongoClient`. A test that builds the
services from the `database` of the `TestGodwit` it migrates with catches this ([testing.md](testing.md)).

### `inBatches` page k fails

`006-order-totals` computes each order's total from its lines. One imported order has a line without `unitPriceMinor`.

**Before.** `001` to `005` applied; 1.2 million orders without `totalMinor`. The bad order is in page 41.

**What happens.** Pages 1 to 40 commit, each with its checkpoint. In page 41, `totalOf` throws a
`NullPointerException`. Page 41's transaction rolls back; godwit records the failure:

```text
DEBUG godwit - Committed batch id=006-order-totals batch=40 lastId=66fcf2a19b1e8a0012a1c0d4
ERROR godwit - Migration failed id=006-order-totals step=IN_BATCHES attempts=1 error=java.lang.NullPointerException: Cannot invoke "java.lang.Long.longValue()" because the return value of "org.bson.Document.getLong(Object)" is null
```

**After.** 20,000 orders have `totalMinor`; the rest do not. History keeps the checkpoint:

```json
{
  "_id": "006-order-totals",
  "kind": "ONCE",
  "steps": ["OUTSIDE_TRANSACTION", "IN_BATCHES"],
  "state": "FAILED",
  "origin": "RAN",
  "attempts": 1,
  "durationMs": 2710,
  "checkpoint": {
    "lastId": { "$oid": "66fcf2a19b1e8a0012a1c0d4" },
    "batches": 40,
    "counts": { "ordersUpdated": 20000 }
  },
  "lastError": {
    "type": "java.lang.NullPointerException",
    "message": "Cannot invoke \"java.lang.Long.longValue()\" because the return value of \"org.bson.Document.getLong(Object)\" is null",
    "stack": "java.lang.NullPointerException: Cannot invoke ...\n\tat com.example.shop.migrations._006_order_totalsKt.totalOf(006-order-totals.kt:35)\n\t...",
    "step": "IN_BATCHES",
    "at": { "$date": "2026-10-02T10:14:03.012Z" }
  },
  "startedAt": { "$date": "2026-10-02T10:14:00.231Z" },
  "finishedAt": { "$date": "2026-10-02T10:14:03.012Z" },
  "holder": "shop-7f9c4/1",
  "owner": "5b0f3c6e-2a41-4f7e-9d1c-0e8a7b6c5d4f",
  "runId": "0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f",
  "godwitVersion": "0.1.0",
  "v": 1
}
```

**Next start.** The outside step runs again (its `createIndex` is a no-op), then paging resumes after `lastId`: page 41
again, which fails again, until the data or the code changes. Pages 1 to 40 are never redone.

**What you do.** Find the order, starting from the checkpoint:

```javascript
db.orders.find({
  _id: { $gt: ObjectId("66fcf2a19b1e8a0012a1c0d4") },
  totalMinor: { $exists: false },
  lines: { $elemMatch: { unitPriceMinor: { $exists: false } } }
}).sort({ _id: 1 }).limit(1)
```

Then either repair the order (set the line's price from the product, through the app or a reviewed shell update), or
change `totalOf` to handle a missing price. Changing `006`'s code affects only databases where it has not applied; on
those where it applied, every order already had prices. On the next start, page 41 commits and the run continues; the
final outcome reports `attempts: 2` and `batches` counted over both runs.

### Killed after the flip, before the release

The pod running the shop's migrations is killed right after `004-order-status`'s transaction committed, before it
started `005` and before it released the lock.

**After.** `004` is `APPLIED`: its data and its history record committed together. If the process died while the commit
reply was in flight, history still answers "did it commit?": `APPLIED` means yes. `005` has no document. The lock
document names the dead process until its lease ends.

**Next start.** `005` is due, so it waits up to one lease (60 s) for the lock, then runs `005` and the rest. `004` never
runs again. If nothing had been due (the killed run had finished every migration, and the list had no every-start
migration), the next start would take the fast path and start at once, ignoring the stale lock.

**What you do.** Nothing.

### Lock lost

A network partition cuts the holder off during `006-order-totals` for longer than the lease
([locking.md](locking.md#losing-the-lock-mid-run) has the full timeline).

**What happens.** The holder's renewals fail, each logging `WARN Lock renewal failed`; its local deadline passes; its
next lock check throws. The open page's transaction aborts. The run writes nothing more to history and throws:

```text
WARN  godwit - Lost migration lock runId=0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f holder=shop-7f9c4/1 reason=DEADLINE_PASSED
ERROR godwit - Migration failed id=006-order-totals step=IN_BATCHES attempts=1 error=godwit.core.LockLostException: Lost the migration lock while running 006-order-totals
WARN  godwit - Lock release failed runId=0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f holder=shop-7f9c4/1 error=com.mongodb.MongoOperationTimeoutException: Timed out while waiting for a server that matches WritableServerSelector...
```

```text
godwit.core.LockLostException: Lost the migration lock while running 006-order-totals
```

The release in `finally` cannot reach the database either; the lease ends on its own.

**After.** `006` is `RUNNING` with the checkpoint of the last committed page, owned by the lost run's token.

**Next start.** Another process acquires the lock after the lease ends, logs `Resuming interrupted migration`, writes
its own `RUNNING` marker (new owner token, `attempts: 2`), re-runs the outside step and continues after the checkpoint.
If the lost run's last commit was still in flight, the owner fence makes it either land before the takeover (and the
new run resumes after it) or fail; no page commits twice.

**What you do.** Nothing, unless lock losses repeat: then the network or the replica set is the problem, or the lease is
too short for the deployment's failovers ([locking.md](locking.md#edge-cases)). `Lock renewal failed` lines without a
`Lost migration lock` mean the run kept the lock through a short outage; they are worth an alert when they repeat.

**A step error at the same moment.** A stop-the-world pause of 70 s freezes the holder while `005-customer-external-ids`
calls the identity provider. When the process resumes, the HTTP call fails with its own timeout, and the lock is lost:
its local deadline passed during the pause. No other process was waiting, so nobody has taken `005` over. godwit sends
the `FAILED` write, fenced on its owner token and on `RUNNING`; it matches, so `lastError` records the HTTP error, and
`migrate` throws `LockLostException` with that error as its cause:

```text
WARN  godwit - Lost migration lock runId=0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f holder=shop-7f9c4/1 reason=DEADLINE_PASSED
ERROR godwit - Migration failed id=005-customer-external-ids step=OUTSIDE_TRANSACTION attempts=1 error=java.net.http.HttpTimeoutException: request timed out
```

```text
godwit.core.LockLostException: Lost the migration lock while running 005-customer-external-ids
Caused by: java.net.http.HttpTimeoutException: request timed out
```

`005` is `FAILED` with that `lastError`, and the next start retries it, outside step first, keeping `lastError` until
it applies. Had another process taken `005` over before the write, the write would match nothing: history would show
that process's run, and the HTTP error would remain in the exception's cause and the `Migration failed` line.

### Lock wait timeout

`shop-c55d0/1` starts with `LockConfig(waitTimeout = 2.minutes)` while `shop-7f9c4/1` runs `006` for 2.5 minutes.

**What happens.** After 2 minutes of waiting:

```text
godwit.core.LockTimeoutException: Waited 2m 0.214s for the migration lock, held by shop-7f9c4/1
```

**After.** Nothing written: the waiting process never held the lock. **Next start.** The orchestrator restarts it; it
reads history and either waits again or, once `006` applied, finds less to do.

**What you do.** Set `waitTimeout` above the longest migration plus one lease ([locking.md](locking.md#waiting-for-the-lock)).

### History write fails

godwit's own reads and writes of history and the lock propagate the driver's exception unchanged; they are not wrapped
in a `GodwitException`. What is left depends on which write failed:

- **The first history read.** The cluster is unreachable at start. After the driver's server selection timeout (30 s
  by default), `migrate` throws `com.mongodb.MongoTimeoutException: Timed out while waiting for a server that matches
  ReadPreferenceServerSelector{readPreference=primary}...`. Nothing was written. The next start tries again.
- **The `RUNNING` marker.** The migration's document is unchanged (missing, `FAILED` or `RUNNING` as before) and its
  step never ran. The lock is released. The next start runs it.
- **The `APPLIED` record of a migration with only an outside step.** `002-carts` created `carts` and its indexes, then
  the write recording `APPLIED` failed. godwit sends the same fenced write once more, with majority write concern. When
  the first one failed before reaching the server, the second records the migration, and the call goes on. When the
  first one applied and only its reply was lost or timed out, the second matches nothing and is acknowledged once the
  first is majority-committed; godwit then finds the document `APPLIED` by this run, as below. When the second write
  fails too (the same outage, or a majority still behind after its own `timeoutMS`), `migrate` throws the first
  write's exception, with the second's attached as suppressed. The document is then `RUNNING`, and the next start logs
  `Resuming interrupted migration`, re-runs the outside step (all no-ops now) and records it; or it is `APPLIED`, and
  the next start finds it applied.
- **The `FAILED` record after a step failed.** Often the same outage that failed the step. The document stays as the
  step left it, without this run's `lastError`: `RUNNING`, or `APPLIED` when a commit applied and the majority was
  still behind when the `FAILED` write's own `timeoutMS` passed. `migrate` throws `MigrationFailedException` for the
  step's failure, with the history write's exception attached as a suppressed exception. The next start logs
  `Resuming interrupted migration` and retries, or finds the migration applied.
- **The `ADOPTED` records.** On a replica set they are one transaction, and a failure that is not transient leaves
  none of them. On a standalone server the ids written before the failure stay recorded. Either way godwit logs
  `Adopted applied migrations` with the ids recorded (none in the transaction's case), then throws the driver's
  exception. History holds nothing but `ADOPTED` documents, so the next start calls the hook again and records what is
  missing.
- **The release.** The lease ends on its own within 60 s; the next start waits at most that long.

The `APPLIED` record of a transactional step is written inside the transaction: it commits with the step's writes or
aborts with them. The driver can still throw after a commit that applied: the app's client sets `timeoutMS` and the
commit's majority acknowledgement takes longer, or the reply is lost and the driver's commit retries run out. godwit
never trusts the exception alone. Its `FAILED` write matches only a document that is still `RUNNING` with this run's
owner token; when it matches nothing, the server acknowledges it, with majority write concern, once the commit is
majority-committed, and godwit then reads the document on the primary. `APPLIED` with this run's token means the commit
applied: godwit reports the migration as applied and continues, so the next start does not run its transactional step
a second time. Anything else means another run owns the document: godwit writes nothing more and throws
`LockLostException`, with the step's error as its cause. When the lock is already lost as the driver gives up, which
is how commit retries that run for 120 s normally end, godwit throws `LockLostException` with the commit's error as its
cause without reading the document; the next start finds the migration `APPLIED`.

**What you do.** Fix the connectivity or the permissions (a database user without write access to `godwit-history`
fails with `Unauthorized`, 13). godwit retries everything on the next start.

### Standalone server with transactional work due

A developer runs the shop against a local standalone `mongod` (no replica set) on a new database.

**What happens.** Before taking the lock, godwit sees that migrations with transactional steps are due and asks the
server whether it supports transactions (`hello`: a replica set member or a `mongos`). A standalone server does not:

```text
godwit.core.TransactionsUnsupportedException: Migrations [004-order-status, 005-customer-external-ids, 006-order-totals, reference-countries, bootstrap-customers] need transactions, which a standalone mongod does not support. Run a single-node replica set: start mongod with --replSet rs0, then run rs.initiate() once.
```

**After.** Nothing ran, not even the DDL-only `001` to `003`: godwit checks before running anything, so a database is
never left half-migrated by a server that could never finish.

**What you do.** Run a single-node replica set, as
[transactions and sessions](transactions-and-sessions.md#standalone-servers) shows with Docker, and connect with
`MONGO_URI=mongodb://localhost:27017/?directConnection=true`. A standalone server is fine for a list
whose due migrations are all outside-only: they run, because they need no transaction. godwit-test's `testGodwit()`
always starts a single-node replica set ([testing.md](testing.md)).

### Out of order

Two branches: one adds `007-product-slugs`, the other `008-cart-currency`. Staging deploys the second branch first, so
`008` applies on staging. Then `007` merges, and the next release lists `007` before `008`.

**What happens on staging.** History has `008` applied and `007` pending, listed before it. Under the default
`OutOfOrder.FAIL`:

```text
godwit.core.PlanConflictException: Migrations cannot run against this database:
- 007-product-slugs is pending, but 008-cart-currency, listed after it, is applied (out of order; OutOfOrder.RUN runs it)
```

Nothing ran. Production never sees this: there, both are pending and run in list order.

An adoption gap is the same conflict
([adopting-an-existing-database.md](adopting-an-existing-database.md#a-gap-in-the-adopted-ids)). While `adoptApplied` is
configured and history holds nothing but `ADOPTED` documents, godwit checks it under the lock, after the hook has run
and its ids are recorded, not before the lock. A gap that the hook fills, such as the one an interrupted adoption on a
standalone server leaves, is therefore neither refused under `OutOfOrder.FAIL` nor run under `OutOfOrder.RUN`: the
plan that runs is made after the hook has recorded the missing ids.

**What you do.** If `007` has applied nowhere, renumber it `009-product-slugs`; the list is then in order everywhere.
Otherwise, and when `007` does not depend on running before `008` (they touch different collections here), let staging
run it out of order. Configure it per environment:

```kotlin
/** Staging runs feature branches early, so it lets a migration merged later run behind ones already applied. */
fun godwitConfigFor(environment: String): GodwitConfig =
    GodwitConfig(outOfOrder = if (environment == "staging") OutOfOrder.RUN else OutOfOrder.FAIL)
```

Staging then runs `007`, logs `WARN Running out-of-order migration id=007-product-slugs appliedAfter=[008-cart-currency]`
and records `outOfOrder: true`.

[ordering-and-validation.md](ordering-and-validation.md) covers the rules and the trade-off.

### Partial supersede

The shop squashes `001` to `006` into `100-baseline` with `supersedes = listOf("001-initial-setup", ...,
"006-order-totals")`. A demo environment skipped the release that added `005` and `006`.

**What happens on the demo database.** `001` to `004` are applied, `005` and `006` are not. `100-baseline` can neither be
recorded (not every superseded id applied) nor run (its outside step assumes nothing exists):

```text
godwit.core.PlanConflictException: Migrations cannot run against this database:
- 100-baseline supersedes 6 migrations, but only 001-initial-setup, 002-carts, 003-file-store, 004-order-status are applied. Deploy the previous release first.
```

Nothing ran.

**What you do.** Deploy the last release that still lists `005` and `006` to the demo environment (it applies them),
then the squash release (which records `100-baseline` as `SUPERSEDED`). `status()` on each environment before
releasing a squash shows which ones are behind ([squashing-migrations.md](squashing-migrations.md)).

### Untracked database

Someone restores a backup of production taken before the shop used godwit into a new database, `shop-restore`, and
points a shop instance at it without adoption configured.

**What happens.** godwit takes the lock, finds no history, adopts nothing (no `adoptApplied`), and finds collections:

```text
godwit.core.UntrackedDatabaseException: The database has collections [carts, countries, customers, files, orders, products, schema-log] but no godwit history. Configure GodwitConfig.adoptApplied to adopt the migrations already applied, or set UntrackedDatabase.RUN_ALL to run every migration.
```

Running every migration here could damage data: `004` would treat every order as one without a status, and `005` would
call the identity provider for every customer. Nothing ran; only the lock document was written.

**What you do.** This backup has the pre-godwit `schema-log`, so adopt it, as production databases are adopted
([adopting-an-existing-database.md](adopting-an-existing-database.md)):

```kotlin
/** Databases created before godwit recorded their changes in schema-log: adopt them. */
val adoptingConfig = GodwitConfig(adoptApplied = ::appliedBeforeGodwit)
```

Other cases of the same exception:

- The backup was of a database godwit already tracked, but the restore left out `godwit-history`: restore that
  collection too.
- The database's state matches a known point and has no record of it: stop every instance, then `markApplied` each
  applied id with a reason, the last-listed first, as for
  [a database whose history was lost](adopting-an-existing-database.md#history-is-lost-while-the-hook-is-configured).
  The first mark turns the guard off: marked first-listed first, a start between two marks would find a valid prefix
  and run the rest over the live data.
- The database holds only collections every migration is known to handle (an empty copy, a scratch database):

```kotlin
/** Only for a database whose collections every migration is known to handle, such as an empty copy. */
val runAllConfig = GodwitConfig(untrackedDatabase = UntrackedDatabase.RUN_ALL)
```

### Invalid declarations

Two branches each add a migration numbered `007`. The merge compiles; the list contains `007-product-slugs` and
`007-cart-currency`.

**What happens.** `migrate` validates the list before any I/O and throws, listing every problem:

```text
godwit.core.InvalidMigrationsException: Invalid migrations:
- 007-cart-currency is listed after 007-product-slugs, but its numeric prefix 7 is not greater than 7
```

Nothing touched the database. Every process of the release fails the same way.

**What you do.** Renumber one of them, and catch it before it ships: the same check runs in a unit test, without a
database.

```kotlin
"two branches that both add 007 fail validation, before any database is involved" {
    val e = shouldThrow<InvalidMigrationsException> {
        validateMigrations(listOf(carts, productSlugs, cartCurrencyClashing))
    }
    e.problems shouldHaveSize 1
}
```

The shop's own test validates the real list (`validateMigrations(shopMigrations(...))`), which fails the merge's build.
The rules are in [ordering-and-validation.md](ordering-and-validation.md).

## Manual repair with `markApplied`

`markApplied(id, reason)` records a once-only migration as `APPLIED` with origin `MARKED`, without running it. Use it
when the migration's effect is already in the database, or must never be applied to this database, and code cannot
express that. It refuses a repeatable or every-start migration (`IllegalArgumentException`): those are due again on the
next start whatever their history says, so the way past one is code. Marking an id while once-only migrations listed
before it are pending makes those out of order, so mark an id after the migrations before it have applied. To record
several applied ids at once, stop every instance and mark the last-listed first, so that a start between two marks
refuses as out of order instead of running the ids not yet marked
([history-and-reports.md](history-and-reports.md#markappliedid-reason)).

On a `Godwit` built with `adoptApplied`, it also refuses while adoption has not ended: history is empty or holds only
`ADOPTED` documents, so the hook still runs on every start that takes the lock. It throws `IllegalStateException` under
the lock and writes nothing, because a `MARKED` document would end adoption before the hook has recorded every applied
id, and the ids it has not recorded would run over the live data. A repair on such a database, such as recording what
was applied after its history was lost while the old record remains, stops every instance and marks from a `Godwit`
built from the same configuration with `adoptApplied = null` (`config.copy(adoptApplied = null)`), so that the marks
land in the app's own history and lock collections, the last-listed id first
([adopting-an-existing-database.md](adopting-an-existing-database.md#history-is-lost-while-the-hook-is-configured)).

During an incident on 2026-10-05, an operator built `008`'s index on `customers.emailLower` (unique, partial on
`emailLower` existing) by hand, named `emailLower_unique`. The next release ships `008-customer-email-lower-index`,
which creates the same index under the default name. On production it fails:

```text
godwit.core.MigrationFailedException: Migration 008-customer-email-lower-index failed in OUTSIDE_TRANSACTION: Command execution failed on MongoDB server with error 85 (IndexOptionsConflict): 'Index already exists with a different name: emailLower_unique' on server ...
```

The hand-built index is the one `008` would build. Record that, with the reason:

```kotlin
/**
 * An operator's one-off command. Production's customers collection already has a unique index on emailLower, built
 * by hand during an incident under another name, so 008 fails there with IndexOptionsConflict. The hand-made index
 * is the one 008 would build: record 008 as applied instead of running it.
 */
fun markHandBuiltIndex() {
    val config = loadShopConfig()
    MongoClient.create(config.mongo.uri).use { client ->
        Godwit(client, config.mongo.database).markApplied(
            "008-customer-email-lower-index",
            reason = "Unique index on customers.emailLower built by hand as emailLower_unique during the 2026-10-05 incident"
        )
    }
}
```

It waits for the lock, turns the `FAILED` document into `APPLIED`/`MARKED` with the reason (removing `lastError`), and
logs `WARN Marked migration applied`. The next start skips `008`. The resulting document is in
[history-and-reports.md](history-and-reports.md#applied-by-hand-marked).

Use it for:

- a change applied by hand, equivalent to what the migration would do (the case above);
- a migration that must not run on one database, such as a data fix for a problem that database never had.

Do not use it to get past a failing data migration whose result the code needs: the code would then run on data the
migration never fixed. Fix the migration instead. The alternative to `markApplied` in the case above, dropping the hand
index so `008` builds its own, is also correct; it costs a unique index build on a large collection.

## Fixing forward

A migration that applied and did something wrong is fixed by a new migration, never by undoing it.

`004-order-status` marked an order `PAID` when it had `paidAt`, `PENDING` otherwise. Orders paid through the gateway's
asynchronous flow have a `paymentId` but no `paidAt`; `004` marked them `PENDING`. The fix is a migration that corrects
exactly those orders:

```kotlin
/**
 * 004-order-status marked an order PENDING when it had no paidAt. Orders paid through the gateway's asynchronous
 * flow have a paymentId and no paidAt: they are paid. This migration corrects them; 004 stays as it is.
 */
val paidOrdersWithoutPaidAt = migration("022-paid-orders-without-paid-at")
    .inTransaction {
        val result = collection("orders").updateMany(
            session,
            and(eq("status", "PENDING"), ne("paymentId", null), eq("paidAt", null)),
            set("status", "PAID")
        )
        count("ordersPaid", result.modifiedCount)
    }
```

| The migration | Fix |
|---|---|
| Failed everywhere it ran, applied nowhere | Change its code; keep its id |
| Applied on some databases, failing on others | Change its code so it produces the same end state as the version that applied; keep its id. Databases where it applied never run it again |
| Applied, with a wrong result | A new migration that corrects the result, like `022` above |
| Applied, and its whole effect must go | A new migration that removes it (drop the index, unset the field); the old one stays in the list |

Never delete or renumber a migration that has applied anywhere: its id is in that database's history, and a new id is
a new migration that runs again ([history-and-reports.md](history-and-reports.md#edge-cases)).

## Edge cases

**A migration that keeps failing.**
`006` fails on page 41 on every start. `attempts` grows (2, 3, 4, ...), the process crash-loops, and the orchestrator
backs off its restarts. godwit never skips it or gives up after N attempts: a skipped migration would leave the code
running on a schema it does not expect. The fix is the data or the code ([`inBatches` page k fails](#inbatches-page-k-fails)).

**An every-start migration that depends on an external service.**
The identity provider is down. `bootstrap-customers` fails on every start, so no shop process can start, although the
database is up to date. `markApplied` cannot get past it: an every-start migration is due on every start whatever its
document says, and `markApplied` refuses one. An every-start migration makes the app's availability depend on
everything it calls. Keep every-start steps to what must be true for the app to work; consider a
`repeatable(id, revision)` that runs only when its input changes, or moving the work out of the list
([repeatable-migrations.md](repeatable-migrations.md)).

**A repeatable migration fails.**
`reference-countries` at revision `"2026-11-15"` fails. Its document is `FAILED` with `revision: "2026-10-01"`: the stored
revision changes only when a run applies, so the countries in the database match the stored revision. The next start
retries it. Every-start migrations listed after it did not run in that call.

**Migrations that applied before the failure in the same call.**
A deploy adds `007`, `008` and `009`; `009` fails. `007` and `008` stay applied, listed in
`MigrationFailedException.report.ran`. The next start runs only `009`.

**A step that catches its own exceptions.**
An outside step wraps each identity provider call in `try { ... } catch (e: Exception) { }` to "be robust". A call fails,
the step continues, the migration applies, and some customers are never linked. godwit can only record failures it
sees. Let exceptions escape the step; count what was skipped deliberately (`count("customersSkipped", n)`) when skipping
is correct.

**The `adoptApplied` hook throws.**
The hook reads `schema-log` and finds a document without `version`; `getString` returns null and building the set
throws. The exception propagates from `migrate` unchanged, under no migration's name. Nothing was recorded, and history
still holds nothing that adoption did not write, so the next start calls the hook again. Fix the hook or the data
([adopting-an-existing-database.md](adopting-an-existing-database.md)).

**Unknown applied ids under `UnknownApplied.FAIL`.**
A database configured to fail on unknown ids starts an older release after a rollback. `PlanConflictException` lists
the ids the older list does not know; nothing ran. Roll forward again, or use the default `WARN` on databases that can
be rolled back ([history-and-reports.md](history-and-reports.md#edge-cases)).

**The process is stopped (`SIGTERM`) mid-run.**
godwit installs no shutdown hook. The JVM stops whatever the migration was doing when it exits, which is a crash from
godwit's point of view: the migration stays `RUNNING` and the next start resumes it after the lease. Give the process a
termination grace period longer than a typical migration if you want deploys to let a run finish.

**A `FAILED` migration dropped from the list.**
`009` failed; the next release drops it from the list instead of fixing it. Its document is `FAILED`, not `APPLIED`, so it
is not an unknown applied id and nothing warns. It is simply never retried. Remove it deliberately (and keep the id
unused), or fix it.

## Design decisions

### No down or rollback hooks

Chosen: migrations go forward only. A failure leaves committed work in place and retries; a wrong result is corrected
by a new migration; `markApplied` skips a migration on purpose, with a reason.

A down step has to undo a change against data that has moved on. For `004-order-status` it would be (not godwit API,
shown for contrast):

```text
down { collection("orders").updateMany(session, Document(), unset("status")) }
```

That also removes the status of every order placed since `004` applied, which the new release's code wrote itself. A
correct down step would have to tell those orders apart, and it would run for the first time on production, in an
incident, without ever having been tested against real data. For an outside step that called the identity provider,
there is nothing to undo at all.

What covers the needs a down step claims to meet:

| Need | godwit's answer |
|---|---|
| A migration failed halfway | A transactional step left nothing behind; an outside step converges when it runs again; an `inBatches` step resumes |
| A migration applied with a wrong result | A new migration that corrects it ([Fixing forward](#fixing-forward)) |
| A release must be rolled back | Migrations in a rolling deploy only add, so the older release runs on the newer schema; godwit warns about the newer ids and continues |
| A migration must not run on one database | `markApplied`, with a reason |

### Stop at the first failure

Chosen: the call stops at the first failed migration. There is no "continue with the rest". Later migrations were
written against the schema the failed one produces; running them anyway can fail in confusing ways or, worse, succeed
on the wrong data.

### Retry on the next start, not inside `migrate`

Chosen: `migrate` does not retry a failed migration in a loop; the orchestrator's restart does. Transient database
errors are already retried by the driver inside the transaction. What is left (a bug, bad data, an external outage) does
not get better in the next few seconds, and a restart backoff is visible in the orchestrator, while a hidden retry loop
only makes the start slower.

### `FAILED` written outside the transaction, fenced

The transaction rolled back, so the failure record cannot be part of it. godwit writes `FAILED` after the abort, with a
filter on its own owner token and on `state: RUNNING`. The token keeps a run that lost the lock from overwriting the
state written by the run that took over. The state keeps a commit that applied, and whose reply the driver turned into
an exception, from being recorded `FAILED` and run again: the write matches nothing, and godwit reads the document
before it decides.

## See also

- [concepts.md](concepts.md): steps and their guarantees
- [locking.md](locking.md): crashed holders, lost locks, waiting
- [history-and-reports.md](history-and-reports.md): the history documents shown on this page, field by field
- [transactions-and-sessions.md](transactions-and-sessions.md): retries, the 60 s lifetime, DDL and the session rule
- [outside-transaction-steps.md](outside-transaction-steps.md): making outside steps idempotent
- [batched-backfills.md](batched-backfills.md): checkpoints and resuming
- [ordering-and-validation.md](ordering-and-validation.md): out-of-order and invalid lists
- [squashing-migrations.md](squashing-migrations.md): partial supersede
- [adopting-an-existing-database.md](adopting-an-existing-database.md): the untracked-database guard
- [architecture.md](architecture.md): the per-migration state machine
- [testing.md](testing.md): catching these failures before a deploy
- [design-decisions.md](design-decisions.md): every design decision in one index
- [README](../README.md)
