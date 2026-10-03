// expect: Initializer type mismatch: expected 'List<Migration>', actual 'List<MigrationDraft>'.
// docs/declaring-migrations.md: a draft without a step is not a Migration.
package neg

import godwit.core.Migration
import godwit.core.migration

val productSlugs = migration("007-product-slugs", description = "URL slugs for products")

val migrations: List<Migration> = listOf(productSlugs)
