# Batched backfills

`inBatches(collection, pending, batchSize) { docs -> }` changes a large collection one page at a time, each page in a
transaction of its own. godwit pages through the collection in `_id` order, hands each page to your step, and commits
the step's writes together with a checkpoint, the last `_id` of the page. A crash, a deploy or a failure resumes after
the last committed page, so every page commits exactly once and no transaction comes near MongoDB's 60 s transaction
lifetime. You write what happens to one page; godwit does the paging, the checkpoint, the resume and the stop.

## A batched step

`006-order-totals` stores `totalMinor` on every order:

```kotlin
package com.example.shop.migrations

import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.Indexes.ascending
import com.mongodb.client.model.Indexes.compoundIndex
import com.mongodb.client.model.Indexes.descending
import com.mongodb.client.model.UpdateOneModel
import com.mongodb.client.model.Updates.set
import godwit.core.migration
import org.bson.Document

/**
 * Stores `totalMinor` on every order, computed from its lines. Too many orders for one transaction, so each page of
 * 500 is its own transaction that also commits the last `_id` it handled; a restart resumes after it. The outside
 * step adds the index that the "largest orders" query uses.
 */
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

private fun totalOf(order: Document): Long =
    order.getList("lines", Document::class.java).orEmpty().sumOf { line ->
        line.getInteger("quantity").toLong() * line.getLong("unitPriceMinor")
    }
```

| Argument | Meaning |
|---|---|
| `collection` | The collection to page through, by name |
| `pending` | A filter that selects the documents still to change. Re-evaluated on every page. |
| `batchSize` | The most documents in one page: 1 to 10000, 500 by default |
| `step` | Receives one page as `List<Document>`, with `TransactionScope` as its receiver: `session`, `attempt`, `count`, `checkLock`, `collection`, `database` |

What godwit does when it runs it:

