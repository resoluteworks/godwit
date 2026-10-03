# Adopting an existing database

A database that was migrated before godwit, by another tool or by hand, has no godwit history. Left alone, godwit would see every migration as pending and run all of them against live data. Adoption closes that gap. You give godwit a function that returns the ids of the changes that are already applied. godwit records those ids as applied (origin `ADOPTED`) and runs only what is left. The function is the only bridge: godwit has no knowledge of any other tool's records, and it never writes to them.

## The short version

- Write `(MongoDatabase) -> Set<String>`: it reads whatever record your database carries and returns the applied ids, spelled as the ids in your migration list.
- Pass it as `GodwitConfig(adoptApplied = ...)`.
- godwit calls it under the lock, on a start that has work due, while its history collection holds nothing but `ADOPTED` documents (an empty collection included). It records the ids history does not hold yet, with idempotent upserts, so an interrupted adoption completes on the next start, which calls the hook again.
- The first history document that adoption did not write (a migration that ran, a squash recorded as superseded, a `markApplied`) ends adoption: while such a document exists, the hook is not called. godwit decides from the history each start reads, so deleting every document that adoption did not write reopens adoption. Until adoption ends, the hook can be called more than once, so it must only read, and `markApplied` on a `Godwit` built with the hook refuses (`IllegalStateException`), so that a mark cannot end adoption before the hook has recorded the applied ids.
- Ids that the list declares as once-only (or names in a `supersedes` list) are recorded `APPLIED` with origin `ADOPTED`. Every other returned id is logged and not recorded.
- If the database has collections, no godwit history and the hook adopts nothing, `migrate` throws `UntrackedDatabaseException` before anything runs, so a forgotten hook, or one that finds nothing, cannot re-run your migrations over live data. [The prefix rule](#the-prefix-rule) catches adopted ids with a gap. A hook that adopts a valid prefix but misses the ids after it (a typo in a later id, a mapping that drops the tail) passes both, and the ids it missed run over live data: [compare the hook with the list](#compare-the-hook-with-the-list) and [rehearse on a restored copy](#rehearse-on-a-restored-copy) before the rollout.

## Wiring the hook

The shop's production database was migrated by hand. Each applied change was recorded as a document in a `schema-log` collection: `{ _id, version: "003-file-store", appliedAt }`. The hook reads the `version` field:

```kotlin
import com.mongodb.kotlin.client.MongoDatabase
import org.bson.Document

/**
 * The migrations applied to a shop database before godwit tracked it, for `GodwitConfig.adoptApplied`. Those
 * databases were migrated by hand, with one document per applied change in `schema-log`:
 * `{ _id: ObjectId, version: "003-file-store", appliedAt: Date }`.
 */
fun appliedBeforeGodwit(database: MongoDatabase): Set<String> =
    database.getCollection("schema-log", Document::class.java)
        .find()
        .map { it.getString("version") }
        .toList()
        .toSet()
```

It is wired in `GodwitConfig`. The rest of the startup is unchanged:

```kotlin
import com.example.shop.ShopConfig
import com.example.shop.migrations.appliedBeforeGodwit
import com.example.shop.migrations.shopMigrations
import com.example.shop.services.CustomerService
import com.example.shop.services.IdentityProvider
import com.mongodb.kotlin.client.MongoClient
import godwit.core.Godwit
import godwit.core.GodwitConfig
import godwit.core.MigrationReport

/** Migrates the shop database, adopting the changes that the hand-maintained `schema-log` already records. */
fun migrateWithAdoption(client: MongoClient, config: ShopConfig, identity: IdentityProvider): MigrationReport {
    val customers = CustomerService(client.getDatabase(config.mongo.database))
    val godwit = Godwit(client, config.mongo.database, GodwitConfig(adoptApplied = ::appliedBeforeGodwit))
    return godwit.migrate(shopMigrations(config, customers, identity))
}
```

`Godwit` and the shop's services are built from the same `MongoClient`, as always (see [configuration.md](configuration.md)).

### What the hook receives and must return

| | |
|---|---|
| Parameter | The `com.mongodb.kotlin.client.MongoDatabase` that `Godwit` migrates, from the cluster you passed to `Godwit`. |
| Result | A `Set<String>` of ids. Matching is exact and case-sensitive. |
| Reads | Whatever you like. A collection that does not exist reads as empty, so the hook is safe on a new database. |
| Writes | None. The hook only reads, and returns the same set for the same old record: godwit can call it more than once. |
| Runs | Under the migration lock, on a start that has work due, while every history document is `ADOPTED` (or there is none). No other instance migrates while it reads, and the lock's heartbeat keeps the lease alive however long the read takes. |
| Fails | An exception from the hook propagates out of `migrate`. The lock is released and nothing is recorded; the next start calls the hook again. |
| Is recorded | After the hook returns, godwit checks the lock and writes an `ADOPTED` document for every adoptable id that history does not hold yet, with an upsert that only inserts: a document that already exists is left as it is. On a replica set the documents go in one transaction, which the driver retries in the same call after a transient error such as a primary stepdown. On a standalone server they go one at a time, with a lock check before each, last-listed first: in the reverse of the list's once-only order, where each migration is preceded by the ids its `supersedes` list names. A process that dies while it records them leaves the adoption incomplete, and the next start calls the hook again and records what is missing. |

### Leaving the hook in place

The hook costs nothing once adoption is over. godwit decides whether to call it from the history it reads on every start anyway: it calls the hook on a start that has work due while history holds nothing but `ADOPTED` documents, and not while history holds a document of another origin (`RAN`, `SUPERSEDED` or `MARKED`). The hook runs on the adopting start. When that start also runs something, adoption ends there: the shop's first start always does, because its repeatable and its every-start migration run on it, and a new database runs the hook once against a collection that does not exist. When the adopting start runs nothing else (the adopting release adds no migrations, per step 2 of the rollout, and the list has no repeatable or every-start migration), adoption stays open: every instance that waited for the lock calls the hook again, and so does the first start of the next release with work due, before it runs anything. The same configuration adopts every environment, however old its database is.

Until a migration has run on the database (or one has been marked, or a squash recorded), the hook runs again on every start that takes the lock: after a refused gap, after an interrupted adoption, or on a later release when the adopting start ran nothing. Each of those starts pays for the read under the lock, and a start with nothing due takes the fast path and does not call it. That is why the hook must be a pure read: no writes, no calls to other services, and the same result for the same old record. A call that returns ids already recorded records nothing; a call that returns fewer ids removes nothing (see "The hook returns a different set on a later call").

There is one reason to remove it later: a database that loses its history while the hook is configured is adopted again from the old record, and replays everything godwit applied since (see "History is lost while the hook is configured"). Without the hook, the untracked-database guard refuses that database instead. Remove the hook when every environment is adopted and no old release can be deployed.

### Renaming ids on the way in

Another tool rarely spells ids as your list does. The mapping belongs in the hook, next to the code that reads the record. Here the old record is a `changelog` collection with one row per attempt:

```json
{ "changeId": "create-carts", "state": "DONE", "finishedAt": { "$date": "2026-01-14T09:30:00Z" } }
```

```kotlin
import com.mongodb.client.model.Sorts.ascending
import com.mongodb.kotlin.client.MongoDatabase
import org.bson.Document

/**
 * The ids as the old changelog spells them, mapped to the ids in the shop's list. An id that is not in the map is
 * returned as it is.
 */
private val RENAMED = mapOf(
    "create-core-collections" to "001-initial-setup",
    "create-carts" to "002-carts",
    "file-store-collection" to "003-file-store",
    "backfill-order-status" to "004-order-status"
)

/**
 * The changes that the old `changelog` collection records as done. A row is
 * `{ changeId, state: "DONE" | "FAILED", finishedAt }` and a change has one row per attempt; the newest row of a
 * change decides, so a change that failed once and then succeeded counts as applied.
 */
fun appliedInChangelog(database: MongoDatabase): Set<String> =
    database.getCollection("changelog", Document::class.java)
        .find()
        .sort(ascending("finishedAt"))
        .toList()
        .associate { row -> row.getString("changeId") to row.getString("state") }
        .filterValues { state -> state == "DONE" }
        .keys
        .map { changeId -> RENAMED[changeId] ?: changeId }
        .toSet()
```

Rules this hook follows, and yours should too:

- The newest row of a change decides, so a change that failed once and then succeeded counts as applied.
- An id missing from the map is returned as it is. godwit ignores ids it does not declare, so unmapped cleanup tasks cost nothing.
- Ids are mapped to the exact spelling of the list: `"002-carts"`, not `"002-Carts"`.

## Which ids are imported

godwit imports a returned id when the list declares it as a once-only migration (`migration(id)`), or when a `supersedes` list in the list names it (see [squashing-migrations.md](squashing-migrations.md)). Everything else is ignored.

The shop's list declares `001-initial-setup` to `006-order-totals` as once-only, `reference-countries` as repeatable and `bootstrap-customers` as every-start. The walkthrough on this page uses a production `schema-log` with six rows, so the hook returns six ids:

| Returned id | Imported | Why |
|---|---|---|
| `001-initial-setup`, `002-carts`, `003-file-store`, `004-order-status` | yes | declared once-only |
| `cleanup-temp-data` | no | the list does not declare it |
| `reference-countries` | no | declared, but repeatable: it is not adopted, so godwit runs it once on this start and records its revision |

The start logs what was imported and what was ignored:

```text
INFO  godwit - Adopted applied migrations adopted=[001-initial-setup, 002-carts, 003-file-store, 004-order-status] ignored=[cleanup-temp-data, reference-countries]
```

Then `005-customer-external-ids` and `006-order-totals` run as pending, followed by the repeatable and the every-start migration.

Each imported id becomes one history document with `state: APPLIED` and `origin: ADOPTED`. godwit knows nothing about how or when the change was applied, so an adopted document carries no counts and no duration. Query them from the shell:

```javascript
db.getCollection("godwit-history").find({ origin: "ADOPTED" }, { state: 1, origin: 1, runId: 1 })
```

### The prefix rule

The adopted once-only ids must form a prefix of the list's once-only order. With the shop's list:

| Hook returns | Result |
|---|---|
| nothing, on a database with no collections | all six run (a new database) |
| `001` | prefix: `002` to `006` run |
| `001`, `002`, `003` | prefix: `004` to `006` run |
| `001`, `003` | `002` is missing before an adopted id: the out-of-order policy decides |
| `003` only | `001` and `002` are missing before an adopted id: the out-of-order policy decides |

A migration in the list assumes the ones before it. A database where `003` is applied and `002` is not is in a state nobody tested. The policy is `GodwitConfig.outOfOrder`: `FAIL` (the default) throws `PlanConflictException` and runs nothing; `RUN` runs the missing migrations in list order and records `outOfOrder: true` on them. See [ordering-and-validation.md](ordering-and-validation.md).

godwit checks the prefix after the hook has run. Out-of-order and partially superseded squashes are normally checked before the lock, but while the hook is configured and history holds nothing but `ADOPTED` documents, those two checks wait until the hook has run under the lock and its ids are recorded. A gap that the hook fills, such as the one an interrupted adoption leaves, is therefore never reported; a gap that remains after the hook follows the policy.

## The untracked-database guard

When godwit's history is empty, the database has at least one collection that is not godwit's, and the hook adopted nothing (or there is no hook), `migrate` throws `UntrackedDatabaseException`. The exception lists the collections it found:

```text
The database has collections [customers, orders, products, schema-log] but no godwit history. Configure GodwitConfig.adoptApplied to adopt the migrations already applied, or set UntrackedDatabase.RUN_ALL to run every migration.
```

What counts as a collection that makes a database non-empty:

| Present in the database | Counts |
|---|---|
| any collection of the application, empty or not | yes |
| a collection that a library or component creates when it starts | yes |
| a hand-maintained record such as `schema-log` | yes |
| `godwit-history` and `godwit-lock` (the names in `GodwitConfig`) | no |
| `system.*` collections | no |

The guard is `UntrackedDatabase.REFUSE` by default. `UntrackedDatabase.RUN_ALL` runs every migration as on an empty database. Choose it only for a database whose migrations are safe to repeat over its data, for example a development database that was built by hand with the same schema and holds nothing worth keeping. Never leave it on in production: it removes the protection that a wrong database name or a mistyped collection name relies on.

Both policies as configuration, for the cases that need them:

```kotlin
import com.example.shop.migrations.appliedBeforeGodwit
import godwit.core.GodwitConfig
import godwit.core.OutOfOrder
import godwit.core.UntrackedDatabase

/** A gap in the adopted ids runs the missing migration instead of failing; for a database that skipped one. */
val adoptAndRunGaps = GodwitConfig(adoptApplied = ::appliedBeforeGodwit, outOfOrder = OutOfOrder.RUN)

/** No hook, and a database with data runs every migration: only for a database whose migrations are safe to repeat. */
val runEverything = GodwitConfig(untrackedDatabase = UntrackedDatabase.RUN_ALL)
```

## Rolling out

Adoption touches production once, on the first start of the first godwit release. This checklist makes that start boring.

1. **Write the hook and test it** against a seeded old record (see [testing.md](testing.md)). Cover a normal record, an id that the list does not declare, a failed-then-succeeded change and an empty database.
2. **Make the list match the database.** Every change that the old record contains must be a migration in the list, under the id that the hook returns, with the same effect. Do not add new migrations to this release: the first godwit release adopts and adds nothing, and the next release adds migrations as usual.
3. **Compare the hook with the list** (below). Anything the hook returns that the list will not import is a typo or an obsolete task.
4. **Rehearse on a restored copy of production** (below). The result must show exactly the ids you expect adopted and exactly the migrations you expect to run.
5. **Leave `untrackedDatabase` at `REFUSE`.** The rehearsal proves the hook works on a copy; the guard protects the deployment if production differs from the copy.
6. **Deploy.** The first instance takes the lock, adopts and runs what is left. The others wait. When the first start ran something, they find it in history when they get the lock and do not call the hook; when it ran nothing, history holds only `ADOPTED` documents, and each of them calls the hook again and records nothing new (see "Two instances start at once").
7. **Verify.** `history()` holds an `ADOPTED` document per imported id, `status(migrations).isUpToDate` is true, and the logs contain `Adopted applied migrations`. Then check whether adoption has ended: `history()` holds a document that adoption did not write. If every document is `ADOPTED`, adoption is still open, and the next start with work due calls the hook again (see "Leaving the hook in place").
8. **Keep the old record.** Do not delete or edit it while any old release can still run. While the hook is configured, keep it readable and unchanged until history holds a document that adoption did not write, or remove the hook first.

### Compare the hook with the list

The ids that the hook returns and godwit will not import are listed by a plain function over the hook's result and the list:

```kotlin
import godwit.core.Migration
import godwit.core.MigrationKind

/**
 * The ids that the hook returns and godwit will not import: the list declares none of them as once-only and no
 * `supersedes` list names them. Check them by eye before the first rollout; a typo shows up here.
 */
fun idsTheListIgnores(applied: Set<String>, migrations: List<Migration>): Set<String> {
    val imported = migrations
        .filter { it.kind == MigrationKind.Once }
        .flatMap { migration -> listOf(migration.id) + migration.supersedes }
    return applied - imported.toSet()
}
```

For the shop's six-row `schema-log` it returns `cleanup-temp-data` and `reference-countries`: the first is an obsolete task, the second a repeatable, which runs once on the first start instead of being adopted. Both are intended. For the `changelog` hook above it returns the unmapped cleanup tasks. A typo shows up here too.

### Rehearse on a restored copy

Restore a backup of production into a scratch database and run the real adoption there. Pass a fake or sandbox identity provider: the outside steps of the pending migrations run for real, and `005-customer-external-ids` calls the identity provider.

```kotlin
import com.example.shop.ShopConfig
import com.example.shop.migrations.appliedBeforeGodwit
import com.example.shop.migrations.shopMigrations
import com.example.shop.services.CustomerService
import com.example.shop.services.IdentityProvider
import com.mongodb.kotlin.client.MongoClient
import godwit.core.Godwit
import godwit.core.GodwitConfig
import godwit.core.Origin

/** What the rehearsal on a restored copy of production recorded and ran. */
data class Rehearsal(val adopted: List<String>, val ran: List<String>, val readyForNextRelease: Boolean)

/**
 * Runs the adoption against [copyName], a restored copy of the production database. [identity] is a fake or a
 * sandbox: the outside steps of the pending migrations call it for real.
 */
fun rehearseAdoption(client: MongoClient, copyName: String, config: ShopConfig, identity: IdentityProvider): Rehearsal {
    val migrations = shopMigrations(config, CustomerService(client.getDatabase(copyName)), identity)
    val godwit = Godwit(client, copyName, GodwitConfig(adoptApplied = ::appliedBeforeGodwit))

    val report = godwit.migrate(migrations)

    return Rehearsal(
        adopted = report.recorded.filter { it.origin == Origin.ADOPTED }.map { it.id },
        ran = report.ran.map { it.id },
        readyForNextRelease = godwit.status(migrations).isUpToDate
    )
}
```

The result for the shop's `schema-log` is `adopted = [001-initial-setup, 002-carts, 003-file-store, 004-order-status]`, `ran = [005-customer-external-ids, 006-order-totals, reference-countries, bootstrap-customers]` and `readyForNextRelease = true`, if production was at `004`.

### Dry run

`status()` does not call the hook. On a database with no godwit history it lists every migration but the every-start ones as pending (every once-only migration and every repeatable), because adoption runs only inside `migrate`. That is expected, and it means `status()` cannot preview an adoption. The preview is the rehearsal in step 4 plus the comparison in step 3. Once history holds a document that adoption did not write, `status()` is accurate: it lists exactly what the next start would run, without taking the lock or writing. While it holds only `ADOPTED` documents (or none) and the hook is configured, `status()` lists the ids adoption has not recorded as pending, even those the next start will adopt, and reports neither an untracked database nor an out-of-order or partial-squash problem that the next start may refuse ([history-and-reports.md](history-and-reports.md#edge-cases)).

### The transition period with old instances

During a rolling deploy, instances of the old release run next to instances of the new one. godwit never writes to the old record and does not take the old tool's lock, so the two sides do not see each other.

| Situation | What happens | What you do |
|---|---|---|
| An old instance restarts while the rollout is in progress | The old release is unchanged, so its tool finds nothing pending in its own record and the instance starts | Nothing |
| The new release contains a new migration | Only new instances run it; old instances do not know it | Ship no new migrations in the adopting release |
| You roll back to the old release after the new one ran | The old release does not know what godwit applied since | Safe if the adopting release added no migrations; otherwise write the change so the old code tolerates it |
| Both sides start at the same moment | They use different locks and the old side has nothing pending | Nothing, for the same reason |

Remove the old tool, and stop keeping its record, only when no old release can be deployed again and, while the hook is configured, history holds a document that adoption did not write: until then the hook reads the record on every start with work due.

## Edge cases

Each case gives the state, what godwit does, and what you do.

### The hook returns ids the list does not declare

- **State:** the shop's `schema-log` also holds a `cleanup-temp-data` row that no migration declares.
- **godwit:** imports the declared ids, ignores `cleanup-temp-data`, and lists it in `ignored` on the `Adopted applied migrations` line.
- **You:** nothing, unless the id is a typo. Compare with `idsTheListIgnores` before the rollout. An ignored id never reaches history, so `unknownApplied` stays quiet about it.

### The hook returns a repeatable or every-start id

- **State:** the old record contains `reference-countries`, which the list declares as `repeatable("reference-countries", revision = "2026-10-01")`.
- **godwit:** ignores it. Only once-only ids are adopted. No history document exists for the repeatable, so it is due and runs once on this start, after the pending once-only migrations, and records its revision.
- **You:** make sure a repeatable is safe to run on a database that already has its data. The shop's version upserts by `_id`, so it is.

### The hook returns an id that only a `supersedes` list names

- **State:** the list contains `100-baseline` with `supersedes` naming `001-initial-setup` to `006-order-totals`, and none of the six is declared. The old record holds all six.
- **godwit:** imports the six as `ADOPTED`, then records `100-baseline` as `SUPERSEDED` without running it.
- **You:** nothing. The first godwit release can start from the baseline. See [squashing-migrations.md](squashing-migrations.md).

### A gap in the adopted ids

- **State:** the old record holds `001-initial-setup` and `003-file-store`. `002-carts` was never applied there.
- **godwit:** records the two adopted ids, then checks the plan and finds `002-carts` pending before the applied `003-file-store`. Under `OutOfOrder.FAIL` it throws `PlanConflictException`, naming both. The adopted documents stay in history, because they were written before the plan was checked. History holds nothing but `ADOPTED` documents, so every following start calls the hook again, records nothing new and refuses the same gap, until one of the answers below changes the state.
- **You:** decide what the gap means.
  - `002-carts` was applied by hand and nobody recorded it: record it with `markApplied` from a `Godwit` built from the shop's configuration with `adoptApplied = null` (below), then start again. The shop's own `Godwit` refuses the mark, because history holds nothing but `ADOPTED` documents and adoption has not ended. The `MARKED` document ends it, so mark only once the hook returns everything it should.
  - `002-carts` was applied and the old record or the hook misses it: correct the record or the hook and start again. The hook runs again and adopts `002-carts`.
  - `002-carts` really is missing and safe to run now: start once with `adoptAndRunGaps` (`OutOfOrder.RUN`, shown above). It runs `002-carts` and records `outOfOrder: true`. Switch back to the default afterwards.
  - The hook returns an id that is not applied: history holds nothing but `ADOPTED` documents at this point, because nothing has run, and adoption never removes a recorded id. Drop the history collection, fix the hook, and start again.

`markApplied` writes the id with origin `MARKED` and the reason, under the lock. The `Godwit` it runs on is built from the shop's own `GodwitConfig` with the hook removed (`copy(adoptApplied = null)`). Every other setting stays, above all `historyCollection` and `lockCollection`: a `Godwit` built with the defaults on an app that names its own collections would mark in a history that the app never reads, under a lock that the app never takes.

```kotlin
import com.example.shop.ShopConfig
import com.mongodb.kotlin.client.MongoClient
import godwit.core.Godwit
import godwit.core.GodwitConfig

/**
 * Records a change that someone applied by hand, with the reason in history. [godwitConfig] is the configuration the
 * shop starts with. The copy drops adoptApplied, because the shop's own Godwit refuses to mark while history holds
 * nothing but ADOPTED documents, and keeps every other setting, so the mark lands in the shop's history and lock
 * collections.
 */
fun recordHandAppliedChange(client: MongoClient, config: ShopConfig, godwitConfig: GodwitConfig) {
    Godwit(client, config.mongo.database, godwitConfig.copy(adoptApplied = null)).markApplied(
        "002-carts",
        reason = "created by hand on 2026-03-02, see ticket SHOP-212"
    )
}
```

### The hook returns nothing on a database with data

- **State:** the hook reads `schema-lg` (a typo) and returns an empty set. The database has `customers`, `orders` and `products`.
- **godwit:** the history is empty, collections exist and nothing was adopted. It throws `UntrackedDatabaseException` listing the collections. No migration runs and nothing is recorded.
- **You:** fix the collection name. Do not reach for `RUN_ALL`.

### The hook returns too little

- **State:** a partial restore left `schema-log` with `001-initial-setup` and `002-carts`, while the database really is at `006`.
- **godwit:** the ids form a valid prefix and are non-empty, so the guard does not trip. godwit adopts two and runs `003` to `006` over data that already has their effects.
- **You:** prevention only. The guard and the prefix rule catch an empty or a gapped set, not a short one. The rehearsal on a restored copy shows `ran` containing migrations that should be adopted. Write migrations to be safe on data that already has their effect: the backfills in this documentation select what still needs the change (`exists("status", false)`), so a repeat changes nothing.

### A new empty database while the hook is configured

- **State:** a developer starts the app against an empty database. The hook is still configured.
- **godwit:** the history is empty, so it calls the hook. `schema-log` does not exist, the read is empty, the database has no collections, so the guard passes. Every migration runs.
- **You:** nothing. The hook is safe on an empty database as long as it tolerates a missing collection.

### The hook changes after migrations ran

- **State:** the database was adopted last month, and migrations have run since. Someone edits the hook to return one more id.
- **godwit:** does not call the hook. History holds documents that adoption did not write (the migrations that ran), so adoption is over and the hook has no effect. Neither does a change to the old record.
- **You:** if an id must be recorded after that, use `markApplied(id, reason)`.

### The hook returns a different set on a later call

- **State:** the first start adopted `001-initial-setup` and `003-file-store` and refused the gap before `003`. Before the next start, someone corrects `schema-log`: rows for `002-carts` and `004-order-status` are added, and the `003-file-store` row is deleted by mistake.
- **godwit:** history holds nothing but `ADOPTED` documents, so the next start calls the hook again. It records the ids that are new (`002-carts`, `004-order-status`), leaves `001` and `003` as they are, and checks the plan: no gap. `003-file-store` stays recorded although the hook no longer returns it: adoption only adds, and nothing is ever removed.
- **You:** nothing, when the additions are right. To take back an id that was adopted wrongly, drop the history collection while it still holds only `ADOPTED` documents, fix the hook or the record, and start again.

### History is lost while the hook is configured

- **State:** the shop was adopted in March, godwit has run `004` to `006` since, and someone drops `godwit-history`. The hook is still configured and the old record still lists `001` to `003`.
- **godwit:** history is empty, so the hook runs and returns `001` to `003`. It adopts three, and `004` to `006` are pending again and run a second time. The guard does not help, because adoption imported something.
- **You:** restore `godwit-history` from a backup. Without one, stop every instance and record what was applied before any of them starts again, from a `Godwit` built from the shop's configuration with `adoptApplied = null`, so that the marks land in the shop's `godwit-history` ([as in the gap case](#a-gap-in-the-adopted-ids)): the shop's own `Godwit` refuses to mark, because history is empty and adoption has not ended. Call `markApplied` for each of `006` down to `001`, the last-listed id first. The first call records a `MARKED` document, which ends adoption, so when the instances start again the hook is not called. Marking in that order means a start between two marks sees the ids not yet marked as pending before a marked one, which the default `OutOfOrder.FAIL` refuses; marked from `001` up, the same start would find a valid prefix and run the rest over the live data. Prevention: once every environment is adopted, remove the hook. The guard then refuses a database that lost its history, instead of replaying it.

### Two instances start at once

- **State:** two instances of the new release start against the unadopted production database.
- **godwit:** instance A takes the lock, adopts, and runs what is left. Instance B waits. When B gets the lock it reads history again, finds the migrations A ran, does not call the hook, plans, and finds only the every-start migration due.

```text
INFO  godwit - Acquired migration lock runId=0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f lockWaitMs=14
INFO  godwit - Adopted applied migrations adopted=[001-initial-setup, 002-carts, 003-file-store, 004-order-status] ignored=[cleanup-temp-data, reference-countries]
INFO  godwit - Migrations complete runId=0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f ran=4 recorded=4 upToDate=0 lockWaitMs=14 durationMs=2310
```

```text
INFO  godwit - Waiting for migration lock holder=shop-7f9c4/1 holderRunId=0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f expiresAt=2026-10-02T10:15:00.210Z waitedMs=10004
INFO  godwit - Acquired migration lock runId=0199a4c2-8e44-7a02-b1d5-6c7d8e9fa0b1 lockWaitMs=14230
INFO  godwit - Migrations complete runId=0199a4c2-8e44-7a02-b1d5-6c7d8e9fa0b1 ran=1 recorded=0 upToDate=7 lockWaitMs=14230 durationMs=96
```

- **You:** nothing. The hook runs once per database however many instances start, as long as the first start runs something. When it does not (it adopted the whole list and the list has no repeatable or every-start migration, or it refused a gap), history still holds only `ADOPTED` documents and B calls the hook again under the lock; that call records nothing new.

### The process dies while adoption records its ids

- **State:** the hook returned `001` to `004`, and the pod is killed, or the primary steps down, while godwit records them.
- **godwit:** on a replica set the four `ADOPTED` documents commit in one transaction: either all are recorded or none. The transaction runs in the driver's `withTransaction`, so a transient error such as the primary stepping down is retried in the same call; a kill, or an error that is not transient, leaves the commit applied or not. The next start calls the hook again and records whatever is missing (nothing, if the commit applied). A standalone server has no transactions, so a start gets this far there only when every due migration is outside-only: the shop's list, whose `004` to `006` are transactional, is refused before the lock ([a standalone server](#a-standalone-server)). For a list of outside-only migrations godwit writes the ids one at a time, last-listed first: with four ids `001` to `004`, `004` is written first, and an interruption after two writes leaves `004` and `003` recorded and `001` and `002` missing. The next start finds history holding nothing but `ADOPTED` documents, so it does not check the order before the lock. Under the lock it calls the hook again, records `001` and `002`, leaves `003` and `004` as they are, and only then checks the plan, which has no gap. This holds under `OutOfOrder.FAIL` and `OutOfOrder.RUN` alike: the missing ids are adopted, never run.
- **You:** nothing. Keep the hook configured until the first start has completed. A start without it sees the partial adoption as a gap before `003`, because the ids are written last-listed first: `OutOfOrder.FAIL` refuses it, while `OutOfOrder.RUN` would run `001` and `002` over data that already has them. Ids that a `supersedes` list names take their migration's place in that order, so an interruption among them leaves a partial supersede, which a start without the hook refuses whatever `outOfOrder` is.

### `markApplied` before the first start

- **State:** before the first godwit release starts on production, an operator runs `markApplied("002-carts", reason = ...)` on a `Godwit` configured like the shop's, with `adoptApplied`, to record a change made by hand.
- **godwit:** takes the lock and reads history: it is empty and the hook is configured, so adoption has not ended. A `MARKED` document is not an adoption document, so it would end adoption before it began: the hook would never be called, and with history no longer empty, the untracked-database guard would be off as well. With `002-carts` marked, `001-initial-setup` would be out of order, which `OutOfOrder.FAIL` refuses and `OutOfOrder.RUN` runs, with everything after `002`, over the live data; with `001-initial-setup` marked, `002` to `006` would run over the live data. `markApplied` therefore throws and writes nothing:

```text
java.lang.IllegalStateException: adoption has not ended on this database; run migrate() first so the adoptApplied hook adopts, or call markApplied from a Godwit built without adoptApplied
```

- **You:** work out what the first start does with the id. It adopts what the hook returns, refuses a gap under the default `OutOfOrder.FAIL`, and runs the once-only migrations listed after the last adopted id, so where the id sits decides the answer.
  - The hook returns it: there is nothing to mark.
  - It is listed before an id the hook returns, as `002-carts` is before `003-file-store`: let the first start adopt, then mark. The first start adopts the rest, refuses the gap before `003-file-store` under the default `OutOfOrder.FAIL`, runs nothing, and leaves history holding only `ADOPTED` documents: mark from a `Godwit` built from the shop's configuration with `adoptApplied = null`, as in [the gap case](#a-gap-in-the-adopted-ids). Under `OutOfOrder.RUN` the first start would run the id instead, so treat it as the next case.
  - It is listed after every id the hook returns, as `004-order-status` is when the old record lists `001` to `003`: do not wait for the first start, because it adopts `001` to `003`, finds a valid prefix and runs `004-order-status` a second time over the live orders, before anyone can mark it. Make the hook return the id, or add it to the old record, as in the gap case's answer for an id the old record or the hook misses. Then start: the hook adopts it with the rest, and nothing is marked.

  Once a migration has run, the shop's own `Godwit` marks. A `Godwit` built without the hook does not refuse: a mark from it before the first start ends adoption as described above. If such a mark came first, delete its document in the shell before any instance starts, which is safe because nothing ran under it (`db.getCollection("godwit-history").deleteOne({ _id: "002-carts", origin: "MARKED" })`). History is then empty again, and the next start calls the hook.

### The hook throws

- **State:** the shop's database user cannot read `schema-log`, and the read throws.
- **godwit:** the exception propagates out of `migrate`, the lock is released and nothing is recorded. The application does not start.
- **You:** fix the permission. The next start calls the hook again, because history still holds nothing that adoption did not write.

### Adoption succeeds and a later migration fails

- **State:** `001` to `004` are adopted; `005-customer-external-ids` fails because the identity provider is down.
- **godwit:** `005` is recorded `FAILED` with `lastError`. Its document has origin `RAN`, written by its `RUNNING` marker, so adoption is over and the next start does not call the hook. It retries `005`, outside step first.
- **You:** fix the cause and restart. Adoption is complete and independent of what runs after it.

### An id is spelled differently

- **State:** the old record holds `003-File-Store`. The list declares `003-file-store`.
- **godwit:** the ids do not match, so `003-File-Store` is ignored. If `004-order-status` is adopted, `003-file-store` is missing before an adopted id: the gap case above.
- **You:** map the spelling in the hook. `idsTheListIgnores` shows the id as ignored before the rollout.

### A collection exists before the first migrate

- **State:** a component of the application writes to the database while the app starts, for example a session store that creates `sessions`, on a database that has never been migrated.
- **godwit:** the database has a collection and no history. If the hook returns nothing, `UntrackedDatabaseException` lists `sessions`.
- **You:** run `migrate` before anything touches the database. Constructors of the shop's services only store the `MongoDatabase`; keep it that way.

### A standalone server

- **State:** the first start runs against a standalone `mongod` copy of production. The list contains transactional migrations.
- **godwit:** the check for transactions comes before the lock and before the hook. History is empty, so the transactional migrations count as due, and `TransactionsUnsupportedException` is thrown, even though adoption would have covered them.
- **You:** run a single-node replica set, also for rehearsals. See [transactions-and-sessions.md](transactions-and-sessions.md).

### A hotfix on the old release adds a change after adoption

- **State:** after adoption, someone patches the old release, and its old tool applies a new change `005b-hotfix` to the production database.
- **godwit:** does not know it. Once a migration has run, the hook is not called; and while it still is, nothing in godwit's list names `005b-hotfix`, so it would be ignored.
- **You:** do not add changes through the old tool after adoption. If it happened, add a migration with the same effect to the godwit list, make it safe to run on a database that has the change, and let it run.

### `status()` before the first start

- **State:** an operator runs `status(migrations)` against the unadopted production database.
- **godwit:** reports every once-only migration and the repeatable as pending. With the hook configured it reports no problem, not even the untracked database: only `migrate` calls the hook, and the hook's answer decides that. It does not call the hook, does not take the lock and writes nothing.
- **You:** read it as "nothing is recorded yet", not as "godwit will run all of these". The rehearsal answers what the first start will do.

## Design decisions

### The application supplies the applied ids

**Chosen:** `adoptApplied: ((MongoDatabase) -> Set<String>)?`. godwit asks a question and the app answers it.

**Alternatives:**

- A reader built into godwit for each tool's record format. godwit would own formats it cannot test against every version of every tool, each format change would need a godwit release, and a record kept by hand has no format at all.
- A declarative description of the record.

  ```text
  // not godwit API
  GodwitConfig(
      adoptFrom = Changelog(collection = "changelog", idField = "changeId", appliedWhen = eq("state", "DONE"))
  )
  ```

  This covers the simplest record and breaks on "the newest row decides", on ids derived from two fields, and on a record spread across collections.

**Why:** the hook is a few lines of Kotlin that you own, test and delete. godwit stays independent of every other tool, and any record that you can read can be adopted.

### The hook runs until something other than adoption is recorded

**Chosen:** godwit calls it under the lock while history holds nothing but `ADOPTED` documents, records only the ids history does not hold yet, and checks the order after it.

**Alternatives:** an explicit second call at startup (`godwit.adopt(...)`), reconciliation on every start, or a call only while history is empty.

```text
// not godwit API
val godwit = Godwit(client, "shop")
godwit.adopt(::appliedBeforeGodwit)   // a second startup step that someone must remember to remove
godwit.migrate(migrations)
```

**Why:** once a migration has run, godwit's history is the only source of truth. A call that reads the old record on every start would let that record disagree with history. A separate call is a second step to forget, to order wrongly or to leave in. With the hook in the config, a database that is already adopted costs nothing, and the same code adopts every environment. A call only while history is empty cannot finish an adoption that a crash interrupted on a standalone server, where the documents are written one at a time: the first recorded id would keep the hook from running again. Calling it until the first document of another origin, with upserts of what is missing, lets the next start finish the adoption, and checking the order after the hook keeps the half-recorded state from being refused as a gap before the hook can fill it.

### `markApplied` refuses until adoption ends

**Chosen:** on a `Godwit` built with `adoptApplied`, `markApplied` throws `IllegalStateException` and writes nothing while history holds no document that adoption did not write (an empty history included). The check runs under the lock that `markApplied` takes anyway, on the history it reads there.

**Alternative:** accept the mark and document the hazard.

**Why:** a `MARKED` document ends adoption. Accepted before the hook has recorded every applied id, a mark keeps the hook from ever recording the rest, and the next start runs them over the live data, or refuses as out of order an id the hook would have adopted. That happens on exactly the databases adoption exists to protect, and a warning on a page does not reach the operator who runs the command. The refusal costs one exception whose message names both ways forward: let `migrate` adopt first, or mark from a `Godwit` built from the same configuration without the hook, a deliberate choice for a repair such as recording what was applied after history was lost, with every instance stopped.

### Only declared ids are imported

**Chosen:** a returned id is recorded only when the list declares it as once-only or a `supersedes` list names it.

**Alternative:** record every returned id.

**Why:** history would fill with ids that godwit cannot link to code (cleanup tasks, steps from other tools, typos), and every one would raise an unknown-applied warning on every start from then on. An id that no migration owns is not worth recording.

### The adopted ids must be a prefix

**Chosen:** a gap follows the out-of-order policy (`FAIL` by default).

**Alternative:** trust the hook and adopt any subset.

**Why:** the list orders migrations because later ones assume earlier ones. A gap means either the hook is wrong or the database skipped a change, and both need a decision from a person. `markApplied` and `OutOfOrder.RUN` give two explicit, recorded answers.

### The untracked-database guard defaults to refusing

**Chosen:** `UntrackedDatabase.REFUSE`, with `RUN_ALL` as an explicit opt-in.

**Alternative:** run every migration on any database without history, which is how an empty database behaves.

**Why:** the two failures are not symmetric. A wrong guard costs one exception and one configuration line. A missing guard re-runs every migration over live data: a backfill that overwrites a field, a unique index that fails on existing duplicates. A mistyped collection name in the hook is exactly how that starts.

### godwit never writes to the old record

**Chosen:** adoption is read-only on the old side.

**Alternative:** mark the old record as migrated, or delete it, to prevent double use.

**Why:** instances of the old release keep working during a rollout, a rollback stays possible, and the audit trail of the old tool stays intact. godwit's history is the new record; the old one is yours.

### Renaming happens in the hook

**Chosen:** the function maps ids with ordinary Kotlin.

**Alternative:** a `renamed = mapOf(...)` option in the config.

**Why:** the mapping and the code that reads the record change together, and a map in the hook is one place that knows both spellings. godwit would add a concept to the API for something a `?:` already does.

## See also

- [squashing-migrations.md](squashing-migrations.md): baselines, and how a squash records the ids that adoption imports.
- [testing.md](testing.md): tests for the hook, the gap case and the untracked guard.
- [configuration.md](configuration.md): `adoptApplied`, `outOfOrder`, `untrackedDatabase`.
- [ordering-and-validation.md](ordering-and-validation.md): the out-of-order policy.
- [history-and-reports.md](history-and-reports.md): `ADOPTED` documents, `status()`, `markApplied`.
- [failure-and-recovery.md](failure-and-recovery.md): the untracked database and plan conflicts as failure modes.
- [locking.md](locking.md#adoption-under-the-lock): why the hook runs under the lock.
- [concepts.md](concepts.md): kinds, steps and the run lifecycle that adoption slots into.
- [repeatable-migrations.md](repeatable-migrations.md): why a repeatable is not adopted and runs once on the first start.
- [design-decisions.md](design-decisions.md): the index of all decisions.
- [../README.md](../README.md): the project overview.
