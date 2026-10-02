package com.example.shop.docs.adopting_an_existing_database

// region: policies
import com.example.shop.migrations.appliedBeforeGodwit
import godwit.core.GodwitConfig
import godwit.core.OutOfOrder
import godwit.core.UntrackedDatabase

/** A gap in the adopted ids runs the missing migration instead of failing; for a database that skipped one. */
val adoptAndRunGaps = GodwitConfig(adoptApplied = ::appliedBeforeGodwit, outOfOrder = OutOfOrder.RUN)

/** No hook, and a database with data runs every migration: only for a database whose migrations are safe to repeat. */
val runEverything = GodwitConfig(untrackedDatabase = UntrackedDatabase.RUN_ALL)
// endregion
