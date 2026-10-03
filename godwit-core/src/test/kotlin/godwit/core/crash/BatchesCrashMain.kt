package godwit.core.crash

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.model.Filters.`in`
import com.mongodb.client.model.Updates.inc
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import com.mongodb.kotlin.client.MongoClient
import godwit.core.Godwit
import godwit.core.Migration
import godwit.core.UntrackedDatabase
import godwit.core.migration
import org.bson.Document
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/** The crash point of [CrashHarness.crashAfterPage]: `AFTER_PAGE_<page>`. */
const val AFTER_PAGE = "AFTER_PAGE_"

/** The orders [batchesCrashScenario] pages: 20 pages of 500 and a last one of 50. */
const val CRASH_ORDERS = 10_050

/**
 * The configuration of both JVMs: [crashConfig], on a database whose orders exist before the first migration runs,
 * which the untracked-database guard would refuse.
 */
fun batchesCrashConfig(holder: String) = crashConfig(holder).copy(untrackedDatabase = UntrackedDatabase.RUN_ALL)

/**
 * What both JVMs migrate: `006-order-totals` pages every order, 500 at a time, incrementing its `probe`. `pending`
 * selects every order whatever the step did to it, so a page that committed twice would leave its probes at 2.
 */
fun batchesCrashScenario(): List<Migration> = listOf(
    migration("006-order-totals").inBatches("orders", Document(), batchSize = 500) { orders ->
        collection("orders").updateMany(session, `in`("_id", orders.map { it["_id"] }), inc("probe", 1))
        count("ordersUpdated", orders.size)
    }
)

/**
 * Stops the thread that starts transaction number [transaction], whose first command is that page's read: every page
 * before it has committed and logged "Committed batch", and no transaction is open. Prints [CRASH_POINT_LINE] for
 * [point], then blocks until the parent kills the process.
 */
class PageCrashListener(private val point: String, private val transaction: Int) : CommandListener {
    private val started = AtomicInteger()

    override fun commandStarted(event: CommandStartedEvent) {
        val opensTransaction = event.command.getBoolean("startTransaction", null)?.value == true
        if (opensTransaction && started.incrementAndGet() == transaction) {
            println("$CRASH_POINT_LINE $point lsid=null")
            System.out.flush()
            Thread.sleep(Long.MAX_VALUE)
        }
    }
}

/**
 * The child JVM of [CrashHarness.crashAfterPage]: `BatchesCrashMainKt <connection string> <database> AFTER_PAGE_<k>`.
 * It migrates [batchesCrashScenario] and stops dead as page k + 1 starts; it exits with 3 when the run ends first.
 */
fun main(args: Array<String>) {
    val (connectionString, databaseName, point) = args
    val page = point.removePrefix(AFTER_PAGE).toInt()
    val settings = MongoClientSettings.builder()
        .applyConnectionString(ConnectionString(connectionString))
        .applicationName("crash-child")
        .addCommandListener(PageCrashListener(point, page + 1))
        .build()
    MongoClient.create(settings).use { client ->
        Godwit(client, databaseName, batchesCrashConfig("crash-child/1")).migrate(batchesCrashScenario())
    }
    println("$CRASH_POINT_LINE not reached: the migrations finished")
    exitProcess(3)
}
