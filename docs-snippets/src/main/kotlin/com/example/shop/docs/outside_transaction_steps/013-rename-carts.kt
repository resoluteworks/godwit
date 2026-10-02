package com.example.shop.docs.outside_transaction_steps

import com.mongodb.MongoNamespace
import godwit.core.migration

/**
 * Renames `carts` to `baskets`. renameCollection is not idempotent, so the step looks at which of the two exists and
 * does only what is left: a retry after a crash that followed the rename finds `baskets` alone and does nothing.
 */
val renameCarts = migration("013-rename-carts")
    .outsideTransaction {
        val names = database.listCollectionNames().toList()
        when {
            "carts" in names && "baskets" !in names ->
                collection("carts").renameCollection(MongoNamespace(database.name, "baskets"))
            "baskets" in names && "carts" !in names -> Unit
            else -> error("013-rename-carts expects exactly one of carts and baskets, found $names")
        }
    }
