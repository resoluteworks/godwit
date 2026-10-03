package godwit.core.fixtures

import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import org.bson.Document
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * Holds the renewals of the client named [appName] on the server for longer than the local deadline of
 * [godwit.core.internal.testTimings] allows (2 s after the last renewal was sent): each renewal waits 2.5 s before the
 * server runs it. Closing the result lets them through again.
 */
fun blockRenewals(appName: String): AutoCloseable = TestMongo.failCommand(
    appName,
    listOf("update"),
    "alwaysOn",
    Document("blockConnection", true).append("blockTimeMS", 2500)
)

/**
 * Waits until the heartbeat thread has logged the loss of the lock. A step that waits this way never calls
 * `checkLock()`, so only the runner's own checks can stop it.
 */
fun LogCapture.awaitHeartbeatLoss() {
    await atMost 20.seconds until { events("Lost migration lock").isNotEmpty() }
}

/** Waits for the latch to open, and fails after 30 s. */
fun CountDownLatch.awaitOrFail() = check(await(30, TimeUnit.SECONDS)) { "timed out waiting for a signal" }
