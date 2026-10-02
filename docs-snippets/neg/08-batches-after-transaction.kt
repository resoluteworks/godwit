// expect: Unresolved reference 'inBatches'
// One transactional step per migration: inBatches cannot follow inTransaction.
package neg

import com.mongodb.client.model.Filters.exists
import godwit.core.migration

val both = migration("001-a").inTransaction { }.inBatches("orders", exists("totalMinor", false)) { }
