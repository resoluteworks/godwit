package godwit.core.internal

import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.UpdateOptions
import com.mongodb.kotlin.client.MongoClient
import com.mongodb.kotlin.client.MongoCollection
import godwit.core.GodwitConfig
import godwit.core.LockConfig
import godwit.core.fixtures.CommandRecorder
import godwit.core.fixtures.TestDatabase
import godwit.core.fixtures.TestMongo
import org.bson.Document
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import java.util.Date
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * The short lock timings of the integration tests: a 3 s lease, judged lost 2 s after the last renewal was sent, with
 * a renewal every 500 ms, so a failed renewal leaves the next ones time to keep the lock.
 */
val testTimings = LockConfig(lease = 3.seconds, heartbeat = 500.milliseconds, safetyMargin = 1.seconds)

/**
 * The acquire's wait in virtual time: [clock] stands in for the monotonic clock, [sleep] for the pause between two
 * attempts (by default it only advances [clock]), and [random] for the pauses' jitter.
 */
class VirtualWait(
    val clock: TestTimeSource,
    val random: Random = Random.Default,
    val sleep: (Duration) -> Unit = { clock += it }
)

/**
 * One process taking part in a lock test: a client of its own named [name], so that a fail point can target its
 * commands alone, and a [MongoLock] on [db] whose holder is `<name>/1`. Without [wait], the lock waits with its own
 * defaults: the monotonic clock, a real sleep and [Random.Default].
 */
class LockProcess(
    val db: TestDatabase,
    val name: String,
    config: LockConfig = testTimings,
    recorder: CommandRecorder? = null,
    historyCollection: String = "godwit-history",
    wait: VirtualWait? = null
) : AutoCloseable {
    val client: MongoClient = TestMongo.client(name, *listOfNotNull(recorder).toTypedArray())

    val holder = "$name/1"

    private val bookkeeping = Bookkeeping(
        client,
        db.name,
        GodwitConfig(historyCollection = historyCollection, lock = config, holder = holder)
    )

    internal val lock = if (wait == null) {
        MongoLock(bookkeeping, config, holder)
    } else {
        MongoLock(bookkeeping, config, holder, wait.clock, wait.sleep, wait.random)
    }

    override fun close() = client.close()
}

val TestDatabase.lockCollection: MongoCollection<Document>
    get() = database.getCollection("godwit-lock", Document::class.java)

/** The lock document of the history collection [id], as it is now. */
fun TestDatabase.lockDocument(id: String = "godwit-history"): Document? =
    lockCollection.find(eq("_id", id)).firstOrNull()

/**
 * Writes the lock document a run that holds the lock leaves, with a lease ending [leaseMs] from now by the server's
 * clock (in the past when negative): a crashed holder's document, or an ended lease.
 */
fun TestDatabase.plantLease(owner: String, holder: String, runId: String, leaseMs: Long) {
    lockCollection.updateOne(
        eq("_id", "godwit-history"),
        listOf(
            Document(
                "\$set",
                Document("owner", owner)
                    .append("holder", holder)
                    .append("runId", runId)
                    .append("acquiredAt", "\$\$NOW")
                    .append("refreshedAt", "\$\$NOW")
                    .append("expiresAt", Document("\$add", listOf("\$\$NOW", leaseMs)))
            )
        ),
        UpdateOptions().upsert(true)
    )
}

/** Ends the lease of the lock document now, as if it had expired, without touching its owner. */
fun TestDatabase.endLease() {
    lockCollection.updateOne(
        eq("_id", "godwit-history"),
        listOf(Document("\$set", Document("expiresAt", Document("\$add", listOf("\$\$NOW", -1L)))))
    )
}

/** An instant as godwit's log lines print it: ISO-8601 with milliseconds. */
fun logged(instant: Instant): String = DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(instant)

fun logged(date: Date): String = logged(date.toInstant())
