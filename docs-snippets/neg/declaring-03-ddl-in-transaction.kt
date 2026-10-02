// expect: Unresolved reference 'ensureCollection'
// docs/declaring-migrations.md: the DDL helpers exist in outsideTransaction only.
package neg

import godwit.core.migration
import org.bson.Document

val productSlugs = migration("007-product-slugs")
    .inTransaction {
        ensureCollection("product-slugs")
        collection("product-slugs").insertOne(session, Document("_id", "red-shoes").append("sku", "SHOE-RED"))
    }
