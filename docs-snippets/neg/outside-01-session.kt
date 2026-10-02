// expect: Unresolved reference 'session'
// docs/outside-transaction-steps.md: an outside step has no session.
package neg

import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.Updates.set
import godwit.core.migration

val orderCurrencyWithSession = migration("011-order-currency")
    .outsideTransaction {
        collection("orders").updateMany(session, exists("currency", false), set("currency", "GBP"))
    }
