# Transactions and sessions

An `inTransaction` step runs in one MongoDB transaction, and godwit writes the migration's APPLIED history record in
that same transaction. The step's writes and the record become visible together or not at all, so the step's effect
commits exactly once per database, however often the process crashes, the driver retries or another process takes
over. This page covers what that guarantee needs from you: pass `session` to every call, keep the body free of side
effects outside MongoDB, keep each transaction short, and give godwit and your services one `MongoClient`. Each page of
an `inBatches` step is a transaction of the same kind ([batched backfills](batched-backfills.md)).

## A transactional step

`004-order-status` from the shop gives every order a status:

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

What godwit does when it runs it:

1. Records the migration RUNNING in `godwit-history`, outside any transaction, with this run's lock token as `owner`.
2. Runs the outside step, if the migration has one, and keeps the value it returns.
3. Starts a transaction with the driver's `ClientSession.withTransaction`, on a session from the `MongoCluster` passed
   to `Godwit`: snapshot read concern, majority write concern, primary reads.
4. Calls `checkLock()`, then the body, with a new `TransactionScope` (its `session`, its `attempt`, empty counters).
5. Calls `checkLock()` again and sets the history document to APPLIED with the counters, through the same session.
   That update matches only a document that is still RUNNING with this run's `owner`; when it matches nothing, godwit
   aborts the transaction.
6. The driver commits. The orders and the APPLIED record become visible at the same moment.

Until the commit, every other reader sees the old orders and a RUNNING record; after it, the new orders and APPLIED.
When the process dies before the commit, the server discards the transaction, the record stays RUNNING, and the next
start runs the migration again. When it dies after the commit, the next start finds APPLIED and skips it.

## `session`: pass it to every call

`session` is a property of the step's receiver, `TransactionScope`. It is the driver's own
`com.mongodb.kotlin.client.ClientSession`, and every driver operation has an overload that takes it as the first
argument: `find(session, filter)`, `updateMany(session, filter, update)`, `bulkWrite(session, models)`,
`countDocuments(session, filter)`, `aggregate(session, pipeline)`. Only operations that receive it run in the
transaction. `collection(name)` and `database` are plain driver handles and do not carry it.

App services take the session the same way. Every write method of the shop's services takes a `ClientSession` first,
so a step passes its `session` through, as `005-customer-external-ids` does:

```kotlin
package com.example.shop.migrations

import com.example.shop.services.CustomerService
import com.example.shop.services.IdentityProvider
import godwit.core.Migration
import godwit.core.migration

/**
 * Links every customer created before the identity provider integration to an identity provider user.
 *
 * The HTTP calls run outside any transaction, one per customer, and are idempotent: findOrCreateUser matches by
 * email. Their results reach the transaction as the outside step's return value, and CustomerService writes the links
 * with the step's session, so they commit together with the history record.
 */
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

Typed collections work the same way: `database.getCollection("orders", Order::class.java)` uses the codec registry of
the client you gave `Godwit`, and its operations take `session` like any other.

### Without `session`

An operation without `session` runs outside the transaction, as if the step were not there:

| The operation | What happens |
|---|---|
| A write to documents the transaction has not written | Commits at once. It is not rolled back when the step fails, and it runs again on every driver retry and every retry of the migration. |
| A write to a document the transaction already wrote | Waits for the transaction to end, while the transaction waits for the body. Nothing moves until the server aborts the transaction at its 60 s lifetime; the driver runs the body again, which waits again, until the 120 s retry window ends and the migration fails. |
| A read | Sees the latest committed data: not the transaction's own uncommitted writes, and not the transaction's snapshot. |

This compiles and is wrong. It is `009-order-payment-status` (below) with the update written without `session`:

```kotlin
/** Wrong: the update does not pass the session, so it runs outside the transaction. */
fun orderPaymentStatusEscaping(gateway: PaymentGateway): Migration = migration("009-order-payment-status")
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
            orders.updateOne(eq("_id", orderId), set("paymentStatus", status.name)) // no session
        }
        count("ordersUpdated", statuses.size)
    }
