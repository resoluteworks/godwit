package com.example.shop.docs.adopting_an_existing_database

// region: mark-applied
import com.example.shop.ShopConfig
import com.mongodb.kotlin.client.MongoClient
import godwit.core.Godwit
import godwit.core.GodwitConfig

/**
 * Records a change that someone applied by hand, with the reason in history. [godwitConfig] is the configuration the
 * shop starts with. The copy drops adoptApplied, because the shop's own Godwit refuses to mark while history holds
 * nothing but ADOPTED documents, and keeps every other setting, so the mark lands in the shop's history and lock
 * collections.
 */
fun recordHandAppliedChange(client: MongoClient, config: ShopConfig, godwitConfig: GodwitConfig) {
    Godwit(client, config.mongo.database, godwitConfig.copy(adoptApplied = null)).markApplied(
        "002-carts",
        reason = "created by hand on 2026-03-02, see ticket SHOP-212"
    )
}
// endregion
