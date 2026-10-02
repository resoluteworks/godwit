package godwit.core

import com.mongodb.kotlin.client.ClientSession
import com.mongodb.kotlin.client.MongoCluster
import com.mongodb.kotlin.client.MongoCollection
import com.mongodb.kotlin.client.MongoDatabase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.bson.Document

/**
 * The I/O that later phases implement: until then each body throws [NotImplementedError] naming its phase, so a call
 * that reaches one fails loudly and says where the work belongs.
 */
class PhaseStubsTest : StringSpec() {
    init {
        val database = mockk<MongoDatabase>()
        val collection = mockk<MongoCollection<Document>>()
        val godwit = Godwit(mockk<MongoCluster>(), "shop", GodwitConfig(holder = "shop-7f9c4/1"))
        val outside = OutsideTransactionScope("001-initial-setup", database)
        val transaction = TransactionScope("004-order-status", database, mockk<ClientSession>(), 1)
        val definition = Document("mappings", Document("dynamic", true))

        val stubs = listOf<Pair<String, () -> Any?>>(
            "P3" to { godwit.history() },
            "P6" to { godwit.markApplied("004-order-status", "applied by hand") },
            "P3" to { outside.count("ordersPaid", 1L) },
            "P3" to { outside.count("ordersPaid", 1) },
            "P3" to { outside.checkLock() },
            "P3" to { outside.ensureCollection("carts") },
            "P3" to { outside.ensureSearchIndex("products", "product-search", definition) },
            "P3" to { outside.dropIndexIfExists("orders", "status_1") },
            "P3" to { transaction.count("ordersPaid", 1L) },
            "P3" to { transaction.checkLock() },
            "P3" to { database.ensureCollection("carts") },
            "P3" to { collection.ensureSearchIndex("product-search", definition) },
            "P3" to { collection.dropIndexIfExists("status_1") }
        )

        "every I/O body throws NotImplementedError naming the phase that implements it" {
            stubs.forEachIndexed { index, (phase, call) ->
                withClue("stub $index") { shouldThrow<NotImplementedError> { call() }.message shouldBe phase }
            }
        }

        "the scopes and Godwit keep what they were given" {
            outside.id shouldBe "001-initial-setup"
            transaction.id shouldBe "004-order-status"
            transaction.attempt shouldBe 1
            godwit.databaseName shouldBe "shop"
            godwit.bookkeepingCollections shouldBe setOf("godwit-history", "godwit-lock")
            Godwit(mockk(), "shop", GodwitConfig(historyCollection = "h", lockCollection = "l", holder = "x/1"))
                .bookkeepingCollections shouldBe setOf("h", "l")
        }
    }
}
