package godwit.core

import com.mongodb.client.model.Filters
import com.mongodb.kotlin.client.ClientSession
import com.mongodb.kotlin.client.MongoDatabase
import godwit.core.internal.InBatchesStep
import godwit.core.internal.InTransactionStep
import godwit.core.internal.OutsideFirst
import godwit.core.internal.StepBodies
import godwit.core.internal.StepContext
import godwit.core.internal.TransactionalOnly
import godwit.core.internal.TransactionalStep
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.mockk
import org.bson.Document

private val database = mockk<MongoDatabase>()
private val outsideScope = OutsideTransactionScope("id", database, StepContext {})
private val transactionScope = TransactionScope("id", database, mockk<ClientSession>(), 1, StepContext {})

/** What running a migration's step bodies did: each step body appends what it saw. */
private class Trace {
    val events = mutableListOf<String>()
    var prepared: Any? = null
    var batch: List<Document>? = null
}

/**
 * Runs [bodies] the way the runner does: the outside step, then the transactional step with the outside step's value,
 * or with Unit when there is no outside step.
 */
private fun runBodies(bodies: StepBodies, batch: List<Document>) {
    when (bodies) {
        is OutsideFirst<*> -> runOutsideFirst(bodies, batch)
        is TransactionalOnly -> runTransactional(bodies.transactional, Unit, batch)
    }
}

private fun <T> runOutsideFirst(bodies: OutsideFirst<T>, batch: List<Document>) {
    val prepared = bodies.outside(outsideScope)
    bodies.transactional?.let { runTransactional(it, prepared, batch) }
}

private fun <T> runTransactional(step: TransactionalStep<T>, prepared: T, batch: List<Document>) {
    when (step) {
        is InTransactionStep -> step.body(transactionScope, prepared)
        is InBatchesStep -> step.body(transactionScope, batch)
    }
}

/** One way of declaring a migration, from a draft, with the steps it should have. */
private class Shape(val name: String, val steps: List<StepKind>, val declare: (MigrationDraft, Trace) -> Migration)

private val prepared = listOf("prepared")
private val outsideThenTransaction = listOf(StepKind.OUTSIDE_TRANSACTION, StepKind.IN_TRANSACTION)
private val outsideThenBatches = listOf(StepKind.OUTSIDE_TRANSACTION, StepKind.IN_BATCHES)
private val pending = Filters.exists("emailLower", false)

private val shapes = listOf(
    Shape("outsideTransaction", listOf(StepKind.OUTSIDE_TRANSACTION)) { draft, trace ->
        draft.outsideTransaction { trace.events += "outside" }
    },
    Shape("inTransaction", listOf(StepKind.IN_TRANSACTION)) { draft, trace ->
        draft.inTransaction { trace.events += "inTransaction" }
    },
    Shape("inBatches", listOf(StepKind.IN_BATCHES)) { draft, trace ->
        draft.inBatches("customers", pending, batchSize = 250) { batch ->
            trace.events += "inBatches"
            trace.batch = batch
        }
    },
    Shape("outsideTransaction then inTransaction", outsideThenTransaction) { draft, trace ->
        draft.outsideTransaction {
            trace.events += "outside"
            prepared
        }.inTransaction { value ->
            trace.events += "inTransaction"
            trace.prepared = value
        }
    },
    Shape("outsideTransaction then inBatches", outsideThenBatches) { draft, trace ->
        draft.outsideTransaction {
            trace.events += "outside"
        }.inBatches("customers", pending, batchSize = 250) { batch ->
            trace.events += "inBatches"
            trace.batch = batch
        }
    }
)

private class Factory(
    val name: String,
    val kind: MigrationKind,
    val supersedes: List<String>,
    val draft: () -> MigrationDraft
)

private val factories = listOf(
    Factory("migration", MigrationKind.Once, listOf("001-old", "002-old")) {
        migration("100-baseline", description = "End state", supersedes = listOf("001-old", "002-old"))
    },
    Factory("everyStart", MigrationKind.EveryStart, emptyList()) {
        everyStart("100-baseline", description = "End state")
    },
    Factory("repeatable", MigrationKind.Repeatable("2026-10-01"), emptyList()) {
        repeatable("100-baseline", revision = "2026-10-01", description = "End state")
    }
)

