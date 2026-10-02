package com.example.shop.docs.configuration

// region: environments
import com.example.shop.migrations.appliedBeforeGodwit
import godwit.core.GodwitConfig
import godwit.core.LockConfig
import godwit.core.OutOfOrder
import godwit.core.UnknownApplied
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

enum class Environment { LOCAL, CI, STAGING, PRODUCTION }

/** A missing variable fails the start: no environment is the default. */
fun environmentOf(env: Map<String, String> = System.getenv()): Environment =
    Environment.valueOf(env.getValue("SHOP_ENVIRONMENT"))

/** [holder] names this process in the lock document, in history and in log lines, for example pod and release. */
fun godwitConfig(environment: Environment, holder: String): GodwitConfig = when (environment) {
    // A developer's database has run migrations from other branches.
    Environment.LOCAL -> GodwitConfig(outOfOrder = OutOfOrder.RUN)

    // One process, a database that nothing else touches: a lock that is held is a bug, so do not wait for it.
    // Against a copy of production, an id that the code does not know means the build is older than production.
    Environment.CI -> GodwitConfig(
        lock = LockConfig(waitTimeout = Duration.ZERO),
        unknownApplied = UnknownApplied.FAIL
    )

    // Staging ran a branch early; its migrations may be listed before ones that staging has applied.
    Environment.STAGING -> GodwitConfig(outOfOrder = OutOfOrder.RUN, holder = holder)

    // The defaults, a wait longer than the longest migration, and the hook that adopts databases migrated by hand.
    Environment.PRODUCTION -> GodwitConfig(
        lock = LockConfig(waitTimeout = 15.minutes),
        adoptApplied = ::appliedBeforeGodwit,
        holder = holder
    )
}
// endregion
