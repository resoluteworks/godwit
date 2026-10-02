package com.example.shop.docs.outside_transaction_steps

import godwit.core.migration

/** No query filters on `status` alone any more; the index only slows writes down. */
val dropOrderStatusIndex = migration("012-orders-drop-status-index")
    .outsideTransaction {
        dropIndexIfExists("orders", "status_1")
    }
