package godwit.test

import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.Updates.set
import godwit.core.GodwitConfig
import godwit.core.MigrationFailedException
import godwit.core.migration
import godwit.test.internal.Images
import godwit.test.internal.SharedContainers
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.bson.Document
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class TestGodwitTest : StringSpec() {
    init {
        "two calls share one container and one client, and get different, empty databases" {
            val first = testGodwit()
            val second = testGodwit()

            first.client shouldBeSameInstanceAs second.client
            first.client shouldBeSameInstanceAs SharedContainers.replicaSet.client
            first.databaseName shouldNotBe second.databaseName
            UUID.fromString(first.databaseName).toString() shouldBe first.databaseName
            UUID.fromString(second.databaseName).toString() shouldBe second.databaseName
            first.database.name shouldBe first.databaseName
            first.database.listCollectionNames().toList().shouldBeEmpty()

            first.database.getCollection("orders", Document::class.java).insertOne(Document("_id", 1))
            second.database.getCollection("orders", Document::class.java).countDocuments() shouldBe 0L
        }

        "the container runs the replica set image, so a transactional step commits" {
            val db = testGodwit()

            val report = db.godwit.migrate(
                migration("004-order-status")
                    .outsideTransaction { collection("orders").insertOne(Document("_id", 1)) }
                    .inTransaction {
                        collection("orders").updateMany(session, exists("status", false), set("status", "PENDING"))
                    }
            )

            report.ran.single().id shouldBe "004-order-status"
            db.database.getCollection("orders", Document::class.java).find().first()["status"] shouldBe "PENDING"
            SharedContainers.replicaSet.container.dockerImageName shouldBe Images.mongo
            db.database.runCommand(Document("hello", 1)).getString("setName").shouldNotBeBlank()
        }

        "concurrent calls share the container and the client" {
            val pool = Executors.newFixedThreadPool(8)
            try {
                val clients = pool.invokeAll(List(8) { Callable { testGodwit().client } }).map { it.get() }

                clients.toSet().single() shouldBeSameInstanceAs testGodwit().client
            } finally {
                pool.shutdown()
            }
        }

        "the Godwit migrates the new database with the configuration passed" {
            val config = GodwitConfig(historyCollection = "schema-history", lockCollection = "schema-lock")
            val db = testGodwit(config)

            db.godwit.migrate(migration("002-carts").outsideTransaction { ensureCollection("carts") })

            db.godwit.databaseName shouldBe db.databaseName
            db.godwit.config shouldBe config
            db.database.listCollectionNames().toList().sorted() shouldBe
                listOf("carts", "schema-history", "schema-lock")
        }

        "the client has the session escape detector installed" {
            val db = testGodwit()

            val failure = shouldThrow<MigrationFailedException> {
                db.godwit.migrate(
                    migration("014-product-price-rise").inTransaction {
                        collection("products").updateMany(exists("priceMinor"), set("priceMinor", 100L))
                    }
                )
            }

            failure.cause.shouldBeInstanceOf<SessionEscapeError>().message shouldBe
                "update on products ran without the step's session, outside the transaction. " +
                "Pass `session` to the driver call or the service method."
        }
    }
}
