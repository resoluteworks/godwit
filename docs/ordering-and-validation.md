# Ordering and validation

This page covers how godwit decides what runs and in which order, and the checks that stop a bad list or a mismatched
database before anything runs. Some checks look at the list alone: they need no database, run before any I/O, and a
unit test runs them too. Others compare the list with the database's history: out-of-order migrations and applied ids
the list does not know. Every rule below has a failing example and the exact message it produces. The page ends with
why ids are flat, why out-of-order fails by default and why unknown ids only warn.

## List order is run order

godwit takes the order from the list and from nothing else. History stores no position and no sequence number.

| Step of a `migrate` call | Order |
|---|---|
| 1. record superseded squashes | list order |
| 2. run due once-only migrations | list order |
| 3. run due repeatable and every-start migrations | list order, after every due once-only migration |

The shop's list on a fresh database and on a database at the current release:

| Position | Id | Kind | Fresh database | Database at the current release |
|---|---|---|---|---|
| 1 | `001-initial-setup` | once-only | runs 1st | up to date |
| 2 | `002-carts` | once-only | runs 2nd | up to date |
| 3 | `003-file-store` | once-only | runs 3rd | up to date |
| 4 | `004-order-status` | once-only | runs 4th | up to date |
| 5 | `005-customer-external-ids` | once-only | runs 5th | up to date |
| 6 | `006-order-totals` | once-only | runs 6th | up to date |
| 7 | `reference-countries` | repeatable, `2026-10-01` | runs 7th | up to date (same revision) |
| 8 | `bootstrap-customers` | every-start | runs 8th | runs |

## Checks on the list

`validateMigrations(list)` checks the list without a database and throws `InvalidMigrationsException` with every
problem it finds, not only the first. `Godwit.migrate`, `Godwit.status` and `Godwit.requireUpToDate` run the same check
before any I/O, so an invalid list never reaches the database. The exception's `problems` holds one line per problem,
and its message lists them:

```text
godwit.core.InvalidMigrationsException: Invalid migrations:
- <problem>
- <problem>
```

| # | Rule | Problem line |
|---|---|---|
| 1 | Every id matches `[A-Za-z0-9][A-Za-z0-9._-]{0,127}` | `invalid id "<id>": ids match [A-Za-z0-9][A-Za-z0-9._-]{0,127}` |
| 2 | No id appears twice, counting ids named in `supersedes` lists | `duplicate id <id> at positions <i> and <j>`, or `duplicate id <id>: named in the supersedes lists of <a> and <b>` |
| 3 | A `supersedes` list does not name an id the list declares | `<a> supersedes <id>, which the list declares` |
| 4 | Among once-only migrations, numeric prefixes strictly increase | `<id> is listed after <previous>, but its numeric prefix <n> is not greater than <m>` |
| 5 | Repeatable and every-start migrations come after every once-only one | `<id> is once-only but listed after <other> (<kind>); list repeatable and every-start migrations after every once-only migration` |
| 6 | A repeatable's revision is not blank | `<id> has a blank revision` |
| 7 | Repeatable and every-start migrations do not use `inBatches` | `<id> is <kind> and uses inBatches, which only once-only migrations can use` |
| 8 | Every `inBatches` batch size is 1 to 10000 | `<id> has batchSize <n>; batchSize is 1 to 10000` |
| 9 | A `Target.Before` or `Target.Through` names a once-only id in the list (`migrate` only) | `Target.Before("<id>") names no once-only migration in the list` |

Positions count from 1.

### 1. Id format

An id is 1–128 characters from `A-Z`, `a-z`, `0-9`, `.`, `_` and `-`, starting with a letter or digit. It is the `_id`
of the history document and appears in log lines as a bare value.

```kotlin
fun invalidId(): List<Migration> = listOf(
    migration("007 product slugs: from SKU").inTransaction { }
)
```

```text
godwit.core.InvalidMigrationsException: Invalid migrations:
- invalid id "007 product slugs: from SKU": ids match [A-Za-z0-9][A-Za-z0-9._-]{0,127}
```

