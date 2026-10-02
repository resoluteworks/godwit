# Outside-transaction steps

An `outsideTransaction` step runs without a session or a transaction: every write in it commits on its own, the
moment it runs. It is the home of DDL (collections, indexes, search indexes, TTL and validator changes), of calls to
external services, and of large server-side updates that are safe to repeat. godwit runs it at least once: when the
migration fails or the process dies, the next start runs the whole step again from its first line, so every call in it
must be idempotent. This page lists which driver calls already are, the three helpers godwit adds for the ones that are
not, and how to write the rest.

## An outside step

`001-initial-setup` creates the shop's first collections and indexes:

```kotlin
package com.example.shop.migrations

import com.mongodb.client.model.IndexModel
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes.ascending
import com.mongodb.client.model.Indexes.compoundIndex
import com.mongodb.client.model.Indexes.descending
import godwit.core.Migration
import godwit.core.migration
import org.bson.Document
import kotlin.time.Duration

/**
 * Customers, orders and products: the collections, their indexes and the product search index. Every call is
 * idempotent, so a retry after a crash converges. [searchIndexWait] is how long to wait for the search index to
 * become queryable; null returns as soon as it is requested.
 */
fun initialSetup(searchIndexWait: Duration?): Migration = migration("001-initial-setup")
    .outsideTransaction {
        ensureCollection("customers")
        ensureCollection("orders")
        ensureCollection("products")

        collection("customers").createIndex(ascending("email"), IndexOptions().unique(true))
        collection("orders").createIndexes(
            listOf(
                IndexModel(compoundIndex(ascending("customerId"), descending("placedAt"))),
                IndexModel(ascending("status"))
            )
        )
        collection("products").createIndex(ascending("sku"), IndexOptions().unique(true))
        ensureSearchIndex(
            "products",
            name = "product-search",
            definition = Document("mappings", Document("dynamic", true)),
            awaitReady = searchIndexWait
        )
    }
```

What godwit does when it runs it:

1. Records the migration RUNNING in `godwit-history`.
2. Calls `checkLock()`, then the step, with an `OutsideTransactionScope` as its receiver: `database`, `collection(name)`,
   `count(name, n)`, `checkLock()` and the three DDL helpers. There is no `session`.
3. Calls `checkLock()` again. A migration with no transactional step is then recorded APPLIED with its counters,
   outside any transaction; a migration with one hands the step's return value to it
   ([transactions and sessions](transactions-and-sessions.md)).

Every write commits as it runs, and nothing ties the step's writes to the APPLIED record. A process that dies after
the last `createIndex` but before step 3 leaves the schema complete and the record RUNNING, so the next start runs the
whole step again. A step can run twice even when it finished, which is why every call in it must be safe to repeat.

An outside step has no session. The fragment below is a migration's outside step that passes one.

This does not compile:

```kotlin
.outsideTransaction {
    collection("orders").updateMany(session, exists("currency", false), set("currency", "GBP"))
}
```

## Which driver calls are idempotent

