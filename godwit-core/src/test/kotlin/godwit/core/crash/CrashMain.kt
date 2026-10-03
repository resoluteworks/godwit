package godwit.core.crash

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates.inc
import com.mongodb.event.CommandFailedEvent
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import com.mongodb.event.CommandSucceededEvent
import com.mongodb.kotlin.client.MongoClient
import godwit.core.Godwit
import godwit.core.GodwitConfig
import godwit.core.Migration
import godwit.core.TransactionScope
import godwit.core.internal.testTimings
import godwit.core.migration
import org.bson.BsonBoolean
import org.bson.BsonDocument
import org.bson.BsonNull
import org.bson.BsonString
import org.bson.Document
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.minutes

/** Where the child JVM stops dead, each point marked by a command the child's client sends or receives. */
enum class CrashPoint {
    /** The RUNNING marker of `001-crash` has been written. */
    AFTER_MARKER,

    /** The outside step has created its first collection, and not its second. */
    MID_OUTSIDE_STEP,

    /** The outside step has returned; the transaction's first command has not been sent. */
    AFTER_OUTSIDE_STEP,

    /** The transaction has written its probe and has not committed. */
    IN_TRANSACTION,

    /** `001-crash` has committed with its APPLIED record; `002-after` has not started and the lock is still held. */
    AFTER_COMMIT
}

/** The line the child prints when it reaches its point, before it stops: `godwit-crash-point <point> lsid=<json>`. */
const val CRASH_POINT_LINE = "godwit-crash-point"

/** The lock timings of both JVMs: a dead holder's lease ends within 3 s, so the next start does not wait long. */
fun crashConfig(holder: String) = GodwitConfig(lock = testTimings.copy(waitTimeout = 1.minutes), holder = holder)

private fun TransactionScope.probe() {
    collection("probes").updateOne(session, eq("_id", id), inc("n", 1), UpdateOptions().upsert(true))
}

/**
 * What both JVMs migrate: `001-crash`, whose outside step records each run in `outside-runs` and creates two
 * collections, and whose transaction increments its probe; then `002-after`, which increments its own. Every probe
 * ends at 1 when every transactional effect committed exactly once.
 */
fun crashScenario(): List<Migration> = listOf(
    migration("001-crash")
        .outsideTransaction {
            collection("outside-runs").insertOne(Document("at", Date()))
            ensureCollection("crash-a")
            ensureCollection("crash-b")
        }
        .inTransaction { probe() },
    migration("002-after").inTransaction { probe() }
)

/**
 * Stops the thread that sends or receives the command that marks [point]: prints [CRASH_POINT_LINE] with the session
 * id of the transaction the server holds open for the child, if any, then blocks until the parent kills the process.
 * The heartbeat keeps renewing the lease meanwhile, as it does for a live holder.
 */
class CrashListener(private val point: CrashPoint) : CommandListener {
    /** The commands in flight, by request id, copied out of the driver's buffers: name, collection, lsid, startTransaction. */
    private val started = ConcurrentHashMap<Int, BsonDocument>()

    /** The session of a transaction the server holds open: its first command succeeded, its commit has not. */
    private var openTransaction: BsonDocument? = null

    override fun commandStarted(event: CommandStartedEvent) {
        val command = event.command
        val opensTransaction = command.getBoolean("startTransaction", null)?.value == true
        started[event.requestId] = BsonDocument("name", BsonString(event.commandName))
            .append("target", command[event.commandName] ?: BsonNull.VALUE)
            .append("lsid", command["lsid"]?.asDocument()?.clone() ?: BsonNull.VALUE)
            .append("opensTransaction", BsonBoolean(opensTransaction))
        if (point == CrashPoint.AFTER_OUTSIDE_STEP && opensTransaction) stop()
    }

    override fun commandSucceeded(event: CommandSucceededEvent) {
        val command = started.remove(event.requestId) ?: return
        val name = command.getString("name").value
        val target = command["target"]?.takeIf { it.isString }?.asString()?.value
        val opensTransaction = command.getBoolean("opensTransaction").value
        if (opensTransaction) openTransaction = command.getDocument("lsid")
        if (name == "commitTransaction" || name == "abortTransaction") openTransaction = null
        val reached = when (point) {
            CrashPoint.AFTER_MARKER -> name == "findAndModify" && target == "godwit-history"
            CrashPoint.MID_OUTSIDE_STEP -> name == "create" && target == "crash-a"
            CrashPoint.AFTER_OUTSIDE_STEP -> false
            CrashPoint.IN_TRANSACTION -> name == "update" && opensTransaction
            CrashPoint.AFTER_COMMIT -> name == "commitTransaction"
        }
        if (reached) stop()
    }

    override fun commandFailed(event: CommandFailedEvent) {
        started.remove(event.requestId)
    }

    private fun stop() {
        println("$CRASH_POINT_LINE $point lsid=${openTransaction?.toJson()}")
        System.out.flush()
        Thread.sleep(Long.MAX_VALUE)
    }
}

/**
 * The child JVM of [CrashHarness]: `CrashMainKt <connection string> <database> <point>`. It migrates [crashScenario] and
 * stops dead at the point; it exits with 3 when the run ends without reaching it.
 */
fun main(args: Array<String>) {
    val (connectionString, databaseName, pointName) = args
    val settings = MongoClientSettings.builder()
        .applyConnectionString(ConnectionString(connectionString))
        .applicationName("crash-child")
        .addCommandListener(CrashListener(CrashPoint.valueOf(pointName)))
        .build()
    MongoClient.create(settings).use { client ->
        Godwit(client, databaseName, crashConfig("crash-child/1")).migrate(crashScenario())
    }
    println("$CRASH_POINT_LINE not reached: the migrations finished")
    exitProcess(3)
}
