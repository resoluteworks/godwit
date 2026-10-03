package godwit.core.docs

import com.example.shop.migrations.initialSetup
import com.example.shop.services.CustomerService
import com.mongodb.client.model.Filters.eq
import godwit.core.Godwit
import godwit.core.GodwitConfig
import godwit.core.fixtures.atlasMongo
import org.bson.BsonDocument
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

/**
 * failure-and-recovery.md's outside step that throws: on the Atlas local image, the shop's own `001-initial-setup`
 * waits for its product search index, which is not queryable by the end of the wait. The docs' wait is 5 minutes;
 * this one is 1 ms, which the index never meets.
 */
val searchIndexNotReady = Scenario("001 waits for the product search index") {
    val database = UUID.randomUUID().toString()
    atlasMongo.client("shop-7f9c4").use { client ->
        val godwit = Godwit(client, database, GodwitConfig(holder = "shop-7f9c4/1"))
        val shop = shopMigrations(CustomerService(client.getDatabase(database)), StubIdentityProvider())
        val list = listOf(initialSetup(searchIndexWait = 1.milliseconds)) + shop.drop(1)
        failing("first start") { godwit.migrate(list) }
        document(
            "001 failed",
            client.getDatabase(database).getCollection("godwit-history", BsonDocument::class.java)
                .find(eq("_id", "001-initial-setup")).firstOrNull()
        )
    }
}