```

On a quiet database it seems to work, because each update commits on its own. The migration is no longer atomic: a
crash halfway leaves half the orders updated and the history document RUNNING.

Reads through services escape too. `CustomerService.findByEmail(email)` takes no session, so inside a transactional
step it reads outside the transaction. Give such a read a session parameter, or do it in the outside step and hand the
result to the transaction.

### Catching a forgotten session in tests

A missing `session` compiles. `SessionEscapeDetector` in godwit-test catches it when a test runs the step. It is a
driver `CommandListener`: while a transactional step runs on a thread, every command that thread sends must belong to
the step's transaction, which means it carries the step's session id (`lsid`) and `autocommit: false`, as the driver
sends every command that gets `session`. A command without a session, or with another session (a service that starts
a session and a transaction of its own), makes the detector throw `SessionEscapeError` before the command is sent; the
step fails, and the failure names the command and the collection.

`testGodwit()` installs the detector on the client it returns, so every test that runs a migration through godwit-test
checks it:

```kotlin
import com.example.shop.migrations.orderPaymentStatus
import com.example.shop.services.PaymentGateway
import com.example.shop.services.PaymentStatus
import godwit.core.MigrationFailedException
import godwit.test.SessionEscapeError
import godwit.test.runIsolated
import godwit.test.testGodwit
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.bson.Document

private class FakePaymentGateway(private val status: PaymentStatus) : PaymentGateway {
    override fun paymentStatus(paymentId: String) = status
}

class OrderPaymentStatusTest : StringSpec({
    "009 stores the gateway status of every paid order" {
        val db = testGodwit()
        val orders = db.database.getCollection("orders", Document::class.java)
        orders.insertOne(Document("paymentId", "pay-1"))

        val outcome = db.godwit.runIsolated(orderPaymentStatus(FakePaymentGateway(PaymentStatus.CAPTURED)))

        outcome.count("ordersUpdated") shouldBe 1L
        orders.find().first()["paymentStatus"] shouldBe "CAPTURED"
    }

    "a step that forgets the session fails the test" {
        val db = testGodwit()
        db.database.getCollection("orders", Document::class.java).insertOne(Document("paymentId", "pay-1"))

        val failure = shouldThrow<MigrationFailedException> {
            db.godwit.runIsolated(orderPaymentStatusEscaping(FakePaymentGateway(PaymentStatus.CAPTURED)))
        }
        failure.cause.shouldBeInstanceOf<SessionEscapeError>()
    }
})
```

The second migration fails with:

```text
godwit.core.MigrationFailedException: Migration 009-order-payment-status failed in IN_TRANSACTION: update on orders ran without the step's session, outside the transaction. Pass `session` to the driver call or the service method.
```

A test that uses a client of the app's own, instead of `testGodwit()`, installs the detector on it:

```kotlin
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

The detector sees only the code paths a test runs, and only commands sent from the thread that runs the step. Work a
service hands to another thread is not checked, and it is outside the transaction anyway: a `ClientSession` serves one
thread at a time.

## Retries: the body can run more than once

The driver's `withTransaction` handles two error labels:

| Label | Typical causes | What the driver does |
|---|---|---|
| `TransientTransactionError` | A write conflict with the app's own writes (`WriteConflict`, 112); a primary election; the server aborting the transaction (`NoSuchTransaction`, 251); a lock request timing out | Aborts and runs the whole body again in a new transaction |
| `UnknownTransactionCommitResult` | The connection dropped while committing | Retries the commit only; the body does not run again |

It retries for up to 120 seconds from the first attempt, then rethrows the last error. Any other exception from the body
aborts the transaction and fails the migration at once.

On every new run of the body, godwit:

- pauses first: 5 ms before the second run, growing by half each time to at most 500 ms, with jitter, because driver
  5.7.0 starts the next run at once and a run that conflicts with a document another transaction holds would
  otherwise retry in a tight loop;
- builds a new `TransactionScope` whose `attempt` is one higher (it is 1 on the first run; a commit retry does not
  change it);
- resets the step's counters, so `count(...)` reports the work that committed and nothing from aborted attempts;
- adds one to `transactionRetries`, which history and `MigrationOutcome` report, and logs a WARN for the first retry
  of the transaction and then at most every 10 s. Its `error` is the code name and code of the error the previous
  run's body threw (for an error without a code name, such as a network error, its exception class and code:
  `MongoSocketReadException (-2)`), or `commit` when the body returned and the commit failed with a transient error.

