package godwit.core

import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.Updates.set
import com.mongodb.kotlin.client.ClientSession
import godwit.core.fixtures.GodwitFixture
import godwit.core.fixtures.seeded
import godwit.core.internal.STEP_TRANSACTION_ENDED
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.bson.Document

/** A call in a step that ends godwit's transaction on the session the step was given, and whether it commits. */
private enum class Ending(val commits: Boolean, val run: (ClientSession) -> Unit) {
    ABORT(false, { it.abortTransaction() }),
    COMMIT(true, { it.commitTransaction() }),
    COMMIT_AND_START_ANOTHER(true, {
        it.commitTransaction()
        it.startTransaction()
    })
}

private const val ID = "004-order-status"

/**
 * A migration whose transactional step of [kind] touches order 1 on the session, then runs [ending] on that session.
 * The `inBatches` step pages `orders` two at a time, so the ending happens on a page that is not the last: the one that
 * would write a checkpoint.
 */
private fun ending(kind: StepKind, ending: Ending): Migration {
    val body: TransactionScope.() -> Unit = {
        collection("orders").updateOne(session, eq("_id", 1), set("touched", true))
        ending.run(session)
    }
    return when (kind) {
        StepKind.IN_TRANSACTION -> migration(ID).inTransaction { body() }
        else -> migration(ID).inBatches("orders", exists("_id"), batchSize = 2) { body() }
    }
}

/**
 * A step must leave the transaction godwit opened on its session as it found it: godwit's last write in it (the
 * APPLIED record, or a page's checkpoint) commits with the step's writes only while that transaction is still open.
 */
class StepSessionTest : StringSpec() {
    init {
        "a step that commits, aborts or replaces godwit's transaction fails, and nothing is recorded applied" {
            for (kind in listOf(StepKind.IN_TRANSACTION, StepKind.IN_BATCHES)) {
                for (end in Ending.entries) {
                    withClue("$end in $kind") {
                        GodwitFixture(appName = "step-session", config = seeded).use { f ->
                            f.collection("orders").insertMany(List(3) { Document("_id", it + 1) })

                            val failure = shouldThrow<MigrationFailedException> { f.godwit.migrate(ending(kind, end)) }

                            failure.step shouldBe kind
                            failure.cause.shouldBeInstanceOf<IllegalStateException>().message shouldBe
                                STEP_TRANSACTION_ENDED
                            val stored = f.stored(ID).shouldNotBeNull()
                            stored.getString("state") shouldBe "FAILED"
                            stored.get("lastError", Document::class.java).getString("step") shouldBe kind.name
                            stored.containsKey("checkpoint") shouldBe false
                            val touched = f.collection("orders").find(eq("_id", 1)).first().getBoolean("touched")
                            touched shouldBe (if (end.commits) true else null)
                        }
                    }
                }
            }
        }
    }
}
