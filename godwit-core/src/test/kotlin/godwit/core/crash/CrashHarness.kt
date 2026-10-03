package godwit.core.crash

import com.mongodb.kotlin.client.MongoClient
import org.bson.BsonDocument
import org.bson.Document
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A child JVM killed at its crash point: the session of the transaction it left open, as `{id: UUID}`, if any, and the
 * lines it printed until it reported the point, its log lines among them.
 */
class CrashedChild(val openTransactionSession: BsonDocument?, val exitCode: Int, val output: List<String>)

/**
 * Runs a scenario in a child JVM on the parent's classpath, waits for it to report that it reached its crash point,
 * and kills it with `destroyForcibly()`: no `finally`, no release, no abort.
 */
object CrashHarness {
    private val log = LoggerFactory.getLogger(CrashHarness::class.java)

    /** How long the child may take to start, connect and reach its point. */
    private const val REACH_TIMEOUT_SECONDS = 60L

    /** Runs [crashScenario] ([main] in CrashMain.kt) and kills the child at [point]. */
    fun crashAt(point: CrashPoint, connectionString: String, databaseName: String): CrashedChild =
        crash("godwit.core.crash.CrashMainKt", point.name, connectionString, databaseName)

    /**
     * Runs [batchesCrashScenario] (`main` in BatchesCrashMain.kt) and kills the child once page [page] has committed
     * and logged "Committed batch", as the next page's transaction starts.
     */
    fun crashAfterPage(page: Int, connectionString: String, databaseName: String): CrashedChild =
        crash("godwit.core.crash.BatchesCrashMainKt", "$AFTER_PAGE$page", connectionString, databaseName)

    private fun crash(mainClass: String, point: String, connectionString: String, databaseName: String): CrashedChild {
        val java = ProcessHandle.current().info().command().orElseThrow()
        val process = ProcessBuilder(
            java,
            "-cp",
            System.getProperty("java.class.path"),
            mainClass,
            connectionString,
            databaseName,
            point
        ).redirectErrorStream(true).start()
        val reached = CompletableFuture<String>()
        val output = CopyOnWriteArrayList<String>()
        thread(name = "crash-child-output", isDaemon = true) {
            process.inputStream.bufferedReader().forEachLine { line ->
                log.info("child: {}", line)
                output += line
                if (line.startsWith(CRASH_POINT_LINE)) reached.complete(line)
            }
            reached.completeExceptionally(IllegalStateException("the child ended without reaching $point"))
        }
        try {
            val line = reached.get(REACH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            check(line.startsWith("$CRASH_POINT_LINE $point ")) { "the child reported $line" }
            val session = line.substringAfter("lsid=").takeUnless { it == "null" }?.let { BsonDocument.parse(it) }
            process.destroyForcibly()
            check(process.waitFor(30, TimeUnit.SECONDS)) { "the child did not die" }
            return CrashedChild(session, process.exitValue(), output.takeWhile { !it.startsWith(CRASH_POINT_LINE) })
        } finally {
            process.destroyForcibly()
        }
    }

    /**
     * Aborts the transaction a killed child left open, which the server would abort when its lifetime ends (60 s by
     * default): writes from the next run to the documents it wrote would conflict with it until then.
     */
    fun abortOpenTransaction(client: MongoClient, child: CrashedChild) {
        val session = child.openTransactionSession ?: return
        client.getDatabase("admin").runCommand(Document("killSessions", listOf(session)))
    }
}