```text
INFO  godwit - Running migration id=004-order-status kind=ONCE steps=[IN_TRANSACTION] attempt=1
WARN  godwit - Retrying transaction id=004-order-status attempt=2 error=WriteConflict (112)
INFO  godwit - Applied migration id=004-order-status kind=ONCE steps=[IN_TRANSACTION] attempts=1 txRetries=1 batches=0 durationMs=131 ordersPaid=1200 ordersPending=37
```

`attempt` on "Running migration" counts runs of the migration (1 unless an earlier run failed); `attempt` on "Retrying
transaction" counts runs of the body within this run. Read `TransactionScope.attempt` in a log line of your own when you
want the second number from inside the step, for example `log.atDebug().addKeyValue("attempt", attempt).log(...)`.

The retry rolls back everything the body did through `session`. Nothing else is rolled back:

- calls to external services (HTTP, queues, email) happen once per attempt;
- changes to variables and collections outside the lambda add up across attempts;
- writes that escaped the session stay.

### External services: the outside step and the hand-off

This compiles and is wrong. It calls the payment gateway once per order inside the transaction:

```kotlin
/** Wrong: an HTTP call per order inside the transaction. */
fun orderPaymentStatusInsideTransaction(gateway: PaymentGateway): Migration =
    migration("009-order-payment-status")
        .inTransaction {
            val orders = collection("orders")
            orders.find(session, and(ne("paymentId", null), exists("paymentStatus", false))).forEach { order ->
                val status = gateway.paymentStatus(order.getString("paymentId")) // HTTP, repeated on every retry
                orders.updateOne(session, eq("_id", order["_id"]), set("paymentStatus", status.name))
            }
        }
```

The transaction stays open for every HTTP round trip, so a few thousand paid orders pass the 60 s lifetime and the
migration can never commit. Every driver retry repeats every call: harmless for `paymentStatus`, which only reads, and
wrong for a call such as `EmailSender.send`, whose effect would repeat.

The right shape puts the calls in the outside step and hands their result to the transaction as the prepared value.
This is the shop's `009`, as [dependencies](dependencies.md#adding-a-migration-that-needs-a-new-service) adds it:

