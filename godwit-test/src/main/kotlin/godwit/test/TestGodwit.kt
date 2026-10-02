package godwit.test

import com.mongodb.kotlin.client.MongoClient
import com.mongodb.kotlin.client.MongoDatabase
import godwit.core.Godwit
import godwit.core.GodwitConfig

/**
 * A new, empty database for one test, on a MongoDB container shared by the whole test JVM.
 *
 * The container is a single-node replica set, so transactions work. It starts on first use and stops when the JVM
 * exits. Every call returns a database named by a random UUID, so tests never see each other's data, can run in
 * parallel, and need no cleanup. [TestGodwit.client] has a [SessionEscapeDetector] installed.
 *
 * With [atlasSearch], the container is the Atlas local image, which also serves Atlas Search indexes (for migrations
 * that call `ensureSearchIndex`); it starts slower. Each image starts at most once per JVM.
 */
fun testGodwit(config: GodwitConfig = GodwitConfig(), atlasSearch: Boolean = false): TestGodwit =
    throw NotImplementedError("P7")

/**
 * A test database and a [Godwit] for it, on one client. Build the app's services from [client] or [database], so the
 * session godwit opens in a transactional step is valid in them.
 */
class TestGodwit internal constructor(val client: MongoClient, val databaseName: String, val godwit: Godwit) {
    val database: MongoDatabase get() = client.getDatabase(databaseName)
}
