package godwit.test

import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent

/**
 * Fails a test whose transactional step runs a database command without the step's session.
 *
 * The sync driver calls command listeners on the thread that runs the command. godwit opens the step's transaction
 * with its own command before the step body runs; the detector records that command's session id (`lsid`) and
 * transaction number. Until a `commitTransaction` or `abortTransaction` for that session ends the transaction, every
 * command on that thread must carry the same `lsid` and `autocommit: false`, which the driver adds to commands sent
 * with the transaction's session. The whole body is covered.
 *
 * A command without them is an escape: one sent without a session, and one sent with another session, such as a
 * service that starts a session and a transaction of its own. The detector throws [SessionEscapeError] from the
 * listener before the command is sent. The driver passes errors from listeners through, so the escaping call throws,
 * the step fails, and the test fails with the command and collection named. A commit or abort of another session does
 * not end the tracked transaction. Commands from other threads are not checked.
 *
 * [testGodwit] installs one. An app's own test client installs it with
 * `MongoClientSettings.builder().addCommandListener(SessionEscapeDetector())`.
 */
class SessionEscapeDetector : CommandListener {
    override fun commandStarted(event: CommandStartedEvent): Unit = TODO()
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