```kotlin
package com.example.shop.migrations

import com.example.shop.services.PaymentGateway
import com.mongodb.client.model.Filters.and
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.Filters.ne
import com.mongodb.client.model.Updates.set
import godwit.core.Migration
import godwit.core.migration

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

The outside step returns a `Map<ObjectId, PaymentStatus>`, and the compiler types the `inTransaction` parameter
`statuses` from it. A driver retry reruns only the transaction, with the same map, so the gateway is not called again.
Every run of the body gets that same instance, so the prepared value must be fully materialised and the body must not
change it: `.toList()` the cursor in the outside step (a cursor or a lazy `Sequence` handed over would be read once and
then be empty, or repeat its calls inside the transaction), and never remove entries from the map in the body. A
failed migration is different: the next start runs the outside step again and hands the transaction a new value;
godwit never stores the value. The outside step runs at least once, so its calls must be safe to repeat
([outside-transaction steps](outside-transaction-steps.md)).

## Transaction lifetime: 60 seconds

The server aborts any transaction open longer than `transactionLifetimeLimitSeconds`, 60 by default. The body's next
operation then fails with `NoSuchTransaction` (251), which is labelled transient, so the driver runs the body again;
that attempt runs out of time as well, and the driver gives up when its 120 s window ends. godwit fails the migration
with `MigrationFailedException`, and the message says the transaction passed its lifetime and that the work belongs in
`inBatches` or an outside step. The migration fails the same way on every start until the code changes. godwit does not
change the server's limit; Atlas exposes it in the cluster's advanced configuration, and raising it only moves the
wall.

godwit warns well before the limit. An attempt slower than `GodwitConfig.slowTransactionWarning` (20 s by default)
logs:

```text
WARN  godwit - Slow transaction id=004-order-status attempt=1 durationMs=23410
```

This step (a fragment: the `inTransaction` step of a migration) updates every order one at a time in one transaction.
With a few thousand orders it commits; with two million it never does:

```kotlin
.inTransaction {
    val orders = collection("orders")
    orders.find(session, exists("totalMinor", false)).forEach { order ->
        orders.updateOne(session, eq("_id", order["_id"]), set("totalMinor", totalOf(order)))
    }
}
```

Where long work goes instead:

| The work | Where it goes |
|---|---|
| Per-document changes over a large collection, atomic per page | `inBatches`: one transaction per page, resumable after a crash. `006-order-totals` does the work above this way ([batched backfills](batched-backfills.md)). |
| One server-side update that is safe to repeat | `updateMany` in an outside step, no transaction ([outside-transaction steps](outside-transaction-steps.md)) |
| Slow reads or external calls that feed a small write | The outside step, handing its result to `inTransaction`, as `005-customer-external-ids` and `009-order-payment-status` do |

## DDL inside a transaction

MongoDB allows little DDL in a transaction: creating an index on an existing collection, `drop`, `dropIndexes`,
`renameCollection` and `collMod` all fail, and explicit `createCollection` and `createIndexes` need read concern
`local`, which godwit's transactions do not use. All DDL belongs in the outside step.

godwit's DDL helpers are members of the outside step's scope only, so they do not resolve in a transactional step. The
fragment below is a migration's `inTransaction` step that calls one.

This does not compile:

```kotlin
.inTransaction {
    ensureCollection("carts")
}
```

Raw driver DDL compiles, because `collection(...)` returns an ordinary `MongoCollection`. This step (a fragment)
builds the index of `006-order-totals` in the transaction:

```kotlin
.inTransaction {
    collection("orders").createIndex(session, compoundIndex(ascending("customerId"), descending("totalMinor")))
}
```

The server rejects it: `createIndexes` refuses a transaction whose read concern is snapshot, as godwit's are
(`InvalidOptions`, 72), and `drop`, `dropIndexes`, `renameCollection` and `collMod` refuse any transaction
(`OperationNotSupportedInTransaction`, 263). godwit records the migration FAILED and throws
`MigrationFailedException`, whose message says to move the call to `outsideTransaction`. Without `session` the call
escapes instead: the index is built outside the transaction, is not rolled back, and can wait behind the transaction's
own locks.

Implicit collection creation is allowed: an insert or upsert into a collection that does not exist creates it inside
the transaction, under any read concern. `reference-countries` creates `countries` that way on a fresh database. When a
collection needs options or indexes, create it with `ensureCollection` in an outside step.

## Transactions that are too large

A transaction keeps every document it writes in the storage engine's cache until it commits. One that does not fit
fails: under cache pressure the server aborts it with a write conflict, which the driver retries and which fails the
same way again, or with `TransactionTooLargeForCache` (388), which the driver does not retry. godwit fails the migration
with the same guidance as for the lifetime. Split the work with `inBatches`, and lower `batchSize` when one page is too
large.

## One MongoClient

A session belongs to the client that started it. godwit starts its sessions on the `MongoCluster` passed to `Godwit`,
and the driver rejects such a session in an operation on any other client's collection with `IllegalStateException:
ClientSession from same MongoClient`. godwit fails the migration with the guidance line "The step passed godwit's
session to an operation on another MongoClient. Build Godwit and the services the migrations call from the same
MongoClient." ([architecture](architecture.md#error-guidance)).

The shop builds `Godwit` and every service from one client:

```kotlin
package com.example.shop

import com.example.shop.migrations.shopMigrations
import com.example.shop.services.CustomerService
import com.example.shop.services.HttpIdentityProvider
import com.example.shop.services.OrderService
import com.mongodb.kotlin.client.MongoClient
import godwit.core.Godwit

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

