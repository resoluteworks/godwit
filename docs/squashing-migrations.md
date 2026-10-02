# Squashing migrations

A list of migrations grows with the project. A new database runs every one of them in order, the code keeps shapes that no data has had for a year, and each old migration is one more thing to read and to keep compiling. A squash replaces many migrations with one baseline that builds the end state directly. The baseline declares the ids it replaces with `supersedes`. godwit then decides for each database from its history: where every replaced migration is applied, the baseline is recorded without running; where none is, the baseline runs; where only some are, godwit refuses to start and asks for the previous release first.

## How a squash decides

The shop has six once-only migrations, `001-initial-setup` to `006-order-totals`. The squash release replaces them with `100-baseline`:

```kotlin
package com.example.shop.migrations

import com.example.filestore.ensureFileStoreSchema
import com.example.shop.ShopConfig
import com.example.shop.services.CustomerService
import com.example.shop.services.IdentityProvider
import com.mongodb.client.model.IndexModel
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes.ascending
import com.mongodb.client.model.Indexes.compoundIndex
import com.mongodb.client.model.Indexes.descending
import godwit.core.Migration
import godwit.core.migration
import org.bson.Document
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/** The once-only migrations that the baseline replaces: all of them, as the release before the squash lists them. */
val squashedIds = listOf(
    "001-initial-setup",
    "002-carts",
    "003-file-store",
    "004-order-status",
    "005-customer-external-ids",
    "006-order-totals"
)

/**
 * The end state of 001 to 006 as schema only. A fresh database has no data in the old shapes, so the backfills 004
 * to 006 have nothing to do and are not repeated here, except for the index that 006 adds.
 */
fun baseline(searchIndexWait: Duration?): Migration = migration(
    "100-baseline",
    description = "End state of 001 to 006",
    supersedes = squashedIds
).outsideTransaction {
    ensureCollection("customers")
    ensureCollection("orders")
    ensureCollection("products")
    ensureCollection("carts")
    database.ensureFileStoreSchema()

    collection("customers").createIndex(ascending("email"), IndexOptions().unique(true))
    collection("orders").createIndexes(
        listOf(
            IndexModel(compoundIndex(ascending("customerId"), descending("placedAt"))),
            IndexModel(ascending("status")),
            IndexModel(compoundIndex(ascending("customerId"), descending("totalMinor")))
        )
    )
    collection("products").createIndex(ascending("sku"), IndexOptions().unique(true))
    collection("carts").createIndexes(
        listOf(
            IndexModel(ascending("customerId"), IndexOptions().unique(true)),
            IndexModel(ascending("updatedAt"), IndexOptions().expireAfter(30L, TimeUnit.DAYS))
        )
    )
    ensureSearchIndex(
        "products",
        name = "product-search",
        definition = Document("mappings", Document("dynamic", true)),
        awaitReady = searchIndexWait
    )
}

/** The list of the release that contains the squash: the baseline replaces the six, the rest of the list stays. */
fun squashedMigrations(config: ShopConfig, customers: CustomerService, identity: IdentityProvider): List<Migration> =
    listOf(
        baseline(searchIndexWait = config.mongo.searchIndexWait),
        referenceCountries,
        bootstrapCustomers(config.bootstrap.customers, customers, identity)
    )
```

`migrate` reads `supersedes` from the list and compares it with the history of each database, as long as the
baseline itself has no `APPLIED` history document:

| Database | Replaced ids applied | godwit does | `100-baseline` in history |
|---|---|---|---|
| Production, up to date on the previous release | 6 of 6 | records it without running it | `APPLIED`, origin `SUPERSEDED` |
| A new developer database | 0 of 6 | runs the baseline's steps | `APPLIED`, origin `RAN` |
| Staging, stuck at `004` | 4 of 6 | throws `PlanConflictException`, nothing runs or is recorded | no document |

A database where every replaced id is applied logs one line per recorded baseline, and runs the rest of the list as usual:

