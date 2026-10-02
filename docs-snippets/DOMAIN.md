# Example domain: the online shop

Every example in the godwit docs uses this one domain. The code below is real and compile-checked; reuse its names,
ids and signatures exactly. Paths are relative to the `docs-snippets/` directory of the repository; the check scripts
are in `scripts/` at the repository root (`../scripts/` from here).

The public godwit API is the source and KDoc of `../godwit-core/src/main/kotlin/godwit/core/` and
`../godwit-test/src/main/kotlin/godwit/test/`, listed declaration by declaration in `../godwit-core/api/godwit-core.api`
and `../godwit-test/api/godwit-test.api`. Never invent an API that is not there.

## Project layout and build

| Path | What |
|---|---|
| `../godwit-core/src/main/kotlin/godwit/core/` | godwit-core (package `godwit.core`), built from the root build |
| `../godwit-test/src/main/kotlin/godwit/test/` | godwit-test (package `godwit.test`), built from the root build |
| `src/main/kotlin/com/example/shop/` | the example app (package `com.example.shop` and subpackages) |
| `src/main/kotlin/com/example/filestore/` | a small library the shop uses (package `com.example.filestore`) |
| `src/main/kotlin/com/example/shop/docs/<doc_slug>/` | docs snippets, one directory per doc (see below) |
| `neg/`, `../scripts/neg-check.sh` | snippets that must not compile, and the script that proves it |
| `../scripts/check-snippets.sh [repo-dir]` | proves every ```kotlin block in the repo's `README.md` and `docs/**/*.md` appears, after whitespace normalisation, as a contiguous run of lines in one file under `src/main/kotlin`, or under `neg/` when the block follows a line reading exactly `This does not compile:` |
| `../scripts/check-links.sh [repo-dir]` | proves every relative link in the repo's `README.md` and `docs/**/*.md` resolves to an existing file and, with a `#fragment`, to a heading or explicit anchor in it |
| `../scripts/check-content.sh [dir]` | content rules for the whole repository: no em dash, en dash only in numeric ranges, no history narrative, no banned phrases |
| `../scripts/check-docs.sh` | runs the compile, `neg-check.sh`, `check-snippets.sh`, `check-links.sh` and `check-content.sh` in that order and stops at the first failure |

Build: `./gradlew --offline compileKotlin` from `docs-snippets/`. It builds godwit-core and godwit-test from the root
build (`includeBuild("..")` in `settings.gradle.kts`) and compiles the example app (with every docs snippet) against
them. The example app sees godwit only through the public API, as a real app does.
Libraries on the example app's classpath: godwit-core, godwit-test, the MongoDB Kotlin sync driver 5.7.0,
slf4j-api 2.0.17, Kotest 6.2.5 (`kotest-runner-junit5`, `kotest-assertions-core`) and MockK 1.14.9.

## Docs snippets

- A doc file `docs/<name>.md` gets the directory `src/main/kotlin/com/example/shop/docs/<doc_slug>/`, where
  `doc_slug` is the file name with `-` replaced by `_` (`docs/getting-started.md` -> `docs/getting_started/`).
  The repo's `README.md` gets `src/main/kotlin/com/example/shop/docs/readme/`.
- Package `com.example.shop.docs.<doc_slug>`. One package per doc, so top-level names never clash across docs. A doc
  block never shows that package line or imports from a `docs` package: it starts at the imports, or shows a real
  package (`com.example.shop.migrations`, `com.example.shop.testing`, `com.example.filestore`).