/** The rest of the shop: serves requests until the process stops. */
fun startHttpServer(customers: CustomerService, orders: OrderService): Unit = TODO("the shop's HTTP server")
```

This compiles and is wrong. Two clients point at the same database:

```kotlin
/** Wrong: godwit and CustomerService use two clients, so 005's session is invalid in CustomerService. */
fun startWithTwoClients() {
    val config = loadShopConfig()
    val godwitClient = MongoClient.create(config.mongo.uri)
    val appClient = MongoClient.create(config.mongo.uri)
    val customers = CustomerService(appClient.getDatabase(config.mongo.database))
    val identity = HttpIdentityProvider(config.identity.baseUrl, config.identity.apiKey)

    Godwit(godwitClient, config.mongo.database).migrate(shopMigrations(config, customers, identity))
}
```

`001-initial-setup` to `004-order-status` apply. `005-customer-external-ids` runs its outside step (no session, so
either client works), then passes the transaction's session from `godwitClient` to `CustomerService`, which runs on
`appClient`, and fails. In tests, build services from `TestGodwit.database` or `TestGodwit.client`, which share the
client of `TestGodwit.godwit`.

## Standalone servers

A standalone `mongod` has no transactions. godwit checks the server before it takes the lock, and only when a
migration with a transactional step (`inTransaction` or `inBatches`) is due:

- when one is due, `migrate` throws `TransactionsUnsupportedException` naming every due migration that needs
  transactions, and nothing runs, not even the outside-only migrations due with them;
- when every due migration is outside-only, they run, so a list of DDL-only migrations works on a standalone server.

The shop needs transactions on every start: `bootstrap-customers` is always due and has an `inTransaction` step. On a
fresh standalone server:

```text
godwit.core.TransactionsUnsupportedException: Migrations [004-order-status, 005-customer-external-ids, 006-order-totals, reference-countries, bootstrap-customers] need transactions, which a standalone mongod does not support. Run a single-node replica set: start mongod with --replSet rs0, then run rs.initiate() once.
```

A single-node replica set is an ordinary `mongod` started with a replica set name. It supports transactions and needs
no other members. With Docker:

```sh
docker run -d --name shop-mongo -p 27017:27017 mongo:8.0 --replSet rs0 --bind_ip_all
docker exec shop-mongo mongosh --quiet --eval 'rs.initiate({ _id: "rs0", members: [{ _id: 0, host: "localhost:27017" }] })'
```

Then connect with `mongodb://localhost:27017/?directConnection=true`, which talks to the one member without
discovering the replica set's host names. A local `mongod` works the same way: start it with
`--replSet rs0` and run `rs.initiate()` once in `mongosh`. `testGodwit()` starts a single-node replica set for tests.

## Read and write concerns

godwit sets these itself; none is configurable:

| Operation | Read concern | Write concern | Read preference |
|---|---|---|---|
| A transactional step: `inTransaction`, each `inBatches` page, with the APPLIED flip and checkpoint written in it | `snapshot` | `majority` | primary |
| History reads: planning, `status()`, `history()` | `majority` | n/a | primary |
| History writes outside a transaction: RUNNING, FAILED, the APPLIED flip of an outside-only migration | n/a | `majority` | primary |
| Lock operations | `majority` | `majority` | primary |
| The outside step | your client's settings | your client's settings | your client's settings |

Snapshot read concern gives the body one consistent view of the database as of its first operation, and a majority
commit makes that view and the commit survive a failover. The outside step is ordinary driver code on `database`, so it
uses whatever your client configures.

## Edge cases

### The app writes an order the step is updating

`004-order-status` is running while a customer pays for an order: the app's `markPaid` writes the order after the
transaction took its snapshot and before the transaction updates it.

godwit: the transaction's write hits a `WriteConflict` (112), labelled transient. The driver runs the body again in a
new transaction, which sees the paid order in its snapshot; godwit logs "Retrying transaction", resets the counters and
records `transactionRetries: 1`.

You: nothing. When a step retries often under real traffic, run it at a quieter time, or turn it into `inBatches` so
each transaction touches fewer documents.

### The connection drops while the transaction commits

