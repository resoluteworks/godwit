// expect: Unresolved reference 'ensureCollection'
// docs/batched-backfills.md: DDL belongs in the outside step, not in the page step.
package neg

import com.mongodb.client.model.Filters.exists
import godwit.core.migration

val emailLowerWithDdl = migration("007-customer-email-lower")
    .inBatches("customers", pending = exists("emailLower", false)) { customers ->
        ensureCollection("customers")
    }
