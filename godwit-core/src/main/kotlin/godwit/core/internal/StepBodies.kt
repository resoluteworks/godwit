package godwit.core.internal

import godwit.core.OutsideTransactionScope
import godwit.core.StepKind
import godwit.core.TransactionScope
import org.bson.Document
import org.bson.conversions.Bson

/**
 * The step bodies of one migration, as the runner calls them: an optional outside step, then an optional transactional
 * step, at least one of the two. The outside step's value reaches the transactional step with its type intact, so the
 * runner passes it on without a cast.
 */
internal sealed class StepBodies {
    /** The step that runs in a transaction, last; null for a migration with only an outside step. */
    abstract val transactional: TransactionalStep<*>?

    /** The kinds of the steps, in run order. */
    abstract val kinds: List<StepKind>
}

/** An outside step, followed by [transactional] when there is one. */
internal class OutsideFirst<T>(
    val outside: OutsideTransactionScope.() -> T,
    override val transactional: TransactionalStep<T>?
) : StepBodies() {
    override val kinds: List<StepKind> get() = listOfNotNull(StepKind.OUTSIDE_TRANSACTION, transactional?.kind)
}

/** A transactional step alone. With no outside step there is no prepared value, so the step receives [Unit]. */
internal class TransactionalOnly(override val transactional: TransactionalStep<Unit>) : StepBodies() {
    override val kinds: List<StepKind> get() = listOf(transactional.kind)
}

/** A step that runs in a transaction, receiving the value of type [T] that the outside step returned. */
internal sealed class TransactionalStep<in T> {
    abstract val kind: StepKind
}

/** An `inTransaction` step: one transaction, which also commits the APPLIED record. */
internal class InTransactionStep<in T>(val body: TransactionScope.(prepared: T) -> Unit) : TransactionalStep<T>() {
    override val kind: StepKind get() = StepKind.IN_TRANSACTION
}

/** An `inBatches` step: one transaction per page of [collection]. It ignores the outside step's value. */
internal class InBatchesStep(
    val collection: String,
    val pending: Bson,
    val batchSize: Int,
    val body: TransactionScope.(batch: List<Document>) -> Unit
) : TransactionalStep<Any?>() {
    override val kind: StepKind get() = StepKind.IN_BATCHES
}
