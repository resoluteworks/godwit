package godwit.core.internal

import com.mongodb.kotlin.client.ClientSession
import com.mongodb.kotlin.client.MongoDatabase
import godwit.core.LockLostException
import godwit.core.OutsideTransactionScope
import godwit.core.TransactionScope
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
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
            val outside = OutsideTransactionScope("001-x", mockk<MongoDatabase>(), context)
            val transaction = TransactionScope("001-x", mockk<MongoDatabase>(), mockk<ClientSession>(), 2, context)

            outside.count("n", 1)
            outside.count("n", 2L)
            transaction.count("n", 3)

            context.counts shouldBe mapOf("n" to 6L)
            shouldThrow<LockLostException> { outside.checkLock() }.id shouldBe "001-x"
            shouldThrow<LockLostException> { transaction.checkLock() }.id shouldBe "001-x"
        }
    }
}
