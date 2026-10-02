# Dependencies

godwit has no dependency injection. A migration that needs a service is a function that takes the service as a
parameter, the app's list is built by a function that takes everything its migrations need, and the app passes real
services at startup and fakes in tests. This page shows that mechanism end to end, what the compiler checks, how
services must be wired so godwit's session works in them, and the cases that need care: expensive services, worker
processes that only check status, services without a session parameter. It ends with why godwit has no container.

## A migration that needs a service is a function

`005-customer-external-ids` links customers to identity provider users. It needs the shop's `CustomerService` and the
`IdentityProvider`, so it is a function of the two; its lambdas capture them:

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

A migration that needs nothing is a `val` (`val orderStatus = migration("004-order-status")...`). godwit looks nothing
up and injects nothing; the step bodies are closures over ordinary Kotlin values.

## The list is a function of everything its migrations need

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

Each migration takes only its own part: `initialSetup` gets one `Duration?`, `bootstrapCustomers` gets the seed list,
and only the two migrations that call services get services.

## Wiring at startup

`main` builds the services from the same `MongoClient` it gives `Godwit`, migrates, then starts serving:

```kotlin
fun main() {
    val config = loadShopConfig()
    MongoClient.create(config.mongo.uri).use { client ->
        val database = client.getDatabase(config.mongo.database)
        val customers = CustomerService(database)
        val orders = OrderService(database)
        val identity = HttpIdentityProvider(config.identity.baseUrl, config.identity.apiKey)

        Godwit(client, config.mongo.database).migrate(shopMigrations(config, customers, identity))

        startHttpServer(customers, orders)
    }
}
```

The services are the ones the app serves requests with. Nothing is built twice and nothing is built for godwit only.

## Adding a migration that needs a new service

The shop adds `009-order-payment-status`, which asks the payment gateway for the status of every paid order. The
gateway is a service no migration has used so far.

1. The migration is a function of the gateway. The HTTP calls run in the outside step; their results reach the
   transaction as the prepared value:

```kotlin
/**
 * Stores the payment gateway's status on every order that has a payment. The gateway calls run outside any
 * transaction, one per order; they only read, so a retry repeats them harmlessly. The statuses reach the transaction
 * as the outside step's value.
 */
fun orderPaymentStatus(gateway: PaymentGateway): Migration = migration("009-order-payment-status")
    .outsideTransaction {
        collection("orders")
            .find(and(ne("paymentId", null), exists("paymentStatus", false)))
            .map { order ->
                checkLock()
                order.getObjectId("_id") to gateway.paymentStatus(order.getString("paymentId"))
            }
            .toList()
            .toMap()
    }
    .inTransaction { statuses ->
        val orders = collection("orders")
        statuses.forEach { (orderId, status) ->
            orders.updateOne(session, eq("_id", orderId), set("paymentStatus", status.name))
        }
        count("ordersUpdated", statuses.size)
    }
```

2. The list function gains the parameter, because one of its migrations needs it (numbers need not be contiguous):

```kotlin
/** The shop's list once 009 is added: the gateway joins the parameters, because 009 needs it. */
fun shopMigrations(
    config: ShopConfig,
    customers: CustomerService,
    identity: IdentityProvider,
    gateway: PaymentGateway
): List<Migration> = listOf(
    initialSetup(searchIndexWait = config.mongo.searchIndexWait),
    carts,
    fileStore,
    orderStatus,
    customerExternalIds(customers, identity),
    orderTotals,
    orderPaymentStatus(gateway),
    referenceCountries,
    bootstrapCustomers(config.bootstrap.customers, customers, identity)
)
```

3. Every caller that does not pass a gateway stops compiling. `main` as it was:

This does not compile:

```kotlin
Godwit(client, config.mongo.database).migrate(shopMigrations(config, customers, identity))
```

```text
e: No value passed for parameter 'gateway'.
```

