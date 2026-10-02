// expect: Unresolved reference 'ensureCollection'
// inBatches steps run in transactions too: no DDL helpers.
package neg

import com.mongodb.client.model.Filters.exists
import godwit.core.migration

val ddlInBatches = migration("001-a").inBatches("orders", exists("totalMinor", false)) { ensureCollection("orders") }