godwit: the driver gets `UnknownTransactionCommitResult` and retries only the commit; the server recognises a commit
that already happened. The body does not run again and `attempt` does not change. If the driver gives up although the
commit applied (the app's client sets `timeoutMS`), godwit's `FAILED` write, which matches only a `RUNNING` document
with this run's owner token, matches nothing; once the server acknowledges it with majority write concern, godwit reads
the document, finds it `APPLIED` by this run and reports the migration as applied. When the commit retries run out of
the 120 s window instead, the lock's deadline has normally passed with them: `migrate` throws `LockLostException` with
the commit's error as its cause, and the next start finds the migration `APPLIED`.

You: nothing.

### The process dies in the middle of the body

The pod running `004-order-status` is killed by the out-of-memory killer after the first `updateMany`.

godwit: the server still holds the open transaction until its 60 s lifetime ends, then discards it. Until then, app
writes to the orders the transaction touched wait. The history document stays RUNNING and the lease on the lock expires
within `LockConfig.lease`. The next process to start takes the lock, logs "Resuming interrupted migration" (WARN) and
runs the migration again from the beginning; `attempts` in history becomes 2.

You: nothing, unless it keeps happening: then give the process more memory or split the work.

### The process dies after the commit

godwit: the orders and the APPLIED record are committed. The lock stays held until its lease expires. The next start
reads history, finds nothing due and takes no lock.

You: nothing.

### The run loses the lock during the body

A garbage collection pause of 90 s stops the process inside the body, the heartbeat cannot renew the lease, and
another pod takes the lock and starts the same migration.

godwit: the paused run's `checkLock()` before the commit throws `LockLostException`, which aborts its transaction. If
it got past that check, its fenced APPLIED update meets the other pod's RUNNING record, which carries a different
`owner`. That record committed after the paused transaction started, so the update conflicts with it (`WriteConflict`,
112), the transaction aborts, and the driver runs the body again, whose first `checkLock()` throws. A transaction that
started after the record reads the new `owner`, and its update matches nothing, which aborts it too. Either way the
paused run commits nothing and `migrate` throws `LockLostException`; the other pod runs the migration.

You: let the process restart, and look at what paused it. See [locking](locking.md).

### The body throws

A bug in the body throws `NullPointerException` on an order without `lines`.

godwit: the transaction aborts, so none of its writes and no APPLIED record commit. godwit writes FAILED with
`lastError` (outside any transaction) and throws `MigrationFailedException` with `step = IN_TRANSACTION`. The app does
not start. The next start runs the migration again, outside step first.

You: fix the body and deploy. The fixed body runs against the same data the failed one saw, because nothing committed.

### State outside the transaction

This migration's transaction appends to a list the caller passed in:

```kotlin
/** Wrong: state outside the transaction survives a driver retry. */
fun orderPaymentStatusAudited(gateway: PaymentGateway, audit: MutableList<ObjectId>): Migration =
    migration("009-order-payment-status")
        .outsideTransaction {
            collection("orders")
                .find(and(ne("paymentId", null), exists("paymentStatus", false)))
                .map { order -> order.getObjectId("_id") to gateway.paymentStatus(order.getString("paymentId")) }
                .toList()
                .toMap()
        }
        .inTransaction { statuses ->
            statuses.forEach { (orderId, status) ->
                collection("orders").updateOne(session, eq("_id", orderId), set("paymentStatus", status.name))
                audit += orderId // a retry adds every id again
            }
        }
```

godwit: on a retry the orders roll back and are written again, but `audit` keeps the ids from the aborted attempt, so
it lists them twice.

You: keep state inside the body. Report numbers with `count(...)`, which godwit resets per attempt; read anything else
from the database after `migrate` returns.

### A service starts its own transaction

A service method the step calls runs `session.withTransaction { ... }` or `session.startTransaction()` on the session
it was given.

godwit: the session already carries godwit's transaction, and the driver throws `IllegalStateException` ("Transaction
already in progress"). The migration fails in `IN_TRANSACTION`.

You: give services plain operations that take a session, as the shop's services do, and let the caller own the
transaction. A service must never commit or abort a session it was given.

### A slow read feeds a small write

The body aggregates a year of orders to compute per-customer totals, then writes a few hundred documents. The
aggregation takes 50 s.

godwit: the read counts toward the 60 s lifetime like any write. godwit logs "Slow transaction" after 20 s, and the
transaction exceeds its lifetime and fails.

You: run the aggregation in the outside step and hand the totals to `inTransaction`, which then only writes.

### A sharded cluster

godwit runs on a sharded cluster through `mongos`, with the same transactions.

godwit: a transaction that writes to more than one shard cannot create a collection, so an implicit creation in such a
transaction fails.

You: create every collection a transactional step writes to with `ensureCollection` in an outside step, before the
transaction needs it.

## Design decisions

### The APPLIED record commits with the data

Chosen: the history flip is the last write of the step's transaction.

Considered: committing the data, then writing APPLIED. A crash between the two leaves the data changed and the record
RUNNING, so the next start runs the step again. That is harmless for a change that is safe to repeat and wrong for one
that is not: `inc("priceMinor", 100L)` would raise every price twice. One transaction makes the step's effect commit
exactly once, whatever the step does, so the author never has to prove a transactional step idempotent.

### An explicit `session`

Chosen: `session` is a scope property, and the author passes it to every driver call and service call.

| Option | What a step writes | Why not chosen |
|---|---|---|
| Explicit `session` (chosen) | `orders.updateOne(session, filter, update)`, `customers.setExternalUserId(session, ...)` | |
| A session-bound collection type from godwit | `orders.updateOne(filter, update)` on a godwit wrapper that adds the session | It mirrors the driver: every operation, every overload, every driver release. Services built on the app's own `MongoDatabase` bypass it, so the guarantee holds only for steps that never call a service. A wrapper also hides which calls are transactional. |
| An ambient session (thread-local or Kotlin context parameter) | `customers.setExternalUserId(customerId, id)` | The driver has no ambient session, so every service would have to fetch it from somewhere. Either way the session disappears from the call site, which is the one place a reader checks what is in the transaction. |

The explicit form is driver-native, works unchanged with services that take a `ClientSession`, and shows at each call
site what is in the transaction. Its one gap, a forgotten argument, is closed in tests by `SessionEscapeDetector`.

### The driver's retry loop

Chosen: `ClientSession.withTransaction`, with godwit counting attempts around the body.

Considered: a retry loop of godwit's own. The driver's loop implements MongoDB's specified retry rules (which labels
retry the body, which retry only the commit, the 120 s window) and follows them as the driver evolves. godwit adds what
the driver does not: a fresh scope and counters per attempt, the `attempt` number, a pause before each attempt after
the first (driver 5.7.0 has none), the rate-limited WARN line and `transactionRetries`.

