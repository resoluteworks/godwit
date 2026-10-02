package com.example.shop.docs.configuration

// region: defaults
import godwit.core.GodwitConfig
import godwit.core.LockConfig
import godwit.core.OutOfOrder
import godwit.core.UnknownApplied
import godwit.core.UntrackedDatabase
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Every setting at its default. `holder` is left out: its default names the host and the process. */
val defaults = GodwitConfig(
    historyCollection = "godwit-history",
    lockCollection = "godwit-lock",
    lock = LockConfig(lease = 60.seconds, heartbeat = 20.seconds, safetyMargin = 10.seconds, waitTimeout = 10.minutes),
    outOfOrder = OutOfOrder.FAIL,
    unknownApplied = UnknownApplied.WARN,
    untrackedDatabase = UntrackedDatabase.REFUSE,
    adoptApplied = null,
    slowTransactionWarning = 20.seconds
)
// endregion
