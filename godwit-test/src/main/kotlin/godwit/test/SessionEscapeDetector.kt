package godwit.test

import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import org.bson.BsonArray
import org.bson.BsonBoolean
import org.bson.BsonDocument
import org.bson.BsonInt64
import org.bson.BsonString

/**
 * Fails a test whose transactional step runs a database command without the step's session.
 *
 * The sync driver calls command listeners on the thread that runs the command. godwit opens each transaction of a
 * transactional step with its own command before the step body runs: a read whose `comment` is
 * `{godwit: <migration id>}`, which the driver sends with `startTransaction: true`. The detector records that command's
 * session id (`lsid`) and transaction number (`txnNumber`). Until a `commitTransaction` or `abortTransaction` for that
 * session ends the transaction, every command on that thread must carry the same `lsid`, the same `txnNumber` and
 * `autocommit: false`, which the driver adds to commands sent with the transaction's session. The whole body is
 * covered.
 *
 * A command without them is an escape: one sent without a session, and one sent with another session, such as a
 * service that starts a session and a transaction of its own. The detector throws [SessionEscapeError] from the
 * listener before the command is sent. The driver passes errors from listeners through, so the escaping call throws,
 * the step fails, and the test fails with the command and collection named. A commit or abort of another session does
 * not end the tracked transaction. An abort of another session is let through: it only rolls back that session's own
 * transaction, and failing it would replace the escape that made the session abort. Commands from other threads are
 * not checked, and neither are transactions that godwit does not open, such as a test's own.
 *
 * godwit's own commands, which carry a `{godwit: ...}` comment too (every history and lock command), are never
 * escapes: godwit sends them outside a step's transaction only once that transaction is over. One outside the tracked
 * transaction ends it, so a transaction whose commit or abort the detector never saw (the thread was interrupted, or
 * the abort found no server, before the listener was called) ends at godwit's next command on that thread; a read that
 * opens the next step's transaction tracks that one instead. [testGodwit] also ends it on the calling thread, as a test
 * starts there. A commit or abort of the step's own session ends it too, so the detector checks nothing after a service
 * commits or aborts the session it was given; godwit fails that step itself.
 *
 * [testGodwit] installs one. An app's own test client installs it with
 * `MongoClientSettings.builder().addCommandListener(SessionEscapeDetector())`.
 */
class SessionEscapeDetector : CommandListener {
    /** The step transaction open on each thread. */
    private val open = ThreadLocal<StepTransaction>()

    override fun commandStarted(event: CommandStartedEvent) {
        val command = event.command
        val name = event.commandName
        val tracked = open.get()
        when {
            tracked != null && sessionOf(command) == tracked.session && (name == COMMIT || name == ABORT) ->
                open.remove()

            tracked != null && inTransaction(command, tracked) -> Unit

            fromGodwit(command) -> if (startsTransaction(command)) open.set(transactionOf(command)) else open.remove()

            tracked != null && name != ABORT -> throw escape(event)
        }
    }

    /**
     * Ends the step transaction tracked on the calling thread, if any. [testGodwit] calls it as a test starts, when no
     * step can be running on the thread.
     */
    internal fun endTrackedTransaction() = open.remove()
}

private const val COMMIT = "commitTransaction"

private const val ABORT = "abortTransaction"

/**
 * A transaction by its session id (`lsid`, as JSON) and its `txnNumber`, copied out of the command: the driver reuses
 * the command's buffer once the listener returns. A command that starts a transaction always carries both.
 */
private data class StepTransaction(val session: String, val txnNumber: Long)

private fun sessionOf(command: BsonDocument): String? = command.getDocument("lsid", null)?.toJson()

private fun transactionOf(command: BsonDocument) =
    StepTransaction(command.getDocument("lsid").toJson(), command.getInt64("txnNumber").value)

/** The driver sends `startTransaction: true` on a transaction's first command, and the field on no other. */
private fun startsTransaction(command: BsonDocument): Boolean = command["startTransaction"] == BsonBoolean.TRUE

/** godwit's own command: its `comment` is `{godwit: ...}`. One that starts a transaction opens a step's. */
private fun fromGodwit(command: BsonDocument): Boolean =
    (command["comment"] as? BsonDocument)?.containsKey("godwit") == true

/** Whether [command] belongs to [tracked]: its `lsid` and `txnNumber`, with `autocommit: false`. */
private fun inTransaction(command: BsonDocument, tracked: StepTransaction): Boolean =
    sessionOf(command) == tracked.session && command["autocommit"] == BsonBoolean.FALSE &&
        command["txnNumber"] == BsonInt64(tracked.txnNumber)

private fun escape(event: CommandStartedEvent) = SessionEscapeError(event.commandName, collectionOf(event))

/**
 * The collection [event]'s command targets: the value of the command's own field (`find: "orders"`), the `collection`
 * field of a `getMore`, or the namespaces of a client `bulkWrite` (`nsInfo`), without their database; null for a
 * command on the database, such as `aggregate: 1`.
 */
private fun collectionOf(event: CommandStartedEvent): String? {
    val command = event.command
    val target = command[event.commandName]
    if (target is BsonString) return target.value
    val collection = command["collection"]
    if (collection is BsonString) return collection.value
    val namespaces = command["nsInfo"] as? BsonArray ?: return null
    return namespaces.joinToString(", ") { it.asDocument().getString("ns").value.substringAfter('.') }
}

/** A command ran without the transaction's session while a transactional step was running on the same thread. */
class SessionEscapeError internal constructor(
    /** The command name, such as `update` or `find`. */
    val command: String,
    /** The collection the command targets, when it has one. */
    val collection: String?
) : AssertionError(
    "$command on ${collection ?: "the database"} ran without the step's session, outside the transaction. " +
        "Pass `session` to the driver call or the service method."
)
