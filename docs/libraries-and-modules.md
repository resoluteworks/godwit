# Libraries and modules

An app's database belongs to the app. godwit does not support migrations shipped inside libraries: a library that owns
collections ships an idempotent setup function, and the app calls it from a migration of its own, with its own id, at
its own place in its own list. In an app split into modules, the app module owns the one list and assembles it from
migration values and functions the feature modules expose. This page shows both patterns with the shop and the small
file store library it uses, what happens when a library changes its schema, the variants that godwit rejects and why,
and the reasoning behind the rule.

## The rule

| Who | Does | Never does |
|---|---|---|
| A library | ships an idempotent setup function for its whole current schema, and session-taking functions for data changes | ships migrations, calls `Godwit.migrate`, writes to `godwit-history` |
| A feature module of the app | declares migration values and functions, like any migration file | builds its own list or calls `Godwit.migrate` |
| The app module | owns the one `List<Migration>` per database and the one `migrate` call | |

## A library's setup function

The shop stores file metadata through a small file store library. The library owns the `files` collection and ships
one function that creates it with its indexes:

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

Every call in it is safe to repeat:

| Call | On a database that already has it |
|---|---|
| `MongoDatabase.ensureCollection(name)`, from godwit-core | returns false; the raw `createCollection` would throw `NamespaceExists` (48) before MongoDB 7.0, or from 7.0 when the options differ |
| `createIndexes` with identical specifications | does nothing |

`ensureCollection`, `MongoCollection.ensureSearchIndex` and `MongoCollection.dropIndexIfExists` are public extensions in
godwit-core, so a library can use them outside any migration. The library then depends on godwit-core, whose own
dependencies are the MongoDB driver and slf4j-api. A library that wants no godwit dependency writes the same check
itself: catch code 48 around `createCollection`. See [outside-transaction steps](outside-transaction-steps.md) for the
three helpers.

## The app's migration

The shop calls the function from its own migration:

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