4. `main` passes one. The gateway here connects lazily; the edge case
   [expensive services](#a-service-that-is-expensive-or-fragile-to-build) explains why:

```kotlin
/** The shop's startup once 009 is added. The gateway connects only if a migration calls it. */
fun main() {
    val config = loadShopConfig()
    MongoClient.create(config.mongo.uri).use { client ->
        val database = client.getDatabase(config.mongo.database)
        val customers = CustomerService(database)
        val orders = OrderService(database)
        val identity = HttpIdentityProvider(config.identity.baseUrl, config.identity.apiKey)
        val gateway = LazyPaymentGateway { connectPaymentGateway() }

        Godwit(client, config.mongo.database).migrate(shopMigrations(config, customers, identity, gateway))

        startHttpServer(customers, orders)
    }
}
```

5. Every test that builds the list passes a fake or a mock; see [testing with fakes](#testing-with-fakes).

A missing dependency is never discovered at startup in production: the build fails first.

## Narrow inputs

Pass a migration the value it reads, not the object that holds it. `001-initial-setup` waits for the product search
index for `config.mongo.searchIndexWait`, and its signature says exactly that:

```kotlin
fun initialSetup(searchIndexWait: Duration?): Migration = migration("001-initial-setup")
```

| Narrow input | Whole object (`initialSetup(config: ShopConfig)`) |
|---|---|
| The signature lists what the migration reads | every reader must open the body to find out |
| A test passes `null` | a test builds a whole `ShopConfig` with values the migration never reads |
| Restructuring `ShopConfig` touches the list function only | it touches every applied migration that takes the config, code that should never change |

The list function takes `config: ShopConfig` because it is the one place that knows which part each migration needs.

## Testing with fakes

The services are parameters, so a test passes whatever it likes. `validateMigrations` runs no step, so any service will
do, mocks included:

```kotlin
"the migration list is valid" {
    validateMigrations(shopMigrations(testConfig, mockk(), mockk(), mockk()))
}
```

A hand-written fake is a class:

```kotlin
private class FakeIdentityProvider : IdentityProvider {
    override fun findOrCreateUser(email: String) = ExternalUser(id = "user-$email", email = email)
}
```

A test that runs a migration through the real runner builds services that use MongoDB from the test database, so they
share the client `testGodwit()` gave godwit, and fakes or mocks for everything external:

```kotlin
"005 links an unlinked customer through the identity provider" {
    val db = testGodwit()
    val customers = CustomerService(db.database)
    db.database.getCollection("customers", Document::class.java)
        .insertOne(Document("email", "ada@example.com").append("name", "Ada"))
    val identity = mockk<IdentityProvider> {
        every { findOrCreateUser("ada@example.com") } returns ExternalUser("user-1", "ada@example.com")
    }

    db.godwit.runIsolated(customerExternalIds(customers, identity)).count("customersLinked") shouldBe 1L
    customers.findByEmail("ada@example.com")?.externalUserId shouldBe "user-1"
}
```

```kotlin
"009 asks the gateway about orders with a payment only" {
    val db = testGodwit()
    val orders = db.database.getCollection("orders", Document::class.java)
    val paid = ObjectId()
    orders.insertMany(
        listOf(
            Document("_id", paid).append("status", "PAID").append("paymentId", "pay-1"),
            Document("_id", ObjectId()).append("status", "PENDING").append("paymentId", null)
        )
    )
    val gateway = mockk<PaymentGateway> {
        every { paymentStatus("pay-1") } returns PaymentStatus.CAPTURED
    }

    db.godwit.runIsolated(orderPaymentStatus(gateway)).count("ordersUpdated") shouldBe 1L
    orders.find(eq("_id", paid)).first()["paymentStatus"] shouldBe "CAPTURED"
    verify(exactly = 1) { gateway.paymentStatus(any()) }
}
```

The unpaid order stores `paymentId: null`, which `exists("paymentId", true)` would match; the migration filters with
`ne("paymentId", null)`, and the `verify` line proves the gateway saw one call. See [testing](testing.md) for
`runIsolated` and the rest of godwit-test.

## Edge cases

### A service built on a different MongoClient

State: `main` creates one client for the services and another for `Godwit`. Everything compiles:

```kotlin
/** Wrong: godwit and the services use two clients, so the services reject godwit's session. */
fun mainWithTwoClients() {
    val config = loadShopConfig()
    val servicesClient = MongoClient.create(config.mongo.uri)
    val customers = CustomerService(servicesClient.getDatabase(config.mongo.database))
    val identity = HttpIdentityProvider(config.identity.baseUrl, config.identity.apiKey)
    val gateway = LazyPaymentGateway { connectPaymentGateway() }

    val godwitClient = MongoClient.create(config.mongo.uri)
    Godwit(godwitClient, config.mongo.database).migrate(shopMigrations(config, customers, identity, gateway))
}
```

What godwit does: on a fresh database `001` to `004` run, because they only use the step's own `collection(...)`, which
belongs to godwit's client. `005-customer-external-ids` runs its outside step (the HTTP calls), then its transaction
passes godwit's session to `customers.setExternalUserId`, whose collection belongs to the other client. The driver
rejects the session, the transaction rolls back, and the migration is recorded `FAILED`:

```text
ERROR godwit - Migration failed id=005-customer-external-ids step=IN_TRANSACTION attempts=1 error=java.lang.IllegalStateException: state should be: ClientSession from same MongoClient
godwit.core.MigrationFailedException: Migration 005-customer-external-ids failed in IN_TRANSACTION: state should be: ClientSession from same MongoClient
The step passed godwit's session to an operation on another MongoClient. Build Godwit and the services the migrations call from the same MongoClient.
Caused by: java.lang.IllegalStateException: state should be: ClientSession from same MongoClient
```

What you do: build every service from the client you give `Godwit`, as `main` above does. Two `MongoDatabase` objects
from the same client are fine; two clients are not, even with the same connection string. In tests, call `testGodwit()`
once (`val db = testGodwit()`) and build the services from `db.database` or `db.client`, the client of `db.godwit`. The
next start retries `005` from its outside step. See
[transactions and sessions](transactions-and-sessions.md#one-mongoclient).

### A service that is expensive or fragile to build

State: the payment gateway client signs in when it is constructed, takes seconds, and throws while the gateway is
down. `009-order-payment-status` applied months ago, but `main` still builds the gateway on every start, because the
list function takes it. A gateway outage now stops the shop from starting, for a migration that will never run again.
What godwit does: nothing; it never touches a dependency unless a step calls it. What you do: make construction cheap
and the expensive part lazy, in the app:

```kotlin
/** Connects on first use, so a start that runs no gateway migration never connects. */
class LazyPaymentGateway(connect: () -> PaymentGateway) : PaymentGateway {
    private val gateway by lazy(connect)

    override fun paymentStatus(paymentId: String): PaymentStatus = gateway.paymentStatus(paymentId)
}
```

`main` passes `LazyPaymentGateway { connectPaymentGateway() }`. A start where `009` is due connects on the first
`paymentStatus` call; every other start never connects. The migration's signature does not change, and tests pass a
plain mock.

### A worker process that only checks the schema

State: the shop deploys a background worker from the same build. The worker must not migrate (the shop does that) and
must not start on a database older than its code. It has no identity provider or payment gateway credentials.
`requireUpToDate` reads history and compares it with the list; it runs no step, so the worker passes stand-ins for the
services it does not have:

```kotlin
/** A background worker deployed next to the shop. It never migrates; it refuses to start on an older schema. */
fun workerMain() {
    val config = loadShopConfig()
    MongoClient.create(config.mongo.uri).use { client ->
        val database = client.getDatabase(config.mongo.database)
        val migrations = shopMigrations(config, CustomerService(database), NoIdentityProvider, NoPaymentGateway)

        Godwit(client, config.mongo.database).requireUpToDate(migrations)

        processPaidOrders(OrderService(database))
    }
}

/** The worker has no identity provider credentials. requireUpToDate runs no step, so nothing calls this. */
private object NoIdentityProvider : IdentityProvider {
    override fun findOrCreateUser(email: String): ExternalUser = error("The worker does not run migrations")
}

/** The worker has no payment gateway credentials either. */
private object NoPaymentGateway : PaymentGateway {
    override fun paymentStatus(paymentId: String): PaymentStatus = error("The worker does not run migrations")
}
```

What godwit does: when the worker starts before the shop has run `009`, it throws, and the worker exits:

```text
godwit.core.PendingMigrationsException: The database is not up to date. Pending: [009-order-payment-status]. Problems: []
```

Every-start migrations never count as pending, so `bootstrap-customers` does not keep the worker down. What you do:
let the orchestrator restart the worker until the shop has migrated. See
[history and reports](history-and-reports.md) for `status()` and `requireUpToDate()`.

### A service method without a session parameter

State: `CustomerService.withoutExternalUserId()` takes no `ClientSession`. Called inside `inTransaction`, its read runs
outside the transaction: it does not see the transaction's uncommitted writes, and it is not part of the snapshot the
transaction reads. In a test with godwit-test, `SessionEscapeDetector` fails the step, and the test sees
`MigrationFailedException` with `SessionEscapeError` as its cause:

```text
godwit.core.MigrationFailedException: Migration 005-customer-external-ids failed in IN_TRANSACTION: find on customers ran without the step's session, outside the transaction. Pass `session` to the driver call or the service method.
Caused by: godwit.test.SessionEscapeError: find on customers ran without the step's session, outside the transaction. Pass `session` to the driver call or the service method.
```

What you do: call it in the outside step and hand the result to the transaction, which is exactly what
`005-customer-external-ids` does, or add a `session` parameter to the service method. The shop's convention is that
every service method that writes takes the session as its first parameter. See
[transactions and sessions](transactions-and-sessions.md).

### A service that reads data when it is built

State: the shop adds a `ShippingCountries` service that loads the `countries` collection in its constructor and keeps
it in memory. `main` builds it before `migrate`. On the start that applies a new `reference-countries` revision, the
service holds the countries from before the migration until the process restarts. What godwit does: nothing; it runs
before or after app code exactly as `main` orders it. What you do: build services that cache data after `migrate`, or
load on first use. Services that migrations need must be cheap to build and must not read data in their constructors.

### Many dependencies

State: the shop grows to a dozen services, and `shopMigrations` takes a dozen parameters. godwit does not care how the
list function gets its inputs. What you do: when the app already groups its services in one object of its own, pass
that object to the list function and keep each migration's parameters narrow:
`customerExternalIds(services.customers, services.identity)`. The grouping object is app code, and no migration
depends on it.

### A dependency no migration uses any more

State: a squash (see [squashing migrations](squashing-migrations.md)) replaces `009-order-payment-status` and the
migrations before it with one baseline migration. No migration calls the payment gateway any more, yet
`shopMigrations` still declares `gateway` and `main` still builds one. The Kotlin compiler does not warn about an
unused function parameter. What you do: the IDE's unused-parameter inspection flags it; remove the parameter, the
wiring in `main` and the fakes in the tests.

## Design decisions

### Function parameters

Chosen: a migration that needs a service is a function of the service; the list is a function of everything its
migrations need. The compiler checks the wiring, the signature documents the dependencies, tests pass fakes with no
setup, and godwit needs no knowledge of how the app builds its objects.

### No DI container

Considered: godwit resolving services from a container, or from a registry the app fills at startup (sketch of a
rejected shape, not godwit API):

```text
migration("005-customer-external-ids").outsideTransaction {
    val identity = get<IdentityProvider>()          // looked up at run time
    ...
}
```

Rejected: a missing registration fails at run time, on the start where the migration is due, possibly in production; a
migration's dependencies are invisible in its signature; godwit would need an integration per container, or its own
container. A list built by a function gives the same wiring with the compiler checking it.

### No typed dependency bag

Considered: one class holding every service, a type parameter on each declaration, and the bag passed to every step
(sketch of a rejected shape, not godwit API):

```text
class ShopDeps(val customers: CustomerService, val identity: IdentityProvider, val gateway: PaymentGateway)

val customerExternalIds = migration<ShopDeps>("005-customer-external-ids").outsideTransaction {
    deps.customers.withoutExternalUserId().associate { it.id to deps.identity.findOrCreateUser(it.email).id }
}
```

Rejected: every declaration that needs anything carries the type parameter, migrations that need nothing need a second
form, every migration sees every service, and mixing migrations with different bags in one list needs variance rules
that produce hard compiler errors. Function parameters give each migration exactly what it uses.

### No lazy providers

Considered: godwit offering `Provider<T>` parameters resolved only when a step runs. Rejected: Kotlin has `lazy`, and
whether a service is expensive is the app's knowledge. `LazyPaymentGateway` above is six lines of app code.

Every decision is indexed in [design decisions](design-decisions.md).

## See also

- [Concepts](concepts.md): the model in one page.
- [Declaring migrations](declaring-migrations.md): `val` and `fun` migrations, the list.
- [Transactions and sessions](transactions-and-sessions.md): the session, the one-client rule.
- [Outside-transaction steps](outside-transaction-steps.md): external calls and idempotency.
- [Libraries and modules](libraries-and-modules.md): services and migrations across modules.
- [Testing](testing.md): `testGodwit`, `runIsolated`, `SessionEscapeDetector`.
- [History and reports](history-and-reports.md): `status()` and `requireUpToDate()`.
- [Configuration](configuration.md) and the [README](../README.md).
