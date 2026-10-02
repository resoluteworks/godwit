// expect: OK
// Positive control: the same imports and shapes as the bad snippets, written correctly. Proves the harness compiles.
package neg

import com.mongodb.client.model.Filters.exists
import godwit.core.Migration
import godwit.core.migration
import org.bson.Document

val control: List<Migration> = listOf(
    migration("001-a").outsideTransaction { ensureCollection("customers") }.inTransaction { collection("customers").insertOne(session, Document()) },
    migration("002-b").inBatches("orders", exists("totalMinor", false)) { orders -> count("orders", orders.size) }
)