```text
INFO  godwit - Recorded superseded migration id=100-baseline supersedes=[001-initial-setup, 002-carts, 003-file-store, 004-order-status, 005-customer-external-ids, 006-order-totals]
INFO  godwit - Migrations complete runId=0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f ran=1 recorded=1 upToDate=1 lockWaitMs=212 durationMs=120
```

A new database runs the baseline like any other migration:

```text
INFO  godwit - Running migration id=100-baseline kind=ONCE steps=[OUTSIDE_TRANSACTION] attempt=1
INFO  godwit - Applied migration id=100-baseline kind=ONCE steps=[OUTSIDE_TRANSACTION] attempts=1 txRetries=0 batches=0 durationMs=1840
```

Either way, the baseline's history document stores the `supersedes` list. These are the two documents, abbreviated to the fields that matter here:

```json
{
  "_id": "100-baseline",
  "kind": "ONCE",
  "description": "End state of 001 to 006",
  "steps": [],
  "state": "APPLIED",
  "origin": "SUPERSEDED",
  "attempts": 0,
  "supersedes": ["001-initial-setup", "002-carts", "003-file-store", "004-order-status", "005-customer-external-ids", "006-order-totals"],
  "holder": "shop-7f9c4/1",
  "runId": "0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f",
  "godwitVersion": "0.1.0",
  "v": 1
}
```

```json
{
  "_id": "100-baseline",
  "kind": "ONCE",
  "description": "End state of 001 to 006",
  "steps": ["OUTSIDE_TRANSACTION"],
  "state": "APPLIED",
  "origin": "RAN",
  "attempts": 1,
  "transactionRetries": 0,
  "durationMs": 1840,
  "supersedes": ["001-initial-setup", "002-carts", "003-file-store", "004-order-status", "005-customer-external-ids", "006-order-totals"],
  "holder": "shop-9a1e2/1",
  "runId": "0199a4c3-1c2d-7e44-8a10-4d5e6f708192",
  "godwitVersion": "0.1.0",
  "v": 1
}
```

### The stored list keeps the old ids known

The six old migrations stay in history as `APPLIED` documents on a migrated database. The list no longer declares them. godwit calls an applied id known when the list declares it, when a declared migration names it in `supersedes`, or when a recorded superseding migration names it in its stored `supersedes` list. The stored list is why the six never produce an unknown-applied warning, and why the `supersedes` argument can be deleted from code later without any (see "When to remove `supersedes`").

### Rules

- **Delete the replaced migrations in the same commit.** A `supersedes` list must not name an id that the list still declares. `validateMigrations` rejects the list and `migrate` throws `InvalidMigrationsException`.
- **Ids stay unique, counting `supersedes` lists.** The baseline's id cannot equal a replaced id, and two migrations cannot replace the same id.
- **The numbering stays increasing.** Among once-only migrations, numeric prefixes strictly increase in list order. A baseline that replaces the whole history takes a number above the old ones (`100-baseline`), and later migrations continue from it (`101-...`). A baseline that replaces only the first part of the history must sort below the migrations that stay: give it the number of the last migration it replaces (`006-baseline` before `007-customer-email-lower`).
- **A rename is a squash of one.** An id never changes, but `migration("004-order-status-backfill", supersedes = listOf("004-order-status"))` replaces `004-order-status` under a new id with the same rules. See [declaring-migrations.md](declaring-migrations.md).
- **List it before the migrations that depend on it.** It is a once-only migration; list position is run order.
- **Recording is not running.** When godwit records a baseline as `SUPERSEDED`, the out-of-order policy does not apply, even if migrations listed after it are already applied. That is what lets a partial squash start on production, where `007` is applied.
- **The replaced ids keep their place.** Until the baseline is `APPLIED`, each applied id it replaces counts, for a once-only migration listed before the baseline, as listed in the baseline's place. A migration pending before it is out of order, as it would be before the old migrations, so a squash or a rename does not hide a gap ([ordering-and-validation.md](ordering-and-validation.md#out-of-order)).
- **An applied baseline is settled.** The all, none or some decision is made only while the baseline has no `APPLIED` history document. Once it is `APPLIED` (recorded or run), its stored `supersedes` list only keeps the old ids known; how many of them are applied no longer matters. A database built by the baseline that later ran some of the old migrations under an older release (see "A rollback onto a new database") rolls forward again without a conflict.
- **A baseline is an ordinary migration.** It can have an outside step, a transactional step, or both. A baseline that godwit runs follows every rule of [declaring-migrations.md](declaring-migrations.md), and its outside step must be idempotent.