### 2. No duplicates

The same migration listed twice, or two migrations with the same id, would share one history document. Declaring an id
twice usually breaks the numbering as well, and both problems are listed:

```kotlin
fun declaredTwice(): List<Migration> = listOf(
    initialSetup(searchIndexWait = null),
    carts,
    fileStore,
    carts
)
```

```text
godwit.core.InvalidMigrationsException: Invalid migrations:
- duplicate id 002-carts at positions 2 and 4
- 002-carts is listed after 003-file-store, but its numeric prefix 2 is not greater than 3
```

Ids named in `supersedes` lists count too. Two squashes cannot both replace `002-carts`:

```kotlin
fun supersededTwice(): List<Migration> = listOf(
    migration("100-baseline", supersedes = listOf("001-initial-setup", "002-carts")).outsideTransaction { },
    migration("101-carts-baseline", supersedes = listOf("002-carts")).outsideTransaction { }
)
```

```text
godwit.core.InvalidMigrationsException: Invalid migrations:
- duplicate id 002-carts: named in the supersedes lists of 100-baseline and 101-carts-baseline
```

### 3. A squash does not name a declared id

A squash replaces migrations that leave the list in the same commit. Naming one that is still declared would make it
both pending and replaced:

```kotlin
fun supersedesDeclared(): List<Migration> = listOf(
    carts,
    migration("100-baseline", supersedes = listOf("001-initial-setup", "002-carts")).outsideTransaction { }
)
```

```text
godwit.core.InvalidMigrationsException: Invalid migrations:
- 100-baseline supersedes 002-carts, which the list declares
```

See [squashing migrations](squashing-migrations.md).

### 4. Numeric prefixes strictly increase

An id that starts with digits followed by `-`, `_` or `.` has a numeric prefix, compared as a number: `9-a` comes
before `10-b`, and `010-a` equals `10-b`. Among once-only migrations, each numeric prefix must be greater than the one
before it in the list. Ids without a numeric prefix, and repeatable and every-start migrations, are not compared. Gaps
are fine.

The rule exists for one situation. Two branches start from the same `main`, which ends at `006-order-totals`. One adds
`007-product-slugs`:

```kotlin
/** Gives every product a URL slug: its SKU in lower case. */
val productSlugs = migration("007-product-slugs")
    .inTransaction {
        val result = collection("products").updateMany(
            session,
            exists("slug", false),
            listOf(Aggregates.set(Field("slug", Document("\$toLower", "\$sku"))))
        )
        count("productsUpdated", result.modifiedCount)
    }
```

The other adds `007-cart-currency`:

```kotlin
/** Gives every cart the shop's currency, as first written on its branch. */
val cartCurrencyOnBranch = migration("007-cart-currency")
    .inTransaction {
        val result = collection("carts").updateMany(session, exists("currency", false), set("currency", "GBP"))
        count("cartsUpdated", result.modifiedCount)
    }
```

Both merge, and Git sees no conflict, because they are different files and different lines of the list:

```kotlin
listOf(
    initialSetup(searchIndexWait = config.mongo.searchIndexWait),
    carts,
    fileStore,
    orderStatus,
    customerExternalIds(customers, identity),
    orderTotals,
    productSlugs,
    cartCurrencyOnBranch,
    referenceCountries,
    bootstrapCustomers(config.bootstrap.customers, customers, identity)
)
```

The unit test on `main` fails:

```text
godwit.core.InvalidMigrationsException: Invalid migrations:
- 007-cart-currency is listed after 007-product-slugs, but its numeric prefix 7 is not greater than 7
```

