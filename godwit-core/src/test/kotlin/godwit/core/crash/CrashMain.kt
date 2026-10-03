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
import com.mongodb.kotlin.client.MongoDatabase
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

/** What the child JVM migrates: [crashScenario], or [adoptionScenario] with the adoption hook configured. */
enum class CrashScenario {
    MIGRATIONS,
    ADOPTION
}

/** Where the child JVM stops dead, each point marked by a command the child's client sends or receives. */
enum class CrashPoint(val scenario: CrashScenario) {
    /** The RUNNING marker of `001-crash` has been written. */
    AFTER_MARKER(CrashScenario.MIGRATIONS),

    /** The outside step has created its first collection, and not its second. */
    MID_OUTSIDE_STEP(CrashScenario.MIGRATIONS),

    /** The outside step has returned; the transaction's first command has not been sent. */
    AFTER_OUTSIDE_STEP(CrashScenario.MIGRATIONS),

    /** The transaction has written its probe and has not committed. */
    IN_TRANSACTION(CrashScenario.MIGRATIONS),

    /** `001-crash` has committed with its APPLIED record; `002-after` has not started and the lock is still held. */
    AFTER_COMMIT(CrashScenario.MIGRATIONS),

    /** On a replica set, adoption has written its first ADOPTED record in its transaction and has not committed. */
    ADOPTION_IN_TRANSACTION(CrashScenario.ADOPTION),

    /** On a replica set, adoption has committed its transaction; history has not been read again. */
    ADOPTION_COMMITTED(CrashScenario.ADOPTION),

    /** On a standalone server, adoption has written two of its ADOPTED records, one at a time, last-listed first. */
    ADOPTION_TWO_WRITES(CrashScenario.ADOPTION)
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

/** The ids the hook of [adoptionConfig] returns: the first three migrations of [adoptionScenario]. */
val adoptedBeforeGodwit = setOf("001-adopted", "002-adopted", "003-adopted")

/** [crashConfig] with an adoption hook, [adoptedBeforeGodwit] unless the parent counts its calls with its own. */
fun adoptionConfig(holder: String, hook: (MongoDatabase) -> Set<String> = { adoptedBeforeGodwit }) =
    crashConfig(holder).copy(adoptApplied = hook)

/**
 * What both JVMs migrate on a database that adoption takes over: three outside-only migrations the hook returns, which
 * fail if they ever run, then `004-left`, which records each run in `outside-runs`. Every migration is outside-only,
 * so the list runs on a standalone server too, and adoption is the only transaction a replica set sees.
 */
fun adoptionScenario(): List<Migration> = adoptedBeforeGodwit.sorted().map { id ->
    migration(id).outsideTransaction { error("$id is adopted, so it never runs") }
} + migration("004-left").outsideTransaction { collection("outside-runs").insertOne(Document("at", Date())) }

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

    /** The writes to `godwit-history` that succeeded so far. */
    private var historyWrites = 0

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
        if (name == "update" && target == "godwit-history") historyWrites++
        val reached = when (point) {
            CrashPoint.AFTER_MARKER -> name == "findAndModify" && target == "godwit-history"
            CrashPoint.MID_OUTSIDE_STEP -> name == "create" && target == "crash-a"
            CrashPoint.AFTER_OUTSIDE_STEP -> false
            CrashPoint.IN_TRANSACTION -> name == "update" && opensTransaction
            CrashPoint.AFTER_COMMIT -> name == "commitTransaction"
            CrashPoint.ADOPTION_IN_TRANSACTION -> name == "update" && target == "godwit-history" && opensTransaction
            CrashPoint.ADOPTION_COMMITTED -> name == "commitTransaction"
            CrashPoint.ADOPTION_TWO_WRITES -> name == "update" && target == "godwit-history" && historyWrites == 2
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
 * The child JVM of [CrashHarness]: `CrashMainKt <connection string> <database> <point>`. It migrates the point's
 * scenario, [crashScenario] or [adoptionScenario] with [adoptionConfig], and stops dead at the point; it exits with 3
 * when the run ends without reaching it.
 */
fun main(args: Array<String>) {
    val (connectionString, databaseName, pointName) = args
    val point = CrashPoint.valueOf(pointName)
    val settings = MongoClientSettings.builder()
        .applyConnectionString(ConnectionString(connectionString))
        .applicationName("crash-child")
        .addCommandListener(CrashListener(point))
        .build()
    val (config, migrations) = when (point.scenario) {
        CrashScenario.MIGRATIONS -> crashConfig("crash-child/1") to crashScenario()
        CrashScenario.ADOPTION -> adoptionConfig("crash-child/1") to adoptionScenario()
    }
    MongoClient.create(settings).use { client -> Godwit(client, databaseName, config).migrate(migrations) }
    println("$CRASH_POINT_LINE not reached: the migrations finished")
    exitProcess(3)
}
