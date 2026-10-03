package godwit.core.internal

import com.mongodb.MongoCommandException
import com.mongodb.MongoException
import com.mongodb.MongoServerException
import kotlin.time.Duration

/** The guidance line for a transaction that ran past the server's transaction lifetime. */
internal const val LIFETIME_GUIDANCE = "The transaction ran past the server's transaction lifetime " +
    "(transactionLifetimeLimitSeconds, 60 s by default). Process the documents with inBatches, or move work that " +
    "needs no atomicity to outsideTransaction."

/** The guidance line for a transaction too large for the storage engine's cache. */
internal const val TOO_LARGE_GUIDANCE = "The transaction was too large for the storage engine's cache. Process the " +
    "documents with inBatches, or move work that needs no atomicity to outsideTransaction."

/** The guidance line for DDL inside a transaction. */
internal const val DDL_GUIDANCE = "DDL cannot run in a transaction: index builds on existing collections, drop, " +
    "dropIndexes, renameCollection and collMod belong in outsideTransaction."

/** The guidance line for godwit's session passed to an operation on another client. */
internal const val OTHER_CLIENT_GUIDANCE = "The step passed godwit's session to an operation on another MongoClient. " +
    "Build Godwit and the services the migrations call from the same MongoClient."

/** NoSuchTransaction (251) and TransactionExceededLifetimeLimitSeconds (290): the server aborted the transaction. */
private val LIFETIME_CODES = setOf(251, 290)

private const val TRANSACTION_TOO_LARGE_FOR_CACHE = 388

private const val OPERATION_NOT_SUPPORTED_IN_TRANSACTION = 263

private const val INVALID_OPTIONS = 72

/**
 * How the server refuses `createIndexes` and `create` in a transaction whose read concern is snapshot, as godwit's
 * are: InvalidOptions (72), "Command createIndexes does not support this transaction's { readConcern: ... }".
 */
private const val READ_CONCERN_REFUSAL = "does not support this transaction's"

/** What the driver says when a session is passed to an operation on a client other than the one that started it. */
private const val OTHER_CLIENT_MESSAGE = "ClientSession from same MongoClient"

/** How deep into a chain of causes godwit looks for one it recognises: an app may wrap the driver's exception. */
private const val CAUSE_DEPTH = 8

/**
 * The guidance line that [godwit.core.MigrationFailedException] adds for a failure whose [error], or one of its
 * causes, godwit recognises; null for any other failure. The lifetime line needs a transaction attempt that ran at
 * least [lifetimeThreshold] ([longestAttempt] is the longest attempt of the failed step's transaction, zero for a step
 * without one): a NoSuchTransaction after a short attempt has another reason.
 */
internal fun guidance(error: Throwable, longestAttempt: Duration, lifetimeThreshold: Duration): String? {
    val ranPastLifetime = longestAttempt >= lifetimeThreshold
    return generateSequence(error) { it.cause }
        .take(CAUSE_DEPTH)
        .firstNotNullOfOrNull { recognised(it, ranPastLifetime) }
}

private fun recognised(error: Throwable, ranPastLifetime: Boolean): String? = when (error) {
    is MongoException -> when {
        error.code in LIFETIME_CODES -> LIFETIME_GUIDANCE.takeIf { ranPastLifetime }
        error.code == TRANSACTION_TOO_LARGE_FOR_CACHE -> TOO_LARGE_GUIDANCE
        error.code == OPERATION_NOT_SUPPORTED_IN_TRANSACTION || error.isReadConcernRefusal() -> DDL_GUIDANCE
        else -> null
    }

    is IllegalStateException -> OTHER_CLIENT_GUIDANCE.takeIf { error.message.orEmpty().contains(OTHER_CLIENT_MESSAGE) }

    else -> null
}

private fun MongoException.isReadConcernRefusal(): Boolean =
    this is MongoCommandException && code == INVALID_OPTIONS && errorMessage.contains(READ_CONCERN_REFUSAL)

/**
 * How "Retrying transaction" names the error that made the driver run a transaction body again: the server's code
 * name and code, such as `WriteConflict (112)`, or for an error without a code name (a network error), the exception's
 * class and code, such as `MongoSocketReadException (-2)`.
 */
internal fun errorName(error: MongoException): String {
    val codeName = (error as? MongoServerException)?.errorCodeName.orEmpty().ifEmpty { error.javaClass.simpleName }
    return "$codeName (${error.code})"
}