### What belongs in a baseline

| Belongs in the baseline | Does not |
|---|---|
| collections, indexes, TTL indexes, search indexes: the schema | backfills of old data shapes (`004`, `005`, `006`'s batches): a new database has no data in those shapes |
| anything a new database needs in order to exist | calls to external services for existing records (`005`) |
| idempotent calls only: `ensureCollection`, `createIndexes` with identical specs | seed data: that is a repeatable or every-start migration |

The baseline above creates the index that `006` adds and none of the backfills. Documents written after the squash are written in the new shape by the application, so a new database has nothing to backfill.

## Procedure

The shop squashes `001` to `006` into `100-baseline`.

1. **Pick the cut.** Every once-only migration that every environment has applied. Repeatable and every-start migrations stay in the list.
2. **Get every environment onto the last release that declares the old migrations.** Call this release N-1. An environment that has not applied all six cannot start the squash release.
3. **Write the baseline from the schema, then prove it.** Create the end-state schema in one outside step with idempotent calls, then compare a new database with a migrated one with the test below. The test is the proof; reading the six migrations is not.
4. **In one commit:** delete the six migration files and their cases that target them by id, add the baseline, replace `shopMigrations` with the new list, add the equivalence test.
5. **Deploy release N.** Migrated databases log `Recorded superseded migration`. New databases log `Running migration id=100-baseline`.
6. **Verify every environment.** `100-baseline` is `APPLIED` with a stored `supersedes` list.
7. **Keep the `supersedes` argument in code.**

### Knowing that every environment is ready

There are two gates. Each asks a question about one database's history, and the answer is deterministic, so ask it with code:

```kotlin
import godwit.core.Godwit
import godwit.core.HistoryState

/** The ids in [superseded] that this database has not applied. Empty: the database is ready for the squash release. */
fun notYetApplied(godwit: Godwit, superseded: List<String>): List<String> {
    val applied = godwit.history().filter { it.state == HistoryState.APPLIED }.map { it.id }.toSet()
    return superseded.filter { it !in applied }
}

/** True when history holds the baseline with its stored `supersedes` list, so the list in code is no longer needed. */
fun hasRecordedBaseline(godwit: Godwit, baselineId: String): Boolean =
    godwit.history().any { it.id == baselineId && it.state == HistoryState.APPLIED && it.supersedes.isNotEmpty() }
```

**Gate 1, before shipping release N: has this database applied every id the baseline replaces?** `notYetApplied(godwit, squashedIds)` must return an empty list. Run it against every environment: production, staging, QA, preview databases, a developer database that matters, and the restore of last night's backup that disaster recovery would use. From the shell, the same question:

```javascript
db.getCollection("godwit-history").countDocuments({
  _id: { $in: ["001-initial-setup", "002-carts", "003-file-store", "004-order-status", "005-customer-external-ids", "006-order-totals"] },
  state: "APPLIED"
})
```

The answer must be `6`. You can also run `godwit.status(squashedMigrations(...))` with release N's list: an environment that is not ready reports the partial squash in `problems`. `notYetApplied` is the gate, though: `status()` does not report a partial squash while history holds only `ADOPTED` documents and the adoption hook is configured.

**Gate 2, before deleting `supersedes` from code: has this database recorded the baseline?** `hasRecordedBaseline(godwit, "100-baseline")` is true when `100-baseline` is `APPLIED` and carries its stored list. See "When to remove `supersedes`".

### When to remove `supersedes`

Leave it in. It is one argument, it costs nothing at runtime, and it protects the one case that matters: an environment that has not started since before the squash. Remove it only when `hasRecordedBaseline` is true for every database that will ever run this code, including backups that you might restore.

Without the argument, the baseline is a plain once-only migration. On an environment that never recorded it, godwit runs the baseline over the migrated schema. Its DDL is idempotent, but the check that refuses a half-migrated database is gone, so backfills that the six would have applied are skipped, and the six old documents are warned about as unknown ids on every start.

## A squash and adoption together

A project that adopts godwit does not need the old migrations in its list. The first godwit release can start from the baseline: the hook returns the old ids, godwit imports every id that a `supersedes` list names, and then records the baseline.

```text
INFO  godwit - Adopted applied migrations adopted=[001-initial-setup, 002-carts, 003-file-store, 004-order-status, 005-customer-external-ids, 006-order-totals] ignored=[]
INFO  godwit - Recorded superseded migration id=100-baseline supersedes=[001-initial-setup, 002-carts, 003-file-store, 004-order-status, 005-customer-external-ids, 006-order-totals]
```

The hook is the one from [adopting-an-existing-database.md](adopting-an-existing-database.md); it needs no change. If it returns only some of the six, the squash is partial and `PlanConflictException` follows (see "The hook returns four of the six").

## If the baseline fails

The baseline fails only where it runs: on a database where none of the replaced ids is applied. There it is an ordinary migration.

- It is recorded `FAILED` with `lastError`, and `migrate` throws `MigrationFailedException`.
- The next start runs its outside step again from the beginning, so every call in it must be idempotent. The baseline above uses `ensureCollection`, `createIndexes` with fixed specs and `ensureSearchIndex`, all of which converge.
- Where it does not run (a migrated database), recording it is a history write. If the write fails the driver's exception propagates, nothing is recorded, and the next start records it.

Example: a new database, and `ensureSearchIndex(..., awaitReady = 5.minutes)` throws `SearchIndexNotReadyException` after the baseline created its collections and indexes. History holds `100-baseline` as `FAILED`. The next start runs the outside step again: `ensureCollection` returns false for the existing collections, `createIndexes` finds identical indexes, and `ensureSearchIndex` finds the index and waits for it again.

## Tests for a squash

Three cases need a test: a new database and a migrated one end in the same schema (the baseline runs on one and is recorded on the other), a database that stopped half-way is refused, and a target that names a replaced id is invalid. `testConfig`, `FakeIdentityProvider` and `migrationsFor` are the fixtures from [testing.md](testing.md); `migrationsFor` builds the release N-1 list.

```kotlin
import com.example.shop.migrations.squashedIds
import com.example.shop.migrations.squashedMigrations
import com.example.shop.services.CustomerService
import com.example.shop.testing.FakeIdentityProvider
import com.example.shop.testing.migrationsFor
import com.example.shop.testing.testConfig
import com.mongodb.kotlin.client.MongoDatabase
import godwit.core.InvalidMigrationsException
import godwit.core.Migration
import godwit.core.Origin
import godwit.core.PlanConflictException
import godwit.core.Target
import godwit.test.TestGodwit
import godwit.test.testGodwit
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.bson.Document

private fun squashedFor(db: TestGodwit): List<Migration> =
    squashedMigrations(testConfig, CustomerService(db.database), FakeIdentityProvider())

/** Every collection of the application and its indexes, without the index format version. */
private fun schemaOf(db: TestGodwit): Map<String, Set<Document>> =
    db.database.listCollectionNames().toList()
        .filterNot { name -> name in db.godwit.bookkeepingCollections }
        .associateWith { name -> indexesOf(db.database, name) }

private fun indexesOf(database: MongoDatabase, collection: String): Set<Document> =
    database.getCollection(collection, Document::class.java).listIndexes<Document>().toList()
        .map { index -> index.apply { remove("v") } }
        .toSet()

class SquashSpec : StringSpec({
    "a fresh database and a migrated one end up with the same collections and indexes" {
        val migrated = testGodwit(atlasSearch = true)
        migrated.godwit.migrate(migrationsFor(migrated))
        migrated.godwit.migrate(squashedFor(migrated))

        val fresh = testGodwit(atlasSearch = true)
        fresh.godwit.migrate(squashedFor(fresh))

        schemaOf(fresh) shouldBe schemaOf(migrated)
        fresh.godwit.history().single { it.id == "100-baseline" }.origin shouldBe Origin.RAN
        migrated.godwit.history().single { it.id == "100-baseline" }.origin shouldBe Origin.SUPERSEDED
    }

    "a database that stopped at 004 is refused until the previous release finishes the job" {
        val db = testGodwit(atlasSearch = true)
        db.godwit.migrate(migrationsFor(db), target = Target.Through("004-order-status"))
        notYetApplied(db.godwit, squashedIds) shouldContainExactly
            listOf("005-customer-external-ids", "006-order-totals")

        shouldThrow<PlanConflictException> { db.godwit.migrate(squashedFor(db)) }.problems shouldHaveSize 1
        db.godwit.history().none { it.id == "100-baseline" } shouldBe true

        db.godwit.migrate(migrationsFor(db))
        notYetApplied(db.godwit, squashedIds) shouldBe emptyList()
        db.godwit.migrate(squashedFor(db))["100-baseline"].origin shouldBe Origin.SUPERSEDED
    }

    "a target that names a superseded id is invalid in the squash release" {
        val db = testGodwit()

        shouldThrow<InvalidMigrationsException> {
            db.godwit.migrate(squashedFor(db), target = Target.Through("004-order-status"))
        }
    }
})
```

What each case proves:

- **Equivalence.** The migrated database is built by the previous release and then by the squash release. The new database is built by the squash release alone. `schemaOf` compares collection names and index definitions. Search indexes are not in `listIndexes`; compare them separately if they matter to you.
- **Refusal.** `Target.Through("004-order-status")` leaves the database at four of six. The squash release throws `PlanConflictException` and records nothing. Finishing the migrations with the previous release's list lets the squash release record the baseline.
- **A target that names a superseded id.** After the squash, `Target.Through("004-order-status")` names an id that the list does not declare, so it is invalid. Tests that target old ids must move to `Target.Through("100-baseline")` or to `runIsolated`.

## Edge cases

Each case gives the state, what godwit does, and what you do.

### An environment is stuck half-way

- **State:** a QA database applied `001` to `004` and was then abandoned. Release N lists `100-baseline`.
- **godwit:** `PlanConflictException`: four of the six are applied, two are not. The problem says to deploy the previous release first. Nothing runs and nothing is recorded.
- **You:** deploy release N-1 to that database; it applies `005` and `006`. Then deploy release N. If the database is disposable, drop it and let release N build it from the baseline. If `005` and `006` were applied by hand, `markApplied` them with the reason.

### The hook returns four of the six

- **State:** the first godwit release starts from the baseline. The old record lists `001` to `004` only; `005` and `006` were applied by hand and never recorded.
- **godwit:** adopts the four, then finds the baseline partially superseded and throws `PlanConflictException`. The four `ADOPTED` documents stay in history. History holds nothing else, so every following start calls the hook again and throws the same conflict until the state changes.
- **You:** record the missing two once you have checked that they are applied, with a `Godwit` built from the shop's configuration with `adoptApplied = null`, so that the marks land in the shop's history: `godwit.markApplied("005-customer-external-ids", reason = "applied by hand, checked 2026-10-02")` and the same for `006-order-totals`. The shop's own `Godwit` refuses the mark (`IllegalStateException`), because history holds nothing but `ADOPTED` documents and adoption has not ended. The hook has already recorded the four it returns, so the first `MARKED` document can end adoption, and the next start records the baseline. If the old record should have listed them, adding them there works too: the next start's hook call adopts them.

### A typo in `supersedes`

- **State:** the list names `006-order-total` (the final `s` is missing) in `supersedes`.
- **godwit:** on every migrated database only five of the six names are applied, so every migrated environment throws `PlanConflictException` on its first start. New databases are unaffected: no name is applied, so the baseline runs.
- **You:** fix the id. The first migrated environment shows the mistake, before anything has been recorded. It fails closed: a typo cannot mark a migration as superseded.

### The old migrations are still in the list

- **State:** the commit added `100-baseline` and forgot to delete `002-carts`.
- **godwit:** `validateMigrations` reports that `supersedes` names `002-carts`, which the list declares. `migrate` throws `InvalidMigrationsException` before any I/O. The unit test "the migration list is valid" fails first.
- **You:** delete the file and its entry in the list.

### The numbering goes backwards

- **State:** the list has `100-baseline` followed by `007-customer-email-lower`, which stays after a partial squash.
- **godwit:** `InvalidMigrationsException`: numeric prefixes must strictly increase among once-only migrations, and `7` is below `100`.
- **You:** give the baseline the number of the last migration it replaces, so it sorts below the ones that stay: `006-baseline`.

### Production has later migrations applied than the baseline

- **State:** the list is `006-baseline`, `007-customer-email-lower`, `008-customer-email-lower-index`. Production has `001` to `008` applied.
- **godwit:** all six replaced ids are applied, so it records `006-baseline` as `SUPERSEDED`. `007` and `008` are applied and stay. The baseline is listed before applied migrations, and the out-of-order policy does not apply to a recording.
- **You:** nothing. A new database runs the baseline, then `007` and `008`, in list order.

### Rolling back past the squash

- **State:** release N ran on production and recorded the baseline. Release N-1 is deployed again.
- **godwit:** N-1 declares `001` to `006`, all `APPLIED`, so nothing is pending. `100-baseline` is applied and unknown to N-1, so godwit logs it.

```text
WARN  godwit - Unknown applied migrations ids=[100-baseline]
```

- **You:** nothing, unless `unknownApplied = UnknownApplied.FAIL` is set: it would stop the rollback. Keep `WARN` in production (see [configuration.md](configuration.md)).

### A rollback onto a new database

- **State:** a database was built by release N (the baseline ran, so history has no `001` to `006`), and release N-1 is deployed to it.
- **godwit:** N-1 declares `001` to `006`, none applied, so they are pending and run in order. They are safe over the baseline's schema: the DDL is idempotent and the backfills find nothing to change. `100-baseline` is reported as unknown applied. If N-1 fails part-way (say at `004`, with `001` to `003` applied) and release N is deployed again, `100-baseline` is already `APPLIED`, so its `supersedes` list is not evaluated: three of six applied is no conflict.
- **You:** nothing. The squash contract is that the replaced migrations and the baseline converge on the same schema.

### Restoring a backup from before the squash

- **State:** a backup taken under release N-1 is restored and release N starts.
- **godwit:** the six are applied, so the baseline is recorded without running.
- **You:** nothing. The same gates apply: a backup taken half-way through the six is a stuck environment.

### A second squash

- **State:** release N+3 squashes again, replacing the first baseline and the migrations added since (`101-product-slugs`, `102-cart-currency`) with `200-baseline`.
- **godwit:** decides each database from its own history. The second baseline names the first baseline and the migrations after it, not the six that the first baseline replaced:

```kotlin
/** The second squash names the first baseline and the migrations added since, not the ids the first one replaced. */
val laterSquashedIds = listOf("100-baseline", "101-product-slugs", "102-cart-currency")
```

  - A database that went through the first squash has all three applied (the first baseline recorded `SUPERSEDED`), so the second baseline is recorded.
  - A database that the first baseline built has all three applied (`100-baseline` is `RAN`), so the second baseline is recorded.
  - A new database has none applied, so the second baseline runs.
  - The six old ids stay known through the stored list of the first baseline's document.

  Naming the six in the second list would refuse every database that the first baseline built, because those databases never held them.
- **You:** know the limit. A database that skipped the first squash holds the six and not `100-baseline`; none of the three is applied, so the second baseline runs on it and the backfills it never applied stay unapplied. The protection reaches one squash deep. Before shipping the second squash, run gate 2 (`hasRecordedBaseline(godwit, "100-baseline")`) against every environment.

### `supersedes` removed too early

- **State:** release N+5 removes the `supersedes` argument. A QA database never started release N and is still at `004`.
- **godwit:** the baseline names nothing, so it is a plain once-only migration that is not applied. It runs over the migrated schema and records `RAN`. `005` and `006` are not applied by anything, and the six old documents are warned about as unknown ids on every start.
- **You:** put the argument back and bring the database to the previous release first. This is why gate 2 exists.

### A target names an old id

- **State:** a test calls `migrate(squashedMigrations(...), target = Target.Through("004-order-status"))`.
- **godwit:** `InvalidMigrationsException`: `004-order-status` is not a once-only id in the list. Nothing runs.
- **You:** target `100-baseline`, or use `runIsolated` for the migration under test. A migration that was replaced is no longer testable by id; its code is gone.

## Design decisions

### `supersedes` on the new migration

**Chosen:** the baseline declares the ids it replaces, and godwit decides per database from history: all applied record, none applied run, some applied refuse.

**Alternatives:**

- A baseline setting in the configuration that trusts a number.

  ```text
  // not godwit API
  GodwitConfig(baseline = "006")   // treat everything up to 006 as applied
  ```

  A database that stopped at `004` is silently accepted and starts with a half-built schema. The decision belongs to the database's history, not to a number in code.
- Keep every old migration forever. It is safe and needs no feature, and it is the right answer for a project with ten migrations. At eighty it makes new databases slow to build and the code slow to read.
- Edit `001-initial-setup` into the end state. Migrated databases skip it and new databases get a different schema than production has: the divergence the squash exists to prevent.
- Delete the old migrations and record the baseline by hand on each environment.

That reaches the same end state through a procedure. Every environment needs the call, and nothing checks that the six really are applied there:

```kotlin
import godwit.core.Godwit

/**
 * Without `supersedes`, every environment needs this call for the baseline before the release that deletes the old
 * migrations, and nothing checks that the six really are applied there.
 */
fun markBaselineByHand(godwit: Godwit) {
    godwit.markApplied("100-baseline", reason = "squash of 001 to 006, checked by hand on 2026-10-02")
}
```

**Why:** the rule is explicit in the baseline's declaration, it decides from the data (history) rather than from a belief, and it fails closed on every state that is not "all" or "none". The decision is made once per database, while the baseline is not yet `APPLIED`; after that the baseline's own history document is the record.

### A partial squash is refused

**Chosen:** `PlanConflictException`, with guidance to deploy the previous release first.

**Alternative:** run the replaced migrations that are missing.

**Why:** the missing migrations are not in the list any more. The only code that can apply them is the previous release, so the correct instruction is to run it. A baseline that ran over a half-built schema would be the guess that squashing exists to remove.

### The stored list

**Chosen:** the baseline's history document stores its `supersedes` list, whether the baseline ran or was recorded.

**Alternative:** keep the list in code forever, or delete the old history documents.

**Why:** code can drop the argument once every database has recorded the baseline, and history stays the whole story. Deleting the old documents would erase the audit trail and make history disagree with what `supersedes` meant when it was recorded.

### A recording is not a run

**Chosen:** the out-of-order policy applies to migrations that run, not to a baseline that godwit records.

**Alternative:** apply the policy to the baseline's position.

**Why:** a baseline is listed before the migrations that stay, which production has already applied. Under `OutOfOrder.FAIL` every partial squash would refuse to start on production, the one place it must work.

## See also

- [adopting-an-existing-database.md](adopting-an-existing-database.md): adoption, and the first release that starts from a baseline.
- [declaring-migrations.md](declaring-migrations.md): `migration(id, description, supersedes)` and the step shapes.
- [ordering-and-validation.md](ordering-and-validation.md): the numbering rule and the out-of-order policy.
- [testing.md](testing.md): the fixtures, `testGodwit`, `Target`, `runIsolated`.
- [history-and-reports.md](history-and-reports.md): `SUPERSEDED` documents, `history()`, `markApplied`.
- [failure-and-recovery.md](failure-and-recovery.md): partial supersede as a failure mode.
- [configuration.md](configuration.md): `unknownApplied`.
- [concepts.md](concepts.md): kinds, steps and the run lifecycle.
- [design-decisions.md](design-decisions.md): the index of all decisions.
- [../README.md](../README.md): the project overview.
