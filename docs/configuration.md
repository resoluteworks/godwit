# Configuration

godwit has one entry point, `Godwit(cluster, databaseName, config)`, and one configuration type, `GodwitConfig`, with `LockConfig` nested in it. Both are immutable data classes, and every default is the recommended production value, so most applications pass no configuration at all. This page is the reference for every setting: what it does, its default, and when to change it. It also lists the settings that are fixed and why, and shows how to set up logging.

## Constructing `Godwit`

```kotlin
import com.example.shop.ShopConfig
import com.mongodb.kotlin.client.MongoClient
import godwit.core.Godwit

/** One Godwit for one database, built from the client that the app's services use. */
fun shopGodwit(client: MongoClient, config: ShopConfig): Godwit = Godwit(client, config.mongo.database)
```

| Parameter | Meaning |
|---|---|
| `cluster` | A `com.mongodb.kotlin.client.MongoCluster`. A `MongoClient` is one. godwit opens its sessions on it. |
| `databaseName` | The database to migrate. One `Godwit` migrates one database. |
| `config` | A `GodwitConfig`. Defaults to `GodwitConfig()`. |

- **Use the client the app's services use.** The session that godwit opens for a transactional step is valid only with the client that opened it. A service built on another client fails with an `IllegalStateException`, `state should be: ClientSession from same MongoClient`. The shop builds one `MongoClient` and gives it to the services and to `Godwit` (see [wiring at startup](dependencies.md#wiring-at-startup)).
- **`Godwit` keeps no state between calls.** Every `migrate`, `status` and `history` call reads history again. Instances are cheap, and a process usually builds one at startup.
- **A multi-database application builds one `Godwit` per database**, each with its own list, and loops over them itself.

godwit owns two collections in the database: the history collection (one document per migration) and the lock collection (one lock document). Their names are settings.

## `GodwitConfig`