The fix: the second branch to merge renumbers its migration to `008-cart-currency`. It has run nowhere but on
developer machines and test databases, so renumbering is safe. If it has run on a shared environment, see
[out of order](#out-of-order) below.

### 5. Repeatable and every-start migrations come last

A repeatable or every-start migration runs after every due once-only migration whatever its position, so the list
must show it last; reading order and run order stay the same. Appending a new migration to the end of the list is the
common way to break this:

```kotlin
fun appendedAtTheEnd(config: ShopConfig, customers: CustomerService, identity: IdentityProvider): List<Migration> =
    shopMigrations(config, customers, identity) + productSlugs
```

```text
godwit.core.InvalidMigrationsException: Invalid migrations:
- 007-product-slugs is once-only but listed after reference-countries (repeatable); list repeatable and every-start migrations after every once-only migration
```

Insert new once-only migrations before `referenceCountries`.

### 6. A repeatable has a revision

The revision is what makes a repeatable due again, so it cannot be blank:

```kotlin
val referenceCurrencies = repeatable("reference-currencies", revision = "")
    .inTransaction {
        collection("currencies").replaceOne(
            session,
            eq("_id", "GBP"),
            Document("_id", "GBP").append("name", "Pound sterling"),
            ReplaceOptions().upsert(true)
        )
    }
```

```text
godwit.core.InvalidMigrationsException: Invalid migrations:
- reference-currencies has a blank revision
```

See [repeatable migrations](repeatable-migrations.md).

### 7. No `inBatches` in repeatable or every-start migrations

A checkpoint records how far one run got. A migration that runs again has no single run to resume, so `inBatches` is
for once-only migrations. The draft type does not carry the kind, so this compiles and fails here:

```kotlin
val productSearchText = repeatable("product-search-text", revision = "2026-10-01")
    .inBatches("products", pending = exists("searchText", false)) { products ->
        collection("products").bulkWrite(
            session,
            products.map { product ->
                UpdateOneModel<Document>(eq("_id", product["_id"]), set("searchText", product.getString("name")))
            }
        )
    }
```

```text
godwit.core.InvalidMigrationsException: Invalid migrations:
- product-search-text is repeatable and uses inBatches, which only once-only migrations can use
```

### 8. Batch sizes are 1 to 10000

A page is one transaction, and a transaction must commit well within the server's 60-second lifetime. 10000 is the
ceiling; most backfills want the default of 500. See [batched backfills](batched-backfills.md).

`007-customer-email-lower` from [batched backfills](batched-backfills.md#data-then-schema-two-migrations), with a batch
size above the ceiling:

```kotlin
val customerEmailLowerOversized = migration("007-customer-email-lower")
    .inBatches("customers", pending = exists("emailLower", false), batchSize = 50_000) { customers ->
        val updates = customers.mapNotNull { customer ->
            val email = customer.getString("email") ?: return@mapNotNull null
            UpdateOneModel<Document>(eq("_id", customer["_id"]), set("emailLower", email.trim().lowercase(Locale.ROOT)))
        }
        if (updates.isNotEmpty()) collection("customers").bulkWrite(session, updates)
        count("customersUpdated", updates.size)
        count("customersWithoutEmail", customers.size - updates.size)
    }
```

```text
godwit.core.InvalidMigrationsException: Invalid migrations:
- 007-customer-email-lower has batchSize 50000; batchSize is 1 to 10000
```

### 9. A target names a once-only migration

`Target.Before(id)` and `Target.Through(id)` stop a test run at a once-only migration. A typo is caught before any
I/O:

```kotlin
godwit.migrate(migrations, target = Target.Before("004-order-totals"))
```

```text
godwit.core.InvalidMigrationsException: Invalid migrations:
- Target.Before("004-order-totals") names no once-only migration in the list
```

`validateMigrations(list)` takes no target, so this check runs in `migrate` only. See [testing](testing.md).

### Catching list problems in a unit test

One test runs every rule above on every build, without a database. `testConfig` and `FakeIdentityProvider` are the
shop's test fixtures ([testing](testing.md#fixtures)):

```kotlin
"the migration list is valid" {
    validateMigrations(shopMigrations(testConfig, mockk(), FakeIdentityProvider()))
}
```

## Checks against the database

After reading history, godwit compares the list with it. These checks need the database, so `validateMigrations`
cannot run them. `status()` reports them in `MigrationStatus.problems` without throwing, except the out-of-order,
squash and untracked-database checks while the adoption hook can still run; `migrate` and `requireUpToDate` throw.
`migrate` runs them before taking the lock and again under the lock, after adoption. While the adoption hook can still
run (it is configured and history holds nothing but `ADOPTED` documents), the out-of-order and squash checks run only
under the lock, after the hook, because the hook may fill the gap they would report; the untracked-database check
always runs under the lock.

| Check | Default | On failure | Configured by |
|---|---|---|---|
| Out of order: a pending once-only migration listed before an applied once-only migration | fail | `PlanConflictException`, nothing runs | `GodwitConfig.outOfOrder` |
| Unknown applied: an `APPLIED` history id the list does not know | warn | WARN log line and `MigrationReport.unknownApplied`; with `FAIL`, `PlanConflictException` | `GodwitConfig.unknownApplied` |
| A squash whose superseded ids are only partly applied | fail | `PlanConflictException` | not configurable; see [squashing migrations](squashing-migrations.md) |
| Collections, no history, nothing adopted | refuse | `UntrackedDatabaseException` | `GodwitConfig.untrackedDatabase`; see [adopting an existing database](adopting-an-existing-database.md) |
| Adopted ids that are not a prefix of the once-only list | as out of order | as out of order | see [adopting an existing database](adopting-an-existing-database.md) |

`PlanConflictException` lists every conflict:

```text
godwit.core.PlanConflictException: Migrations cannot run against this database:
- <problem>
```

### Out of order

A once-only migration is out of order on a database when it is pending there and a once-only migration listed after it
is `APPLIED` there. Only once-only history counts: the `APPLIED` documents of repeatable and every-start migrations,
which every live database has and which are listed last, and ids the list does not know never make a once-only migration
out of order. The `appliedAfter` key of the log line lists once-only ids only. It happens when a database runs code from
a branch before an earlier migration from another branch merges. Running it would apply the two in a different order on
this database than everywhere else, so by default godwit refuses.

A walkthrough with two branches. `main` ends at `006-order-totals`, and production and staging are both at `006`.

1. Branch `product-slugs` adds `007-product-slugs` and opens a pull request.
2. Branch `cart-currency`, which knows `007` is taken, adds `008-cart-currency`:

```kotlin
/** The same migration after its author renumbered it. */
val cartCurrency = migration("008-cart-currency")
    .inTransaction {
        val result = collection("carts").updateMany(session, exists("currency", false), set("currency", "GBP"))
        count("cartsUpdated", result.modifiedCount)
    }
```

3. The `cart-currency` branch is deployed to staging for a demo. Its list ends `orderTotals, cartCurrency`: the
   numbering check passes (6 then 8), and staging applies `008-cart-currency`.
4. Both branches merge. `main` lists `orderTotals, productSlugs, cartCurrency`. The branch's list and release 1.4's,
   which the tests below use:

```kotlin
/** The cart-currency branch as staging ran it, before 007-product-slugs merged. */
fun cartCurrencyBranchMigrations(
    config: ShopConfig,
    customers: CustomerService,
    identity: IdentityProvider
): List<Migration> =
    listOf(
        initialSetup(searchIndexWait = config.mongo.searchIndexWait),
        carts,
        fileStore,
        orderStatus,
        customerExternalIds(customers, identity),
        orderTotals,
        cartCurrency,
        referenceCountries,
        bootstrapCustomers(config.bootstrap.customers, customers, identity)
    )

/** Release 1.4: main after both branches merged and cart-currency was renumbered. */
fun release14Migrations(config: ShopConfig, customers: CustomerService, identity: IdentityProvider): List<Migration> =
    listOf(
        initialSetup(searchIndexWait = config.mongo.searchIndexWait),
        carts,
        fileStore,
        orderStatus,
        customerExternalIds(customers, identity),
        orderTotals,
        productSlugs,
        cartCurrency,
        referenceCountries,
        bootstrapCustomers(config.bootstrap.customers, customers, identity)
    )
```

5. `main` deploys to staging. Staging's history has `008-cart-currency` applied and `007-product-slugs` pending, listed
   before it. godwit throws before it takes the lock, and nothing runs:

```text
godwit.core.PlanConflictException: Migrations cannot run against this database:
- 007-product-slugs is pending, but 008-cart-currency, listed after it, is applied (out of order; OutOfOrder.RUN runs it)
```

6. `main` deploys to production. Production never ran the branch: `007` runs, then `008`, in list order.

What you do about staging, in order of preference:

| Option | When |
|---|---|
| Recreate the staging database from scratch or from a production copy | staging data is disposable |
| Run staging with `OutOfOrder.RUN` | the two migrations are independent, as here: one touches `products`, the other `carts` |

`OutOfOrder.RUN` is a per-database setting. The shop turns it on for staging only:

```kotlin
/** Staging runs branch builds before they merge, so it accepts migrations that arrive out of order. */
fun shopGodwitConfig(allowOutOfOrder: Boolean): GodwitConfig =
    GodwitConfig(outOfOrder = if (allowOutOfOrder) OutOfOrder.RUN else OutOfOrder.FAIL)
```

With it, staging runs `007-product-slugs` in list order with the other due migrations, logs it, and records it:

```text
WARN  godwit - Running out-of-order migration id=007-product-slugs appliedAfter=[008-cart-currency]
```

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
  "godwitVersion": "0.1.0",
  "v": 1
}
```

`MigrationOutcome.outOfOrder` is true for it in the report. The walkthrough as a test:

```kotlin
"staging ran 008 from its branch, so main's 007 is out of order there" {
    val db = testGodwit(atlasSearch = true)
    val customers = CustomerService(db.database)
    db.godwit.migrate(cartCurrencyBranchMigrations(testConfig, customers, FakeIdentityProvider()))

    val main = release14Migrations(testConfig, customers, FakeIdentityProvider())
    shouldThrow<PlanConflictException> { db.godwit.migrate(main) }

    val staging = Godwit(db.client, db.databaseName, GodwitConfig(outOfOrder = OutOfOrder.RUN))
    staging.migrate(main)["007-product-slugs"].outOfOrder shouldBe true
}
```

Only once-only migrations are ever out of order, and only applied once-only migrations put them there. Repeatable and
every-start migrations run after every once-only one by definition.

### Unknown applied ids

An id is unknown when history records it `APPLIED` and the list neither declares it nor finds it in the stored
`supersedes` list of a superseding migration recorded in history. The normal cause is a rollback deploy.

A walkthrough. Release 1.4 adds `007-product-slugs` and `008-cart-currency` and deploys; production applies both. 1.4
has a bug in an unrelated feature, and the team redeploys 1.3, whose list ends at `006-order-totals`.

1. 1.3 starts. History holds `007` and `008` as `APPLIED`; 1.3's list does not know them. godwit logs them, puts them
   in the report, and carries on: nothing in 1.3 is due except `bootstrap-customers`.

```text
WARN  godwit - Unknown applied migrations ids=[007-product-slugs, 008-cart-currency]
```

2. 1.3 runs against a schema one step ahead of its code. That is safe when migrations are additive (new fields,
   new indexes), which is the reason to write them that way.
3. 1.4.1 deploys with the fix. `007` and `008` are already `APPLIED`: they do not run again.

As a test: release 1.4's list from the walkthrough above, then release 1.3's, which is the shop's `shopMigrations`:

```kotlin
"the previous release still starts on a database this release migrated" {
    val db = testGodwit(atlasSearch = true)
    val customers = CustomerService(db.database)
    db.godwit.migrate(release14Migrations(testConfig, customers, FakeIdentityProvider()))

    val release13 = shopMigrations(testConfig, customers, FakeIdentityProvider())
    db.godwit.migrate(release13).unknownApplied shouldBe listOf("007-product-slugs", "008-cart-currency")
}
```

`UnknownApplied.FAIL` turns the warning into `PlanConflictException`. It suits a check that must refuse a database
holding migrations this build does not know, such as a CI job that verifies a production snapshot against the release
about to ship. It does not suit the app itself, which could then never be rolled back:

```kotlin
/** For a check that must fail when the database holds migrations this build does not know. */
val strictGodwitConfig = GodwitConfig(unknownApplied = UnknownApplied.FAIL)
```

```text
godwit.core.PlanConflictException: Migrations cannot run against this database:
- 007-product-slugs is applied, but the list does not declare it (UnknownApplied.FAIL)
- 008-cart-currency is applied, but the list does not declare it (UnknownApplied.FAIL)
```

### How squashed ids stay known

A squash replaces old migrations with one that `supersedes` them: `100-baseline` in
[squashing migrations](squashing-migrations.md#how-a-squash-decides) declares `supersedes = squashedIds`, the six ids
`001-initial-setup` to `006-order-totals`. The history document of `100-baseline` stores that list, whether it was
recorded `SUPERSEDED` (every replaced id was applied) or `RAN` (none was). Once every database has recorded
`100-baseline`, the `supersedes` argument can be deleted from the code.

History still holds `001` to `006` as `APPLIED` on old databases. They are not unknown: `100-baseline`'s stored
`supersedes` list names them, so no warning is logged. Removing the argument before every database has recorded
`100-baseline` is a mistake: a database still at `006` would see `100-baseline` as an ordinary pending migration and
run it over its existing schema. `history()` on each environment shows whether `100-baseline` is recorded.

## Edge cases

### A migration is inserted between two applied ones

State: production is at `006`. A developer adds `order-currency`, without a number, between
`005-customer-external-ids` and `006-order-totals` in the list. The list check passes, because ids without a numeric
prefix are not compared. On production, `order-currency` is pending and listed before the applied `006`, so `migrate`
throws `PlanConflictException` (out of order) and nothing runs. With a number there is no room between `005` and `006`:
`005-order-currency` after `005-customer-external-ids` fails the numbering check (5 is not greater than 5). What you do:
add new once-only migrations after the last once-only migration, with the next number.

### Applied migrations are reordered

State: someone swaps `002-carts` and `003-file-store` in the list. The numbering check fails (3 then 2). Without
numbers, the swap passes every check: both are applied everywhere, history stores no position, and existing databases
see nothing due. A fresh database runs the new order. What you do: never reorder; the numbering check enforces it for
numbered ids.

### Numbers compare as numbers

| Ids in list order | Result |
|---|---|
| `9-add-cart-index`, `10-backfill-carts` | valid: 9 < 10 |
| `009-a`, `010-b` | valid |
| `010-a`, `10-b` | invalid: 10 is not greater than 10 |
| `001-a`, `cart-cleanup`, `002-b` | valid: `cart-cleanup` has no numeric prefix and is not compared |
| `2026.10.01-a` | prefix 2026 (digits up to the first `.`) |

Pick one width (the shop uses three digits) so file listings sort in run order.

### A migration is deleted from the list

State: `003-file-store` is deleted from the list without a squash. Existing databases log it as unknown applied on
every start. Fresh databases never run it, so they lack the `files` collection that old databases have. What you do:
never delete a migration on its own; replace a run of old migrations with a squash, which records their ids as known.
See [squashing migrations](squashing-migrations.md).

### A failed migration is missing from a rolled-back release

State: 1.4 adds `007-product-slugs`, which fails on production (`FAILED`, with `lastError`). The team rolls back to
1.3. Only `APPLIED` ids count as unknown, so 1.3 neither warns about `007` nor runs it: its document stays `FAILED`
until a release that declares `007` starts and retries it, outside step first. What you do: fix and redeploy; check
`history()` for documents left `FAILED`.

### Old instances restart during a rolling deploy

State: a rolling deploy of 1.4 has migrated the database; an instance still on 1.3 crashes and restarts. With the
default `UnknownApplied.WARN` it logs the two unknown ids and starts. With `UnknownApplied.FAIL` it would fail to start
until the deploy finished, leaving the old release with less capacity mid-deploy. What you do: keep the default for the
app.

### `OutOfOrder.RUN` on migrations that touch the same data

State: staging ran `008-cart-currency` (sets `currency` on carts), then gets `007-cart-totals` (computes cart totals,
reading `currency` when present) out of order. On staging `007` sees carts that already have a currency; on production
`007` runs first and sees none. The two environments end up with different data from the same code. What you do: use
`OutOfOrder.RUN` only when the migrations are independent; otherwise recreate the staging database.

### Targets ignore repeatable and every-start migrations

State: a test calls `migrate(list, target = Target.Through("004-order-status"))`. godwit runs once-only migrations up
to and including `004` and no repeatable or every-start migration, so `reference-countries` and `bootstrap-customers`
do not run. `MigrationReport.pending` lists the once-only ids after the target. What you do: insert test data shaped
for the migration under test between a `Before` and a `Through` call. See [testing](testing.md).

## Design decisions

### Flat global ids

Chosen: an id is one string, unique in the database, and it is the history document's `_id`. There are no groups,
modules or libraries in an id, and no separate version field.

| Considered | Rejected because |
|---|---|
| A per-library or per-module prefix with its own ordering, so each owner numbers independently | Order across owners is undefined: a fresh database runs the owners' migrations in a different order than production did. godwit does not support migrations shipped in libraries; see [libraries and modules](libraries-and-modules.md). |
| A version number plus a name (`version = 4, name = "order-status"`) | Two fields to keep unique and in step; renaming the name is a free-text change that looks like a new migration. |
| The class or property name as the id | Renaming a Kotlin symbol, an everyday refactoring, would create a new migration. |
| Timestamps as numbers (`20261002-order-status`) | Branch collisions become rare, but a branch created earlier and merged later is out of order on every database that ran the other branch first, which is more frequent than collisions. Sequential numbers make collisions visible in the unit test instead. |

### Out-of-order fails by default

Chosen: `OutOfOrder.FAIL`, with `OutOfOrder.RUN` as a per-database setting recorded as `outOfOrder: true`. A database
that applies the same migrations in a different order than every other database is the one way the same code produces
different data, and whether it matters depends on what the migrations touch, which godwit cannot know. Failing makes
the person who deployed decide. The cost is that branch deploys to shared environments need the setting or a database
reset.

### Unknown ids warn by default

Chosen: `UnknownApplied.WARN`, with `UnknownApplied.FAIL` available. An applied id the list does not know is the normal
state while an older release runs after a rollback, and during a rolling deploy. Failing would make rollbacks
impossible exactly when they are needed. The warning, `MigrationReport.unknownApplied` and `MigrationStatus.unknownApplied`
make it visible; ids recorded in a squash's stored `supersedes` list do not count, so a cleaned-up list stays quiet.

Every decision is indexed in [design decisions](design-decisions.md).

## See also

- [Concepts](concepts.md): the run lifecycle.
- [Declaring migrations](declaring-migrations.md): ids, the list, `validateMigrations` in a test.
- [Squashing migrations](squashing-migrations.md): `supersedes` and partially applied squashes.
- [Adopting an existing database](adopting-an-existing-database.md): the untracked-database guard and the adoption
  prefix rule.
- [Repeatable migrations](repeatable-migrations.md): why they are listed last.
- [History and reports](history-and-reports.md): `status()`, `MigrationReport`, the log lines.
- [Failure and recovery](failure-and-recovery.md): every failure, including plan conflicts.
- [Testing](testing.md): `Target.Before` and `Target.Through`.
- [Configuration](configuration.md): `outOfOrder`, `unknownApplied` and the other settings, and the
  [README](../README.md).
