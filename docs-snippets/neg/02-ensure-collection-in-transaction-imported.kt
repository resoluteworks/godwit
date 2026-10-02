// expect: inapplicable because of a receiver type mismatch
// The public MongoDatabase.ensureCollection extension, even imported, needs a MongoDatabase receiver, which a
// transactional step does not have. (database.ensureCollection(...) compiles: it runs without the session, outside the
// transaction, and SessionEscapeDetector fails it in tests: see docs/transactions-and-sessions.md.)
package neg

import godwit.core.*

val ddlInTransactionImported = migration("001-a").inTransaction { ensureCollection("customers") }
