package godwit.core.docs

import com.example.filestore.ensureFileStoreSchema
import com.example.shop.SeedCustomer
import com.example.shop.migrations.bootstrapCustomers
import com.example.shop.migrations.carts
import com.example.shop.migrations.customerExternalIds
import com.example.shop.migrations.fileStore
import com.example.shop.migrations.orderStatus
import com.example.shop.migrations.orderTotals
import com.example.shop.migrations.referenceCountries
import com.example.shop.services.CustomerService
import com.example.shop.services.ExternalUser
import com.example.shop.services.IdentityProvider
import com.example.shop.services.PaymentGateway
import com.example.shop.services.PaymentStatus
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.Filters.nin
import com.mongodb.client.model.IndexModel
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes.ascending
import com.mongodb.client.model.Indexes.compoundIndex
import com.mongodb.client.model.Indexes.descending
import com.mongodb.client.model.InsertManyOptions
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.model.Updates.set
import com.mongodb.kotlin.client.MongoCollection
import com.mongodb.kotlin.client.MongoDatabase
import godwit.core.Migration
import godwit.core.migration
import godwit.core.repeatable
import org.bson.Document
import org.bson.types.ObjectId
import java.math.BigInteger
import java.util.Date
import java.util.concurrent.TimeUnit

// The shop of the docs, for the scenarios of DocsFidelityTest. Its migrations are the docs-snippets shop's own files
// (compiled into this module's docsShop source set), except the few whose shop code needs Atlas, which are written
// here with the same ids, steps and counters, and the same code minus the Atlas search index.

/**
 * `001-initial-setup` as the shop declares it, without `ensureSearchIndex`: the product search index needs Atlas,
 * which the replica set of these scenarios is not. The scenarios that quote the search index's failure run the shop's
 * own `initialSetup` on the Atlas local image.
 */
val initialSetupWithoutSearch: Migration = migration("001-initial-setup")
    .outsideTransaction {
        ensureCollection("customers")
        ensureCollection("orders")
        ensureCollection("products")

        collection("customers").createIndex(ascending("email"), IndexOptions().unique(true))
        collection("orders").createIndexes(
            listOf(
                IndexModel(compoundIndex(ascending("customerId"), descending("placedAt"))),
                IndexModel(ascending("status"))
            )
        )
        collection("products").createIndex(ascending("sku"), IndexOptions().unique(true))
    }

/** `008-cart-currency` as ordering-and-validation.md shows it: every cart in the shop's currency. */
val cartCurrency: Migration = migration("008-cart-currency")
    .inTransaction {
        val result = collection("carts").updateMany(session, exists("currency", false), set("currency", "GBP"))
        count("cartsUpdated", result.modifiedCount)
    }

/** The ids `100-baseline` replaces. */
val squashedIds = listOf(
    "001-initial-setup",
    "002-carts",
    "003-file-store",
    "004-order-status",
    "005-customer-external-ids",
    "006-order-totals"
)

/** `100-baseline` as the shop declares it, without the product search index, like [initialSetupWithoutSearch]. */
val baseline: Migration = migration("100-baseline", description = "End state of 001 to 006", supersedes = squashedIds)
    .outsideTransaction {
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
    }

/** The countries of `reference-countries` at revision `2026-11-15`: those of `2026-10-01` and Spain. */
private val NOVEMBER_COUNTRIES = listOf(
    "GB" to "United Kingdom",
    "IE" to "Ireland",
    "FR" to "France",
    "DE" to "Germany",
    "ES" to "Spain"
)

/** `reference-countries` at revision `2026-11-15`: the shop's code with the next revision and its list. */
val referenceCountriesNovember: Migration = repeatable("reference-countries", revision = "2026-11-15")
    .inTransaction {
        val countries = collection("countries")
        NOVEMBER_COUNTRIES.forEach { (code, name) ->
            countries.replaceOne(
                session,
                eq("_id", code),
                Document("_id", code).append("name", name),
                ReplaceOptions().upsert(true)
            )
        }
        val removed = countries.deleteMany(session, nin("_id", NOVEMBER_COUNTRIES.map { it.first })).deletedCount
        count("countriesRemoved", removed)
    }

/** The customer every environment's configuration seeds. It exists on every database that has run the shop. */
val SEED: List<SeedCustomer> = listOf(SeedCustomer(email = "staff@example.com", name = "staff"))

/**
 * The identity provider of the scenarios: the same user for the same email, as the real one. [onCall] runs on every
 * call first, so a scenario can make the provider slow or time out.
 */
