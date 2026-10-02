package com.example.shop.docs.repeatable_migrations

import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.ne
import com.mongodb.client.model.UpdateOneModel
import com.mongodb.client.model.Updates.combine
import com.mongodb.client.model.Updates.set
import godwit.core.migration
import org.bson.Document

/**
 * The third version of the product search text. A once-only migration per version: `searchTextVersion` marks the
 * products already done, so the pages resume and the next version selects every product again.
 */
val productSearchTextV3 = migration("020-product-search-text-v3")
    .inBatches("products", pending = ne("searchTextVersion", 3), batchSize = 500) { products ->
        collection("products").bulkWrite(
            session,
            products.map { product ->
                UpdateOneModel<Document>(
                    eq("_id", product["_id"]),
                    combine(set("searchText", searchTextOf(product)), set("searchTextVersion", 3))
                )
            }
        )
        count("productsUpdated", products.size)
    }
