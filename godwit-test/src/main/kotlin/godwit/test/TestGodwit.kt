package godwit.test

import com.mongodb.kotlin.client.MongoClient
import com.mongodb.kotlin.client.MongoDatabase
import godwit.core.Godwit
import godwit.core.GodwitConfig
import godwit.test.internal.SharedContainers
import java.util.UUID

/**
 * A new, empty database for one test, on a MongoDB container shared by the whole test JVM.
 *
 * The container is a single-node replica set, so transactions work. It starts on first use and stops when the JVM
 * exits. Every call returns a database named by a random UUID, so tests never see each other's data, can run in
 * parallel, and need no cleanup. [TestGodwit.client] has a [SessionEscapeDetector] installed.
 *
 * With [atlasSearch], the container is the Atlas local image, which also serves Atlas Search indexes (for migrations
 * that call `ensureSearchIndex`); it starts slower. Each image starts at most once per JVM: a container that fails to
 * start is replaced by a new one, for up to three attempts in all; when every attempt fails the call throws the
 * Testcontainers error, as the next call does after trying again. The start logs `godwit-test container started` at
 * INFO, with `image` and `startupMs`, on the logger `godwit.test`.
 *
 * Every call on one image returns the same [TestGodwit.client]; a test must not close it. A call starts a test on its
 * thread, so it ends any step transaction the client's detector still tracks there: one whose end the detector never
 * saw, such as a step an earlier test's timeout interrupted.
 */
fun testGodwit(config: GodwitConfig = GodwitConfig(), atlasSearch: Boolean = false): TestGodwit {
    val shared = if (atlasSearch) SharedContainers.atlasLocal else SharedContainers.replicaSet
    val client = shared.client
    shared.detector.endTrackedTransaction()
    val databaseName = UUID.randomUUID().toString()
    return TestGodwit(client, databaseName, Godwit(client, databaseName, config))
}

/**
 * A test database and a [Godwit] for it, on one client. Build the app's services from [client] or [database], so the
 * session godwit opens in a transactional step is valid in them.
 */
class TestGodwit internal constructor(val client: MongoClient, val databaseName: String, val godwit: Godwit) {
    val database: MongoDatabase get() = client.getDatabase(databaseName)
}
