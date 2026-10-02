package com.example.shop.docs.squashing_migrations

// region: manual-route
import godwit.core.Godwit

/**
 * Without `supersedes`, every environment needs this call for the baseline before the release that deletes the old
 * migrations, and nothing checks that the six really are applied there.
 */
fun markBaselineByHand(godwit: Godwit) {
    godwit.markApplied("100-baseline", reason = "squash of 001 to 006, checked by hand on 2026-10-02")
}
// endregion
