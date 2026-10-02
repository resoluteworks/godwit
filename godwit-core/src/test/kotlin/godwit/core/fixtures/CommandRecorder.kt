package godwit.core.fixtures

import com.mongodb.MongoException
import com.mongodb.event.CommandFailedEvent
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import com.mongodb.event.CommandSucceededEvent
import org.bson.BsonDocument
import org.bson.json.JsonMode
import org.bson.json.JsonWriterSettings
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A command a client sent, copied out of the driver's buffers, with the reply to it (null while it runs or when it
 * failed) and the error code the server answered, if any: a failed command's code, or the first write error's.
 */
class RecordedCommand(val name: String, val command: BsonDocument) {
    @Volatile
    var reply: BsonDocument? = null
        internal set

    @Volatile
    var errorCode: Int? = null
        internal set

    @Volatile
    var finished: Boolean = false
        internal set
}

/**
 * Records the commands of the client it is installed on. [onFailed] and [onSucceeded] run on the thread that sent the
 * command, after its reply, so a test can act at an exact point of an operation.
 */
class CommandRecorder(
    private val onSucceeded: (RecordedCommand) -> Unit = {},
    private val onFailed: (RecordedCommand) -> Unit = {}
) : CommandListener {
    private val byRequest = ConcurrentHashMap<Int, RecordedCommand>()
    private val all = CopyOnWriteArrayList<RecordedCommand>()

    /** Every command started so far, in order. */
    val commands: List<RecordedCommand> get() = all.toList()

    /** The commands named [name] (`find`, `update`, `findAndModify`, `commitTransaction`, ...). */
    fun commands(name: String): List<RecordedCommand> = all.filter { it.name == name }

    fun clear() = all.clear()

    override fun commandStarted(event: CommandStartedEvent) {
        val copy = BsonDocument.parse(event.command.toJson(EXTENDED))
        val recorded = RecordedCommand(event.commandName, copy)
        byRequest[event.requestId] = recorded
        all += recorded
    }

    override fun commandSucceeded(event: CommandSucceededEvent) {
        val recorded = byRequest.remove(event.requestId) ?: return
        // A write command answers ok: 1 and lists the errors of its writes, such as a duplicate key on an upsert.
        recorded.reply = BsonDocument.parse(event.response.toJson(EXTENDED))
        val writeErrors = event.response.getArray("writeErrors", null)
        recorded.errorCode = writeErrors?.firstOrNull()?.asDocument()?.getInt32("code")?.value
        recorded.finished = true
        onSucceeded(recorded)
    }

    override fun commandFailed(event: CommandFailedEvent) {
        val recorded = byRequest.remove(event.requestId) ?: return
        recorded.errorCode = (event.throwable as? MongoException)?.code
        recorded.finished = true
        onFailed(recorded)
    }

    private companion object {
        val EXTENDED: JsonWriterSettings = JsonWriterSettings.builder().outputMode(JsonMode.EXTENDED).build()
    }
}