### Fixed concerns

Chosen: snapshot, majority and primary for every transaction, with no setting.

Considered: making them configurable. The guarantees on this page rest on them: the fenced flip is only safe when the
RUNNING record and the commit are majority-durable, and the body's reads are only consistent under snapshot. A setting
would let one deployment silently lose exactly-once.

### No DDL in transactions

Chosen: all DDL lives in the outside step; the DDL helpers do not resolve in a transactional step.

Considered: read concern `local` as a per-migration option, which MongoDB requires to create a collection and its
indexes inside a transaction. It covers one narrow case (a new collection, its indexes and its seed data at once),
gives up the snapshot view for the whole step, and still fails for an index on an existing collection. An outside step
followed by a transaction covers the same case with one rule.

### Fail on a standalone server, only when needed

Chosen: `TransactionsUnsupportedException` when a transactional step is due on a standalone server; outside-only work
runs.

Considered: a non-transactional mode for standalone servers, and failing on standalone servers always. The first
removes the guarantee this page is about, without the reader of a migration being able to tell. The second blocks
lists that never need a transaction. A single-node replica set costs one flag and one command, so godwit asks for it
in the exception message.

## See also

- [Concepts](concepts.md): steps and the guarantee of each
- [Declaring migrations](declaring-migrations.md): step combinations and the typed hand-off
- [Outside-transaction steps](outside-transaction-steps.md): DDL, external calls and idempotency
- [Batched backfills](batched-backfills.md): `inBatches`, one transaction per page
- [Repeatable migrations](repeatable-migrations.md): `everyStart` and `repeatable` use the same transactions
- [Testing](testing.md): `testGodwit()`, `runIsolated` and `SessionEscapeDetector`
- [Failure and recovery](failure-and-recovery.md): every failure as a worked example
- [Locking](locking.md): leases, `checkLock()` and losing the lock
- [Architecture](architecture.md): the fenced flip and the runner algorithm
- [Configuration](configuration.md): `slowTransactionWarning` and the lock settings
- [Design decisions](design-decisions.md): every decision in one index
- [README](../README.md)
