// expect: Unresolved reference 'inTransaction' on receiver of type 'Migration'.
// docs/declaring-migrations.md: one transactional step per migration.
package neg

import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.Updates.set
import godwit.core.migration

val defaultCurrency = migration("024-default-currency")
    .inTransaction {
        collection("orders").updateMany(session, exists("currency", false), set("currency", "GBP"))
    }
    .inTransaction {
        collection("carts").updateMany(session, exists("currency", false), set("currency", "GBP"))
    }
