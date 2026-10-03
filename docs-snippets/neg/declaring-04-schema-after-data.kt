// expect: Unresolved reference 'outsideTransaction' on receiver of type 'Migration'.
// docs/declaring-migrations.md: the outside step comes first; data then schema is two migrations.
package neg

import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Field
import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes.ascending
import godwit.core.migration
import org.bson.Document

val customerEmailLower = migration("007-customer-email-lower")
    .inTransaction {
        collection("customers").updateMany(
            session,
            exists("emailLower", false),
            listOf(Aggregates.set(Field("emailLower", Document("\$toLower", "\$email"))))
        )
    }
    .outsideTransaction {
        collection("customers").createIndex(ascending("emailLower"), IndexOptions().unique(true))
    }
