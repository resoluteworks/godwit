package godwit.core.internal

import com.mongodb.ReadPreference
import com.mongodb.kotlin.client.MongoDatabase
import godwit.core.TransactionsUnsupportedException
import org.bson.Document

/**
 * Whether the server that answered [hello] runs transactions: a replica set member (a single-node replica set counts)
 * answers with `setName`, a `mongos` with `msg: "isdbgrid"`, and a standalone `mongod` with neither.
 */
internal fun transactionsSupported(hello: Document): Boolean =
    hello.containsKey("setName") || hello.getString("msg") == "isdbgrid"

/**
 * The topology check of one call: `hello` on the primary, sent at most once per call and only when an answer is
 * needed. The plan made before the lock asks first when it has a transactional step due; under the lock, adoption asks
 * before it records ids (one transaction, or one write per id), and the plan made there asks when it has a
 * transactional step due. A standalone server fails a plan with a transactional step due with
 * [TransactionsUnsupportedException] before any migration runs, instead of the driver's misleading error on the first
 * transactional operation.
 */
internal class Topology(private val database: MongoDatabase) {
    /** The server's answer, once `hello` has been sent. */
    private var supported: Boolean? = null

    /** Whether the server runs transactions: `hello` the first time, the same answer after that. */
    fun supportsTransactions(): Boolean = supported ?: transactionsSupported(hello()).also { supported = it }

    /** Throws [TransactionsUnsupportedException] naming the due transactional migrations on a standalone server. */
    fun requireTransactions(plan: Plan) {
        if (plan.needsTransactions && !supportsTransactions()) {
            throw TransactionsUnsupportedException(plan.transactional.map { it.id })
        }
    }

    private fun hello(): Document = database.runCommand(Document("hello", 1), ReadPreference.primary())
}
