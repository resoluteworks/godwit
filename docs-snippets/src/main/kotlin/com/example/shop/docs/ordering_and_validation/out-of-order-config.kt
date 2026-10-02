package com.example.shop.docs.ordering_and_validation

import godwit.core.GodwitConfig
import godwit.core.OutOfOrder
import godwit.core.UnknownApplied

/** Staging runs branch builds before they merge, so it accepts migrations that arrive out of order. */
fun shopGodwitConfig(allowOutOfOrder: Boolean): GodwitConfig =
    GodwitConfig(outOfOrder = if (allowOutOfOrder) OutOfOrder.RUN else OutOfOrder.FAIL)

/** For a check that must fail when the database holds migrations this build does not know. */
val strictGodwitConfig = GodwitConfig(unknownApplied = UnknownApplied.FAIL)