class DeclarationTest : StringSpec() {
    init {
        for (factory in factories) {
            for (shape in shapes) {
                "${factory.name} with ${shape.name} is a complete migration with its id, kind and steps" {
                    val trace = Trace()
                    val migration = shape.declare(factory.draft(), trace)

                    migration.id shouldBe "100-baseline"
                    migration.description shouldBe "End state"
                    migration.kind shouldBe factory.kind
                    migration.supersedes shouldBe factory.supersedes
                    migration.steps shouldBe shape.steps
                    migration.bodies.kinds shouldBe shape.steps
                    migration.toString() shouldBe "100-baseline"
                }

                "${factory.name} with ${shape.name} runs its step bodies in order, handing the outside value on" {
                    val trace = Trace()
                    val batch = listOf(Document("_id", 1))
                    runBodies(shape.declare(factory.draft(), trace).bodies, batch)

                    trace.events shouldBe shape.steps.map {
                        when (it) {
                            StepKind.OUTSIDE_TRANSACTION -> "outside"
                            StepKind.IN_TRANSACTION -> "inTransaction"
                            StepKind.IN_BATCHES -> "inBatches"
                        }
                    }
                    if (StepKind.IN_TRANSACTION in shape.steps && StepKind.OUTSIDE_TRANSACTION in shape.steps) {
                        trace.prepared shouldBeSameInstanceAs prepared
                    }
                    if (StepKind.IN_BATCHES in shape.steps) trace.batch shouldBeSameInstanceAs batch
                }
            }
        }

        "a draft is not a migration until a step function gives it a step" {
            val draft: Any = migration("001-initial-setup")
            withClue("a MigrationDraft must not be a Migration") { (draft is Migration) shouldBe false }
        }

        "outsideTransaction alone returns a complete migration whose only step is the outside one" {
            val migration: Migration = migration("003-file-store").outsideTransaction { }
            migration.shouldBeInstanceOf<OutsideTransactionMigration<Unit>>()
            migration.steps shouldBe listOf(StepKind.OUTSIDE_TRANSACTION)
            migration.bodies.transactional shouldBe null
        }

        "the transactional step comes last: its bodies hold the outside step, if any, and that step" {
            val bodies = migration("004-order-status").outsideTransaction { 1 }.inTransaction { }.bodies
            bodies.shouldBeInstanceOf<OutsideFirst<Int>>()
            bodies.transactional.shouldBeInstanceOf<InTransactionStep<Int>>()

            migration("004-order-status").inTransaction { }.bodies.shouldBeInstanceOf<TransactionalOnly>()
        }

        "inBatches keeps its collection, filter and batch size, 500 by default" {
            val defaulted = migration("006-order-totals").inBatches("orders", pending) { }.bodies.transactional
            defaulted.shouldBeInstanceOf<InBatchesStep>()
            defaulted.collection shouldBe "orders"
            defaulted.pending shouldBeSameInstanceAs pending
            defaulted.batchSize shouldBe 500

            val afterOutside = migration("006-order-totals").outsideTransaction { }
                .inBatches("orders", pending) { }.bodies.transactional
            afterOutside.shouldBeInstanceOf<InBatchesStep>()
            afterOutside.batchSize shouldBe 500
        }

        "a batch size outside 1 to 10000 is accepted here and reported by validateMigrations" {
            val migration = migration("007-customer-email-lower").inBatches("customers", pending, batchSize = 50_000) {
            }
            (migration.bodies.transactional as InBatchesStep).batchSize shouldBe 50_000
        }

        "supersedes is empty by default and a copy of the list passed" {
            migration("001-initial-setup").outsideTransaction { }.supersedes shouldBe emptyList()

            val ids = mutableListOf("001-initial-setup")
            val baseline = migration("100-baseline", supersedes = ids).outsideTransaction { }
            ids += "002-carts"
            baseline.supersedes shouldBe listOf("001-initial-setup")
        }

        "description is null by default for every factory" {
            migration("001-a").outsideTransaction { }.description shouldBe null
            everyStart("every").outsideTransaction { }.description shouldBe null
            repeatable("rep", revision = "1").outsideTransaction { }.description shouldBe null
        }

        "the kinds are values: a repeatable carries its revision" {
            MigrationKind.Repeatable("1") shouldBe MigrationKind.Repeatable("1")
            (MigrationKind.Repeatable("1") == MigrationKind.Repeatable("2")) shouldBe false
            MigrationKind.Repeatable("2026-10-01").revision shouldBe "2026-10-01"
        }
    }
}
