# History and reports

godwit records what it did in two places. The `godwit-history` collection holds one document per migration, updated in
place, and is the source of truth for what is due: a migration whose document is `APPLIED` never runs again. Each
`migrate` call also returns a `MigrationReport` and writes structured log lines, which say what that one call did. This
page describes the history documents field by field, with an example of every state and origin, the report and status
APIs, `history()` and `markApplied`, the log lines, and how to query history from the mongo shell.

## Contents

- [Using it](#using-it)
- [The history collection](#the-history-collection)
- [Example documents](#example-documents)
- [Counts](#counts)
- [`MigrationReport` and `MigrationOutcome`](#migrationreport-and-migrationoutcome)
- [`status()` and `requireUpToDate()`](#status-and-requireuptodate)
- [`history()`](#history)
- [`markApplied(id, reason)`](#markappliedid-reason)
- [Log lines](#log-lines)
- [Querying history from the mongo shell](#querying-history-from-the-mongo-shell)
- [Edge cases](#edge-cases)
- [Design decisions](#design-decisions)
- [See also](#see-also)

## Using it

`migrate` returns a report of the call. The shop logs one line per migration it ran, so a deploy's log shows the work
each migration did:

```kotlin
/** Migrates and logs one line per migration that ran or was recorded, plus any history ids this release does not know. */
fun migrateAndSummarise(godwit: Godwit, migrations: List<Migration>) {
    val report = godwit.migrate(migrations)

    report.ran.forEach { outcome ->
        log.info(
            "{} ran in {} (attempt {}, {} transaction retries, {} batches): {}",
            outcome.id, outcome.duration, outcome.attempts, outcome.transactionRetries, outcome.batches, outcome.counts
        )
    }
    report.recorded.forEach { outcome ->
        log.info("{} recorded as {} without running", outcome.id, outcome.origin)
    }
    if (report.unknownApplied.isNotEmpty()) {
        log.warn("History holds migrations this release does not declare: {}", report.unknownApplied)
    }
    log.info(
        "run {}: {} up to date, lock wait {}, total {}",
        report.runId, report.upToDate.size, report.lockWait ?: "none (nothing was due)", report.duration
    )
}
```

godwit logs the same facts itself ([Log lines](#log-lines)); the report is for code that acts on them: a test that
asserts a counter, a start that publishes metrics, a deploy step that fails on unknown ids. The history collection is
for everything after the call: what has been applied to this database, when, by which process, and why something
failed.

## The history collection

`godwit-history` (configurable as `GodwitConfig.historyCollection`) holds one document per migration. Its `_id` is the
migration id, so there is exactly one document per id, and godwit updates it in place on every run. The collection has
only its `_id` index. It is small (one document per migration the app ever declared) and godwit reads all of it, with
majority read concern on the primary, at the start of every call.

| Field | Type | Written | Meaning |
|---|---|---|---|
| `_id` | String | always | The migration id |
| `kind` | String | always | `ONCE`, `EVERY_START` or `REPEATABLE`. In Kotlin, `HistoryEntry.kind` and `Migration.kind` are a `MigrationKind`: `MigrationKind.Once`, `MigrationKind.EveryStart` or `MigrationKind.Repeatable(revision)` |
| `revision` | String | repeatable, when a run applies it | The revision that was last applied. A different revision in code makes it due |
| `description` | String | when declared | The migration's description as of the last run |
| `steps` | [String] | always | `OUTSIDE_TRANSACTION`, `IN_TRANSACTION`, `IN_BATCHES`, in order, as of the last run. Empty for a migration recorded without running |
| `state` | String | always | `RUNNING`, `FAILED` or `APPLIED` |
| `origin` | String | always | `RAN`, `ADOPTED`, `SUPERSEDED` or `MARKED`: how it came to be (or is becoming) `APPLIED` |
| `attempts` | Int | always | Runs started since the last `APPLIED`, the current one included. 0 for a migration recorded without running |
| `transactionRetries` | Int | when a run applies it | Driver retries of transaction bodies in the run that applied it, over every transaction (every page of an `inBatches` step) |
| `counts` | Document | when a run applies it | The counters the steps set with `count`, such as `{ordersUpdated: 1199873}` |
| `durationMs` | Long | after a run | Duration of the last run, applied or failed, on the monotonic clock |
| `startedAt`, `finishedAt` | Date | `startedAt` when a run starts, `finishedAt` when it applies or fails | The writing process's clock. Informational |
| `lastError` | Document | when a run fails | `{type, message, stack, step, at}`. Kept while the migration is retried, removed when it applies |
| `checkpoint` | Document | `inBatches`, with each page | `{lastId, batches, counts}`: the last committed page. Removed when it applies |
| `runCount`, `lastRunAt` | Long, Date | repeatable and every-start, when a run applies it | Successful runs, and when the last one finished |
| `supersedes` | [String] | superseding migrations | The ids it replaces, stored so they stay known after the code drops the list |
| `outOfOrder` | Boolean | when true | It ran under `OutOfOrder.RUN` behind an applied migration listed after it |
| `reason` | String | `markApplied` | The reason given to `markApplied` |
| `holder` | String | always | `GodwitConfig.holder` of the process that last wrote the document |
| `owner` | String | always | The lock token of the run that last wrote `state`. Fences a run that lost the lock ([locking.md](locking.md#losing-the-lock-mid-run)) |
| `runId` | String | always | The `MigrationReport.runId` of the call that last wrote it |
| `godwitVersion`, `v` | String, Int | always | The godwit version that wrote it, and the document format version (1) |

Fields that do not apply are absent. `history()` reads an absent `counts` as an empty map and an absent `supersedes` as
an empty list.

How the states and origins combine:

| `state` | `origin` | Means | Next `migrate` |
|---|---|---|---|
| `RUNNING` | `RAN` | A run is in progress, or a run was interrupted (crash, lost lock) | If no process holds the lock: runs it again, logging `Resuming interrupted migration` |
| `FAILED` | `RAN` | The last run failed; `lastError` says why | Runs it again, outside step first |
| `APPLIED` | `RAN` | godwit ran it | Once-only: never again. Repeatable: when the revision changes. Every-start: on every start |
| `APPLIED` | `ADOPTED` | `GodwitConfig.adoptApplied` reported it applied before godwit tracked the database | Never runs it |
| `APPLIED` | `SUPERSEDED` | Every id it supersedes was applied, so it was recorded without running | Never runs it |
| `APPLIED` | `MARKED` | `markApplied` recorded it, with a `reason` | Never runs it |

## Example documents

### `RUNNING`: a run in progress

`001-initial-setup` on a new database, while its outside step creates collections and indexes:

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
  "godwitVersion": "1.0.0",
  "v": 1
}
```

The same document is what a crash leaves behind. The two cases differ only in the lock: a live run renews it, a dead one
does not ([locking.md](locking.md#a-crashed-holder)).

### `FAILED`, with `lastError`

`005-customer-external-ids` failed because the identity provider timed out during the outside step:

```json
{
  "_id": "005-customer-external-ids",
  "kind": "ONCE",
  "steps": ["OUTSIDE_TRANSACTION", "IN_TRANSACTION"],
  "state": "FAILED",
  "origin": "RAN",
  "attempts": 1,
  "durationMs": 30412,
  "startedAt": { "$date": "2026-10-02T10:14:05.300Z" },
  "finishedAt": { "$date": "2026-10-02T10:14:35.712Z" },
  "lastError": {
    "type": "java.net.http.HttpTimeoutException",
    "message": "request timed out",
    "stack": "java.net.http.HttpTimeoutException: request timed out\n\tat java.net.http/jdk.internal.net.http.HttpClientImpl.send(HttpClientImpl.java:950)\n\tat com.example.shop.services.HttpIdentityProvider.findOrCreateUser(IdentityProvider.kt:24)\n\t...",
    "step": "OUTSIDE_TRANSACTION",
    "at": { "$date": "2026-10-02T10:14:35.712Z" }
  },
  "holder": "shop-7f9c4/1",
  "owner": "5b0f3c6e-2a41-4f7e-9d1c-0e8a7b6c5d4f",
  "runId": "0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f",
  "godwitVersion": "1.0.0",
  "v": 1
}
```

`lastError.type` is the class of the exception the step threw (the cause of `MigrationFailedException`), and `stack`
is its stack trace, capped at 8 KB. `step` is the step that failed. The next start retries: the document goes back to
`RUNNING` with `attempts: 2` and keeps `lastError`, so a run in progress still shows why the previous one failed. When
that run applies, `lastError` is removed and `attempts` stays at 2.

### `APPLIED` by running it (`RAN`)

`004-order-status` after its transaction committed:

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
  "godwitVersion": "1.0.0",
  "v": 1
}
```

The `APPLIED` state, the counts and the order updates committed in one transaction: if the transaction had rolled back,
none of them would exist.

### `APPLIED` by adoption (`ADOPTED`)

A shop database migrated by hand before godwit, with `003-file-store` in its `schema-log`, adopted through
`GodwitConfig(adoptApplied = ::appliedBeforeGodwit)` ([adopting-an-existing-database.md](adopting-an-existing-database.md)):

```json
{
  "_id": "003-file-store",
  "kind": "ONCE",
  "steps": [],
  "state": "APPLIED",
  "origin": "ADOPTED",
  "attempts": 0,
  "finishedAt": { "$date": "2026-10-02T10:14:00.260Z" },
  "holder": "shop-7f9c4/1",
  "owner": "5b0f3c6e-2a41-4f7e-9d1c-0e8a7b6c5d4f",
  "runId": "0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f",
  "godwitVersion": "1.0.0",
  "v": 1
}
```

`finishedAt` is when godwit recorded the adoption, not when the change was made.

### `APPLIED` by a squash (`SUPERSEDED`)

`100-baseline` supersedes `001` to `006`. On a database where all six were applied, it is recorded without running
([squashing-migrations.md](squashing-migrations.md)):

```json
{
  "_id": "100-baseline",
  "kind": "ONCE",
  "steps": [],
  "state": "APPLIED",
  "origin": "SUPERSEDED",
  "supersedes": [
    "001-initial-setup",
    "002-carts",
    "003-file-store",
    "004-order-status",
    "005-customer-external-ids",
    "006-order-totals"
  ],
  "attempts": 0,
  "finishedAt": { "$date": "2027-03-01T09:00:00.410Z" },
  "holder": "shop-7f9c4/1",
  "owner": "0c4d1a7e-58f2-4b39-8e61-2d9a3f7b5c80",
  "runId": "019a1f3e-2c5d-7b80-9e1f-4a5b6c7d8e9f",
  "godwitVersion": "1.0.0",
  "v": 1
}
```

On a new database, `100-baseline` runs instead: `origin` is `RAN`, `steps` is `["OUTSIDE_TRANSACTION"]`, and the
`supersedes` list is stored all the same. The documents of `001` to `006` stay where they exist; the stored list keeps
those ids known, so they never show up as unknown applied ids, even after the code drops the `supersedes` list.

### `APPLIED` by hand (`MARKED`)

`008-customer-email-lower-index` failed on production because an equivalent index already existed under another name
(`IndexOptionsConflict`, code 85). An operator recorded it with `markApplied`
([failure-and-recovery.md](failure-and-recovery.md#manual-repair-with-markapplied)):

```json
{
  "_id": "008-customer-email-lower-index",
  "kind": "ONCE",
  "steps": ["OUTSIDE_TRANSACTION"],
  "state": "APPLIED",
  "origin": "MARKED",
  "reason": "Unique index on customers.emailLower built by hand as emailLower_unique during the 2026-10-05 incident",
  "attempts": 1,
  "durationMs": 212,
  "startedAt": { "$date": "2026-10-06T08:02:11.030Z" },
  "finishedAt": { "$date": "2026-10-06T09:41:52.118Z" },
  "holder": "ops-laptop-3/48211",
  "owner": "e2b7f9a4-0d13-4c6e-b8a5-91f2c3d4e5a6",
  "runId": "0199b0d7-4e21-7f00-8a3b-6c5d4e3f2a10",
  "godwitVersion": "1.0.0",
  "v": 1
}
```

`markApplied` sets `state`, `origin`, `reason`, `holder`, `owner`, `runId` and `finishedAt`, and removes `lastError` and
`checkpoint`. The other fields (`steps`, `attempts`, `durationMs`) are those of the failed run. For an id with no
document, it creates one with `kind: "ONCE"`, `steps: []` and `attempts: 0`.

### A repeatable migration

`reference-countries` after its second revision applied:

```json
{
  "_id": "reference-countries",
  "kind": "REPEATABLE",
  "revision": "2026-10-01",
  "steps": ["IN_TRANSACTION"],
  "state": "APPLIED",
  "origin": "RAN",
  "attempts": 1,
  "transactionRetries": 0,
  "counts": { "countriesRemoved": 0 },
  "durationMs": 41,
  "runCount": 2,
  "lastRunAt": { "$date": "2026-10-02T10:16:31.610Z" },
  "startedAt": { "$date": "2026-10-02T10:16:31.569Z" },
  "finishedAt": { "$date": "2026-10-02T10:16:31.610Z" },
  "holder": "shop-7f9c4/1",
  "owner": "5b0f3c6e-2a41-4f7e-9d1c-0e8a7b6c5d4f",
  "runId": "0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f",
  "godwitVersion": "1.0.0",
  "v": 1
}
```

`revision` is written in the same transaction as the work, so it always names the revision whose data is in the
database. When the code's revision becomes `"2026-11-15"`, the next start runs it again: the document goes to `RUNNING`
with `attempts: 1` (attempts count from the last `APPLIED`), and on success `revision` becomes `"2026-11-15"` and
`runCount` 3. `counts` and `durationMs` always describe the last run.

### An every-start migration

`bootstrap-customers` runs on every start; its document shows the last one:

```json
{
  "_id": "bootstrap-customers",
  "kind": "EVERY_START",
  "description": "Seed customers from configuration",
  "steps": ["OUTSIDE_TRANSACTION", "IN_TRANSACTION"],
  "state": "APPLIED",
  "origin": "RAN",
  "attempts": 1,
  "transactionRetries": 0,
  "counts": { "customersCreated": 0 },
  "durationMs": 290,
  "runCount": 214,
  "lastRunAt": { "$date": "2026-10-02T10:16:35.402Z" },
  "startedAt": { "$date": "2026-10-02T10:16:35.112Z" },
  "finishedAt": { "$date": "2026-10-02T10:16:35.402Z" },
  "holder": "shop-c55d0/1",
  "owner": "71d3a9c2-5e8f-4b10-a6c7-d8e9f0a1b2c3",
  "runId": "0199a4c2-9b51-7e23-84d5-2f3a4b5c6d7e",
  "godwitVersion": "1.0.0",
  "v": 1
}
```

### An `inBatches` migration in progress

`006-order-totals` with 1050 pages of 500 committed. The checkpoint commits in the same transaction as each page:

```json
{
  "_id": "006-order-totals",
  "kind": "ONCE",
  "steps": ["OUTSIDE_TRANSACTION", "IN_BATCHES"],
  "state": "RUNNING",
  "origin": "RAN",
  "attempts": 1,
  "checkpoint": {
    "lastId": { "$oid": "66fd0c4e9b1e8a0012a3f5c2" },
    "batches": 1050,
    "counts": { "ordersUpdated": 525000 }
  },
  "startedAt": { "$date": "2026-10-02T10:14:00.231Z" },
  "holder": "shop-7f9c4/1",
  "owner": "5b0f3c6e-2a41-4f7e-9d1c-0e8a7b6c5d4f",
  "runId": "0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f",
  "godwitVersion": "1.0.0",
  "v": 1
}
```

`lastId` is the `_id` of the last order of page 1050, and `counts` holds the counters of the committed pages. A run
that resumes after a crash or a failure starts after `lastId` and keeps adding to `batches` and `counts`. When the last
page commits, `checkpoint` is removed and `counts` holds the totals ([batched-backfills.md](batched-backfills.md)).

### A migration that ran out of order

On staging, `008-cart-currency` (from one branch) was applied before `007-product-slugs` (from another) was merged.
Staging runs with `OutOfOrder.RUN`, so `007` ran behind `008` and says so:

```json
{
  "_id": "007-product-slugs",
  "kind": "ONCE",
  "steps": ["IN_TRANSACTION"],
  "state": "APPLIED",
  "origin": "RAN",
  "outOfOrder": true,
  "attempts": 1,
  "transactionRetries": 0,
  "counts": { "productsUpdated": 412 },
  "durationMs": 61,
  "startedAt": { "$date": "2026-10-03T14:20:02.010Z" },
  "finishedAt": { "$date": "2026-10-03T14:20:02.071Z" },
  "holder": "shop-staging-1/1",
  "owner": "a4c8e2f0-1b3d-4f5a-9c7e-6d8b0a2c4e6f",
  "runId": "0199a8f1-3c2d-7a10-b4e5-f6a7b8c9d0e1",
  "godwitVersion": "1.0.0",
  "v": 1
}
```

## Counts

A step reports what it did with `count(name, n)`. godwit adds the numbers up per name and stores them in `counts`, logs
them at the end of the `Applied migration` line, and returns them in `MigrationOutcome.counts`.

| Rule | Example |
|---|---|
| The app names the counters; godwit counts nothing on its own | `004` counts `ordersPaid` and `ordersPending` from its two `updateMany` results |
| The same name in both steps adds up | an outside step and a transaction that both call `count("customersLinked", ...)` store their sum |
| A transactional step's counters reset when the driver re-runs its body | `004` retried after a `WriteConflict` reports the counts of the attempt that committed, not twice that |
| `inBatches` counters accumulate across pages and attempts through the checkpoint | `006` resumed after page 1050 ends with `ordersUpdated` equal to every order, not just the second run's |
| An outside step's counters are those of the run that applied | a retried outside step counts again from zero |
| Reading a counter that was never set returns 0 | `outcome.count("ordersCancelled") == 0L` |

Count what a reader of the history needs to trust the migration: rows changed, rows skipped, external records created.
In tests, the counts are the assertions (`outcome.count("ordersPaid") shouldBe 1L`, see [testing.md](testing.md)).

## `MigrationReport` and `MigrationOutcome`

`MigrationReport` is what one `migrate` call did:

| Property | Meaning |
|---|---|
| `runId` | A UUID per call. It is in every log line of the call and every history document the call wrote |
| `ran` | The migrations that ran, in run order, as `MigrationOutcome`s |
| `recorded` | The migrations recorded without running: adopted or superseded |
| `upToDate` | Ids found already applied, and repeatables found at their current revision |
| `pending` | Ids still due because the `Target` stopped before them. Always empty under `Target.Latest` |
| `unknownApplied` | `APPLIED` ids in history that the list does not know ([edge cases](#edge-cases)) |
| `lockWait` | Time spent waiting for the lock; `null` when nothing was due and the lock was never taken |
| `duration` | The whole call |
| `report[id]` | The outcome of `id` in `ran` or `recorded`; throws `NoSuchElementException` when the call did neither |

`MigrationOutcome` is one migration in `ran` or `recorded`:

| Property | Meaning |
|---|---|
| `id`, `kind` | As declared |
| `origin` | `RAN`, `ADOPTED` or `SUPERSEDED` |
| `steps` | The steps that ran; empty for a recorded migration |
| `attempts` | Runs started since it was last applied, this one included: 1 unless earlier runs failed or were interrupted |
| `transactionRetries` | Driver retries of transaction bodies in this call, over every transaction of the migration |
| `batches` | Pages committed by an `inBatches` step, over every attempt; 0 for other migrations |
| `counts`, `count(name)` | The counters, and one counter (0 when never set) |
| `outOfOrder` | True when it ran under `OutOfOrder.RUN` behind an applied migration listed after it |
| `duration` | This call's run of the migration |

When a migration fails, `migrate` throws `MigrationFailedException` instead of returning. Its `report` covers what the
call did before the failure (the migrations that ran and applied), its `id` and `step` name the failure, and its cause
is the step's exception ([failure-and-recovery.md](failure-and-recovery.md)).

## `status()` and `requireUpToDate()`

`status(migrations)` says what `migrate` with `Target.Latest` would do, without taking the lock or writing anything. It
validates the list first (and throws `InvalidMigrationsException` for an invalid one), then reads history.

| Property | Meaning |
|---|---|
| `pending` | The ids `migrate` would run, in run order. Never includes every-start migrations |
| `problems` | Why `migrate` would throw: an out-of-order migration under `OutOfOrder.FAIL`, a partially superseded squash, an untracked database, unknown applied ids under `UnknownApplied.FAIL` |
| `unknownApplied` | `APPLIED` ids the list does not know |
| `isUpToDate` | `pending` and `problems` are both empty |

A deploy pipeline can run it before the rollout, to see what the release will do to production and stop on problems:

```kotlin
/**
 * A deploy pipeline step, run before the rollout: prints what the new release would do to the database and returns
 * the exit code, 0 when nothing is pending.
 */
fun checkDeploy(): Int {
    val config = loadShopConfig()
    MongoClient.create(config.mongo.uri).use { client ->
        val database = client.getDatabase(config.mongo.database)
        val identity = HttpIdentityProvider(config.identity.baseUrl, config.identity.apiKey)
        val migrations = shopMigrations(config, CustomerService(database), identity)

        val status = Godwit(client, config.mongo.database).status(migrations)
        println("pending: ${status.pending}")
        println("problems: ${status.problems}")
        println("unknown applied: ${status.unknownApplied}")
        return if (status.isUpToDate) 0 else 1
    }
}
```

Building the list does not call any service (`HttpIdentityProvider` makes HTTP calls only when a migration runs), so
`status` is cheap.

`requireUpToDate(migrations)` throws `PendingMigrationsException` (with `pending` and `problems`) unless `status` is up
to date. It is for a process that runs the app's code but must not migrate, such as a worker deployed next to the app.
It runs no step, so the worker builds the list with stand-ins for the services it has no credentials for:
[a worker process that only checks the schema](dependencies.md#a-worker-process-that-only-checks-the-schema) shows the
whole worker.

If the worker starts while the shop's pods are still migrating, it fails, restarts and fails again until they finish.
To have it wait instead, poll `status`:

```kotlin
/**
 * Waits up to [timeout] for the shop's processes to finish migrating, checking every 5 seconds, then fails with
 * PendingMigrationsException. For a worker deployed together with the shop, so it starts as soon as the schema is ready.
 */
fun awaitUpToDate(godwit: Godwit, migrations: List<Migration>, timeout: Duration) {
    val deadline = TimeSource.Monotonic.markNow() + timeout
    while (!godwit.status(migrations).isUpToDate) {
        if (deadline.hasPassedNow()) godwit.requireUpToDate(migrations)
        Thread.sleep(5_000)
    }
}
```

## `history()`

`history()` returns every history document as a `HistoryEntry`, sorted by id. Its properties mirror the fields above:
`id`, `kind` (a `MigrationKind`, carrying the stored revision for a repeatable), `state`, `origin`, `description`,
`steps`, `attempts`, `transactionRetries`, `counts`, `duration`, `startedAt`, `finishedAt`, `lastError` (`type`,
`message`, `stack`, `step`, `at`), `checkpoint` (`lastId`, `batches`), `runCount`, `lastRunAt`, `supersedes`,
`outOfOrder`, `reason`, `holder`, `runId` and `godwitVersion`. It reads without the lock.

An admin command that prints it:

```kotlin
/** An admin command: one line per history document, with the error and checkpoint of unfinished ones. */
fun printHistory(godwit: Godwit) {
    godwit.history().forEach { entry ->
        println("${entry.id} ${entry.state} ${entry.origin} attempts=${entry.attempts} counts=${entry.counts}")
        entry.lastError?.let { error -> println("  last error in ${error.step}: ${error.type}: ${error.message}") }
        entry.checkpoint?.let { checkpoint ->
            println("  ${checkpoint.batches} batches committed, last _id ${checkpoint.lastId}")
        }
    }
}
```

```text
001-initial-setup APPLIED RAN attempts=1 counts={}
002-carts APPLIED RAN attempts=1 counts={}
003-file-store APPLIED ADOPTED attempts=0 counts={}
004-order-status APPLIED RAN attempts=1 counts={ordersPaid=1200, ordersPending=37}
005-customer-external-ids APPLIED RAN attempts=2 counts={customersLinked=812}
006-order-totals FAILED RAN attempts=1 counts={}
  last error in IN_BATCHES: java.lang.NullPointerException: Cannot invoke "java.lang.Long.longValue()" because the return value of "org.bson.Document.getLong(Object)" is null
  40 batches committed, last _id 66fcf2a19b1e8a0012a1c0d4
bootstrap-customers APPLIED RAN attempts=1 counts={customersCreated=0}
reference-countries APPLIED RAN attempts=1 counts={countriesRemoved=0}
```

And the ids that are not done:

```kotlin
/** The ids whose last run failed or never finished. */
fun unfinished(godwit: Godwit): List<String> =
    godwit.history().filter { it.state != HistoryState.APPLIED }.map { it.id }
```

## `markApplied(id, reason)`

`markApplied` records a once-only migration as `APPLIED` with origin `MARKED` and the given reason, without running
anything. godwit rolls forward only ([failure-and-recovery.md](failure-and-recovery.md)); this is the one way to skip a
once-only migration, and it leaves an audit trail: the reason, the holder that ran it, and a WARN log line.

```kotlin
/** Records the hand-made index as the outcome of 008, which would otherwise fail on this database. */
fun markEmailIndexApplied(godwit: Godwit) {
    godwit.markApplied(
        "008-customer-email-lower-index",
        reason = "Unique index on customers.emailLower built by hand as emailLower_unique during the 2026-10-05 incident"
    )
}
```

```text
WARN  godwit - Marked migration applied id=008-customer-email-lower-index reason=Unique index on customers.emailLower built by hand as emailLower_unique during the 2026-10-05 incident holder=ops-laptop-3/48211
```

| Existing document | Result |
|---|---|
| none | A new document: `kind: "ONCE"`, `state: "APPLIED"`, `origin: "MARKED"`, `reason`, `attempts: 0` |
| `RUNNING` or `FAILED`, once-only | Becomes `APPLIED`/`MARKED` with `reason`; `lastError` and `checkpoint` are removed |
| `APPLIED` (any origin) | Unchanged; the reason is not recorded |
| `kind: "REPEATABLE"` or `"EVERY_START"`, not `APPLIED` | `IllegalArgumentException`, nothing written |

It waits for the lock like `migrate` (up to `LockConfig.waitTimeout`), so it never marks a migration that a live run is
executing: the run finishes first. `reason` must not be blank. The id is not checked against any list; a typo shows up
as an unknown applied id on the next `migrate` ([edge cases](#edge-cases)).

Two limits:

- **Once-only migrations only.** An every-start migration is due on every start whatever its document says, and a
  repeatable stays due until a run applies its current revision (`MARKED` would keep the old `revision`). Marking either
  would change nothing, so `markApplied` refuses. The way past a failing repeatable or every-start migration is code:
  fix it, or remove it from the list.
- **Order.** Marking an id while once-only migrations listed before it are still pending makes those out of order on
  the next `migrate` ([ordering-and-validation.md](ordering-and-validation.md#out-of-order)). Mark ids in list order,
  or after the migrations before them have applied.

## Log lines

godwit logs through slf4j-api to the logger named `godwit`, with the facts as key-value pairs (the slf4j 2 fluent API).
With Logback, the `%kvp{NONE}` conversion word prints them after the message, as below; the setup is in
[configuration.md](configuration.md#logging). Every line of one call carries the same `runId` or migration `id`, so a
log search for either finds the whole story.

Every log line in these docs prints its values the same way:

| Value | Printed as | Example |
|---|---|---|
| Text, numbers | as they are, without quotes, spaces included | `holder=shop-7f9c4/1`, `durationMs=84` |
| Lists | in brackets, comma-separated | `steps=[OUTSIDE_TRANSACTION, IN_TRANSACTION]`, `appliedAfter=[008-cart-currency]` |
| Times | ISO-8601 instants with milliseconds | `expiresAt=2026-10-02T10:15:00.210Z` |
| `error` on `Migration failed` | the exception's class and message | `error=java.net.http.HttpTimeoutException: request timed out` |
| `error` on `Retrying transaction` | the code name and code of the error the previous attempt's body threw, or `commit` when the body returned and the commit failed with a transient error | `error=WriteConflict (112)`, `error=commit` |

| Level | Message | Keys | Example |
|---|---|---|---|
| INFO | Migrations up to date | `runId`, `checked`, `durationMs` | `Migrations up to date runId=0199a4c2-... checked=7 durationMs=6` |
| INFO | Waiting for migration lock | `holder`, `holderRunId`, `expiresAt`, `waitedMs` (every 10 s) | `Waiting for migration lock holder=shop-7f9c4/1 holderRunId=0199a4c2-... expiresAt=2026-10-02T10:15:00.210Z waitedMs=10004` |
| INFO | Acquired migration lock | `runId`, `lockWaitMs` | `Acquired migration lock runId=0199a4c2-... lockWaitMs=212` |
| INFO | Adopted applied migrations | `adopted`, `ignored` | `Adopted applied migrations adopted=[001-initial-setup, 002-carts, 003-file-store] ignored=[2025-02-cart-index-hotfix]` |
| INFO | Recorded superseded migration | `id`, `supersedes` | `Recorded superseded migration id=100-baseline supersedes=[001-initial-setup, ..., 006-order-totals]` |
| WARN | Resuming interrupted migration | `id`, `attempts` | `Resuming interrupted migration id=006-order-totals attempts=2` |
| WARN | Running out-of-order migration | `id`, `appliedAfter` | `Running out-of-order migration id=007-product-slugs appliedAfter=[008-cart-currency]` |
| INFO | Running migration | `id`, `kind`, `steps`, `attempt` | `Running migration id=004-order-status kind=ONCE steps=[IN_TRANSACTION] attempt=1` |
| WARN | Retrying transaction | `id`, `attempt`, `error` (the first retry of a transaction, then at most every 10 s) | `Retrying transaction id=004-order-status attempt=2 error=WriteConflict (112)` |
| WARN | Slow transaction | `id`, `attempt`, `durationMs` | `Slow transaction id=007-customer-email-lower attempt=1 durationMs=24310` |
| DEBUG | Committed batch | `id`, `batch`, `lastId` | `Committed batch id=006-order-totals batch=41 lastId=66fcf2a19b1e8a0012a1c4bc` |
| INFO | Applied migration | `id`, `kind`, `steps`, `attempts`, `txRetries`, `batches`, `durationMs`, then each counter | `Applied migration id=004-order-status kind=ONCE steps=[IN_TRANSACTION] attempts=1 txRetries=0 batches=0 durationMs=84 ordersPaid=1200 ordersPending=37` |
| WARN | Unknown applied migrations | `ids` | `Unknown applied migrations ids=[009-order-payment-status]` |
| ERROR | Migration failed | `id`, `step`, `attempts`, `error` | `Migration failed id=005-customer-external-ids step=OUTSIDE_TRANSACTION attempts=1 error=java.net.http.HttpTimeoutException: request timed out` |
| INFO | Migrations complete | `runId`, `ran`, `recorded`, `upToDate`, `lockWaitMs`, `durationMs` | `Migrations complete runId=0199a4c2-... ran=2 recorded=0 upToDate=6 lockWaitMs=212 durationMs=402` |
| WARN | Marked migration applied | `id`, `reason`, `holder` | see [above](#markappliedid-reason) |

Two different attempt numbers appear: `attempt` on `Running migration` is the migration's run count since it last
applied (the `attempts` field), while `attempt` on `Retrying transaction` and `Slow transaction` is the driver's attempt
at one transaction body (`TransactionScope.attempt`), which starts at 1 for every transaction and every page.

A deploy that adds `004-order-status` and `005-customer-external-ids`, on a database where the identity provider is slow
for the first start:

```text
INFO  godwit - Acquired migration lock runId=0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f lockWaitMs=212
INFO  godwit - Running migration id=004-order-status kind=ONCE steps=[IN_TRANSACTION] attempt=1
WARN  godwit - Retrying transaction id=004-order-status attempt=2 error=WriteConflict (112)
INFO  godwit - Applied migration id=004-order-status kind=ONCE steps=[IN_TRANSACTION] attempts=1 txRetries=1 batches=0 durationMs=131 ordersPaid=1200 ordersPending=37
INFO  godwit - Running migration id=005-customer-external-ids kind=ONCE steps=[OUTSIDE_TRANSACTION, IN_TRANSACTION] attempt=1
ERROR godwit - Migration failed id=005-customer-external-ids step=OUTSIDE_TRANSACTION attempts=1 error=java.net.http.HttpTimeoutException: request timed out
```

The next start:

```text
INFO  godwit - Acquired migration lock runId=0199a4c3-0a11-7c52-8d93-e4f5a6b7c8d9 lockWaitMs=3
INFO  godwit - Running migration id=005-customer-external-ids kind=ONCE steps=[OUTSIDE_TRANSACTION, IN_TRANSACTION] attempt=2
INFO  godwit - Applied migration id=005-customer-external-ids kind=ONCE steps=[OUTSIDE_TRANSACTION, IN_TRANSACTION] attempts=2 txRetries=0 batches=0 durationMs=48211 customersLinked=812
INFO  godwit - Running migration id=006-order-totals kind=ONCE steps=[OUTSIDE_TRANSACTION, IN_BATCHES] attempt=1
...
```

## Querying history from the mongo shell

The history and lock collections are plain documents; read them with any client. In `mongosh`, connected to the app's
database:

```javascript
// Everything not applied: running now, interrupted, or failed
db.getCollection("godwit-history").find({ state: { $ne: "APPLIED" } })

// One line per migration, in id order
db.getCollection("godwit-history")
  .find({}, { state: 1, origin: 1, attempts: 1, durationMs: 1, finishedAt: 1 })
  .sort({ _id: 1 })

// The five slowest migrations
db.getCollection("godwit-history").find({}, { durationMs: 1 }).sort({ durationMs: -1 }).limit(5)

// Why unfinished migrations failed
db.getCollection("godwit-history").find(
  { lastError: { $exists: true } },
  { state: 1, attempts: 1, "lastError.type": 1, "lastError.message": 1, "lastError.step": 1 }
)

// How far a backfill has got
db.getCollection("godwit-history").findOne({ _id: "006-order-totals" }, { state: 1, checkpoint: 1 })

// Everything that did not run on this database: adopted, superseded or marked
db.getCollection("godwit-history").find({ origin: { $ne: "RAN" } }, { origin: 1, reason: 1, supersedes: 1 })

// What one deploy did
db.getCollection("godwit-history").find({ runId: "0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f" })

// Who holds the lock, and whether the lease is live (server time)
db.getCollection("godwit-lock").aggregate([
  { $project: { holder: 1, runId: 1, refreshedAt: 1, expiresAt: 1, held: { $gt: ["$expiresAt", "$$NOW"] } } }
])
```

Read only. Every change to these collections goes through godwit: `migrate`, `markApplied`, and in tests the
godwit-test helpers. The edge cases below show what hand edits do.

## Edge cases

**A history document deleted by hand.**
An operator deletes `004-order-status`'s document to "re-run" it. The next start finds no document, so `004` is due and
runs again. Its filters (`exists("status", false)`) match nothing now, so it changes nothing and records
`ordersPaid: 0`. A migration whose work is not guarded by its own filter, such as one that sets a field on every
document, would apply twice. Never delete history to re-run a migration; write a new migration that does what you
need. In tests, `forget` and `rerun` from godwit-test do this deliberately ([testing.md](testing.md)).

**A history document edited by hand to `APPLIED`.**
It works like `markApplied` without the audit: no `reason`, no WARN line, the previous run's `owner`. Use `markApplied`,
which records who and why.

**A release rolled back.**
Release 2026.10.3 applied `009-order-payment-status`; production is rolled back to 2026.10.2, whose list ends at `008`.
On start, `009` is an `APPLIED` id the list does not declare. Under the default `UnknownApplied.WARN`, godwit logs
`Unknown applied migrations ids=[009-order-payment-status]`, lists it in `report.unknownApplied` and
`status().unknownApplied`, and carries on. The older code must cope with `paymentStatus` existing, which is why a
migration in a rolling deploy only adds. Rolling forward again finds `009` applied and skips it. With
`UnknownApplied.FAIL`, the rolled-back release refuses to start (`PlanConflictException`); choose it only for databases
that are never rolled back.

**Ids replaced by a squash, after the code drops the `supersedes` list.**
`100-baseline` was recorded with `supersedes: [001, ..., 006]`. A later release declares
`migration("100-baseline")` without `supersedes` and no longer lists `001` to `006`. Their history documents are still
there, `APPLIED`, and not declared, but they are known: an id named in any superseding migration's stored `supersedes`
counts as known. No warning.

**Renaming an id that has applied.** A refactor renames `004-order-status` to `004-order-statuses` in code. On databases
where `004-order-status` applied, the new id is pending and listed before the applied `005` and `006`, so `migrate`
throws `PlanConflictException` (out of order) and nothing runs; under `OutOfOrder.RUN` the backfill would run a second
time. Once the conflict is resolved, the old id is reported as unknown applied. Ids are permanent once applied anywhere.
To consolidate or rename, declare the new id with `supersedes = listOf("004-order-status")`
([squashing-migrations.md](squashing-migrations.md)), which records it without running wherever the old id applied.

**`markApplied` on an id that is already `APPLIED`.**
`markApplied("004-order-status", reason = "...")` changes nothing: the document keeps `origin: "RAN"` and the reason is
not stored. Nothing is logged.

**`markApplied` with a typo.**
`markApplied("008-customer-email-lower-indx", ...)` creates a `MARKED` document for an id nobody declares. The next
`migrate` still finds `008-customer-email-lower-index` due (and fails on the same index conflict), and warns about the
unknown `008-customer-email-lower-indx` on every start. Run `markApplied` with the right id. The typo's document stays
as an unknown applied id. Because nothing ran under it, it is safe to delete in the shell:
`db.getCollection("godwit-history").deleteOne({ _id: "008-customer-email-lower-indx", origin: "MARKED" })`.

**`markApplied` with a blank reason.**
`markApplied("008-customer-email-lower-index", reason = " ")` throws `IllegalArgumentException` before taking the lock or
writing anything.

**`status()` on a database that adoption has not reached yet.**
A database migrated by hand has no godwit history; the shop is configured with `adoptApplied`. Before any shop process
has started on it, `status()` reports every migration pending, because adoption runs only inside `migrate`, under the
lock. A worker calling `requireUpToDate` fails until a shop process has migrated (and adopted) the database. Start the
shop first, or use `awaitUpToDate` in the worker.

**Every-start migrations and `status()`.**
`status()` never lists an every-start migration as pending, even when its last run failed. `requireUpToDate` therefore
does not wait for `bootstrap-customers`; the shop's next start retries it.

**`report[id]` for a migration that did not run.**
On a second start, `report["004-order-status"]` throws `NoSuchElementException`: `004` is in `report.upToDate`, not in
`ran`. Check `report.ran.map { it.id }` or `report.upToDate` first.

**A targeted call.**
`migrate(migrations, target = Target.Before("004-order-status"))` on a new database runs `001` to `003`, and
`report.pending` includes `004-order-status`, `005-customer-external-ids` and `006-order-totals`, the once-only
migrations the target stopped before. A target never runs repeatable or every-start migrations. Targets exist for
tests ([testing.md](testing.md)).

**A description changed after the migration applied.**
`002-carts` is declared with a new description. Its history document keeps the description of the run that applied it,
since a once-only migration never runs again. Repeatable and every-start migrations store the description of their
last run.

**Clock skew on a pod.**
A pod whose clock is 3 minutes slow writes `startedAt` and `finishedAt` 3 minutes early; sorted by `finishedAt`,
history can look out of order across pods. `durationMs` is measured on the monotonic clock and is correct. The lock is
unaffected ([locking.md](locking.md#clock-skew)).

**Documents written by different godwit versions.**
After an upgrade, older documents show the older `godwitVersion`. `v` is the document format; godwit reads every format
it has written.

**A very long error.**
A step fails with a 50 KB stack trace (deep recursion). `lastError.stack` keeps the first 8 KB; the full trace is in the
`Migration failed` log line's exception.

## Design decisions

### One document per migration, updated in place

Chosen: one document per migration id, with `_id` = id, holding the current state and the facts of the last run.

| Option | "Is 004 applied here?" | Why not |
|---|---|---|
| One document per migration (chosen) | `findOne({ _id: "004-order-status", state: "APPLIED" })` | |
| A run log: one document per `migrate` call, listing what it did | Find the latest run that mentions `004` and check its outcome | Every start must reduce the whole log to the current state. Two runs can record the same id; nothing in the database prevents a second `APPLIED` |
| Append-only attempts: one document per attempt of each migration | `aggregate` the attempts of `004`, sorted, and take the last | The collection grows on every retry and every every-start run (the shop's `bootstrap-customers` adds one per pod start). The fence must target the newest attempt, which is a query, not a key |

With `_id` = id, the database enforces the rule that matters: a once-only migration's `RUNNING` marker is an upsert
filtered on `state != "APPLIED"`, and an applied document makes it fail with a duplicate key, so no run can start an
applied migration ([architecture.md](architecture.md#history-writes)). The fenced `APPLIED` write targets one known
document. The whole collection is read in one query on every start.

The cost is per-attempt history: a document shows the last run, plus `attempts` and the last error. The log lines carry
every attempt (`Running migration`, `Retrying transaction`, `Migration failed`, with `runId`), which is where an
event history belongs.

### A report object and log lines, no listener API

Chosen: `migrate` returns a `MigrationReport`, and godwit logs structured lines through slf4j. An app that wants metrics
reads the report after the call. A listener or event API would add callbacks that run inside the lock, on godwit's
thread, with their own failure modes, for information the report already has.

### Client clocks for informational timestamps

`startedAt`, `finishedAt` and `lastRunAt` come from the writing process's clock; nothing depends on them. Server time
(`$$NOW`) would need a pipeline update for every history write and still not make them more useful for reading. Only
the lock's lease, which decides correctness, uses server time.

### Counters named by the app

godwit could count modified documents itself, but only the step knows what matters: `004` distinguishes paid from
pending orders, `005` counts customers linked, not documents written. `count(name, n)` costs one line and makes the
history self-explanatory.

## See also

- [concepts.md](concepts.md): kinds, steps and the run lifecycle
- [locking.md](locking.md): the owner token in history, the fast path, waiting
- [failure-and-recovery.md](failure-and-recovery.md): what history looks like after every kind of failure
- [architecture.md](architecture.md): the exact history writes and the per-migration state machine
- [batched-backfills.md](batched-backfills.md): checkpoints
- [repeatable-migrations.md](repeatable-migrations.md): `revision`, `runCount`, `lastRunAt`
- [adopting-an-existing-database.md](adopting-an-existing-database.md): `ADOPTED` documents
- [squashing-migrations.md](squashing-migrations.md): `SUPERSEDED` documents and the stored `supersedes` list
- [testing.md](testing.md): asserting on reports, `forget`, `rerun`, `shouldHaveApplied`
- [configuration.md](configuration.md): history collection name, `UnknownApplied`, logging setup
- [design-decisions.md](design-decisions.md): every design decision in one index
- [README](../README.md)
