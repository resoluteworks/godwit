# Locking

godwit serialises migrations across every process that shares a database. When several instances of an app start at
once (a rolling deploy, an autoscaler, a developer running the app twice), exactly one of them runs the due
migrations and the others wait, then find the work done. The lock is a single leased document in `godwit-lock`, renewed
by a heartbeat and judged by the server's clock. This page covers what the lock does, how long processes wait, what
happens when a holder crashes or loses the lock mid-run, and the settings that control it. You never call the lock
yourself: [`Godwit.migrate`](concepts.md) and `Godwit.markApplied` take and release it.

## Contents

- [Using it](#using-it)
- [The lock document](#the-lock-document)
- [The fast path: no lock when nothing is due](#the-fast-path-no-lock-when-nothing-is-due)
- [Waiting for the lock](#waiting-for-the-lock)
- [Re-reading history after acquiring: a rolling deploy](#re-reading-history-after-acquiring-a-rolling-deploy)
- [A crashed holder](#a-crashed-holder)
- [Losing the lock mid-run](#losing-the-lock-mid-run)
- [Clock skew](#clock-skew)
- [Why there is no TTL index](#why-there-is-no-ttl-index)
- [Skipping migrations when the lock is busy](#skipping-migrations-when-the-lock-is-busy)
- [Edge cases](#edge-cases)
- [Design decisions](#design-decisions)
- [See also](#see-also)

## Using it

The lock needs no code. Every `migrate` call that finds work due takes it, holds it while it runs the migrations, and
releases it before returning or throwing. What you choose is how long a starting process waits for another one, through
`LockConfig`. The shop waits up to 15 minutes, because its longest migration, `006-order-totals`, takes about 2.5 minutes
on the production `orders` collection, and that collection grows:

```kotlin
import com.example.shop.loadShopConfig
import com.example.shop.migrations.shopMigrations
import com.example.shop.services.CustomerService
import com.example.shop.services.HttpIdentityProvider
import com.example.shop.services.OrderService
import com.example.shop.startHttpServer
import com.mongodb.kotlin.client.MongoClient
import godwit.core.Godwit
import godwit.core.GodwitConfig
import godwit.core.LockConfig
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes

private val log = LoggerFactory.getLogger("shop")

/**
 * The shop's start: migrate, then serve. 006-order-totals, the longest migration, takes about 2.5 minutes on the
 * production orders collection, which keeps growing, so a process that starts while another one migrates waits up
 * to 15 minutes.
 */
fun main() {
    val config = loadShopConfig()
    MongoClient.create(config.mongo.uri).use { client ->
        val database = client.getDatabase(config.mongo.database)
        val customers = CustomerService(database)
        val orders = OrderService(database)
        val identity = HttpIdentityProvider(config.identity.baseUrl, config.identity.apiKey)
        val godwit = Godwit(
            client,
            config.mongo.database,
            GodwitConfig(lock = LockConfig(waitTimeout = 15.minutes))
        )

        val report = godwit.migrate(shopMigrations(config, customers, identity))
        log.info("Migrated: ran={} lockWait={}", report.ran.map { it.id }, report.lockWait)

        startHttpServer(customers, orders)
    }
}
```

`report.lockWait` is the time this process spent waiting for the lock, or `null` when nothing was due and the lock was
never taken.

| `LockConfig` field | Default | What it controls |
|---|---|---|
| `lease` | 60 s | How long the lock stays held without a renewal. A crashed holder blocks others for at most this long. |
| `heartbeat` | 20 s | How often the holder renews the lease. Must be below `lease` minus `safetyMargin`. |
| `safetyMargin` | 10 s | The holder treats the lock as lost this long before the lease would end without a renewal. |
| `waitTimeout` | 10 min | How long `migrate` and `markApplied` wait for another holder. `Duration.ZERO` fails at once. |

The full configuration reference, with environment-specific examples, is in [configuration.md](configuration.md).

## The lock document

The lock is one document in `godwit-lock` whose `_id` is the history collection's name, `godwit-history` by default. All
of a database's migrations share it. While a run holds it:

```json
{
  "_id": "godwit-history",
  "owner": "5b0f3c6e-2a41-4f7e-9d1c-0e8a7b6c5d4f",
  "holder": "shop-7f9c4/1",
  "runId": "0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f",
  "acquiredAt": { "$date": "2026-10-02T10:14:00.210Z" },
  "refreshedAt": { "$date": "2026-10-02T10:14:40.214Z" },
  "expiresAt": { "$date": "2026-10-02T10:15:40.214Z" }
}
```

After the run releases it, the document stays, with the lease ended at the release time:

```json
{
  "_id": "godwit-history",
  "owner": "5b0f3c6e-2a41-4f7e-9d1c-0e8a7b6c5d4f",
  "holder": "shop-7f9c4/1",
  "runId": "0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f",
  "acquiredAt": { "$date": "2026-10-02T10:14:00.210Z" },
  "refreshedAt": { "$date": "2026-10-02T10:16:20.230Z" },
  "expiresAt": { "$date": "2026-10-02T10:16:31.902Z" },
  "releasedAt": { "$date": "2026-10-02T10:16:31.902Z" }
}
```

| Field | Meaning |
|---|---|
| `_id` | The history collection's name. Two `Godwit` configurations with different history collections have different locks. |
| `owner` | A random UUID per acquisition: the owner token. History documents written under the lock carry it too, which is how a run that lost the lock is fenced out (see [Losing the lock mid-run](#losing-the-lock-mid-run)). |
| `holder` | `GodwitConfig.holder`, `<hostname>/<pid>` by default. Diagnostics only. |
| `runId` | The `MigrationReport.runId` of the holding call. Every log line and history document that call writes carries it. |
| `acquiredAt`, `refreshedAt`, `expiresAt`, `releasedAt` | Server times (`$$NOW`). The lock is free when `expiresAt` is at or before the server's current time. |

The lock is free when its document is missing, when `expiresAt` has passed, or after a release (which sets `expiresAt`
to the release time). Nothing ever deletes the document; it always shows the last holder. The exact operations are in
[architecture.md](architecture.md#lock-operations).

## The fast path: no lock when nothing is due

Every `migrate` call starts by reading the history collection (majority read concern, primary). When no migration is
due, it returns at once: no lock, no writes, one log line, `report.lockWait == null`.

```text
INFO  godwit - Migrations up to date runId=0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f checked=7 durationMs=6
```

This is safe because migrations are code. Another process cannot add a due migration to this process's list, and godwit
writes `APPLIED` only after a migration's work has committed. A process that reads every migration as `APPLIED` (and
every repeatable at its current revision) has nothing to do, whatever other processes are doing.

What is due:

| Kind | Due when | Effect on the fast path |
|---|---|---|
| once-only (`migration(id)`) | no history document, or one that is not `APPLIED` | none: a start skips the lock once it has applied |
| repeatable (`repeatable(id, revision)`) | not `APPLIED`, or the stored `revision` differs | none: a start at the same revision skips the lock |
| every-start (`everyStart(id)`) | always, under `Target.Latest` | a list that contains one takes the lock on every start |

The shop's list ends with `bootstrap-customers`, an every-start migration, so every shop start takes the lock, runs that
migration and releases the lock. A second start of an up-to-date shop logs:

```text
INFO  godwit - Acquired migration lock runId=0199a4c3-1f20-7e44-a1b2-c3d4e5f60718 lockWaitMs=3
INFO  godwit - Running migration id=bootstrap-customers kind=EVERY_START steps=[OUTSIDE_TRANSACTION, IN_TRANSACTION] attempt=1
INFO  godwit - Applied migration id=bootstrap-customers kind=EVERY_START steps=[OUTSIDE_TRANSACTION, IN_TRANSACTION] attempts=1 txRetries=0 batches=0 durationMs=290 customersCreated=0
INFO  godwit - Migrations complete runId=0199a4c3-1f20-7e44-a1b2-c3d4e5f60718 ran=1 recorded=0 upToDate=7 lockWaitMs=3 durationMs=318
```

A list with no every-start migration (the shop's first seven) takes the fast path instead, and the "Migrations up to
date" line above is all it logs. Whether `bootstrap-customers` is worth the lock on every start is discussed in
[repeatable-migrations.md](repeatable-migrations.md). `Target.Before` and `Target.Through` never include every-start
migrations, so a targeted call with nothing due also takes the fast path.

A test proves the fast path with the real runner (from godwit-test, see [testing.md](testing.md)):

```kotlin
"a start with nothing due reads history and takes no lock" {
    val db = testGodwit()

    db.godwit.migrate(orderStatus).lockWait shouldNotBe null
    db.godwit.migrate(orderStatus).lockWait shouldBe null
}
```

## Waiting for the lock

When work is due and another process holds the lock, `migrate` waits:

1. It tries to acquire the lock. If the lock is held, it sleeps and tries again: 250 ms, then double each time up to
   5 s (250 ms, 500 ms, 1 s, 2 s, 4 s, 5 s, 5 s, ...). Every sleep is randomised (jitter), so waiting processes do not
   poll in lockstep.
2. Every 10 s it logs the holder at INFO, read from the lock document:

   ```text
   INFO  godwit - Waiting for migration lock holder=shop-7f9c4/1 holderRunId=0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f expiresAt=2026-10-02T10:15:00.210Z waitedMs=10004
   ```

3. When it acquires the lock, it logs `Acquired migration lock` with `lockWaitMs`, re-reads history and plans again
   (next section).
4. When `waitTimeout` passes first, it throws `LockTimeoutException` without having written anything. Its `holder` is
   the process holding the lock at that moment (`null` if the lock was released just as the wait ended) and `waited` is
   how long it waited.

A start that gives up should say who it waited for. The exception carries it:

```kotlin
/** Migrates, and on a lock timeout logs who holds the lock before failing the start. */
fun migrateOrExplain(godwit: Godwit, migrations: List<Migration>): MigrationReport =
    try {
        godwit.migrate(migrations)
    } catch (e: LockTimeoutException) {
        val holder = e.holder
        if (holder == null) {
            log.error("Waited {} for the migration lock; it was released as the wait ended", e.waited)
        } else {
            log.error(
                "Waited {} for the migration lock; {} (run {}) has held it since {}, lease until {}",
                e.waited, holder.holder, holder.runId, holder.acquiredAt, holder.expiresAt
            )
        }
        throw e
    }
```

`Duration.ZERO` makes a busy lock fail at once, which suits a one-off run (a CI job, an operator's command) that should
not queue behind a deploy:

```kotlin
/** For a one-off run (a CI job, an operator's command): fail at once when another process holds the lock. */
fun failFastGodwit(client: MongoClient, databaseName: String): Godwit =
    Godwit(client, databaseName, GodwitConfig(lock = LockConfig(waitTimeout = Duration.ZERO)))
```

A free lock is acquired normally with `Duration.ZERO`; only a held one fails.

Choosing `waitTimeout`: set it above the longest migration plus one lease (the time a crashed holder can block the
lock). For the shop that is 2.5 min + 60 s, so the default 10 min already covers it; 15 min leaves room for the orders
collection to grow. A waiting process that times out fails its start, and the orchestrator restarts it, so a
`waitTimeout` that is too short produces a crash loop that ends when the holder finishes (see
[Edge cases](#edge-cases)).

## Re-reading history after acquiring: a rolling deploy

A process decides that work is due before it waits for the lock. By the time it acquires the lock, another process may
have done that work. So every process re-reads history after acquiring the lock and plans again; the plan made before
the wait only decided whether to wait.

Release `2026.10.2` adds `006-order-totals` (about 1.2 million orders, 2400 pages of 500). Three new pods start within
the same second; the database has `001` to `005`, `reference-countries` at its current revision, and
`bootstrap-customers` from earlier starts:

| Time (server) | `shop-7f9c4/1` | `shop-2b8e1/1` | `shop-c55d0/1` |
|---|---|---|---|
| 10:14:00.150 | Reads history: `006-order-totals` and `bootstrap-customers` due | Same | Same |
| 10:14:00.210 | Acquires the lock, lease until 10:15:00.210 | Acquire fails: held | Acquire fails: held |
| 10:14:00.230 | Re-reads history: `006` still due. Runs it | Retries after 250 ms, 500 ms, 1 s, 2 s, 4 s, then about every 5 s | Same, different jitter |
| 10:14:10.2 | Page 160 | Logs `Waiting for migration lock holder=shop-7f9c4/1 expiresAt=...10:15:00.210Z waitedMs=10004` | Logs the same |
| 10:14:20.2 | Heartbeat: lease until 10:15:20.214 | Logs `expiresAt=...10:15:20.214Z waitedMs=20011` | Same |
| 10:14:40.2 | Heartbeat: lease until 10:15:40.214 | Keeps waiting | Keeps waiting |
| 10:16:31.6 | `006` applied (2400 batches). Runs `bootstrap-customers` | | |
| 10:16:31.9 | Releases the lock. Starts serving | | |
| 10:16:33.4 | | Acquires the lock (`lockWaitMs=153190`). Re-reads history: `006` is `APPLIED`. Runs only `bootstrap-customers` | |
| 10:16:33.7 | | Releases. Starts serving | |
| 10:16:35.1 | | | Acquires, re-reads, runs `bootstrap-customers`, releases, serves |

`shop-7f9c4/1` logs:

```text
INFO  godwit - Acquired migration lock runId=0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f lockWaitMs=4
INFO  godwit - Running migration id=006-order-totals kind=ONCE steps=[OUTSIDE_TRANSACTION, IN_BATCHES] attempt=1
INFO  godwit - Applied migration id=006-order-totals kind=ONCE steps=[OUTSIDE_TRANSACTION, IN_BATCHES] attempts=1 txRetries=0 batches=2400 durationMs=151370 ordersUpdated=1199873
INFO  godwit - Running migration id=bootstrap-customers kind=EVERY_START steps=[OUTSIDE_TRANSACTION, IN_TRANSACTION] attempt=1
INFO  godwit - Applied migration id=bootstrap-customers kind=EVERY_START steps=[OUTSIDE_TRANSACTION, IN_TRANSACTION] attempts=1 txRetries=0 batches=0 durationMs=290 customersCreated=0
INFO  godwit - Migrations complete runId=0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f ran=2 recorded=0 upToDate=6 lockWaitMs=4 durationMs=151694
```

`shop-2b8e1/1` logs:

```text
INFO  godwit - Waiting for migration lock holder=shop-7f9c4/1 holderRunId=0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f expiresAt=2026-10-02T10:15:00.210Z waitedMs=10004
INFO  godwit - Waiting for migration lock holder=shop-7f9c4/1 holderRunId=0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f expiresAt=2026-10-02T10:15:20.214Z waitedMs=20011
(one line every 10 s)
INFO  godwit - Acquired migration lock runId=0199a4c2-8a40-7d12-b3c4-1e2f3a4b5c6d lockWaitMs=153190
INFO  godwit - Running migration id=bootstrap-customers kind=EVERY_START steps=[OUTSIDE_TRANSACTION, IN_TRANSACTION] attempt=1
INFO  godwit - Applied migration id=bootstrap-customers kind=EVERY_START steps=[OUTSIDE_TRANSACTION, IN_TRANSACTION] attempts=1 txRetries=0 batches=0 durationMs=280 customersCreated=0
INFO  godwit - Migrations complete runId=0199a4c2-8a40-7d12-b3c4-1e2f3a4b5c6d ran=1 recorded=0 upToDate=7 lockWaitMs=153190 durationMs=153520
```

What to take from it:

- `006` ran once. The two waiting pods planned it before the wait and dropped it after re-reading history.
- The waiting pods started serving about 2.5 minutes later than they would have without the migration. Size startup
  probes for that (see [Edge cases](#edge-cases)).
- `bootstrap-customers` ran three times, once per pod, as every-start migrations do. Without it, the second and third
  pods would acquire the lock, find nothing due, log `Migrations complete ... ran=0` and release at once.
- Pods of the previous release kept serving throughout, on a database where some orders had `totalMinor` and some did
  not. Migrations in a rolling deploy must suit both releases (add fields and indexes first, remove them in a later
  release).

## A crashed holder

A holder that dies (OOM kill, `SIGKILL`, a node failure) stops renewing its lease. Nothing releases the lock; the lease
ends on its own, at most `lease` (60 s) after the last renewal, and the next waiting process acquires it within one
polling interval (about 5 s) after that.

Same deploy, but `shop-7f9c4/1` is OOM-killed at 10:15:10.0, in the middle of page 1050 of `006-order-totals`:

| Time (server) | What happens |
|---|---|
| 10:15:00.214 | Last successful heartbeat: lease until 10:16:00.214 |
| 10:15:10.0 | `shop-7f9c4/1` dies. Page 1050's transaction is open and uncommitted; pages 1 to 1049 and their checkpoint are committed |
| 10:15:10 to 10:16:00 | `shop-2b8e1/1` keeps logging `Waiting for migration lock holder=shop-7f9c4/1`, with `expiresAt` stuck at 10:16:00.214. An `expiresAt` that stops moving between two lines means the holder stopped renewing |
| 10:16:03.1 | `shop-2b8e1/1` acquires the lock, re-reads history: `006-order-totals` is `RUNNING` with a checkpoint after page 1049 |
| 10:16:03.1 | It logs `WARN Resuming interrupted migration id=006-order-totals attempts=2` and writes its own `RUNNING` marker (its owner token, `attempts: 2`) |
| 10:16:03.2 | It re-runs the outside step (the `createIndex` is a no-op now) and starts page 1050. The dead process's page 1050 transaction is still open on the server and holds the same orders, so this page's writes conflict (`WriteConflict`, 112) and the driver runs the page again until the server aborts the dead transaction, 60 s after it started (about 10:16:09.9). godwit pauses before each run (5 ms, growing by half each time to 500 ms, with jitter), so the page runs a few dozen times rather than in a tight loop; it logs `Retrying transaction` once, then at most every 10 s, and `transactionRetries` counts every run |
| 10:17:35 | `006` applied: `attempts=2`, `batches=2400` (1049 committed by the first process, 1351 by the second) |

No page is lost or applied twice: each page's writes and checkpoint commit together, so the uncommitted page 1050 left
nothing behind. A crash inside an outside step leaves partial DDL instead, which the next run completes because the outside
step is idempotent ([failure-and-recovery.md](failure-and-recovery.md#process-killed-mid-outside-step)).

You can watch this in a test by planting a lock document whose lease ends 10 s from now, as a crashed process leaves
it:

```kotlin
"a start waits for a crashed holder's lease to end, then runs" {
    val db = testGodwit(GodwitConfig(lock = LockConfig(waitTimeout = 2.minutes)))
    db.database.getCollection("godwit-lock", Document::class.java).insertOne(
        Document("_id", "godwit-history")
            .append("owner", "token-of-a-crashed-process")
            .append("holder", "shop-dead1/1")
            .append("runId", "0199a4c1-0d2e-7a11-8c3b-5d6e7f809a1b")
            .append("expiresAt", Date.from(Instant.now().plusSeconds(10)))
    )

    val report = db.godwit.migrate(orderStatus)

    report.lockWait!! shouldBeGreaterThan 9.seconds
    report.ran.map { it.id } shouldBe listOf("004-order-status")
}
```

## Losing the lock mid-run

A holder can lose the lock while it is alive: a network partition between it and the database, a primary that cannot
reach a majority, a stop-the-world pause longer than the lease. godwit detects it in two ways:

- **A renewal fails to match.** The heartbeat renews with a filter on the owner token. If the lock document now has
  another owner (or was deleted), the renewal matches nothing and the lock is lost.
- **The local deadline passes.** After every successful renewal, the holder sets a deadline on its own monotonic clock:
  the time it sent that renewal, plus `lease`, minus `safetyMargin` (60 s − 10 s = 50 s by default). If no renewal
  succeeds before it, the lock is lost. Because the deadline counts from when the renewal was sent, the holder gives
  the lock up at least `safetyMargin` before the server lets anyone else take it.

Once lost, the lock stays lost for that run: the heartbeat stops and never renews again. The run finds out at its next
check:

- godwit calls `checkLock()` before and after every step and before every commit (each transaction, each `inBatches`
  page);
- a long loop inside a step calls `checkLock()` itself, once per item (below).

`checkLock()` throws `LockLostException`. Inside a transaction that error is not transient, so the transaction aborts and
its writes roll back. The run writes nothing more to history and throws `LockLostException` from `migrate`. The process
fails its start and the orchestrator restarts it.

A second guard covers the case where the check passes and the lock is lost an instant later, before the commit lands: the
`APPLIED` record and every `inBatches` checkpoint are written inside the step's transaction with a filter on the owner
token. Once another process has taken over the migration (its `RUNNING` marker carries its own token), that write
matches nothing and the transaction aborts. A migration's transactional work therefore commits at most once, whatever
the timing.

### Example: a network partition during 006

| Time (server) | `shop-7f9c4/1` | Database | `shop-2b8e1/1` |
|---|---|---|---|
| 10:15:00.214 | Renewal succeeds. Local deadline 10:15:50.214 | Lease until 10:16:00.214 | Waiting |
| 10:15:05 | Network path to the database fails during page 1051 | Page 1050's checkpoint is committed | |
| 10:15:20.2 | Renewal times out (5 s) | | |
| 10:15:40.2 | Renewal times out | | |
| 10:15:50.214 | Local deadline passes: lock lost, heartbeat stops | | |
| 10:16:00.214 | | Lease ends | |
| 10:16:02.6 | | | Acquires, re-reads history: `006` `RUNNING`, checkpoint after page 1050. `WARN Resuming interrupted migration id=006-order-totals attempts=2` |
| 10:16:05 | The page 1051 call fails: no primary is reachable. The run checks the lock before going further, finds it lost, and `migrate` throws `LockLostException`, writing nothing to history. The start fails | | Running pages 1051 onwards |
| 10:16:40 | Network returns. The restarted process reads history: `006` is not `APPLIED`, so it waits for the lock | | |
| 10:17:31 | | | `006` applied, `attempts=2`. Releases |
| 10:17:33 | Acquires, re-reads, runs only `bootstrap-customers` | | |

`shop-7f9c4/1` logs:

```text
ERROR godwit - Migration failed id=006-order-totals step=IN_BATCHES attempts=1 error=godwit.core.LockLostException: Lost the migration lock while running 006-order-totals
```

The history document of `006-order-totals` while `shop-7f9c4/1` was running it:

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

After `shop-2b8e1/1` took it over (the checkpoint is unchanged; owner, holder, run and attempts are the new run's):

```json
{
  "_id": "006-order-totals",
  "kind": "ONCE",
  "steps": ["OUTSIDE_TRANSACTION", "IN_BATCHES"],
  "state": "RUNNING",
  "origin": "RAN",
  "attempts": 2,
  "checkpoint": {
    "lastId": { "$oid": "66fd0c4e9b1e8a0012a3f5c2" },
    "batches": 1050,
    "counts": { "ordersUpdated": 525000 }
  },
  "startedAt": { "$date": "2026-10-02T10:16:02.640Z" },
  "holder": "shop-2b8e1/1",
  "owner": "9a7c1e20-64b3-4c8f-a5d2-7e3f10b2c946",
  "runId": "0199a4c2-8a40-7d12-b3c4-1e2f3a4b5c6d",
  "godwitVersion": "1.0.0",
  "v": 1
}
```

### Example: a pause longer than the lease

A stop-the-world pause freezes `shop-7f9c4/1` for 75 s, from 10:15:08 to 10:16:23, inside page 1051's transaction, after
the page's writes and its fenced checkpoint update and before the commit.

- 10:15:50.214: the local deadline passes, but the process is frozen and cannot act on it.
- 10:16:00.214: the lease ends. 10:16:02.6: `shop-2b8e1/1` acquires the lock and writes its `RUNNING` marker. The
  frozen transaction has written the same history document, so the marker write waits for it.
- About 10:16:08: the frozen transaction reaches the server's 60 s transaction lifetime and is aborted. The marker
  write goes through: the document now carries `shop-2b8e1/1`'s owner token and the checkpoint after page 1050.
- 10:16:23: `shop-7f9c4/1` resumes. Its heartbeat finds the deadline passed and stops renewing. The transaction's
  commit fails, because the server already aborted the transaction; that error is transient, so the driver runs the
  page's body again, and the body's first `checkLock()` throws `LockLostException`. Without that check, the re-run's
  fenced checkpoint update would match nothing (the owner token is `shop-2b8e1/1`'s now) and abort the transaction.

### Long steps and `checkLock()`

godwit's own checks sit between steps and around commits. A step that loops for minutes should check inside the loop, so
a run that lost the lock stops early instead of finishing work another process is about to repeat. `005` makes one HTTP
call per customer in its outside step and checks before each:

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

`ensureSearchIndex(awaitReady = ...)` calls `checkLock()` between its polls for the same reason. `checkLock()` is a
local check (a flag and a clock comparison), so calling it per item costs nothing.

## Clock skew

The lock is decided by the server's clock, not the clients'.

- **Lease boundaries use `$$NOW`.** Acquire, renew and release compute `expiresAt` from the server's time, and acquire
  compares it with the server's time. A pod whose clock is 3 minutes behind takes and keeps the lock exactly like the
  others.
- **The holder's own deadline uses its monotonic clock** (elapsed time since the renewal was sent), so a wall-clock
  jump on the holder (an NTP step, a manual change) does not move it.
- **History timestamps are client times and informational.** `startedAt` and `finishedAt` come from the clock of the
  process that wrote them. With that 3-minute skew, the pod records `006-order-totals` as finished at 10:13:31 on a
  deploy that started at 10:14:00. `durationMs` is measured on the monotonic clock and stays correct.
- **The server's clock can jump on failover.** After an election, `$$NOW` is the new primary's clock. If it runs 4 s
  ahead of the old primary's, every lease ends 4 s earlier than the holder expects; the 10 s `safetyMargin` absorbs it.
  Skew larger than `safetyMargin` can let a waiting process take the lock while the holder still believes it holds it.
  The owner-token fence still keeps transactional work to one commit, but outside steps could run in both processes at
  once. Idempotent calls repeated one after another converge; calls that overlap (a long server-side update, two
  creates at an external service) need the measures in
  [outside-transaction steps](outside-transaction-steps.md#long-outside-steps-and-checklock). Run NTP on every replica
  set member; MongoDB expects synchronised clocks for replication anyway.

## Why there is no TTL index

A TTL index on `expiresAt` would make the server delete expired lock documents. It adds nothing godwit needs:

- The lock is free as soon as `expiresAt` passes, because acquire compares it with `$$NOW`. Deleting the document later
  changes nothing.
- The TTL monitor runs about once every 60 s and gives no deadline for deletion, so a correct lock cannot depend on it
  anyway.
- The document after a release (or a crash) is the best record of the last holder: who, which run, when it was last
  renewed. A TTL index would delete it.
- It would need index DDL on every database godwit touches. godwit's collections need only their `_id` index.

## Skipping migrations when the lock is busy

godwit always waits (up to `waitTimeout`) and has no "skip if busy" setting. An app that wants a process to start
without waiting can do it with `Duration.ZERO` and a `try`/`catch`:

```kotlin
/**
 * Migrates unless another process is migrating right now, in which case it returns null at once and the caller
 * starts anyway. The caller then runs this release's code on a database that may not have this release's
 * migrations yet: use it only where that code works on both schemas.
 */
fun migrateUnlessBusy(client: MongoClient, databaseName: String, migrations: List<Migration>): MigrationReport? {
    val godwit = Godwit(client, databaseName, GodwitConfig(lock = LockConfig(waitTimeout = Duration.ZERO)))
    return try {
        godwit.migrate(migrations)
    } catch (e: LockTimeoutException) {
        log.warn("Skipping migrations: {} holds the migration lock", e.holder?.holder)
        null
    }
}
```

The risk is the one the wait exists to prevent: new code on an old schema.

- **The code runs before its migrations.** In the rolling deploy above, a pod using `migrateUnlessBusy` would start
  serving at 10:14:00 with code that sorts a customer's orders by `totalMinor`, while `006-order-totals` has filled it
  for only some orders. Orders without it sort last, and totals show as missing, until 10:16:31.
- **Nobody may run them at all.** If the holder fails, the skipping process does not retry. The database stays behind
  until some process runs `migrate` again.
- **Unchecked by default.** Nothing in the skipping process notices if a later migration is due; only the next process
  that waits will run it.

Use it only for a process whose code works on both schemas. Usually such a process does not need to migrate at all: let
the app processes migrate, and have the other process call `requireUpToDate` (fail until the schema is ready) or
nothing ([history-and-reports.md](history-and-reports.md#status-and-requireuptodate)).

## Edge cases

**Two processes start on a database that has never had a lock document.**
Both send the acquire upsert at the same moment. One inserts the document and holds the lock; the other gets a
duplicate-key error (11000) on the `_id`, which godwit reads as "held", and waits like any other process. You do
nothing.

**`waitTimeout` is shorter than a migration.**
With `waitTimeout = 2.minutes` and `006-order-totals` taking 2.5 minutes, `shop-2b8e1/1` and `shop-c55d0/1` throw
`LockTimeoutException` at 10:16:00, exit and restart. Their next start finds `006` still due and waits again, then
finds it applied. Nothing breaks, but each pod crash-loops once and Kubernetes backs off its restarts. Set `waitTimeout`
above the longest migration plus one lease.

**The orchestrator kills the holder for starting too slowly.**
A startup probe that allows 2 minutes kills `shop-7f9c4/1` 2 minutes into `006`. The next holder resumes after the last
committed page, so an `inBatches` migration still finishes after enough restarts. A single long `inTransaction` step or
a long outside step restarts from the beginning every time and never finishes. Make the startup probe allow the longest
migration plus one lease, or run migrations in a separate pre-deploy process that calls `migrate` and exits.

**The holder hangs instead of crashing.**
An outside step blocks forever on an HTTP call that has no timeout. The heartbeat thread is healthy and keeps renewing
the lease, so the lock never expires. Every other start waits `waitTimeout` and fails. The lease protects against a
dead holder, not a stuck one. Give every external call a timeout. To recover now, kill the stuck process: its lease
ends within 60 s and the next start retries the migration.

**A primary election during the run.**
The primary steps down at 10:15:00 and a new one is elected 12 s later. The renewal at 10:15:00.2 times out; the one at
10:15:20.2 reaches the new primary and succeeds. The local deadline (50 s after the renewal sent at 10:14:40.2) never
passed, so the run continues. The page whose transaction was in flight fails with a transient error and the driver
runs it again. You do nothing. Elections usually take well under 60 s; if your deployment's take longer, raise the
lease (next case).

**Tuning the lease for slow failovers.**
A run keeps the lock as long as some renewal succeeds within `lease` minus `safetyMargin` of the previous successful one
(50 s by default, with an attempt every 20 s). A lease of 2 minutes, renewed every 30 s with a 20 s margin, stretches
that to 100 s, at the cost of a crashed holder blocking others for up to 2 minutes:

```kotlin
/** For a deployment whose failovers can take longer than a minute: a 2-minute lease, renewed every 30 s. */
val slowFailoverLock = LockConfig(lease = 2.minutes, heartbeat = 30.seconds, safetyMargin = 20.seconds)
```

`LockConfig` checks its own consistency when it is built. Shortening only the lease fails, because the default
heartbeat (20 s) is not below 30 s minus the default margin (10 s):

```kotlin
/** Throws IllegalArgumentException: the default heartbeat (20 s) is not below lease minus safetyMargin (20 s). */
fun shortLease(): LockConfig = LockConfig(lease = 30.seconds)
```

The message is "heartbeat must be positive and below lease minus safetyMargin". Lower `heartbeat` along with `lease`.

**Someone deletes the lock document during a run.**
`shop-7f9c4/1` holds the lock; an operator deletes `godwit-lock`'s document to "unstick" a deploy. The next renewal
matches nothing, the holder marks the lock lost and fails its run with `LockLostException` at its next check, and any
waiting process acquires a fresh lock at once (the acquire upsert recreates the document). The fence keeps the
transactional work safe, but the run is wasted and restarts. Never delete or edit the lock document. A lock that looks
stuck either belongs to a live run (look at `refreshedAt`: it moves every 20 s) or frees itself within 60 s.

**Someone sets `expiresAt` far in the future by hand.**
Every start waits `waitTimeout` and fails, until that time. Fix the document (set `expiresAt` to now) and leave it alone
afterwards.

**Two apps, or two configurations, in one database.**
The lock's `_id` is the history collection's name. A shop and a separate admin app that keeps its own history in
`admin-history` hold two different locks and can migrate at the same time. That is correct only if their migrations
never touch the same collections. If they do, give both the same history collection (and so the same lock), or better,
keep one app as the owner of the database ([libraries-and-modules.md](libraries-and-modules.md)).

**`migrate` called from two threads of one process.**
Each call is a separate run with its own owner token. The second waits for the first, exactly as a second process
would, then re-reads history and finds the work done.

**`markApplied` during a deploy.**
An operator runs `markApplied("008-customer-email-lower-index", reason = ...)` while a pod is running `006`. The command
waits for the lock like `migrate`, up to its own `waitTimeout`, then records the mark. Run it with a `waitTimeout` long
enough for the deploy, or after it.

**The holder dies after its last migration applied, before releasing.**
History is complete, but the lock document still shows the dead holder until its lease ends. A restarted process with
nothing due takes the fast path and starts at once; the lock is irrelevant to it. A shop pod, which always has
`bootstrap-customers` due, waits up to 60 s for the lease to end.

**A process is stopped while it waits.**
It never acquired the lock and has written nothing. The holder and the other waiting processes are unaffected.

**A database user without write access to `godwit-lock`.**
The first acquire fails with the server's `Unauthorized` error (13), which godwit does not wrap: driver errors from its
own lock and history operations propagate unchanged. Grant the app's user `readWrite` on the database, or at least on
`godwit-lock` and `godwit-history` (or the names you configured).

## Design decisions

### Wait with a timeout when the lock is busy

Chosen: a process that finds work due and the lock held waits, polling with backoff and logging the holder, for up to
`waitTimeout` (10 minutes), then fails. `Duration.ZERO` turns it into fail-fast.

Alternatives considered:

| Option | What a starting process does | Why not the default |
|---|---|---|
| Wait with a timeout (chosen) | Waits, re-reads history, runs what is still due (usually nothing), starts | Pods start later during a long migration; that is the correct outcome |
| Fail fast | Throws at once; the orchestrator restarts it with backoff | Works, but every rolling deploy with a migration becomes a series of crash loops and restart backoffs. Available as `waitTimeout = Duration.ZERO` |
| Skip | Starts without migrating | Runs new code on an old schema, the one outcome a migration tool exists to prevent. Possible in app code ([above](#skipping-migrations-when-the-lock-is-busy)), never a godwit setting |

### A leased document with server time, a heartbeat and an owner token

Chosen: one document per history collection, an `expiresAt` computed from `$$NOW`, renewed by a heartbeat thread every
`heartbeat`, plus an owner token that also fences history writes.

- **TTL-index lock** (insert a document, let a TTL index remove it): expiry depends on the TTL monitor's 60 s cycle, and
  nothing renews a long run's claim. Rejected.
- **No lock, fences only:** the owner fence on history alone would keep transactional work to one commit, but every pod
  would run every outside step concurrently: N identical index builds, N rounds of identity provider calls, N search
  index waits. Rejected.
- **A lock outside MongoDB** (an orchestrator lease, a separate coordination service): a second system to configure
  and to keep consistent with the history it protects. The history and the lock live in the same database, so the
  fence works within one transaction. Rejected.
- **Renew from `checkLock()` instead of a thread:** a step that blocks in a long call would stop renewing and lose the
  lock while it is healthy. A daemon heartbeat thread renews independently of the step; `checkLock()` stays a local check.
- **Fence by writing the lock document inside each transaction:** every heartbeat would then write-conflict with the
  open transaction, and the driver would re-run the transaction body on each renewal. The history document's owner
  field gives the same fence without touching the lock document.

### No lock when nothing is due

Chosen: read history first and return without touching the lock when nothing is due. Most starts of a deployed app
are this case, and they cost one query (about 6 ms in the shop). Taking the lock on every start would serialise every
pod start of every deploy, for no work. Every-start migrations opt out of this by design; that cost is visible in the
list ([repeatable-migrations.md](repeatable-migrations.md)).

## See also

- [concepts.md](concepts.md): the run lifecycle (validate, read history, fast path, lock, plan, run, release)
- [failure-and-recovery.md](failure-and-recovery.md): every crash window, including the lock-related ones, with the
  state before and after
- [history-and-reports.md](history-and-reports.md): the history documents the lock's owner token fences, and
  `MigrationReport.lockWait`
- [architecture.md](architecture.md): the exact lock operations, the heartbeat, read and write concerns
- [configuration.md](configuration.md): `LockConfig` and `GodwitConfig.holder` reference
- [transactions-and-sessions.md](transactions-and-sessions.md): the transaction lifetime and driver retries the lock
  interacts with
- [batched-backfills.md](batched-backfills.md): checkpoints, which make lock takeover resume instead of restart
- [repeatable-migrations.md](repeatable-migrations.md): why every-start migrations take the lock on every start
- [design-decisions.md](design-decisions.md): every design decision in one index
- [README](../README.md)
