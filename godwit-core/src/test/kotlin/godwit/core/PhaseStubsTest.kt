package godwit.core

import com.mongodb.kotlin.client.ClientSession
import com.mongodb.kotlin.client.MongoCluster
import com.mongodb.kotlin.client.MongoDatabase
import godwit.core.fixtures.GodwitFixture
import godwit.core.internal.StepContext
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.bson.Document

/**
 * The work later phases implement: until then each call that needs it throws [NotImplementedError] naming its phase,
 * so a call that reaches one fails loudly and says where the work belongs. A `migrate` whose plan needs such work
 * refuses it under the lock, before anything runs or is recorded, and releases the lock.
 */
class PhaseStubsTest : StringSpec() {
    init {
        "markApplied throws NotImplementedError naming P6" {
            val godwit = Godwit(mockk<MongoCluster>(), "shop", GodwitConfig(holder = "shop-7f9c4/1"))
            shouldThrow<NotImplementedError> {
                godwit.markApplied("004-order-status", "applied by hand")
            }.message shouldBe
                "P6"
        }

        "a plan that needs a later phase is refused under the lock, before anything runs or is recorded" {
            val hookCalls = mutableListOf<String>()
            val ranFirst = migration("001-first").outsideTransaction { error("must not run") }
            val cases = listOf(
                Triple(
                    "P5",
                    GodwitConfig(),
                    listOf(ranFirst, repeatable("reference-countries", "2026-10-01").outsideTransaction { })
                ),
                Triple(
                    "P5",
                    GodwitConfig(),
                    listOf(
                        ranFirst,
                        everyStart("bootstrap-customers").outsideTransaction {
                        }
                    )
                ),
                Triple(
                    "P6",
                    GodwitConfig(
                        adoptApplied = { database ->
                            hookCalls += database.name
                            emptySet()
                        }
                    ),
                    listOf(ranFirst)
                )
            )
            cases.forEachIndexed { index, (phase, config, migrations) ->
                withClue("case $index") {
                    GodwitFixture(config = config).use { f ->
                        shouldThrow<NotImplementedError> { f.godwit.migrate(migrations) }.message shouldBe phase
                        f.history.countDocuments() shouldBe 0L
                        f.collection("godwit-lock").find().first().containsKey("releasedAt") shouldBe true
                    }
                }
            }
            hookCalls shouldBe emptyList()
        }

        "a squash to record is refused under the lock with NotImplementedError naming P6" {
            GodwitFixture().use { f ->
                f.history.insertOne(
                    Document("_id", "001-old").append("kind", "ONCE").append("state", "APPLIED").append("origin", "RAN")
                )
                val baseline = migration("100-baseline", supersedes = listOf("001-old")).outsideTransaction { }

                shouldThrow<NotImplementedError> { f.godwit.migrate(baseline) }.message shouldBe "P6"

                f.stored("100-baseline") shouldBe null
                f.collection("godwit-lock").find().first().containsKey("releasedAt") shouldBe true
            }
        }

        "the scopes and Godwit keep what they were given" {
            val database = mockk<MongoDatabase>()
            val outside = OutsideTransactionScope("001-initial-setup", database, StepContext {})
            val transaction = TransactionScope("004-order-status", database, mockk<ClientSession>(), 1, StepContext {})
            val godwit = Godwit(mockk<MongoCluster>(), "shop", GodwitConfig(holder = "shop-7f9c4/1"))
            outside.id shouldBe "001-initial-setup"
            outside.database shouldBe database
            transaction.id shouldBe "004-order-status"
            transaction.attempt shouldBe 1
            godwit.databaseName shouldBe "shop"
            godwit.bookkeepingCollections shouldBe setOf("godwit-history", "godwit-lock")
            Godwit(mockk(), "shop", GodwitConfig(historyCollection = "h", lockCollection = "l", holder = "x/1"))
                .bookkeepingCollections shouldBe setOf("h", "l")
        }
    }
}
