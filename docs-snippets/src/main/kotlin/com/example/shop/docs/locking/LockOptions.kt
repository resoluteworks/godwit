package com.example.shop.docs.locking

import com.mongodb.kotlin.client.MongoClient
import godwit.core.Godwit
import godwit.core.GodwitConfig
import godwit.core.LockConfig
import godwit.core.LockTimeoutException
import godwit.core.Migration
import godwit.core.MigrationReport
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("shop")

/** For a one-off run (a CI job, an operator's command): fail at once when another process holds the lock. */
fun failFastGodwit(client: MongoClient, databaseName: String): Godwit =
    Godwit(client, databaseName, GodwitConfig(lock = LockConfig(waitTimeout = Duration.ZERO)))

/** Migrates, and on a lock timeout logs who holds the lock before failing the start. */
fun migrateOrExplain(godwit: Godwit, migrations: List<Migration>): MigrationReport =
    try {
        godwit.migrate(migrations)
    } catch (e: LockTimeoutException) {
        val holder = e.holder
        if (holder == null) {
            log.error("Waited {} for the migration lock; it was released as the wait ended", e.waited)
        } else {
            log.error(
                "Waited {} for the migration lock; {} (run {}) has held it since {}, lease until {}",
                e.waited, holder.holder, holder.runId, holder.acquiredAt, holder.expiresAt
            )
        }
        throw e
    }

/**
 * Migrates unless another process is migrating right now, in which case it returns null at once and the caller
 * starts anyway. The caller then runs this release's code on a database that may not have this release's
 * migrations yet: use it only where that code works on both schemas.
 */
fun migrateUnlessBusy(client: MongoClient, databaseName: String, migrations: List<Migration>): MigrationReport? {
    val godwit = Godwit(client, databaseName, GodwitConfig(lock = LockConfig(waitTimeout = Duration.ZERO)))
    return try {
        godwit.migrate(migrations)
    } catch (e: LockTimeoutException) {
        log.warn("Skipping migrations: {} holds the migration lock", e.holder?.holder)
        null
    }
}

/** For a deployment whose failovers can take longer than a minute: a 2-minute lease, renewed every 30 s. */
val slowFailoverLock = LockConfig(lease = 2.minutes, heartbeat = 30.seconds, safetyMargin = 20.seconds)

/** Throws IllegalArgumentException: the default heartbeat (20 s) is not below lease minus safetyMargin (20 s). */
fun shortLease(): LockConfig = LockConfig(lease = 30.seconds)
