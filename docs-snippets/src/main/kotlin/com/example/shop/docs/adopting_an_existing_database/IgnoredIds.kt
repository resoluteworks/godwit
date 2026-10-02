package com.example.shop.docs.adopting_an_existing_database

// region: ignored-ids
import godwit.core.Migration
import godwit.core.MigrationKind

/**
 * The ids that the hook returns and godwit will not import: the list declares none of them as once-only and no
 * `supersedes` list names them. Check them by eye before the first rollout; a typo shows up here.
 */
fun idsTheListIgnores(applied: Set<String>, migrations: List<Migration>): Set<String> {
    val imported = migrations
        .filter { it.kind == MigrationKind.Once }
        .flatMap { migration -> listOf(migration.id) + migration.supersedes }
    return applied - imported.toSet()
}
// endregion