class StubIdentityProvider(private val onCall: (email: String) -> Unit = {}) : IdentityProvider {
    override fun findOrCreateUser(email: String): ExternalUser {
        onCall(email)
        return ExternalUser(id = "user-${email.substringBefore('@')}", email = email)
    }
}

/** The payment gateway of the scenarios: every payment is captured. [onCall] runs on every call first. */
class StubPaymentGateway(private val onCall: (paymentId: String) -> Unit = {}) : PaymentGateway {
    override fun paymentStatus(paymentId: String): PaymentStatus {
        onCall(paymentId)
        return PaymentStatus.CAPTURED
    }
}

/**
 * The shop's list at its current release, as `shopMigrations` builds it, with [initialSetupWithoutSearch] for
 * `001-initial-setup`, [customers] as the service `005` and `bootstrap-customers` write with, and [identity].
 */
fun shopMigrations(customers: CustomerService, identity: IdentityProvider): List<Migration> = listOf(
    initialSetupWithoutSearch,
    carts,
    fileStore,
    orderStatus,
    customerExternalIds(customers, identity),
    orderTotals,
    referenceCountries,
    bootstrapCustomers(SEED, customers, identity)
)

/** [list] with [migrations] inserted before its first repeatable or every-start migration. */
fun List<Migration>.plusOnceOnly(vararg migrations: Migration): List<Migration> {
    val at = indexOfFirst { it.id == "reference-countries" || it.id == "bootstrap-customers" }.let {
        if (it < 0) size else it
    }
    return take(at) + migrations + drop(at)
}

/** [list] without the migrations [ids]. */
fun List<Migration>.without(vararg ids: String): List<Migration> = filter { it.id !in ids }

/** Writes [documents] in chunks of 10,000, unordered: the bulk loads of the scenarios. */
fun MongoCollection<Document>.load(documents: Sequence<Document>) {
    documents.chunked(10_000).forEach { insertMany(it, InsertManyOptions().ordered(false)) }
}

/** The ObjectId [offset] after [first], counting the twelve bytes as one number. */
fun objectIdAfter(first: String, offset: Long): ObjectId {
    val value = BigInteger(first, 16) + BigInteger.valueOf(offset)
    return ObjectId(value.toString(16).padStart(24, '0'))
}

/**
 * An order of the shop: [lines] of one item each at 10.00, and the fields the scenario sets. 004 gives an order
 * without `status` one, PAID when it has `paidAt`; 006 sums its lines into `totalMinor`.
 */
fun order(
    id: Any,
    paid: Boolean = true,
    status: String? = null,
    lines: List<Document> = listOf(Document("quantity", 1).append("unitPriceMinor", 1000L))
): Document = Document("_id", id)
    .append("customerId", ObjectId("66f0a1b2c3d4e5f6a7b8c9d0"))
    .append("placedAt", Date(1_759_400_000_000L))
    .apply { if (paid) append("paidAt", Date(1_759_400_060_000L)) }
    .apply { if (status != null) append("status", status) }
    .append("lines", lines)

/** A customer of the shop, linked to the identity provider unless [linked] is false. */
fun customer(n: Int, linked: Boolean = true): Document = Document("_id", ObjectId())
    .append("email", "customer$n@example.com")
    .append("name", "Customer $n")
    .apply { if (linked) append("externalUserId", "user-customer$n") }
    .append("createdAt", Date(1_759_400_000_000L))

/** The seed customers, as a database that has run the shop holds them. */
fun MongoDatabase.seedCustomers() {
    getCollection("customers", Document::class.java).insertMany(
        SEED.map {
            Document("_id", ObjectId()).append("email", it.email).append("name", it.name)
                .append("externalUserId", "user-${it.email.substringBefore('@')}").append("createdAt", Date())
        }
    )
}

/** Release 1 of the shop: `001` to `003`, the repeatable and the every-start migration. */
fun List<Migration>.release1(): List<Migration> =
    without("004-order-status", "005-customer-external-ids", "006-order-totals")

/**
 * A new database that has run the shop's releases up to [release] of the current list, with the 1,237 orders the app
 * took after release 1, 1,200 of them paid, as `004-order-status` finds them.
 */
fun shopDatabase(release: (List<Migration>) -> List<Migration> = { it }): String {
    val database = DocsMongo.newDatabase()
    ShopProcess("shop-7f9c4/1", database).use { shop ->
        val current = shopMigrations(CustomerService(shop.db), StubIdentityProvider())
        shop.godwit.migrate(current.release1())
        shop.collection("orders").load((0 until 1237).asSequence().map { order(ObjectId(), paid = it < 1200) })
        shop.godwit.migrate(release(current))
    }
    return database
}
