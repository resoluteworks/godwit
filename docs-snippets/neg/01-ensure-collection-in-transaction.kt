// expect: Unresolved reference 'ensureCollection'
// DDL helpers are OutsideTransactionScope members only: they do not resolve inside inTransaction.
package neg

import godwit.core.migration

val ddlInTransaction = migration("001-a").inTransaction { ensureCollection("customers") }