The id, the position in the list and the decision to run it are the shop's. `database` is the step's database, from the
client godwit was given, with majority write concern, so the library's DDL is majority-committed before godwit records
the migration APPLIED ([write concern](outside-transaction-steps.md#write-concern)). The setup function is DDL, so it
goes in the outside step.

## When the library changes its schema

File store 2.0 adds an index for listing a user's files by content type, and gives files stored by 1.x a default
content type. The library changes its setup function to describe the whole 2.0 schema, and ships a function for the
data change that takes the caller's session:

```kotlin
/**
 * Creates the file store's collection and indexes. Idempotent, so it is safe to call on every start or from a
 * migration. The library ships this function, not migrations: an app calls it from one of its own migrations.
 */
fun MongoDatabase.ensureFileStoreSchema() {
    ensureCollection(FILES_COLLECTION)
    getCollection(FILES_COLLECTION, Document::class.java).createIndexes(
        listOf(
            IndexModel(compoundIndex(ascending("ownerId"), descending("createdAt"))),
            IndexModel(compoundIndex(ascending("ownerId"), ascending("contentType"))),
            IndexModel(ascending("storageKey"), IndexOptions().unique(true)),
            IndexModel(ascending("tempExpiresAt"), IndexOptions().expireAfter(0L, TimeUnit.SECONDS))
        )
    )
}

/** The content type of a file stored without one. */
const val DEFAULT_CONTENT_TYPE = "application/octet-stream"

/**
 * Gives every file stored before 2.0 the default content type and returns how many it changed. Idempotent: it only
 * touches files without a content type. Runs in the caller's transaction through [session].
 */
fun MongoDatabase.setDefaultContentTypes(session: ClientSession): Long =
    getCollection(FILES_COLLECTION, Document::class.java)
        .updateMany(session, exists("contentType", false), set("contentType", DEFAULT_CONTENT_TYPE))
        .modifiedCount
```

The shop upgrades the dependency and, in the same commit, adds a migration that calls both:

```kotlin
/** File store 2.0: its new index first, then the content type of the files stored before it. */
val fileStoreContentType = migration("023-file-store-content-type")
    .outsideTransaction {
        database.ensureFileStoreSchema()
    }
    .inTransaction {
        count("filesUpdated", database.setDefaultContentTypes(session))
    }
```

and lists it after the last once-only migration (numbers need not be contiguous):

```kotlin
/** The shop's list once it upgrades to file store 2.0. */
fun shopMigrations(config: ShopConfig, customers: CustomerService, identity: IdentityProvider): List<Migration> =
    listOf(
        initialSetup(searchIndexWait = config.mongo.searchIndexWait),
        carts,
        fileStore,
        orderStatus,
        customerExternalIds(customers, identity),
        orderTotals,
        fileStoreContentType,
        referenceCountries,
        bootstrapCustomers(config.bootstrap.customers, customers, identity)
    )
```

What each database does on the first start with 2.0:

| Database | `003-file-store` | `023-file-store-content-type` |
|---|---|---|
| Existing, migrated with 1.x | applied: skipped | outside step: creates the content-type index; the other three already exist and are left alone. Transaction: sets the default on every 1.x file, `filesUpdated` = their number |
| Fresh | runs 2.0's setup: the whole 2.0 schema, content-type index included | outside step: nothing to create. Transaction: no file lacks a content type, `filesUpdated` = 0 |

Both end with the same schema. This is why a setup function describes the library's whole current schema and must be
idempotent: the app's first migration and every upgrade migration call the same function, on databases at any version.

## Writing a library for this pattern

| Do | Why |
|---|---|
| One function that creates the whole current schema, idempotently | the app's first migration and each upgrade call it on databases at any version |
| Use `ensureCollection`, `ensureSearchIndex` and `dropIndexIfExists` | the raw `createCollection`, `createSearchIndex` and `dropIndex` can fail when run a second time, depending on the server version and the options |
| Give a changed index a new name: create it first, then drop the old one with `dropIndexIfExists` | under the old name, a changed unique, sparse, partial filter or collation option fails with `IndexKeySpecsConflict` (86) and a changed TTL with `IndexOptionsConflict` (85); building the new index first keeps the old one, and its unique constraint, in place until the new one is ready |
| Data changes as functions that take a `ClientSession` | the app runs them inside its own transaction, with its history record |
| For large data changes, publish the `pending` filter and a per-page function | the app pages with `inBatches` |
| Take the collection name as a parameter when an app may need to choose it | an app may already have a collection with the library's default name |
| Say in the release notes that the schema changed, and change a published schema version constant | the app must add a migration that calls the setup function again |
| Test that the setup function can run twice | a second call that throws breaks every app's upgrade |

The file store's own test, in the library's repository:

```kotlin
class FileStoreSchemaTest : StringSpec({
    "ensureFileStoreSchema can run any number of times" {
        val database = testGodwit().database
        database.ensureFileStoreSchema()
        database.ensureFileStoreSchema()

        val indexes = database.getCollection(FILES_COLLECTION, Document::class.java).listIndexes()
            .map { it.getString("name") }
            .toList()
        indexes shouldContainExactlyInAnyOrder listOf(
            "_id_",
            "ownerId_1_createdAt_-1",
            "ownerId_1_contentType_1",
            "storageKey_1",
            "tempExpiresAt_1"
        )
    }
})
```

`testGodwit()` serves here only as a throwaway database on a test container; see [testing](testing.md).

## Multi-module applications

An app built from several Gradle modules has one list, in the app module. Feature modules hold migration files like
any other and expose the values and functions; the app module imports them and lists them in run order. The shop split
into modules:

```text
:app        ShopMigrations.kt (the list), 001-initial-setup.kt (customers, orders and products together),
            003-file-store.kt (wires a library), Main.kt (main: the one migrate call)
:carts      002-carts.kt
:orders     004-order-status.kt, 006-order-totals.kt
:customers  005-customer-external-ids.kt, bootstrap-customers.kt
:catalogue  reference-countries.kt
```

The list in `:app` reads exactly as in a single-module build; only the imports point at modules:

```kotlin
fun shopMigrations(
    config: ShopConfig,
    customers: CustomerService,
    identity: IdentityProvider
): List<Migration> = listOf(
    initialSetup(searchIndexWait = config.mongo.searchIndexWait),
    carts,
    fileStore,
    orderStatus,
    customerExternalIds(customers, identity),
    orderTotals,
    referenceCountries,
    bootstrapCustomers(config.bootstrap.customers, customers, identity)
)
```

| Convention | Why |
|---|---|
| A migration that touches several modules' collections lives in `:app` | `001-initial-setup` creates three modules' collections in one migration |
| Migration declarations in feature modules are public | the app module must reference them |
| A migration that needs another module's service takes it as a parameter | the app module wires `customerExternalIds(customers, identity)`; `:customers` does not depend on the identity module's implementation. See [dependencies](dependencies.md) |
| Numbers are global | two modules adding the next number in parallel is the branch collision `validateMigrations` catches |

## Edge cases

### The app upgrades the library and forgets the migration

State: the shop moves to file store 2.0 but adds no migration. Existing databases keep the 1.x indexes, because
`003-file-store` is applied and never runs again; fresh databases get the 2.0 schema, because `003-file-store` calls
2.0's function. Nothing fails; queries by content type are slow on old databases only. What godwit does: nothing, it
cannot know the library changed. What you do: read the library's release notes and add the migration in the upgrade
commit. When a library changes its schema often, a repeatable migration keyed on the library's published schema
version (here `FILE_STORE_SCHEMA_VERSION`, a constant the file store library would publish next to its setup function)
re-applies the setup whenever the version changes:

```kotlin
/** Re-applies the file store schema whenever the library's schema version changes. */
val fileStoreSchema = repeatable("file-store-schema", revision = FILE_STORE_SCHEMA_VERSION)
    .outsideTransaction {
        database.ensureFileStoreSchema()
    }
```

The trade-off: repeatable migrations run after every once-only migration, so a once-only migration in the same release
cannot rely on the new schema; data changes still need a once-only migration; and the library must keep
`FILE_STORE_SCHEMA_VERSION` in step with its setup function. See [repeatable migrations](repeatable-migrations.md).

### The library's data change is too large for one transaction

State: the shop holds 30 million files. `setDefaultContentTypes(session)` would update every 1.x file in one
transaction and fail past the 60-second lifetime, again on every start. What you do: page through them with
`inBatches`, using the pieces the library publishes for this:

```kotlin
/** The files [setDefaultContentType] has not handled yet, for an app that pages through them. */
val filesWithoutContentType: Bson = exists("contentType", false)

/** Gives [files] the default content type, in the caller's transaction. */
fun MongoDatabase.setDefaultContentType(session: ClientSession, files: List<Document>) {
    getCollection(FILES_COLLECTION, Document::class.java)
        .updateMany(session, `in`("_id", files.map { it["_id"] }), set("contentType", DEFAULT_CONTENT_TYPE))
}
```

```kotlin
/** The same change for a files collection too large for one transaction. */
val fileStoreContentTypeInBatches = migration("023-file-store-content-type")
    .outsideTransaction {
        database.ensureFileStoreSchema()
    }
    .inBatches(FILES_COLLECTION, pending = filesWithoutContentType, batchSize = 1000) { files ->
        database.setDefaultContentType(session, files)
        count("filesUpdated", files.size)
    }
```

Each page of 1000 files commits with its checkpoint; a restart resumes after it. See
[batched backfills](batched-backfills.md).

### The library changes an index's options

State: file store 3.0 makes the `storageKey` index partial (unique among files that have a `storageKey`), keeping the
key and the default name `storageKey_1`. On an existing database, the upgrade migration's `createIndexes` meets the old
`storageKey_1` with different options, the server rejects it with `IndexKeySpecsConflict` (86), and the migration is
recorded `FAILED`; fresh databases are fine. What you do: the library gives the new index its own name
(`storageKey_unique_partial`), and its setup function calls `createIndexes` with it first and
`dropIndexIfExists("storageKey_1")` after. The server accepts the partial index next to the old one, since their
options differ, and both calls are idempotent, so the setup function still converges on databases at any version. The
old `storageKey_1` guards `storageKey` until the new index is built, so no moment passes without a unique index on it;
dropping first would leave that gap for as long as the build takes.

### The app already has a collection with the library's name

State: before adopting the file store, the shop kept invoice PDFs in its own `files` collection, without `storageKey`.
`003-file-store` runs: `ensureCollection("files")` returns false, and `createIndexes` builds the library's indexes over
the shop's documents. The unique `storageKey` index fails with a duplicate key error (11000), because every invoice
document has no `storageKey` and they all index as null. The migration is recorded `FAILED`. What you do: rename the
app's collection in an earlier migration, or use a library whose setup function takes the collection name as a
parameter. The file store's `FILES_COLLECTION` is a constant, so the first is the only option here.

### The app appends a library's migration list

State: a library ships migrations anyway, and the app appends them to its list. Both compile:

```kotlin
/** Not supported: a library that ships its own migrations. */
val fileStoreMigrations: List<Migration> = listOf(
    migration("001-files-collection").outsideTransaction {
        ensureCollection(FILES_COLLECTION)
    },
    migration("002-files-indexes").outsideTransaction {
        collection(FILES_COLLECTION).createIndex(ascending("storageKey"), IndexOptions().unique(true))
    }
)

/** The app appends the library's list to its own. validateMigrations rejects the result. */
fun shopMigrationsWithLibrary(config: ShopConfig, customers: CustomerService, identity: IdentityProvider) =
    shopMigrations(config, customers, identity) + fileStoreMigrations
```

What godwit does: `validateMigrations`, and `migrate` before any I/O, rejects the list. The library's numbers restart
at 1, and its migrations land after the shop's repeatable ones:

```text
godwit.core.InvalidMigrationsException: Invalid migrations:
- 001-files-collection is listed after 006-order-totals, but its numeric prefix 1 is not greater than 6
- 001-files-collection is once-only but listed after reference-countries (repeatable); list repeatable and every-start migrations after every once-only migration
- 002-files-indexes is once-only but listed after reference-countries (repeatable); list repeatable and every-start migrations after every once-only migration
```

Prepending the library's list fails the numbering check the same way (`001-initial-setup` after `002-files-indexes`).
A library that drops the numbers passes validation, and the problems move to production: each library upgrade adds
migrations that run on the next deploy without appearing in the app's diff, at a position the app did not choose. What
you do: ask the library for its setup function, or write one in the app from the library's documented schema.

### Feature modules expose lists and the app concatenates them

State: `:orders` exposes `orderMigrations`, and the app concatenates module lists:

```kotlin
/** In the orders module. Not this: a module-level list fixes the order of that module's migrations only. */
val orderMigrations: List<Migration> = listOf(orderStatus, orderTotals)

/** In the app module, concatenating module lists. validateMigrations rejects the result. */
fun shopMigrationsByModule(config: ShopConfig, customers: CustomerService, identity: IdentityProvider) =
    listOf(initialSetup(config.mongo.searchIndexWait), carts, fileStore) +
        orderMigrations +
        listOf(
            customerExternalIds(customers, identity),
            referenceCountries,
            bootstrapCustomers(config.bootstrap.customers, customers, identity)
        )
```

What godwit does: the shop's migrations interleave across modules in time (`004` orders, `005` customers, `006`
orders), so no concatenation of module lists matches run order:

```text
godwit.core.InvalidMigrationsException: Invalid migrations:
- 005-customer-external-ids is listed after 006-order-totals, but its numeric prefix 5 is not greater than 6
```

What you do: modules expose values and functions; the app module lists each one, in run order.

### A module calls `migrate` with its own list on the shared database

State: `:customers` builds a `Godwit` for the shop database and migrates its own two migrations
(`005-customer-external-ids`, `bootstrap-customers`) at its own startup hook; the app migrates the rest. Each call reads the whole history collection and knows only its own list, so each
start logs the other list's ids as unknown applied:

```text
WARN  godwit - Unknown applied migrations ids=[001-initial-setup, 002-carts, 003-file-store, 004-order-status, 006-order-totals, reference-countries]
```

The out-of-order check of each list cannot see the other list's migrations, `status()` with either list reports only
half the picture, and a fresh database runs the two lists in whichever order the two calls happen at startup. What you
do: one list and one `migrate` call per database, in the app module.

### The app owns two databases

State: the shop keeps reporting data in a separate `shop-reporting` database. This is supported: godwit works per
database, and each database has its own history, lock and list:

```kotlin
/** The reporting database's own list. It never touches the shop database. */
fun reportingMigrations(): List<Migration> = listOf(
    migration("001-daily-sales").outsideTransaction {
        ensureCollection("daily-sales")
        collection("daily-sales").createIndex(descending("day"), IndexOptions().unique(true))
    }
)
```

```kotlin
/** Two databases: two Godwit instances, two lists, two histories, two locks. */
fun migrateBothDatabases(
    client: MongoClient,
    config: ShopConfig,
    customers: CustomerService,
    identity: IdentityProvider
) {
    Godwit(client, config.mongo.database).migrate(shopMigrations(config, customers, identity))
    Godwit(client, "shop-reporting").migrate(reportingMigrations())
}
```

Ids are unique per database, so both lists can start at `001`. A transactional step's session is valid across both
databases, because they share a client, but each migration should touch its own database only: a migration's history
record commits in the database it belongs to.

### Two modules take the same number

State: `:carts` adds `007-cart-currency` while `:catalogue` adds `007-product-slugs`, on different branches. The merged
list fails `validateMigrations` in the unit test. What you do: the second to merge renumbers. See
[ordering and validation](ordering-and-validation.md#4-numeric-prefixes-strictly-increase).

## Design decisions

### Libraries ship setup functions, not migrations

Chosen: a library exposes idempotent setup functions (and session-taking functions for data changes); the app calls
them from its own migrations. Three reasons:

| Reason | With library-shipped migrations | With setup functions |
|---|---|---|
| Schema changes are visible | bumping a library version adds migrations that run on the next deploy, in production, absent from the app's diff | the app's diff contains the migration that calls the new setup |
| One order | library migrations and app migrations interleave in time; a fresh database runs them in a different order than production did, and the out-of-order check cannot compare two tracks | every change is a line in one list; fresh and old databases run the same order |
| The app owns its database | the library decides when the app's database changes, how large the transaction is, and what a failure blocks | the app decides the id, the position, the transaction, the batch size and the release |

Considered:

| Alternative | Why not |
|---|---|
| Libraries ship lists with their own id prefix and their own ordering track, each with its own history | Fixes id collisions only. Order across tracks is still undefined, the history and configuration gain a grouping concept, and upgrades still change production silently. |
| Libraries ship migration values that the app lists | The app chooses the position, but the library still owns the ids and the code, and an upgrade that edits an applied migration changes nothing on old databases and everything on fresh ones. |
| The app calls the library's setup function directly at every start, outside godwit | No record of what ran, DDL on every boot, no ordering against the app's migrations, no lock between instances, and no place for a data change. |

### One list per database in a multi-module app

Chosen: the app module owns the list; modules expose values and functions. Run order is a property of the whole
database, and only a module that sees every migration can state it. Per-module lists fail the numbering check as soon as
two modules' migrations interleave, which is the normal case.

Every decision is indexed in [design decisions](design-decisions.md).

## See also

- [Concepts](concepts.md): one database, one list, one history, one lock.
- [Declaring migrations](declaring-migrations.md): migration files and the list.
- [Dependencies](dependencies.md): services across modules as parameters.
- [Ordering and validation](ordering-and-validation.md): the checks that reject concatenated lists.
- [Outside-transaction steps](outside-transaction-steps.md): `ensureCollection`, `ensureSearchIndex`,
  `dropIndexIfExists`.
- [Batched backfills](batched-backfills.md): large data changes.
- [Repeatable migrations](repeatable-migrations.md): re-applying a setup function by revision.
- [Adopting an existing database](adopting-an-existing-database.md): a database a library's setup function already
  prepared.
- [Testing](testing.md), [configuration](configuration.md) and the [README](../README.md).
