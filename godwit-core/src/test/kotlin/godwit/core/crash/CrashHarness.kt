package godwit.core.crash

import com.mongodb.kotlin.client.MongoClient
import org.bson.BsonDocument
import org.bson.Document
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** A child JVM killed at its crash point: the session of the transaction it left open, as `{id: UUID}`, if any. */
class CrashedChild(val openTransactionSession: BsonDocument?, val exitCode: Int)

/**
 * Runs [crashScenario] in a child JVM ([main] in CrashMain.kt) on the parent's classpath, waits for it to report that
 * it reached its [CrashPoint], and kills it with `destroyForcibly()`: no `finally`, no release, no abort.
 */
object CrashHarness {
    private val log = LoggerFactory.getLogger(CrashHarness::class.java)

    /** How long the child may take to start, connect and reach its point. */
    private const val REACH_TIMEOUT_SECONDS = 60L

    fun crashAt(point: CrashPoint, connectionString: String, databaseName: String): CrashedChild {
        val java = ProcessHandle.current().info().command().orElseThrow()
        val process = ProcessBuilder(
            java,
            "-cp",
            System.getProperty("java.class.path"),
            "godwit.core.crash.CrashMainKt",
            connectionString,
            databaseName,
            point.name
        ).redirectErrorStream(true).start()
        val reached = CompletableFuture<String>()
        thread(name = "crash-child-output", isDaemon = true) {
            process.inputStream.bufferedReader().forEachLine { line ->
                log.info("child: {}", line)
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
            return CrashedChild(session, process.exitValue())
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
