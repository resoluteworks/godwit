package godwit.core.internal

import com.mongodb.WriteConcern
import com.mongodb.kotlin.client.ClientSession
import com.mongodb.kotlin.client.MongoCluster
import com.mongodb.kotlin.client.MongoDatabase
import godwit.core.Godwit
import godwit.core.GodwitConfig
import godwit.core.LockLostException
import godwit.core.OutsideTransactionScope
import godwit.core.TransactionScope
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

class ScopesTest : StringSpec() {
    init {
        "a step's counters add up per name, in the order the names were first counted" {
            val context = StepContext {}
            context.count("b", 2)
            context.count("a", 1)
            context.count("b", 3)

            context.counts shouldBe mapOf("b" to 5L, "a" to 1L)
            context.counts.keys.toList() shouldBe listOf("b", "a")
        }

        "the counters of two steps add up: the first step's names in their order, then the names only the second has" {
            addCounts(mapOf("a" to 1L, "c" to 2L), mapOf("b" to 4L, "a" to 2L)).toList() shouldBe
                listOf("a" to 3L, "c" to 2L, "b" to 4L)
        }

        "the scopes count through their context and check the lock through it" {
            val context = StepContext { throw LockLostException("001-x") }
            val outside = OutsideTransactionScope("001-x", mockk<MongoDatabase>(relaxed = true), context)
            val transaction = TransactionScope("001-x", mockk<MongoDatabase>(), mockk<ClientSession>(), 2, context)

            outside.count("n", 1)
            outside.count("n", 2L)
            transaction.count("n", 3)

            context.counts shouldBe mapOf("n" to 6L)
            shouldThrow<LockLostException> { outside.checkLock() }.id shouldBe "001-x"
            shouldThrow<LockLostException> { transaction.checkLock() }.id shouldBe "001-x"
        }

        "the scopes and Godwit keep what they were given; the outside scope's database writes with majority" {
            val database = mockk<MongoDatabase>()
            val majority = mockk<MongoDatabase>()
            every { database.withWriteConcern(WriteConcern.MAJORITY) } returns majority
            val outside = OutsideTransactionScope("001-initial-setup", database, StepContext {})
            val transaction = TransactionScope("004-order-status", database, mockk<ClientSession>(), 1, StepContext {})
            val godwit = Godwit(mockk<MongoCluster>(), "shop", GodwitConfig(holder = "shop-7f9c4/1"))
            outside.id shouldBe "001-initial-setup"
            outside.database shouldBe majority
            transaction.database shouldBe database
            transaction.id shouldBe "004-order-status"
            transaction.attempt shouldBe 1
            godwit.databaseName shouldBe "shop"
            godwit.bookkeepingCollections shouldBe setOf("godwit-history", "godwit-lock")
            Godwit(mockk(), "shop", GodwitConfig(historyCollection = "h", lockCollection = "l", holder = "x/1"))
                .bookkeepingCollections shouldBe setOf("h", "l")
        }
    }
}
