// expect: Unresolved reference 'session'
// docs/declaring-migrations.md: an outside step has no session.
package neg

import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Field
import com.mongodb.client.model.Filters.exists
import godwit.core.migration
import org.bson.Document

val productSlugs = migration("007-product-slugs")
    .outsideTransaction {
        collection("products").updateMany(
            session,
            exists("slug", false),
            listOf(Aggregates.set(Field("slug", Document("\$toLower", "\$sku"))))
        )
    }
