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
 * The topology check of one call: `hello` on the primary, sent only when a plan has a transactional step due and at
 * most once per call. The plan made before the lock decides first; the plan made under the lock checks again only when
 * it has a transactional step due that the first one did not. A standalone server then fails the call with
 * [TransactionsUnsupportedException] before any migration runs, instead of the driver's misleading error on the first
 * transactional operation.
 */
internal class Topology(private val database: MongoDatabase) {
    private var checked = false

    /** Throws [TransactionsUnsupportedException] naming the due transactional migrations on a standalone server. */
    fun requireTransactions(plan: Plan) {
        if (checked || !plan.needsTransactions) return
        checked = true
        val hello = database.runCommand(Document("hello", 1), ReadPreference.primary())
        if (!transactionsSupported(hello)) throw TransactionsUnsupportedException(plan.transactional.map { it.id })
    }
}
