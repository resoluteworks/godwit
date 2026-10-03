package godwit.test

import com.mongodb.client.model.Updates.set
import godwit.core.migration
import godwit.test.internal.Images
import godwit.test.internal.SharedContainers
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.bson.Document
import kotlin.time.Duration.Companion.minutes

/** `testGodwit(atlasSearch = true)` against the Atlas local image, which serves Atlas Search. */
@Tags("Atlas")
class TestGodwitAtlasTest : StringSpec() {
    init {
        "atlasSearch starts the Atlas local image once, and its calls share one client" {
            val first = testGodwit(atlasSearch = true)
            val second = testGodwit(atlasSearch = true)

            first.client shouldBeSameInstanceAs second.client
            first.client shouldBeSameInstanceAs SharedContainers.atlasLocal.client
            first.databaseName shouldNotBe second.databaseName
            SharedContainers.atlasLocal.container.dockerImageName shouldBe Images.atlasLocal
        }

        "a migration that creates a search index and then writes in a transaction applies on it" {
            val db = testGodwit(atlasSearch = true)

            val report = db.godwit.migrate(
                migration("001-initial-setup")
                    .outsideTransaction {
                        collection("products").insertOne(Document("name", "kettle"))
                        ensureSearchIndex(
                            "products",
                            "product-search",
                            Document("mappings", Document("dynamic", true)),
                            awaitReady = 2.minutes
                        )
                    }
                    .inTransaction { collection("products").updateMany(session, Document(), set("searchable", true)) }
            )

            report.ran.single().id shouldBe "001-initial-setup"
            db.godwit shouldHaveApplied "001-initial-setup"
            db.database.getCollection("products", Document::class.java).find().first()["searchable"] shouldBe true
        }
    }
}