| Call | Run again when its result already exists | Safe to repeat | Write it as |
|---|---|---|---|
| `createIndex` / `createIndexes`, same keys and options | No-op | Yes | The driver call |
| `createIndex`, same keys, different options (unique, TTL, partial filter, collation) | `IndexOptionsConflict` (85) | Fails: real drift | See [Edge cases](#an-index-with-the-same-keys-and-different-options-exists) |
| `createIndex`, same name, different keys | `IndexKeySpecsConflict` (86) | Fails: real drift | A new name, or drop and recreate in a new migration |
| `createCollection` | `NamespaceExists` (48) before MongoDB 7.0; from 7.0 only when the options differ | No | `ensureCollection` |
| `dropIndex` of an index that is gone | `IndexNotFound` (27) before MongoDB 8.3; from 8.3 it succeeds | No, before 8.3 | `dropIndexIfExists` |
| `createSearchIndex` with a name that exists | Fails | No | `ensureSearchIndex` |
| `updateSearchIndex` with the same definition | Sets it again | Yes | The driver call |
| `drop()` of a collection that is gone | The driver ignores `NamespaceNotFound` (26) | Yes | The driver call |
| `collMod` (TTL, validator, validation level) | Sets the same value | Yes | `database.runCommand(...)` |
| `renameCollection` | `NamespaceNotFound` (26) when the source is gone; `NamespaceExists` (48) when the target exists | No | Check first: [Renaming a collection](#renaming-a-collection) |
| `updateMany` with `$set` and a filter that excludes finished documents | Updates only what is left | Yes | [Large server-side updates](#large-server-side-updates) |
| `$inc`, `$push`, `$mul`, inserts without a natural key | Applies again | No | `inTransaction`, or a guard in the filter |

Index builds run on the server and survive the client: a step interrupted during a build finds, on the next start, an
identical `createIndex` that either returns at once or waits for the build already in progress.

## The three DDL helpers

godwit adds a helper for each common DDL call whose raw form fails when it runs a second time. Inside a step they are
members of `OutsideTransactionScope`; everywhere else they are public extensions in `godwit.core`.

| Member (inside an outside step) | Extension (anywhere) | Returns | Behaviour |
|---|---|---|---|
| `ensureCollection(name, options)` | `MongoDatabase.ensureCollection(name, options)` | `true` when this call created it | Creates the collection unless one with that name exists. The options of an existing collection are neither compared nor changed. A concurrent create (`NamespaceExists`, 48) counts as existing. |
| `ensureSearchIndex(collection, name, definition, awaitReady)` | `MongoCollection<*>.ensureSearchIndex(name, definition, awaitReady)` | `true` when this call created it | Creates the Atlas Search index unless a search index with that name exists. The definition of an existing one is neither compared nor changed. A concurrent create of the same name counts as existing. With `awaitReady` null it returns once the index is requested; otherwise it polls until the index is queryable and throws `SearchIndexNotReadyException` when `awaitReady` passes first. The member calls `checkLock()` between polls. |
| `dropIndexIfExists(collection, indexName)` | `MongoCollection<*>.dropIndexIfExists(indexName)` | `true` when this call dropped it | Drops the index when `listIndexes` shows it; returns `false` when it does not exist, on every server version (from 8.3 `dropIndexes` itself succeeds for a missing index, so its result cannot tell). A concurrent drop (`IndexNotFound`, 27) counts as gone. |

This step (a fragment of a migration) is wrong, because each line fails when a retry runs it again:

```kotlin
.outsideTransaction {
    database.createCollection("carts")
    collection("products").createSearchIndex("product-search", Document("mappings", Document("dynamic", true)))
    collection("orders").dropIndex("status_1")
}
```

The same step with the helpers converges on every run (a fragment as well):

```kotlin
.outsideTransaction {
    ensureCollection("carts")
    ensureSearchIndex("products", name = "product-search", definition = Document("mappings", Document("dynamic", true)))
    dropIndexIfExists("orders", "status_1")
}
```

`002-carts` is the usual shape: `ensureCollection`, then the driver's own `createIndexes`, which needs no helper:

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

Dropping an index that is no longer used:

```kotlin
import godwit.core.migration

/** No query filters on `status` alone any more; the index only slows writes down. */
val dropOrderStatusIndex = migration("012-orders-drop-status-index")
    .outsideTransaction {
        dropIndexIfExists("orders", "status_1")
    }
```

### The extensions, for libraries

A library that owns collections ships an idempotent setup function built on the extensions, never migrations of its
own ([libraries and modules](libraries-and-modules.md)). The shop's file store library does this:

```kotlin
package com.example.filestore

import com.mongodb.client.model.IndexModel
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes.ascending
import com.mongodb.client.model.Indexes.compoundIndex
import com.mongodb.client.model.Indexes.descending
import com.mongodb.kotlin.client.MongoDatabase
import godwit.core.ensureCollection
import org.bson.Document
import java.util.concurrent.TimeUnit

/** The collection the file store keeps file metadata in. */
const val FILES_COLLECTION = "files"

/**
 * Creates the file store's collection and indexes. Idempotent, so it is safe to call on every start or from a
 * migration. The library ships this function, not migrations: an app calls it from one of its own migrations.
 */
fun MongoDatabase.ensureFileStoreSchema() {
    ensureCollection(FILES_COLLECTION)
    getCollection(FILES_COLLECTION, Document::class.java).createIndexes(
        listOf(
            IndexModel(compoundIndex(ascending("ownerId"), descending("createdAt"))),
            IndexModel(ascending("storageKey"), IndexOptions().unique(true)),
            IndexModel(ascending("tempExpiresAt"), IndexOptions().expireAfter(0L, TimeUnit.SECONDS))
        )
    )
}
```

and the shop calls it from its own migration, which gives it the lock and the history record:

```kotlin
package com.example.shop.migrations

import com.example.filestore.ensureFileStoreSchema
import godwit.core.migration

/** The file store library's collection and indexes, through the library's own idempotent setup function. */
val fileStore = migration("003-file-store")
    .outsideTransaction {
        database.ensureFileStoreSchema()
    }
```

The other two extensions work the same way. A later version of the library adds a search index and drops an index it
no longer needs; the shop adds a migration that calls the new function:

```kotlin
import com.example.filestore.FILES_COLLECTION
import com.mongodb.kotlin.client.MongoDatabase
import godwit.core.dropIndexIfExists
import godwit.core.ensureSearchIndex
import godwit.core.migration
import org.bson.Document

/**
 * A setup function a library can ship: idempotent, built on godwit's public DDL extensions, and called from the app's
 * own migration, which provides the lock and the history record.
 */
fun MongoDatabase.ensureFileSearch() {
    val files = getCollection(FILES_COLLECTION, Document::class.java)
    files.dropIndexIfExists("ownerId_1")
    files.ensureSearchIndex("file-search", Document("mappings", Document("dynamic", true)))
}

val fileSearch = migration("015-file-search")
    .outsideTransaction {
        database.ensureFileSearch()
    }
```

The extension `ensureSearchIndex` has no lock to check, so while it waits for an index (`awaitReady` set) it does not
call `checkLock()`; the member does. Inside a migration, prefer the member when you wait.

## Long outside steps and `checkLock()`

A heartbeat thread renews the lock's lease every `LockConfig.heartbeat` for as long as the run holds it, however long
the step takes. The lease protects against a dead process, not a slow one.

`checkLock()` throws `LockLostException` when this run no longer holds the lock: a renewal found another owner, or the
lease deadline passed without a renewal (a long pause, a lost connection to the primary). godwit calls it before and
after every step. A step that loops over many items calls it once per item, so a run that lost the lock stops at the
next item instead of at the end of the loop. The outside step of `005-customer-external-ids` (a fragment of the
migration) makes one HTTP call per customer and checks the lock before each:

```kotlin
.outsideTransaction {
    customers.withoutExternalUserId().associate { customer ->
        checkLock()
        customer.id to identity.findOrCreateUser(customer.email).id
    }
}
```

Without `checkLock()` a run that lost the lock carries on until the step ends, while the new holder may already run the
same step. Only the run that holds the lock can record APPLIED, and a transactional step's writes are fenced the same
way (godwit fences them with the lock owner). An outside step's writes are not fenced: work the stale run still has in
flight overlaps with the new holder's, and it can land after the new holder has moved on to later migrations.
Idempotent calls repeated one after another converge; calls that overlap need more:

| In flight in the stale run | Hazard | What to do |
|---|---|---|
| A long server-side `updateMany`, which keeps running on the server after the client gives up | It writes after the new holder applied this migration and later ones: `011-order-currency` sets `currency` on orders that a later migration has already renamed | Bound long server-side writes in outside steps with `maxTime` or the client's `timeoutMS`, or use `inBatches`, whose page writes are fenced |
| An external create, such as a `findOrCreateUser` call | Find-then-create protects against retries, not against two callers at once: both find nothing and both create, and the provider has two users | Pass the provider an idempotency key, or rely on a unique constraint on its side |
| `ensureSearchIndex` | Two creates of the same name | Nothing: the helper counts a duplicate name as existing |

`checkLock()` stops the stale run at its next item. A pause or partition shorter than `LockConfig.safetyMargin` never
lets a second holder start; a longer one can, which is what the table is about ([locking](locking.md#losing-the-lock-mid-run)).

Other processes wait while a long step runs, up to `LockConfig.waitTimeout` (10 minutes by default); set it above your
longest migration ([locking](locking.md)). Migrations run before the shop's HTTP server starts, so a container
platform's startup check must also allow for the longest migration, or it kills the process mid-step and the next start
runs the step again.

## External services

An external call in an outside step runs once per attempt of the migration, so it must be safe to repeat.

| Call | Safe to repeat | Why |
|---|---|---|
| `IdentityProvider.findOrCreateUser(email)` | Yes | Finds by a natural key first, so a repeat returns the same user |
| `PaymentGateway.paymentStatus(paymentId)` | Yes | Reads only |
| `EmailSender.send(to, subject, body)` | No | A repeat sends the email again |

Find-or-create is the pattern for calls that create something. When the service has no such operation, write it as a
lookup by a natural key followed by a create: a crash after the create leaves a user the next run finds by email,
instead of a duplicate. That covers a retry after a crash. Two calls at once, from a run that lost the lock and the run
that took over, can both find nothing; an idempotency key or a unique constraint on the provider's side covers that
case ([above](#long-outside-steps-and-checklock)).

Results reach the transaction as the step's return value (`005-customer-external-ids`, `009-order-payment-status`), so
the transaction never calls out ([transactions and sessions](transactions-and-sessions.md#external-services-the-outside-step-and-the-hand-off)).

A call that is not safe to repeat belongs in no migration. This compiles and is wrong:

```kotlin
/** Wrong: a retry sends every email again. */
fun welcomeEmails(email: EmailSender): Migration =
    migration("016-welcome-emails")
        .outsideTransaction {
            collection("customers").find(exists("welcomedAt", false)).forEach { customer ->
                email.send(customer.getString("email"), "Welcome to the shop", "Your account is ready.")
            }
        }
```

When the step fails after 300 emails, the next start sends those 300 again. Setting `welcomedAt` after each send
narrows the window and does not close it: a crash between the send and the update still repeats one email. Let the
migration change data only, and let the app's own mailer send:

```kotlin
import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.Updates.set
import godwit.core.migration

/**
 * Marks every customer who never got a welcome email. The shop's mailer sends the emails and clears the flag; the
 * migration only changes data, so it commits once with its history record.
 */
val welcomeEmailsDue = migration("016-welcome-emails-due")
    .inTransaction {
        val result = collection("customers").updateMany(session, exists("welcomedAt", false), set("welcomeEmailDue", true))
        count("customersFlagged", result.modifiedCount)
    }
```

## Large server-side updates

A data change that needs no Kotlin logic and no atomicity can be one `updateMany` in an outside step. The server does
the work in one command, with no transaction and so no 60 s lifetime:

```kotlin
import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.Updates.set
import godwit.core.migration

/**
 * Orders placed before the shop sold in more than one currency have no `currency`; they were all in pounds. One
 * server-side update, idempotent through its filter: a retry updates only the orders an interrupted run did not reach.
 */
val orderCurrency = migration("011-order-currency")
    .outsideTransaction {
        val result = collection("orders").updateMany(exists("currency", false), set("currency", "GBP"))
        count("ordersUpdated", result.modifiedCount)
    }
```

The filter is what makes it safe to repeat: an order that has a `currency` is never selected again. The update is not
atomic: the app sees orders change one by one while it runs, and a failover or a killed process leaves it part done.
The next start updates the rest. `ordersUpdated` counts the orders this run modified.

An operator that changes a value relative to itself is not safe to repeat. This compiles and is wrong:

```kotlin
/** Wrong: a retry raises every price again. */
val productPriceRiseOutside = migration("014-product-price-rise")
    .outsideTransaction {
        collection("products").updateMany(Filters.empty(), inc("priceMinor", 100L))
    }
```

A crash after the update and before the APPLIED record raises every price by 2.00 on the next start. Put it in a
transaction, which commits the update with the record, exactly once:

```kotlin
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates.inc
import godwit.core.migration

/** Every price goes up by 1.00. `$inc` is not idempotent, so the update commits with the history record. */
val productPriceRise = migration("014-product-price-rise")
    .inTransaction {
        val result = collection("products").updateMany(session, Filters.empty(), inc("priceMinor", 100L))
        count("productsRepriced", result.modifiedCount)
    }
```

When the collection is too large for one transaction, use `inBatches`, which commits each page exactly once
([batched backfills](batched-backfills.md)).

## Changing an index or a collection option

`collMod` sets the TTL or validator of what exists, and setting the value it already has succeeds, so it is safe to
repeat. The shop keeps carts for 60 days instead of 30:

```kotlin
import godwit.core.migration
import org.bson.Document

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

Changing `002-carts` instead would do nothing on any database where `002-carts` is applied: godwit never runs it there
again. It would only change fresh databases, so production and a new test database would disagree. Change schema with
a new migration; on a fresh database `002-carts` creates the 30-day index and `010-cart-expiry-60-days` changes it a
moment later.

For changes `collMod` cannot make (unique, partial filter, collation, keys), drop the old index with
`dropIndexIfExists` and create the new one in the same step. Both calls are safe to repeat, and the index is missing
for as long as the new build takes.

## Renaming a collection

`renameCollection` fails when it runs a second time, so the step checks which name exists and does only what is left:

```kotlin
import com.mongodb.MongoNamespace
import godwit.core.migration

/**
 * Renames `carts` to `baskets`. renameCollection is not idempotent, so the step looks at which of the two exists and
 * does only what is left: a retry after a crash that followed the rename finds `baskets` alone and does nothing.
 */
val renameCarts = migration("013-rename-carts")
    .outsideTransaction {
        val names = database.listCollectionNames().toList()
        when {
            "carts" in names && "baskets" !in names ->
                collection("carts").renameCollection(MongoNamespace(database.name, "baskets"))
            "baskets" in names && "carts" !in names -> Unit
            else -> error("013-rename-carts expects exactly one of carts and baskets, found $names")
        }
    }
```

| Found | The step does |
|---|---|
| `carts` only | Renames it |
| `baskets` only | Nothing: an earlier run renamed it |
| Both, or neither | Fails, naming what it found |

The state check makes the step safe to repeat; it does not make the rename safe for running code. During a rolling
deploy, processes on the previous release still use `carts`; the first insert from one of them after the rename creates
a new, empty `carts`, and its reads find no carts. Rename a collection only when nothing writes to it: deploy with every
old process stopped first, or keep the collection name and rename only the Kotlin code around it.

## Edge cases

### The process dies after three of five indexes

The pod running `001-initial-setup` is killed after `customers` and `orders` have their indexes.

godwit: the history document stays RUNNING. When the lease expires, the next start logs "Resuming interrupted
migration" (WARN) and runs the step from the top: the three `ensureCollection` calls return `false`, the existing
`createIndex` calls return at once, and the missing ones are built.

You: nothing.

### An index with the same keys and different options exists

On a database adopted from hand-run scripts, `customers` already has a non-unique `email_1` index. `001-initial-setup`
asks for `email_1` unique.

godwit: `createIndex` fails with `IndexOptionsConflict` (85). The migration is recorded FAILED and `migrate` throws
`MigrationFailedException` on every start. godwit does not guess which definition is right.

You: make the database match the migration. Check that no two customers share an email, drop the stray index by hand
(`db.customers.dropIndex("email_1")` in `mongosh`) and restart: the step runs again and builds the unique index. When
the shop needs a different index, change it for every database in a new migration.

### A collection exists with different options

`ensureCollection("carts", CreateCollectionOptions().validationOptions(...))` runs on a database where `carts` was
created implicitly by an insert, without a validator.

godwit: `ensureCollection` returns `false` and changes nothing; it does not compare options.

You: set options on an existing collection with `collMod` (`database.runCommand(Document("collMod", "carts")...)`), in
the same step after `ensureCollection` or in a new migration. `collMod` is safe to repeat.

### The search index is not ready in time

`001-initial-setup` runs with `SEARCH_INDEX_WAIT_SECONDS=120`, and the Atlas Search build of `product-search` takes 4
minutes on a large `products` collection.

godwit: after 120 s, `ensureSearchIndex` throws `SearchIndexNotReadyException`. The migration is recorded FAILED and
the app does not start. The build carries on in Atlas. On the next start `ensureSearchIndex` finds the index, returns
`false`, and waits for it again, this time briefly.

You: raise `SEARCH_INDEX_WAIT_SECONDS`, or leave it unset so the step returns once the index is requested and the app
starts while the index builds (search results are incomplete until it is ready). Keep `LockConfig.waitTimeout` above the
wait, or other processes give up waiting for the lock first.

### The server has no Atlas Search

`001-initial-setup` runs against a plain `mongod` or a plain Testcontainers image.

godwit: the server rejects the search index command; the migration fails in `OUTSIDE_TRANSACTION`.

You: run against Atlas or the Atlas local image. In tests, `testGodwit(atlasSearch = true)` starts the Atlas local image
for any test that runs `001-initial-setup` ([testing](testing.md)).

### The outside step succeeds and the transaction fails

`005-customer-external-ids` links 4,000 customers: the outside step makes 4,000 identity provider calls, then the
transaction fails on a bug.

godwit: nothing of the transaction commits; the migration is FAILED. On the next start godwit runs the outside step
again, which makes the 4,000 calls again (each returns the user created the first time), then the transaction with the
new map.

You: fix the bug. When the calls are slow or rate limited, have the outside step store each result as it gets it (an
upsert keyed by customer id into a collection of its own) and skip customers already stored, so a second run only calls
for the rest.

### The run loses the lock during a long step

A network partition cuts the process off from the primary for 70 s while `005-customer-external-ids` makes its HTTP
calls.

godwit: the heartbeat cannot renew the lease, so this run marks the lock lost. The next `checkLock()`, before the next
customer, throws `LockLostException`, and `migrate` throws it. Another process takes the lock after the lease expires
and runs the migration from its outside step.

You: let the process restart. See [locking](locking.md).

### A primary election during an index build

The primary steps down while `001-initial-setup` builds the `orders` indexes.

godwit: the driver returns an error (`InterruptedDueToReplStateChange`, 11602, or `NotWritablePrimary`, 10107) and the
migration fails. The build may continue on the server. The next start runs the step again; the identical `createIndex`
returns at once or waits for the build.

You: nothing.

### The app reads data while the step changes it

`011-order-currency` runs on a large `orders` collection while the previous release still serves traffic.

godwit: each order is updated in its own write, so the app sees some orders with `currency` and some without until the
step ends.

You: keep the code of both releases correct for both shapes while the step runs: new code treats a missing `currency`
as `GBP` until the migration has applied everywhere, and old code ignores the new field. When the change must appear all
at once, use `inTransaction` or `inBatches`.

### A standalone server

`001-initial-setup`, `002-carts` and `003-file-store` are the only due migrations, and the server is a standalone
`mongod`.

godwit: outside steps need no transaction, so they run. The check for transactions happens only when a migration with
a transactional step is due ([transactions and sessions](transactions-and-sessions.md#standalone-servers)).

You: nothing for these three; the shop's later migrations need a replica set.

## Design decisions

### Three helpers, and no wrapper over the index API

Chosen: `ensureCollection`, `ensureSearchIndex` and `dropIndexIfExists`, and nothing else.

These are the three common DDL calls whose raw form fails when it runs a second time. Everything else in the table
above is already safe to repeat (`createIndex`, `collMod`, `drop`, `updateSearchIndex`) or must not be made safe
blindly (`renameCollection`).

Considered:

| Option | Why not chosen |
|---|---|
| An `ensureIndex` helper | `createIndex` is already a no-op for an identical index. The only case a helper could add is a conflicting one (85, 86), and there is no safe automatic answer: rebuilding silently can take hours on a large collection and drops a unique constraint while it runs; ignoring hides drift. The driver's error is the right result. |
| A declarative schema (declare indexes, godwit computes the difference) | It mirrors the driver's index and collection options, needs a normaliser for every option the server rewrites, and hides the cost of each change. A migration states the change and its cost in plain driver calls. |
| No helpers | Every author writes the same `try`/`catch` on error codes 48, 27 and the search index error, and the easy mistakes (catching 48 when the options really differ) hide drift. |
| Helpers that compare the existing options or definition | The server reports collections and search indexes with defaults filled in, so a comparison either reports false differences or needs a normaliser per option. A change of options is explicit: `collMod` or `updateSearchIndex` in a new migration. |

### Members and extensions

Chosen: each helper is an `OutsideTransactionScope` member and a public extension.

The member form is what a migration calls unqualified, and it does not resolve inside `inTransaction` or `inBatches`,
where DDL fails at runtime, so a misplaced call is a compile error. The extension form lets a library's setup function
use the same helpers without depending on godwit's runner. The member form of `ensureSearchIndex` also checks the lock
while it waits.

### At least once, without per-call progress

Chosen: a retry runs the whole outside step again.

Considered: recording progress inside the step, so a retry resumes after the last call that succeeded. A crash between
a call and its progress record still repeats that call, so the calls must be idempotent anyway; the record would only
save time, at the cost of a step-level state machine. Where the time matters (thousands of HTTP calls), the step can
store its own results, as in the edge case above. For large data changes, `inBatches` keeps a checkpoint.

## See also

- [Concepts](concepts.md): steps and the guarantee of each
- [Declaring migrations](declaring-migrations.md): step combinations and the typed hand-off
- [Transactions and sessions](transactions-and-sessions.md): the transactional step after an outside step
- [Batched backfills](batched-backfills.md): an outside step that builds an index before the pages
- [Libraries and modules](libraries-and-modules.md): setup functions instead of migrations in libraries
- [Locking](locking.md): leases, heartbeats and `waitTimeout`
- [Failure and recovery](failure-and-recovery.md): every crash window as a worked example
- [Adopting an existing database](adopting-an-existing-database.md): schema that exists before godwit
- [Testing](testing.md): `testGodwit(atlasSearch = true)` and `rerun`
- [Configuration](configuration.md): the lock settings
- [Design decisions](design-decisions.md): every decision in one index
- [README](../README.md)