- Every ```kotlin block in a doc appears verbatim in a file there, or verbatim inside a surrounding function when it
  is a fragment (for example a `val report = godwit.migrate(...)` line wrapped in `fun example(godwit: Godwit, ...)`).
- Reuse the canonical code below by importing it (`com.example.shop.migrations.shopMigrations`,
  `com.example.shop.services.CustomerService`, ...). When a doc shows one of the canonical files, copy it verbatim
  from its path; do not write a variant with the same id.
- A test snippet is a Kotest `StringSpec` in the same directory (in a real project it lives in `src/test/kotlin`).
- Log lines and BSON documents go in ```text and ```json blocks, in the formats given at the end of this file.

## Collections

| Collection | Holds | Created by | Indexes |
|---|---|---|---|
| `customers` | `Customer` documents | `001-initial-setup` | `email` unique |
| `orders` | `Order` documents | `001-initial-setup` | `{customerId: 1, placedAt: -1}`, `status`; `{customerId: 1, totalMinor: -1}` from `006-order-totals` |
| `products` | `Product` documents | `001-initial-setup` | `sku` unique; Atlas Search index `product-search` (dynamic mappings) |
| `carts` | `Cart` documents | `002-carts` | `customerId` unique; TTL on `updatedAt` (30 days) |
| `countries` | `{_id: "GB", name: "United Kingdom"}` | `reference-countries` (upserts) | `_id` only |
| `files` | file metadata of the file store library | `003-file-store` via `ensureFileStoreSchema()` | `{ownerId: 1, createdAt: -1}`, `storageKey` unique, TTL on `tempExpiresAt` |
| `schema-log` | pre-godwit, hand-maintained record of applied changes: `{_id, version, appliedAt}` | (existing databases only) | `_id` only |
| `godwit-history` | godwit history, one document per migration | godwit | `_id` only |
| `godwit-lock` | the godwit lock document | godwit | `_id` only |

Document fields (all services and migrations use `org.bson.Document`; money is in minor units, `Long`):

| Collection | Fields |
|---|---|
| `customers` | `_id` ObjectId, `email`, `name`, `externalUserId` (absent until linked), `createdAt` Date |
| `orders` | `_id` ObjectId, `customerId` ObjectId, `status` (`PENDING`, `PAID`, `SHIPPED`, `CANCELLED`), `lines` [{`productId`, `quantity` Int, `unitPriceMinor` Long}], `totalMinor` Long, `currency`, `placedAt` Date, `paidAt` Date?, `paymentId`? |
| `products` | `_id` ObjectId, `sku`, `name`, `description`, `priceMinor` Long, `currency` |
| `carts` | `_id` ObjectId, `customerId` ObjectId, `items` [{`productId`, `quantity`}], `updatedAt` Date |

## Domain classes (`com.example.shop.domain`)

| Class | File |
|---|---|
| `Customer(id: ObjectId, email, name, externalUserId: String?, createdAt: Instant)` | `src/main/kotlin/com/example/shop/domain/Customer.kt` |
| `Order(id, customerId, status: OrderStatus, lines: List<OrderLine>, totalMinor: Long, currency, placedAt: Instant, paidAt: Instant?, paymentId: String?)`, `OrderLine(productId, quantity: Int, unitPriceMinor: Long)`, `enum OrderStatus { PENDING, PAID, SHIPPED, CANCELLED }` | `src/main/kotlin/com/example/shop/domain/Order.kt` |
| `Product(id, sku, name, description, priceMinor: Long, currency)` | `src/main/kotlin/com/example/shop/domain/Product.kt` |
| `Cart(id, customerId, items: List<CartItem>, updatedAt: Instant)`, `CartItem(productId, quantity: Int)` | `src/main/kotlin/com/example/shop/domain/Cart.kt` |
| `Country(code, name)` | `src/main/kotlin/com/example/shop/domain/Country.kt` |

## Services (`com.example.shop.services`)

The shop's services are plain classes built from a `MongoDatabase`. Every method that writes takes a `ClientSession`
as its first parameter, so a migration's transactional step passes its `session`. Services must be built from the
same `MongoClient` that `Godwit` gets.

| Type | Members | File |
|---|---|---|
| `class CustomerService(database: MongoDatabase)` | `findByEmail(email): Customer?`; `withoutExternalUserId(): List<Customer>`; `setExternalUserId(session, customerId: ObjectId, externalUserId: String)`; `ensureCustomer(session, seed: SeedCustomer, externalUserId: String): Boolean` | `src/main/kotlin/com/example/shop/services/CustomerService.kt` |
| `class OrderService(database: MongoDatabase)` | `findByCustomer(customerId): List<Order>`; `place(session, order)`; `markPaid(session, orderId, paymentId, paidAt: Instant)`; `cancel(session, orderId)` | `src/main/kotlin/com/example/shop/services/OrderService.kt` |
| `interface IdentityProvider` | `findOrCreateUser(email: String): ExternalUser` (HTTP, idempotent by email) | `src/main/kotlin/com/example/shop/services/IdentityProvider.kt` |
| `data class ExternalUser(id: String, email: String)` | | same file |
| `class HttpIdentityProvider(baseUrl: String, apiKey: String) : IdentityProvider` | the production implementation | same file |
| `interface PaymentGateway` | `paymentStatus(paymentId: String): PaymentStatus` (HTTP, read-only) | `src/main/kotlin/com/example/shop/services/PaymentGateway.kt` |
| `enum class PaymentStatus { AUTHORISED, CAPTURED, REFUNDED, FAILED }` | | same file |
| `interface EmailSender` | `send(to: String, subject: String, body: String)` (HTTP, not idempotent: the counter-example, never called from a migration) | `src/main/kotlin/com/example/shop/services/EmailSender.kt` |

## Configuration (`com.example.shop`)

`src/main/kotlin/com/example/shop/ShopConfig.kt`:

| Type | Fields |
|---|---|
| `ShopConfig` | `mongo: MongoSettings`, `identity: IdentitySettings`, `bootstrap: BootstrapSettings` |
| `MongoSettings` | `uri: String`, `database: String`, `searchIndexWait: Duration?` (null: do not wait for the search index) |
| `IdentitySettings` | `baseUrl: String`, `apiKey: String` |
| `BootstrapSettings` | `customers: List<SeedCustomer>` |
| `SeedCustomer` | `email: String`, `name: String` |
| `fun loadShopConfig(env: Map<String, String> = System.getenv()): ShopConfig` | reads `MONGO_URI`, `MONGO_DATABASE` (default `shop`), `SEARCH_INDEX_WAIT_SECONDS`, `IDENTITY_URL`, `IDENTITY_API_KEY`, `SEED_CUSTOMERS` |

## The file store library (`com.example.filestore`)

`src/main/kotlin/com/example/filestore/FileStoreSchema.kt`: `const val FILES_COLLECTION = "files"` and
`fun MongoDatabase.ensureFileStoreSchema()`, an idempotent setup function built on godwit's public DDL extension
`MongoDatabase.ensureCollection`. The library ships no migrations; the shop calls this function from its own
migration `003-file-store`. This is the pattern the docs recommend for any library that owns collections.

## Migrations (`com.example.shop.migrations`)

All in `src/main/kotlin/com/example/shop/migrations/`. List order = run order.

| # | Id | Kind | Steps | Kotlin name | File | Does | Counters |
|---|---|---|---|---|---|---|---|
| 1 | `001-initial-setup` | once | outside | `fun initialSetup(searchIndexWait: Duration?): Migration` | `001-initial-setup.kt` | creates `customers`, `orders`, `products`, their indexes and the `product-search` search index | none |
| 2 | `002-carts` | once | outside | `val carts` | `002-carts.kt` | creates `carts`, unique `customerId`, 30-day TTL; description "One cart per customer, expiring after 30 days" | none |
| 3 | `003-file-store` | once | outside | `val fileStore` | `003-file-store.kt` | `database.ensureFileStoreSchema()` | none |
| 4 | `004-order-status` | once | inTransaction | `val orderStatus` | `004-order-status.kt` | backfills `status`: PAID when `paidAt` exists, else PENDING | `ordersPaid`, `ordersPending` |
| 5 | `005-customer-external-ids` | once | outside + inTransaction | `fun customerExternalIds(customers: CustomerService, identity: IdentityProvider): Migration` | `005-customer-external-ids.kt` | outside: one `findOrCreateUser` HTTP call per unlinked customer, returns `Map<ObjectId, String>`; transaction: `customers.setExternalUserId(session, ...)` | `customersLinked` |
| 6 | `006-order-totals` | once | outside + inBatches | `val orderTotals` | `006-order-totals.kt` | outside: index `{customerId: 1, totalMinor: -1}`; batches of 500 over `orders` with `pending = exists("totalMinor", false)`, `bulkWrite(session, ...)` | `ordersUpdated` |
| 7 | `reference-countries` | repeatable, revision `"2026-10-01"` | inTransaction | `val referenceCountries` | `reference-countries.kt` | upserts GB, IE, FR, DE into `countries`, deletes the rest | `countriesRemoved` |
| 8 | `bootstrap-customers` | everyStart | outside + inTransaction | `fun bootstrapCustomers(seed: List<SeedCustomer>, customers: CustomerService, identity: IdentityProvider): Migration` | `bootstrap-customers.kt` | outside: `findOrCreateUser` per seed customer, returns `Map<String, String>` (email to external id); transaction: `customers.ensureCustomer(session, ...)`; description "Seed customers from configuration" | `customersCreated` |

The list: `src/main/kotlin/com/example/shop/migrations/ShopMigrations.kt`

```text
fun shopMigrations(config: ShopConfig, customers: CustomerService, identity: IdentityProvider): List<Migration>
```

It passes each migration only what it needs: `config.mongo.searchIndexWait` to `initialSetup`,
`config.bootstrap.customers` to `bootstrapCustomers`, the services to the two migrations that use them.

Adoption hook: `src/main/kotlin/com/example/shop/migrations/applied-before-godwit.kt`,
`fun appliedBeforeGodwit(database: MongoDatabase): Set<String>`, reads the `version` field of every `schema-log`
document. Shop databases created before godwit were migrated by hand and recorded each change there. Wire it as
`GodwitConfig(adoptApplied = ::appliedBeforeGodwit)`.

## Startup wiring

`src/main/kotlin/com/example/shop/ShopApplication.kt`: `fun main()` loads the config, creates one `MongoClient`,
builds `CustomerService(database)`, `OrderService(database)` and `HttpIdentityProvider(...)` from it, runs
`Godwit(client, config.mongo.database).migrate(shopMigrations(config, customers, identity))`, then calls
`startHttpServer(customers, orders)` (the rest of the app). Migrations run before the server starts serving.

## Tests

`src/main/kotlin/com/example/shop/migrations/ShopMigrationsTest.kt` (a `src/test/kotlin` file in a real project):
`class ShopMigrationsTest : StringSpec`, with `private val testConfig: ShopConfig` (`searchIndexWait = null`, one seed
customer `staff@example.com`) and `private class FakeIdentityProvider : IdentityProvider` returning
`ExternalUser(id = "user-$email", email = email)`. Cases:

1. "the migration list is valid": `validateMigrations(shopMigrations(testConfig, mockk(), FakeIdentityProvider()))`.
2. "every migration applies to an empty database, and the next start runs only the every-start one":
   `testGodwit(atlasSearch = true)`, migrate twice, second `ran` is `["bootstrap-customers"]`, `status(...).isUpToDate`.
3. "004 marks orders with paidAt PAID and the rest PENDING, and a second run changes nothing":
   `Target.Before("004-order-status")`, insert two orders, `Target.Through("004-order-status")`, counters,
   `shouldHaveApplied`, `rerun(...)` counts 0.
4. "006 totals every order, on its own": `testGodwit()` (no search needed), `runIsolated(orderTotals)`.

`atlasSearch = true` is needed whenever `001-initial-setup` runs, because it creates a search index.

Shared test fixtures for the docs: `src/main/kotlin/com/example/shop/testing/TestFixtures.kt` (package
`com.example.shop.testing`): `testConfig`, `class FakeIdentityProvider`, `fun migrationsFor(db: TestGodwit)`.

## Names for examples beyond the canonical code

Use these when a doc needs a migration the canonical list does not have, so docs agree with each other:

| Purpose | Id(s) | Shape |
|---|---|---|
| Data then schema (two migrations) | `007-customer-email-lower` then `008-customer-email-lower-index` | canonical files `src/main/kotlin/com/example/shop/migrations/007-customer-email-lower.kt` and `008-customer-email-lower-index.kt`. 007: inBatches of 1000 over `customers`, sets `emailLower` (Kotlin `lowercase(Locale.ROOT)`, skips customers without an email); 008: outside, partial unique index on `emailLower` |
| A migration calling the payment gateway | `009-order-payment-status` | canonical file `src/main/kotlin/com/example/shop/migrations/009-order-payment-status.kt`, `fun orderPaymentStatus(gateway: PaymentGateway)`. outside: `gateway.paymentStatus(paymentId)` per order with `ne("paymentId", null)` and no `paymentStatus`, returns `Map<ObjectId, PaymentStatus>`; inTransaction writes `paymentStatus` |
| Every other example migration | `010` onward, one number per example | a wrong version shown next to its fix shares the fix's id |
| Two branches that both add the next number (pure check) | `007-product-slugs` and `007-cart-currency` | `validateMigrations` reports that the numeric prefixes do not strictly increase |
| Out of order (database check) | `008-cart-currency` applied on staging from one branch, then `007-product-slugs` merged from another | `migrate` throws `PlanConflictException` under `OutOfOrder.FAIL`; `OutOfOrder.RUN` runs 007 and records `outOfOrder: true` |
| A squash | `100-baseline` with `supersedes = squashedIds` (`001-initial-setup` to `006-order-totals`) | canonical file `src/main/kotlin/com/example/shop/migrations/100-baseline.kt`: `squashedIds`, `fun baseline(searchIndexWait)`, `fun squashedMigrations(...)`; outside: the end-state schema |
| Repeatable revision bump | `reference-countries` revision `"2026-10-01"` -> `"2026-11-15"` (adds `"ES" to "Spain"`) | |

## Glossary (use these terms)

| Term | Meaning |
|---|---|
| once-only migration | declared with `migration(id)`; runs once per database |
| every-start migration | declared with `everyStart(id)`; runs on every start, under the lock |
| repeatable migration | declared with `repeatable(id, revision)`; runs when the revision changes |
| outside step | the `outsideTransaction { }` step: no session, at least once, idempotent |
| transactional step | the `inTransaction { }` or `inBatches(...) { }` step: commits with the history record |
| prepared value | what the outside step returns, the parameter of the `inTransaction` lambda |
| history | the `godwit-history` collection, one document per migration, `_id` = migration id |
| lock | the `godwit-lock` document; one holder at a time, leased, renewed by a heartbeat |
| holder | `GodwitConfig.holder`, `<hostname>/<pid>` by default |
| fast path | nothing due: one history read, no lock |
| adoption | adopting a database migrated by another tool, or by hand, through `GodwitConfig.adoptApplied` |
| squash | a superseding migration declared with `supersedes = listOf(...)` |
| untracked database | collections present, no godwit history, nothing adopted |

## Output formats for ```text and ```json blocks

Log lines (logger `godwit`, slf4j key-value pairs as Logback's `%kvp{NONE}` prints them: values unquoted, lists as
`[a, b]`, times as ISO-8601 instants with milliseconds, `error` on "Migration failed" as the exception's class and
message, `error` on "Retrying transaction" as `<codeName> (<code>)` or `commit`; the full list of events is in the
`Godwit` KDoc in `../godwit-core/src/main/kotlin/godwit/core/Godwit.kt`). Holder `shop-7f9c4/1`, run ids are UUIDs.
"Migrations up to date" only appears for a list without an every-start migration (`checked=7` is the shop's list
without `bootstrap-customers`):

```text
INFO  godwit - Migrations up to date runId=0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f checked=7 durationMs=6
INFO  godwit - Waiting for migration lock holder=shop-7f9c4/1 holderRunId=0199a4c1-0d2e-7a11-8c3b-5d6e7f809a1b expiresAt=2026-10-02T10:15:00.210Z waitedMs=10004
INFO  godwit - Acquired migration lock runId=0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f lockWaitMs=212
INFO  godwit - Running migration id=004-order-status kind=ONCE steps=[IN_TRANSACTION] attempt=1
INFO  godwit - Applied migration id=004-order-status kind=ONCE steps=[IN_TRANSACTION] attempts=1 txRetries=0 batches=0 durationMs=84 ordersPaid=1200 ordersPending=37
INFO  godwit - Migrations complete runId=0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f ran=2 recorded=0 upToDate=6 lockWaitMs=212 durationMs=402
```

History document (`godwit-history`) after `004-order-status` ran:

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
  "godwitVersion": "0.1.0",
  "v": 1
}
```