| Setting | Type | Default | What it does | Change it when |
|---|---|---|---|---|
| `historyCollection` | `String` | `"godwit-history"` | One document per migration; `_id` is the migration id | your naming convention needs another name; never on a database that already has history (see the edge cases) |
| `lockCollection` | `String` | `"godwit-lock"` | One lock document; its `_id` is the history collection's name, so configurations with different history collections never share a lock | your naming convention needs another name; change it with every instance stopped, because runs that use two lock collections do not exclude each other |
| `lock` | `LockConfig` | `LockConfig()` | Lease, heartbeat, margin and wait (below) | migrations run for minutes, or the cluster fails over slowly |
| `outOfOrder` | `OutOfOrder` | `FAIL` | What happens to a pending once-only migration that is listed before an applied once-only migration | a database ran a branch early (staging, a developer's database): `RUN` |
| `unknownApplied` | `UnknownApplied` | `WARN` | What happens when history holds an applied id that the list does not know | a CI job migrates a copy of production and the build must not be older than it: `FAIL` |
| `untrackedDatabase` | `UntrackedDatabase` | `REFUSE` | What happens when the database has collections (other than godwit's two and `system.*`), no godwit history, and nothing was adopted | a database whose migrations are safe to repeat: `RUN_ALL`, rarely |
| `adoptApplied` | `((MongoDatabase) -> Set<String>)?` | `null` | Returns the ids already applied, to adopt a database migrated by another tool or by hand. Called under the lock, on a start that has work due, while history holds nothing but `ADOPTED` documents, so possibly more than once: it must only read | while databases are adopted; see [adopting-an-existing-database.md](adopting-an-existing-database.md) |
| `slowTransactionWarning` | `Duration` | `20.seconds` | A transaction attempt slower than this logs `Slow transaction` when it returns or throws | the server's transaction lifetime differs from 60 s, or you want an earlier signal |
| `holder` | `String` | `<hostname>/<pid>` | Names this process in the lock document, in history documents and in log lines | you want the pod and the release in them |

The enums:

| Enum | Values |
|---|---|
| `OutOfOrder` | `FAIL`: throw `PlanConflictException`, nothing runs. `RUN`: run the migration in list order and record `outOfOrder: true`. |
| `UnknownApplied` | `WARN`: log it and return it in `MigrationReport.unknownApplied`. `FAIL`: throw `PlanConflictException`. |
| `UntrackedDatabase` | `REFUSE`: throw `UntrackedDatabaseException`. `RUN_ALL`: run every migration as on an empty database. |

Every setting spelled out at its default (`holder` is left out, because its default names the host and the process):

```kotlin
import godwit.core.GodwitConfig
import godwit.core.LockConfig
import godwit.core.OutOfOrder
import godwit.core.UnknownApplied
import godwit.core.UntrackedDatabase
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Every setting at its default. `holder` is left out: its default names the host and the process. */
val defaults = GodwitConfig(
    historyCollection = "godwit-history",
    lockCollection = "godwit-lock",
    lock = LockConfig(lease = 60.seconds, heartbeat = 20.seconds, safetyMargin = 10.seconds, waitTimeout = 10.minutes),
    outOfOrder = OutOfOrder.FAIL,
    unknownApplied = UnknownApplied.WARN,
    untrackedDatabase = UntrackedDatabase.REFUSE,
    adoptApplied = null,
    slowTransactionWarning = 20.seconds
)
```

The default `holder` is `<hostname>/<pid>`, taken from the `HOSTNAME` environment variable when it is set, from the host name otherwise, and `unknown-host` when the host name lookup fails. In Kubernetes `HOSTNAME` is the pod name, so the default is already `shop-7f9c4/1`. It is informational: correctness uses a random token per lock acquisition, not the holder.

## `LockConfig`

| Setting | Default | What it does | Change it when |
|---|---|---|---|
| `lease` | `60.seconds` | How long the lock stays held without a renewal. A crashed holder blocks everyone else for up to this long | failovers on your cluster take longer than the lease allows (raise), or restarts after a crash must be quicker (lower) |
| `heartbeat` | `20.seconds` | How often the holder renews the lease | the lease changes; keep it well under `lease - safetyMargin` |
| `safetyMargin` | `10.seconds` | The holder treats the lock as lost this long before its lease would end without a renewal | clocks or pauses are unusually bad; keep it above the longest pause you accept |
| `waitTimeout` | `10.minutes` | How long `migrate` and `markApplied` wait while another process holds the lock. `Duration.ZERO` fails at once | any migration can run longer than ten minutes: set it above the longest |

The constructor checks the relations and throws `IllegalArgumentException` when one fails:

- `lease` is positive.
- `safetyMargin` is at least 0 and below `lease`.
- `heartbeat` is positive and below `lease - safetyMargin`.
- `waitTimeout` is not negative.

### How the lease works

The lock is one document. Its expiry is computed on the server (`$$NOW` plus `lease`), so client clocks do not matter. With the defaults:

| Time | What happens |
|---|---|
| 0 s | The lock is acquired; the server sets it to expire at 60 s |
| 20 s, 40 s | The heartbeat renews it; each renewal moves the expiry to 60 s after the renewal |
| 45 s | The holder crashes; the last renewal was at 40 s, so the lock expires at 100 s |
| 100 s | Another process acquires it |

The holder judges the lock lost by its own monotonic clock: `lease - safetyMargin` (50 s) after its last renewal was sent. That is 10 s before anyone else can take it, so a holder that is partitioned, or paused for less than `safetyMargin`, stops at its next lock check before a second holder starts. A longer pause can outlast the lease: the paused holder acts on the lost lock only when it wakes, after a second holder may have started, and from then on the owner fence keeps its transactional work from committing ([locking.md](locking.md#losing-the-lock-mid-run)). With the defaults the holder survives one missed renewal and loses the lock on two in a row. A failover on a replica set typically takes about 12 s, well inside the lease. When the holder finds the lock lost, the step it is in stops at the next `checkLock()`, an uncommitted transaction rolls back, and the process throws `LockLostException`. The process that holds the lock continues the work. See [locking.md](locking.md).

For a cluster whose elections take about 30 s:

```kotlin
import godwit.core.LockConfig
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** For a cluster whose elections take about 30 s: the lease outlasts one election. */
val patientLock = LockConfig(
    lease = 3.minutes,
    heartbeat = 30.seconds,
    safetyMargin = 20.seconds,
    waitTimeout = 30.minutes
)

/** Throws IllegalArgumentException when it is built: the heartbeat must be below lease minus safetyMargin (20 s). */
fun invalidLock(): LockConfig = LockConfig(lease = 30.seconds, heartbeat = 25.seconds)
```

## Setting it per environment

The shop picks its settings from an environment name that it reads from its own configuration. A missing variable fails the start: no environment is the default.

```kotlin
import com.example.shop.migrations.appliedBeforeGodwit
import godwit.core.GodwitConfig
import godwit.core.LockConfig
import godwit.core.OutOfOrder
import godwit.core.UnknownApplied
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

enum class Environment { LOCAL, CI, STAGING, PRODUCTION }

/** A missing variable fails the start: no environment is the default. */
fun environmentOf(env: Map<String, String> = System.getenv()): Environment =
    Environment.valueOf(env.getValue("SHOP_ENVIRONMENT"))

/** [holder] names this process in the lock document, in history and in log lines, for example pod and release. */
fun godwitConfig(environment: Environment, holder: String): GodwitConfig = when (environment) {
    // A developer's database has run migrations from other branches.
    Environment.LOCAL -> GodwitConfig(outOfOrder = OutOfOrder.RUN)

    // One process, a database that nothing else touches: a lock that is held is a bug, so do not wait for it.
    // Against a copy of production, an id that the code does not know means the build is older than production.
    Environment.CI -> GodwitConfig(
        lock = LockConfig(waitTimeout = Duration.ZERO),
        unknownApplied = UnknownApplied.FAIL
    )

    // Staging ran a branch early; its migrations may be listed before ones that staging has applied.
    Environment.STAGING -> GodwitConfig(outOfOrder = OutOfOrder.RUN, holder = holder)

    // The defaults, a wait longer than the longest migration, and the hook that adopts databases migrated by hand.
    Environment.PRODUCTION -> GodwitConfig(
        lock = LockConfig(waitTimeout = 15.minutes),
        adoptApplied = ::appliedBeforeGodwit,
        holder = holder
    )
}
```

The startup passes the result to `Godwit`:

```kotlin
import com.example.shop.ShopConfig
import com.example.shop.migrations.shopMigrations
import com.example.shop.services.CustomerService
import com.example.shop.services.IdentityProvider
import com.mongodb.kotlin.client.MongoClient
import godwit.core.Godwit
import godwit.core.MigrationReport

fun migrateShop(
    client: MongoClient,
    config: ShopConfig,
    identity: IdentityProvider,
    environment: Environment,
    holder: String
): MigrationReport {
    val customers = CustomerService(client.getDatabase(config.mongo.database))
    val godwit = Godwit(client, config.mongo.database, godwitConfig(environment, holder))
    return godwit.migrate(shopMigrations(config, customers, identity))
}
```

| Environment | Differs from the defaults | Why |
|---|---|---|
| Local development | `outOfOrder = RUN` | a developer's database has run migrations from other branches |
| CI | `waitTimeout = ZERO`, `unknownApplied = FAIL` | one process on a database nothing else touches, so a held lock is a bug; run against a copy of production, an id the code does not know means the build is older than production |
| Staging | `outOfOrder = RUN`, `holder` | staging ran a branch early (see below) |
| Production | `waitTimeout = 15.minutes`, `adoptApplied`, `holder` | the wait covers the longest migration (`006-order-totals`, about 2.5 minutes, plus one lease and room for growth; see [locking.md](locking.md#waiting-for-the-lock)), the hook adopts databases migrated by hand, the holder names pod and release |

Keep the list short. Every difference is something your tests of another environment do not prove about production.

### A staging database that ran a branch early

Staging ran a feature branch that added `008-cart-currency`. Main then merged `007-product-slugs`. The list is now `007-product-slugs`, `008-cart-currency`, and staging has `008` applied.

- With the default `OutOfOrder.FAIL`, staging throws `PlanConflictException` on its next start, with the problem `007-product-slugs is pending, but 008-cart-currency, listed after it, is applied (out of order; OutOfOrder.RUN runs it)`. Nothing runs.
- With `OutOfOrder.RUN` (the staging setting above), godwit runs `007` in list order and records `outOfOrder: true` on its history document:

```text
WARN  godwit - Running out-of-order migration id=007-product-slugs appliedAfter=[008-cart-currency]
```

Production never ran `008` before `007`, so it keeps `FAIL`. If the order matters to the data, reset staging instead.

## Settings that are fixed

These are not configurable. Each protects a guarantee, or is not worth a knob.

| Setting | Value | Why it is fixed |
|---|---|---|
| Transaction options | snapshot read concern, majority write concern, reads from the primary | the history record and the data it describes survive a failover together only with majority writes, the step's reads are one consistent view only under snapshot, and a transaction reads from the primary; another value breaks the exactly-once guarantee |
| Bookkeeping collections | majority read and write concern, primary reads, the driver's default codec registry | a lock acquired with `w:1` can be rolled back on failover and leave two holders; an app's custom codecs must not reach godwit's own documents |
| An outside step's `database` and `collection(...)` | majority write concern; the client's codec registry, read preference, read concern and timeout | a step's write with `w:1` can be rolled back on failover after the migration is recorded APPLIED, and the step never runs again ([outside-transaction steps](outside-transaction-steps.md#write-concern)) |
| Lock operation timeout | 5 s on the client for every lock operation | a stalled majority must not hold the heartbeat past the lease |
| Lock polling | 250 ms to 5 s with jitter; the holder is logged every 10 s | `waitTimeout` is the one knob that matters |
| Failure policy | stop at the first failure, no skip, no `failFast = false` | later migrations assume earlier ones |
| Rollback | none: godwit rolls forward only | transactions undo what they wrote; `markApplied` is the one escape hatch |
| Transaction retry window | 120 s, the driver's | `withTransaction` owns the retry loop |
| Transaction lifetime the guidance assumes | 60 s, the server's default `transactionLifetimeLimitSeconds` | the lifetime guidance line needs an attempt that ran at least 60 s, so a `NoSuchTransaction` after a short attempt, which has another cause, gets none; the default `slowTransactionWarning` assumes it too |
| Pause between runs of a transaction body | 5 ms before the second run, growing by half each time to at most 500 ms, with jitter | driver 5.7.0 runs the body again at once; the pause keeps a conflicting retry out of a tight loop |
| `Retrying transaction` lines | the first retry of a transaction, then at most one every 10 s | a retry storm stays readable; `transactionRetries` counts every retry |
| `ensureSearchIndex` polling | every second while `awaitReady` lasts, with `checkLock()` between polls in a step | a search index takes seconds to minutes to build, so a one-second poll adds little delay and little load |
| Batch size range | 1 to 10,000, per migration | declared with `inBatches(...)`, validated by `validateMigrations` |
| Id format | `[A-Za-z0-9][A-Za-z0-9._-]{0,127}` | the id is the `_id` of the history document |
| `lastError` stack trace | capped at 8 KB | history stays small |
| History document version | `v: 1` | tells a reader what it is looking at |

## Logging

godwit logs through slf4j-api 2.x to the logger named `godwit`. Its messages carry their data as key-value pairs, using the slf4j 2 fluent API. The application provides the binding. With no binding on the classpath, slf4j drops the messages and godwit is silent.

For Logback 1.5, print the pairs with `%kvp{NONE}`. It prints each value without quotes, as every log line in these docs shows; plain `%kvp` puts double quotes around every value (`runId="0199a4c2-..."`):

```xml
<configuration>
  <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
      <pattern>%-5level %logger{20} - %msg %kvp{NONE}%n</pattern>
    </encoder>
  </appender>
  <logger name="godwit" level="INFO"/>
  <root level="WARN">
    <appender-ref ref="STDOUT"/>
  </root>
</configuration>
```

```kts
dependencies {
    runtimeOnly("ch.qos.logback:logback-classic:1.5.32")
}
```

The first start of the release that adds `004-order-status`, on a database that has applied `001` to `003` and `reference-countries`, logs, at `INFO` (the every-start migration `bootstrap-customers` logs its own `Running migration` and `Applied migration` lines too; they are left out here):

```text
INFO  godwit - Acquired migration lock runId=0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f lockWaitMs=212
INFO  godwit - Running migration id=004-order-status kind=ONCE steps=[IN_TRANSACTION] attempt=1
INFO  godwit - Applied migration id=004-order-status kind=ONCE steps=[IN_TRANSACTION] attempts=1 txRetries=0 batches=0 durationMs=84 ordersPaid=1200 ordersPending=37
INFO  godwit - Migrations complete runId=0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f ran=2 recorded=0 upToDate=4 lockWaitMs=212 durationMs=402
```

A start with nothing pending logs one line and takes no lock, when the list has no every-start migration. This is the shop's list without `bootstrap-customers`; with it, every start takes the lock and runs `bootstrap-customers` ([locking.md](locking.md#the-fast-path-no-lock-when-nothing-is-due)):

```text
INFO  godwit - Migrations up to date runId=0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f checked=7 durationMs=6
```

Set the logger to `DEBUG` for one `Committed batch` line per committed page of an `inBatches` step that held documents. The full catalogue of messages and their keys is in [history-and-reports.md](history-and-reports.md).

### What to alert on

| Level | Message | Means |
|---|---|---|
| `WARN` | `Resuming interrupted migration` | an earlier run died mid-migration; this run takes over (`attempts` rises) |
| `WARN` | `Running out-of-order migration` | `OutOfOrder.RUN` ran a once-only migration listed before an applied once-only migration |
| `WARN` | `Retrying transaction` | the driver repeated a transaction body after a transient error |
| `WARN` | `Slow transaction` | an attempt passed `slowTransactionWarning`; the next step is `inBatches` or an outside step |
| `WARN` | `Unknown applied migrations` | history holds ids the list does not know; normal while older code runs after a rollback |
| `WARN` | `Marked migration applied` | someone used `markApplied`; the reason is in the line |
| `WARN` | `Lock renewal failed` | a heartbeat renewal threw; the run keeps the lock until its local deadline. Repeated lines point at the network or the replica set |
| `WARN` | `Lost migration lock` | the run lost the lock (`reason=NOT_OWNER` or `DEADLINE_PASSED`) and stops at its next check with `LockLostException` |
| `WARN` | `Lock release failed` | the release threw; the lease ends on its own, and the next start waits at most one lease |
| `ERROR` | `Migration failed` | a migration failed, or its run lost the lock; the process stops and the next start retries |
| `INFO` | `Waiting for migration lock` | another process holds the lock (`holder`, `holderRunId`). A `waitedMs` that keeps growing, or the line on every start, means a long migration or a holder that is stuck |

Some failures stop `migrate` with no godwit line of their own: `InvalidMigrationsException` (the list),
`PlanConflictException` (out of order, a partial squash, `UnknownApplied.FAIL`), `UntrackedDatabaseException`,
`TransactionsUnsupportedException`, `LockTimeoutException`, an exception from the `adoptApplied` hook, and a driver
exception from godwit's own reads and writes (no primary, for example); `requireUpToDate` throws
`PendingMigrationsException` the same way. Each one reaches the code that called godwit. Log it there, as
[failure and recovery](failure-and-recovery.md#using-it) does for `MigrationFailedException`, and alert on a process
that exits with an error or restarts in a loop.

Between deploys, a history document whose `state` stays other than `APPLIED` is a migration that keeps failing or was
interrupted; the first query in [querying history](history-and-reports.md#querying-history-from-the-mongo-shell) lists
them.

The driver logs separately, under `org.mongodb.driver`.

## Edge cases

Each case gives the state, what godwit does, and what you do.

### The history collection is renamed on a database that has history

- **State:** production has history in `godwit-history`. A change sets `historyCollection = "schema-history"`.
- **godwit:** the new collection is empty, so godwit sees no history. If `adoptApplied` is configured, it runs the hook and adopts the old record's ids; the migrations that godwit ran since then are not in that record and run a second time. Without a hook, the database has collections and no history, so `UntrackedDatabaseException`. The lock moves with the name, so a run holding the old lock does not exclude the new one.
- **You:** do not rename on a database that has history. If you must, stop every instance, rename the collection with `renameCollection`, and deploy the new `historyCollection` setting before anything starts.

### `waitTimeout` is shorter than the longest migration

- **State:** a migration takes 25 minutes on production (`006-order-totals` takes about 2.5 minutes; this one is a larger backfill). Three instances start; `waitTimeout` is 10 minutes.
- **godwit:** the first instance takes the lock and runs. The other two wait ten minutes, then throw `LockTimeoutException` naming the holder. The orchestrator restarts them, they wait again, and one of them gets the lock after the first finishes.
- **You:** set `waitTimeout` above the longest migration. Size the orchestrator's startup probe to match: the instance that migrates needs it to cover the migration, and the instances that wait need it to cover `waitTimeout`.

### `waitTimeout = ZERO` with an every-start migration

- **State:** production uses `Duration.ZERO`, and the list contains `bootstrap-customers`, an every-start migration.
- **godwit:** an every-start migration is due on every start, so every start takes the lock. In a rolling deploy two instances start close together, and the second throws `LockTimeoutException` at once, although nothing is wrong.
- **You:** keep a wait in production. `ZERO` fits one process on a database nothing else touches, such as CI. Prefer `repeatable(id, revision)` to `everyStart` where the work only needs to follow a revision: it keeps the no-lock fast path.

### `lease` and `heartbeat` do not fit

- **State:** `LockConfig(lease = 30.seconds, heartbeat = 25.seconds)`, with the default `safetyMargin` of 10 s.
- **godwit:** the heartbeat must be below `lease - safetyMargin` (20 s). The constructor throws `IllegalArgumentException` when the config is built, at startup and before any I/O.
- **You:** lower the heartbeat to a third of the lease or less. The `invalidLock()` function in the snippet above is this case.

### `lease` is too short for the cluster

- **State:** `LockConfig(lease = 15.seconds, heartbeat = 2.seconds, safetyMargin = 10.seconds)`: valid. A failover takes 12 s.
- **godwit:** the holder judges the lock lost 5 s after its last renewal. A 12 s stall, such as an election, loses the lock mid-migration. The run throws `LockLostException`, its uncommitted transaction rolls back, and the next holder resumes.
- **You:** keep the lease well above a failover. The default 60 s covers one with room to spare; `patientLock` above covers a slow cluster.

### `lease` is too long

- **State:** `lease = 10.minutes` with the default `waitTimeout` of 10 minutes.
- **godwit:** after a crash the lock stays held until its lease ends, up to ten minutes. Instances that start in that window wait the whole of `waitTimeout` and may throw `LockTimeoutException`.
- **You:** keep the lease close to a minute and make the heartbeat do the work of keeping it alive during long migrations.

### `unknownApplied = FAIL` in production

- **State:** release 12 applied `009-order-payment-status`. Release 12 is rolled back to release 11, which does not list it.
- **godwit:** `009` is applied and unknown to release 11. Under `WARN` the old release starts and logs it. Under `FAIL` it throws `PlanConflictException`, and the rollback cannot start.
- **You:** use `FAIL` only in a CI job that migrates a copy of production. A rollback produces unknown ids by design.

### `outOfOrder = RUN` in production

- **State:** two branches both add the next number; main merges the later one first and it runs, then the earlier one merges.
- **godwit:** under `RUN` the earlier migration runs after the later one, recorded `outOfOrder: true`. If the two touch the same data, the order that tests proved is not the order production ran.
- **You:** keep `FAIL` in production. A failed start is a signal to renumber, or to reset the staging database that ran the branch early.

### `untrackedDatabase = RUN_ALL` is left on

- **State:** the production config sets `RUN_ALL` to get past an adoption, and stays that way. A later deploy points `MONGO_DATABASE` at the wrong database, one that has collections and no godwit history.
- **godwit:** the guard is off, so every migration runs against that database.
- **You:** remove `RUN_ALL` as soon as the adoption is done. The default refuses an unknown database with data, which is the mistake it exists to catch.

### `slowTransactionWarning` above the server's limit

- **State:** `slowTransactionWarning = 90.seconds`, and the server aborts transactions after 60 s.
- **godwit:** an attempt slower than 60 s cannot commit, because the server aborts its transaction at 60 s. The warning fires only for attempts that fail anyway, never as an early signal.
- **You:** keep it below the transaction lifetime (the default is 20 s). Raise it only when the server's `transactionLifetimeLimitSeconds` is raised.

### Two instances with the same `holder`

- **State:** every instance sets `holder = "shop"`.
- **godwit:** nothing breaks: the lock uses a random token per acquisition, so two instances are still distinguished. The lock document, history and the `Waiting for migration lock` line all say `shop`, and an operator cannot tell which instance holds the lock.
- **You:** leave the default, or include something unique: `"$podName/$releaseVersion"`.

### The application uses a custom codec registry

- **State:** the `MongoClient` is built with a registry for Kotlin data classes.
- **godwit:** its own history and lock documents are read and written with the driver's default registry, so the app's codecs do not reach them. The `database` that steps receive comes from your client, so `database.getCollection("orders", Order::class.java)` in a step uses your registry.
- **You:** nothing. The shop's migrations use `Document` for the same reason: they need no codec at all.

## Design decisions

### One immutable config, every default a production value

**Chosen:** `GodwitConfig` is a data class, and `GodwitConfig()` is the configuration you would run in production.

**Alternatives:**

- A builder or DSL.
- Settings read by godwit from the environment.

```text
// not godwit API: godwit reads the environment itself
GODWIT_LOCK_WAIT_TIMEOUT=PT20M GODWIT_OUT_OF_ORDER=RUN java -jar shop.jar
```

**Why:** a data class with named arguments is already a builder, and `copy` gives per-environment variants. Reading your own configuration and passing values is explicit; godwit reading variables is a second configuration channel with its own names, parsing and failure modes. Defaults that are safe in production mean that the simplest code is the right code.

### Few knobs, and none for correctness

**Chosen:** nine settings, one of which holds the four lock settings. Concerns, timeouts, polling and the codec registry are fixed.

**Alternative:** expose read concern, write concern and timeouts.

**Why:** each fixed value protects a guarantee. A configurable write concern is a way to lose the atomicity of data and history. The settings that remain are decisions about your deployment: how long to wait, what to do with a conflict, how to name a process.

### Wait on a busy lock, with a timeout

**Chosen:** `migrate` waits up to `waitTimeout` (10 minutes by default); `ZERO` fails at once.

**Alternatives:** fail fast by default, or skip migrating when the lock is busy.

**Why:** a rolling deploy starts several instances together. Failing fast turns that into a restart loop. Skipping starts new code on an old schema, the one outcome that is never safe. Waiting costs a pod a few seconds and, in most deploys, finds nothing left to do.

### `outOfOrder = FAIL` and `unknownApplied = WARN`

**Chosen:** a pending once-only migration listed before an applied once-only migration refuses by default; an applied id that the list does not know only warns.

**Alternatives:** run out-of-order migrations silently, or fail on unknown ids.

**Why:** the first is a merge mistake that is cheap to fix before it runs and expensive after. The second is the normal state after a rollback, and refusing it would make rollbacks impossible.

### The relations between lock settings are checked when the config is built

**Chosen:** `LockConfig` validates `lease`, `heartbeat` and `safetyMargin` in its constructor.

**Alternative:** accept any values and fail, or misbehave, at run time.

**Why:** a heartbeat that is slower than the lease would lose the lock during its first migration. The check turns a failure that shows up at 3 a.m. into an exception at startup.

### Per-environment differences are code you own

**Chosen:** no profiles in godwit. The application maps its environment to a `GodwitConfig`, as `godwitConfig(environment, holder)` does.

**Alternative:** `GodwitConfig.forEnvironment("staging")` in godwit.

**Why:** godwit does not know your environments or how you name them, and a built-in profile would be a second place to look for a setting that changed behaviour.

## See also

- [adopting-an-existing-database.md](adopting-an-existing-database.md): `adoptApplied` and `untrackedDatabase`.
- [locking.md](locking.md): the lease, the heartbeat, waiting and `LockTimeoutException`.
- [ordering-and-validation.md](ordering-and-validation.md): `outOfOrder` and `unknownApplied`.
- [transactions-and-sessions.md](transactions-and-sessions.md): the same-client rule, the 60 s lifetime and `slowTransactionWarning`.
- [history-and-reports.md](history-and-reports.md): the log catalogue, the history document, the reports.
- [failure-and-recovery.md](failure-and-recovery.md): `LockTimeoutException`, `LockLostException` and the rest.
- [testing.md](testing.md): `testGodwit(config)`.
- [architecture.md](architecture.md): how the settings are used inside the runner.
- [concepts.md](concepts.md): the run lifecycle that the settings tune.
- [repeatable-migrations.md](repeatable-migrations.md): `everyStart` and `repeatable`, and what each does to the lock.
- [design-decisions.md](design-decisions.md): the index of all decisions.
- [../README.md](../README.md): the project overview.
