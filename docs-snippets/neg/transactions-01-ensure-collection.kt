// expect: Unresolved reference 'ensureCollection'
// docs/transactions-and-sessions.md: the DDL helpers do not resolve inside inTransaction.
package neg

import godwit.core.migration

val cartsInTransaction = migration("002-carts")
    .inTransaction {
        ensureCollection("carts")
    }