1. Records the migration RUNNING in `godwit-history`.
2. Runs the outside step, if the migration has one: here, the index the "largest orders" query needs.
3. Runs pages, one transaction each (snapshot read concern, majority write concern, primary reads):
   1. calls `checkLock()`;
   2. reads up to `batchSize` documents of `collection` that match `pending` and whose `_id` is greater than the
      checkpoint, sorted by `_id` (the first page has no checkpoint), and checks that their `_id`s have one type
      ([Edge cases](#_ids-of-more-than-one-bson-type));
   3. when it read fewer than `batchSize`, this is the last page, and godwit first checks that no document matching
      `pending` has an `_id` of another type. That check runs outside any transaction: the page's transaction commits
      here without calling the step, the check runs, and the page runs again from 1 in a new transaction, which skips
      this item;
   4. when it found any, calls the step with them;
   5. calls `checkLock()`;
   6. when the page held `batchSize` documents, writes the checkpoint to the history document, fenced on this run's
      lock token: `lastId` (the page's last `_id`), the page count and the counters so far; on the last page, records
      the migration APPLIED instead, which removes the checkpoint;
   7. commits.

With 1,203 orders to total, the pages hold 500, 500 and 203 orders, and the third commits APPLIED. With exactly 1,000,
two full pages are followed by a read that finds nothing; it commits APPLIED without calling the step, and `batches` is
2: a read that finds nothing is not a page. The step is never called with an empty page.

Two properties make the loop end and keep it correct:

- **The `_id` cursor.** Each page starts after the last `_id` of the page before, so no document is read twice in a
  run, whether or not the step changed it.
- **`pending` on every page.** A document that stopped matching `pending` since the run started (fixed by the app, or
  by an earlier page) is not read.

The pages are ordinary transactions: pass `session` to every call, keep external calls out, and expect a page body to
run more than once when the driver retries it ([transactions and sessions](transactions-and-sessions.md)). A page's
counters reset when its body runs again, and `attempt` counts the runs of the current page's body.

## Resume after a crash or a deploy

The pod running `006-order-totals` is killed during page 38. Pages 1 to 37 are committed; page 38's transaction never
commits, so the server discards it. History holds:

```json
{
  "_id": "006-order-totals",
  "kind": "ONCE",
  "steps": ["OUTSIDE_TRANSACTION", "IN_BATCHES"],
  "state": "RUNNING",
  "origin": "RAN",
  "attempts": 1,
  "startedAt": { "$date": "2026-10-02T10:14:05.120Z" },
  "checkpoint": {
    "lastId": { "$oid": "66f1c3e2a8b4d10f2e7c9a40" },
    "batches": 37,
    "counts": { "ordersUpdated": 18500 }
  },
  "holder": "shop-7f9c4/1",
  "owner": "5b0f3c6e-2a41-4f7e-9d1c-0e8a7b6c5d4f",
  "runId": "0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f",
  "godwitVersion": "0.1.0",
  "v": 1
}
```

The next process to start waits for the dead pod's lease to expire, takes the lock, runs the outside step again (the
index exists, so `createIndex` returns at once) and continues after `lastId`. Page 38 runs from scratch:

```text
WARN  godwit - Resuming interrupted migration id=006-order-totals attempts=2
INFO  godwit - Running migration id=006-order-totals kind=ONCE steps=[OUTSIDE_TRANSACTION, IN_BATCHES] attempt=2
DEBUG godwit - Committed batch id=006-order-totals batch=38 lastId=66f1c3e2a8b4d10f2e7c9c2b
INFO  godwit - Applied migration id=006-order-totals kind=ONCE steps=[OUTSIDE_TRANSACTION, IN_BATCHES] attempts=2 txRetries=0 batches=241 durationMs=48211 ordersUpdated=120318
```

`batches` and the counters cover every attempt: the checkpoint carries them across the restart, so `ordersUpdated` is
the number of orders totalled overall, not just by the second process. A deploy that stops the process between pages
has the same effect as the crash. A failure in a page does too, with the history document FAILED instead of RUNNING
([Edge cases](#a-page-fails)).

## Choosing `batchSize`

A page reads `batchSize` documents, runs the step and writes the checkpoint, all in one transaction. Aim for pages that
commit in about a second.

| What you see | What to change |
|---|---|
| `Slow transaction` WARN lines for the migration's pages | Lower `batchSize` |
| The migration fails because a page passed the transaction lifetime or was too large | Lower `batchSize` |
| Frequent `Retrying transaction` lines with `WriteConflict`: pages collide with the app's writes | Lower `batchSize`, so each page holds fewer documents |
| Thousands of pages that each commit in a few milliseconds | Raise `batchSize` |

```text
WARN  godwit - Slow transaction id=006-order-totals attempt=1 durationMs=21877
```

The warning fires above `GodwitConfig.slowTransactionWarning` (20 s by default); a page that runs past the server's
60 s lifetime can never commit. A page also keeps its writes in the storage engine's cache until it commits, and holds
the documents it wrote: app writes to those documents wait until the page commits. Large documents and step bodies
that write to other collections call for smaller pages; small documents and one `bulkWrite` per page allow larger ones.

`batchSize` can change between attempts. The checkpoint is an `_id`, not a page number, so a resumed run with a new
`batchSize` continues after the same document.

## An outside step first

The outside step runs at the start of every attempt, before the first page, including an attempt that resumes from a
checkpoint. `006-order-totals` uses it for the index, which must be in place before code that sorts by `totalMinor`
ships; DDL cannot run inside a page. The fragment below is a migration's page step that calls a DDL helper.

This does not compile:

```kotlin
.inBatches("customers", pending = exists("emailLower", false)) { customers ->
    ensureCollection("customers")
}
```

The page step's only parameter is the page: it does not receive the outside step's return value. Work that an
`inTransaction` step would get as a prepared value (external calls, slow reads) runs per page inside the step when it
only reads MongoDB, or belongs in a migration of its own when it calls out.

## Data, then schema: two migrations

The steps of one migration run in a fixed order, outside step first. A change whose schema part must follow its data
part is two migrations. The shop adds case-insensitive sign-in: first fill `emailLower` on every customer, then make it
unique.

```kotlin
package com.example.shop.migrations

import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.UpdateOneModel
import com.mongodb.client.model.Updates.set
import godwit.core.migration
import org.bson.Document
import java.util.Locale

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

```kotlin
package com.example.shop.migrations

import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes.ascending
import godwit.core.migration

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

`007-customer-email-lower` guards its `bulkWrite`, because the driver rejects an empty list of writes: a page of
customers without emails would otherwise fail. `008-customer-email-lower-index` fails with `DuplicateKey` (11000) when
two customers' emails differ only in case, and keeps failing on every start until the data is fixed. Find and merge
those customers in a migration listed between the two, or in 007 itself, before 008 ships.

## Edge cases

### `_id`s of more than one BSON type

`orders` holds 1.2 million ObjectId `_id`s from the shop and 3,000 string `_id`s (`"mkt-1042"`) from orders imported
from a marketplace.

godwit: paging compares `_id` with `$gt`, which matches values of the same BSON type only, so a run would silently skip
every order of the other type. All numeric types (int, long, double, decimal) count as one type here, because `$gt`
and the `_id` sort compare them with each other, and so do strings and symbols. godwit fails instead of skipping:

- The first page has no checkpoint and reads the lowest `_id`s in BSON order, where numbers sort before strings and
  strings before ObjectIds. The run's type is that of its checkpoint's `lastId` when it resumes, and the first page's
  otherwise. Every page's `_id`s must have that type.
- Before the last page commits APPLIED, including a resumed run whose first page is empty, no document matching
  `pending` may have an `_id` of another type. This check runs outside any transaction, before the step sees the last
  page. It reads the documents of the other types that do not match `pending`, which can take long when they are many
  and no index serves `pending`, but no transaction lifetime limits it; with `pending` narrowed to one `_id` type, it
  reads nothing.

Either check fails the migration with `MigrationFailedException` in `IN_BATCHES`, naming both types by their `$type`
aliases (`number` for every numeric type, `string` for a symbol); the pages committed before it stay committed, and so
does the checkpoint. Here pages 1 to 6 hold the 3,000 string orders and commit. The next page finds no string above the
checkpoint, so it would be the last one; the second check finds the ObjectId orders and fails `006`, with a string as
its checkpoint's `lastId`:

```text
ERROR godwit - Migration failed id=006-order-totals step=IN_BATCHES attempts=1 error=java.lang.IllegalStateException: The documents of orders that match pending have _ids of two types, string and objectId. inBatches pages by _id with $gt, which compares values of one BSON type (all numeric types count as one), so it would skip the documents of the other type. Select one _id type per migration with Filters.type("_id", ...) in pending; a migration that has a checkpoint keeps the type of its lastId.
```

You: give each type its own run, and narrow the failed migration to the type of its checkpoint. Read it with `history()`
(`checkpoint.lastId`): here it is a string, so `006` keeps the strings and a new migration takes the ObjectIds.
Narrowing `006` to ObjectIds instead would resume after the string checkpoint and find no string (`$gt` on a string
matches strings only); the check before the last commit would then find the ObjectId orders and fail `006` again. A
migration without a checkpoint can be narrowed to either type; one with a checkpoint keeps its checkpoint's. Changing
`006` is safe because it has not applied on this database (it failed); on every database where it applied it never runs
again, and where it has not run yet the new migration totals the ObjectId orders that `006` now leaves out.

First move `006`'s page step into a `TransactionScope` extension in `006-order-totals.kt`, next to the private
`totalOf` it calls, so the new migration reuses it:

```kotlin
/** The page step of 006, as a scope extension so later migrations reuse it. */
fun TransactionScope.writeOrderTotals(orders: List<Document>) {
    collection("orders").bulkWrite(
        session,
        orders.map { order -> UpdateOneModel<Document>(eq("_id", order["_id"]), set("totalMinor", totalOf(order))) }
    )
    count("ordersUpdated", orders.size)
}
```

`006`'s step (a fragment of the migration), narrowed to the type of its checkpoint:

```kotlin
.inBatches("orders", pending = and(exists("totalMinor", false), type("_id", BsonType.STRING)), batchSize = 500) { orders ->
    writeOrderTotals(orders)
}
```

```kotlin
/** The orders with ObjectId `_id`s, which 006, narrowed to the type of its checkpoint, no longer selects. */
val objectIdOrderTotals = migration("017-object-id-order-totals")
    .inBatches("orders", pending = and(exists("totalMinor", false), type("_id", BsonType.OBJECT_ID)), batchSize = 500) { orders ->
        writeOrderTotals(orders)
    }
```

### Documents that still match `pending` after the step

`007-customer-email-lower` leaves customers without an email unchanged, so they still have no `emailLower` and still
match `pending`.

godwit: the next page starts after the last `_id` of this one, so they are not read again in this run. The run ends;
the migration is APPLIED with `customersWithoutEmail` counting them.

You: decide what those documents need, and change them in a later migration if they need anything. A loop that
re-reads `pending` from the start would never end here; the `_id` cursor is what prevents that.

### Documents inserted behind the checkpoint, and the previous release

Two cases leave documents with the old shape after the run:

- The app inserts a document whose `_id` sorts below the checkpoint while the run is in progress: string or UUID
  `_id`s, or ObjectIds made on a machine whose clock is behind. Pages never go back, so it is not visited.
- During a rolling deploy, processes on the previous release keep writing orders without `totalMinor`, during the run
  and after it has applied.

godwit: the migration is APPLIED; godwit does not look at the collection again.

You: make the new release write `totalMinor` on every order it creates or changes, and keep its reads correct for an
order without it until the old release is gone. Then add a migration with the same `pending`, which totals whatever was
written in between:

```kotlin
/** Totals for the orders that pods still running the previous release wrote while 006 ran. */
val orderTotalsCatchUp = migration("019-order-totals-catch-up")
    .inBatches("orders", pending = exists("totalMinor", false), batchSize = 500) { orders ->
        writeOrderTotals(orders)
    }
```

### Nothing to change

On a fresh database, `orders` is empty (or does not exist yet) when `006-order-totals` runs.

godwit: the first page reads nothing. Its transaction commits APPLIED without calling the step; the outcome has no
counters, so `outcome.count("ordersUpdated")` is 0.

You: nothing. This is what every fresh database and every test database does.

### Deleting documents in a page

```kotlin
import com.mongodb.client.model.Filters.`in`
import com.mongodb.client.model.Filters.size
import godwit.core.migration

/** Orders with no lines were never real orders: a checkout bug created them. */
val removeEmptyOrders = migration("018-remove-empty-orders")
    .inBatches("orders", pending = size("lines", 0), batchSize = 500) { orders ->
        val result = collection("orders").deleteMany(session, `in`("_id", orders.map { it["_id"] }))
        count("ordersRemoved", result.deletedCount)
    }
```

godwit: the checkpoint is the last `_id` value of the page, not a reference to the document, so the next page still
starts after it when that document is gone. The deletes and the checkpoint commit together.

You: nothing. When the deletion needs no atomicity with the history record, one `deleteMany(size("lines", 0))` in an
outside step does the same and is safe to repeat.

### A page fails

The step throws on page 120 because an order has a line without `unitPriceMinor`.

godwit: page 120's transaction aborts. Pages 1 to 119 stay committed with their checkpoint. godwit writes FAILED with
`lastError` and throws `MigrationFailedException` with `step = IN_BATCHES`; the app does not start. The next start runs
the outside step, then continues with page 120.

You: fix the step and deploy. Pages 1 to 119 do not run again, so when the bug also wrote wrong values in committed
pages, repair them in a new migration; editing this one does not reach them.

### The app writes a document while a page holds it

A customer pays for an order in page 42 while the page is in progress.

godwit: the page's write conflicts (`WriteConflict`, 112), and the driver runs the page body again in a new
transaction. The page is read again from a new snapshot (`pending` and the `_id` range are evaluated again), and the
counters for that page start from zero; `attempt` is 2. Committed pages are not affected.

You: nothing, unless `Retrying transaction` lines are frequent: then lower `batchSize`.

### The run loses the lock between pages

The process is paused for longer than the lease after committing page 70.

godwit: the `checkLock()` at the start of page 71, or before its commit, throws `LockLostException`, and that page's
transaction aborts. If the paused run had passed that check when another process took over, its checkpoint write
meets the new holder's RUNNING record, which carries a different `owner`. That record committed after the page's
transaction started, so the write conflicts with it (`WriteConflict`, 112), the transaction aborts, and the driver runs
the page again, whose first `checkLock()` throws. A page that starts after the record reads the new `owner`, and its
fenced checkpoint write matches nothing, which aborts that transaction too. The new holder resumes after page 70.

You: let the process restart. See [locking](locking.md).

### Few documents match `pending` in a large collection

`pending` selects 300 orders out of 20 million.

godwit: each page's query asks for documents matching `pending` above the checkpoint in `_id` order, inside the page's
transaction. Without an index that serves `pending`, the server walks the `_id` index through the non-matching orders
to fill a page. A page whose read takes longer than the transaction lifetime (60 s) never commits: the server aborts
it, the driver retries it until its 120 s window ends, and the migration fails without a checkpoint, the same way on
every start. `Slow transaction` lines for the migration's first page are the warning sign.

You: in the outside step, create an index whose prefix serves `pending` and that continues with `_id`, such as
`{totalMinor: 1, _id: 1}` for `exists("totalMinor", false)`, so each page reads only matching documents; or, when the
change is safe to repeat, use one server-side update instead ([below](#one-server-side-update-instead)).

### An invalid batch size, or `inBatches` in a repeatable

`batchSize = 0`, `batchSize = 20000`, or `inBatches` on a migration declared with `everyStart` or `repeatable`.

godwit: `validateMigrations` reports it, and `migrate` throws `InvalidMigrationsException` before any I/O.

You: use a size from 1 to 10000. For repeatables, see
[repeatable migrations](repeatable-migrations.md#no-inbatches-in-repeatables).

## Long backfills at startup

Migrations run before the app serves: the shop calls `migrate` at the top of `main`. A backfill that takes 20 minutes
holds the pod that runs it for 20 minutes, and every pod that starts meanwhile waits for the lock.

| Concern | What to do |
|---|---|
| Waiting pods give up after `LockConfig.waitTimeout` (10 minutes by default) with `LockTimeoutException`, and restart | Set `waitTimeout` above the longest migration plus one lease ([locking](locking.md#waiting-for-the-lock)) |
| A startup or readiness probe kills the migrating pod | Size the probe for the longest migration plus one lease. An `inBatches` migration resumes after its last committed page, so it finishes after enough restarts, but each restart costs up to one lease ([locking](locking.md#edge-cases)) |
| Nothing serves while the backfill runs on a new release | Run `migrate` in a separate process before the rollout (a job that calls `migrate` and exits); the app's own `migrate` then finds nothing due, or the app calls `requireUpToDate` ([history and reports](history-and-reports.md#status-and-requireuptodate)) |
| Pods of the previous release serve during the run | Keep both releases correct for documents with and without the new field ([outside-transaction steps](outside-transaction-steps.md#long-outside-steps-and-checklock)) |

## One server-side update instead

When the change needs no Kotlin logic and no atomicity with the history record, a single update in an outside step is
simpler and usually faster. The orders' totals as one server-side pipeline update, in a migration's outside step (a
fragment):

```kotlin
.outsideTransaction {
    val lineTotals = Document("\$map", Document("input", "\$lines").append("in", Document("\$multiply", listOf("\$\$this.quantity", "\$\$this.unitPriceMinor"))))
    val result = collection("orders").updateMany(
        exists("totalMinor", false),
        listOf(Document("\$set", Document("totalMinor", Document("\$sum", lineTotals))))
    )
    count("ordersUpdated", result.modifiedCount)
}
```

It is safe to repeat because of its filter, it has no transaction lifetime, and it does not commit with the history
record: the app sees orders change one by one, and an interrupted run leaves part of the work done for the next start.

| | `inTransaction` | `inBatches` | `updateMany` in an outside step |
|---|---|---|---|
| Commits with the history record | The whole change | Each page | Never |
| Size limit | One transaction: 60 s, cache | None; each page is one transaction | None |
| Logic | Kotlin | Kotlin, per page | Server expressions only |
| Must be safe to repeat | No | No | Yes, through its filter |
| After a failure | Runs again from scratch | Continues after the checkpoint | Runs again; the filter skips finished documents |
| What the app sees during the run | Nothing until the commit | Page by page | Document by document |

Single-update outside steps are covered in [outside-transaction steps](outside-transaction-steps.md#large-server-side-updates).

## Design decisions

### godwit pages, the author writes the page

Chosen: `inBatches` owns the paging (`_id` order, `pending` on every page), the checkpoint committed with each page,
the resume, the `_id` type check and the stop; the author writes what happens to one page.

Considered:

| Option | What the author writes | Why not chosen |
|---|---|---|
| Library-paged (chosen) | `{ orders -> bulkWrite(session, ...) }` | |
| Author-paged: the step gets the last checkpoint and returns the next | The query, the sort, the limit, the stop condition and the checkpoint value, in every migration | Every author re-implements paging, and the common mistakes (paging with `skip`, re-reading from the start, a checkpoint of the wrong type) loop forever or miss documents. The checkpoint becomes an untyped document. |
| No primitive: a loop in an outside step | The loop below | No page is atomic with anything, so the work must be safe to repeat, and the loop ends only when every document stops matching its filter |

The loop that "no primitive" leaves to the author, as a migration's outside step (a fragment):

```kotlin
.outsideTransaction {
    val orders = collection("orders")
    while (true) {
        checkLock()
        val page = orders.find(exists("totalMinor", false)).limit(500).toList()
        if (page.isEmpty()) break
        orders.bulkWrite(page.map { UpdateOneModel<Document>(eq("_id", it["_id"]), set("totalMinor", totalOf(it))) })
        count("ordersUpdated", page.size)
    }
}
```

It is correct only while every document it touches stops matching the filter. One order that the update cannot change
(a validator rejects it, or the step skips it on purpose, as 007 skips customers without an email) comes back in every
page, and the loop never ends. With `inBatches` the `_id` cursor moves past it.

### Paging by `_id`

Chosen: `_id` greater than the checkpoint, sorted by `_id`.

`_id` is unique, always indexed and never changes, so a page boundary is exact and the next page can always find its
start. `skip` gets slower with every page and shifts when documents stop matching `pending`. A field of the author's
choice may be missing, duplicated or changed by the step itself. The cost of `_id` paging is the one-type rule for the
`_id`s of the documents that match `pending` (all numeric types count as one), which godwit checks rather than leaves
to chance.

### One transaction per page, with the checkpoint inside

Chosen: each page's writes and its checkpoint commit together.

Considered: non-transactional pages with a checkpoint written after each. A crash between the page's writes and the
checkpoint repeats the page, so the page work would have to be safe to repeat. With the checkpoint inside the
transaction, each page commits exactly once, and a page may use `$inc` or deletes as freely as an `inTransaction` step.

### No `inBatches` in repeatables

Chosen: `validateMigrations` rejects `inBatches` in `everyStart` and `repeatable` migrations. A checkpoint marks
progress through one run of a change; a migration that runs again on every start or every revision would need to
decide when a checkpoint belongs to the previous run, and a full collection pass on every start is rarely what anyone
wants. A versioned once-only migration per change does the same job
([repeatable migrations](repeatable-migrations.md#no-inbatches-in-repeatables)).

## See also

- [Concepts](concepts.md): steps and the guarantee of each
- [Declaring migrations](declaring-migrations.md): valid step combinations
- [Transactions and sessions](transactions-and-sessions.md): `session`, retries and the transaction lifetime
- [Outside-transaction steps](outside-transaction-steps.md): the index before the pages, and single-update backfills
- [Repeatable migrations](repeatable-migrations.md): why repeatables cannot use `inBatches`
- [History and reports](history-and-reports.md): the `checkpoint` field and `MigrationOutcome.batches`
- [Failure and recovery](failure-and-recovery.md): a failed page as a worked example
- [Locking](locking.md): losing the lock between pages
- [Testing](testing.md): `runIsolated` for one batched migration
- [Configuration](configuration.md): `slowTransactionWarning`
- [Design decisions](design-decisions.md): every decision in one index
- [README](../README.md)
