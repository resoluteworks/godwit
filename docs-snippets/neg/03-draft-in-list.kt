// expect: actual 'List<MigrationDraft>'
// A draft without a step is not a Migration, so it cannot go in the app's List<Migration>.
package neg

import godwit.core.Migration
import godwit.core.migration

val stepless: List<Migration> = listOf(migration("001-a"))
