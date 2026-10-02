package com.example.shop.docs.configuration

// region: lock-tuning
import godwit.core.LockConfig
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** For a cluster whose elections take about 30 s: the lease outlasts one election. */
val patientLock = LockConfig(
    lease = 3.minutes,
    heartbeat = 30.seconds,
    safetyMargin = 20.seconds,
    waitTimeout = 30.minutes
)

/** Throws IllegalArgumentException when it is built: the heartbeat must be below lease minus safetyMargin (20 s). */
fun invalidLock(): LockConfig = LockConfig(lease = 30.seconds, heartbeat = 25.seconds)
// endregion
