package godwit.core.fixtures

import com.mongodb.client.model.Filters.`in`
import com.mongodb.client.model.Updates.inc
import godwit.core.GodwitConfig
import godwit.core.TransactionScope
import godwit.core.UntrackedDatabase
import org.bson.Document

/**
 * The configuration of specs that seed their collections before the first migration runs, which the untracked-database
 * guard would refuse.
 */
val seeded = GodwitConfig(untrackedDatabase = UntrackedDatabase.RUN_ALL)

/**
 * `probe` incremented on every document of [page] in [collection], on the page's session: an effect that must commit
 * exactly once per document.
 */
fun TransactionScope.probe(collection: String, page: List<Document>) {
    collection(collection).updateMany(session, `in`("_id", page.map { it["_id"] }), inc("probe", 1))
}
