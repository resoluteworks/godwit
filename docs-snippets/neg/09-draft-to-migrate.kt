// expect: actual type is 'MigrationDraft', but 'Migration' was expected
// Godwit.migrate takes complete migrations only.
package neg

import godwit.core.Godwit
import godwit.core.migration

fun runDraft(godwit: Godwit) = godwit.migrate(migration("001-a"))
