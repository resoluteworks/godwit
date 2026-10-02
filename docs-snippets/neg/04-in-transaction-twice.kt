// expect: Unresolved reference 'inTransaction'
// Nothing follows the transactional step: a second inTransaction does not exist on Migration.
package neg

import godwit.core.migration

val twice = migration("001-a").inTransaction { }.inTransaction { }
