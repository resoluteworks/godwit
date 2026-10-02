package godwit.core.fixtures

import com.mongodb.kotlin.client.MongoDatabase
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.bson.Document
import org.slf4j.LoggerFactory
import java.util.UUID

private val log = LoggerFactory.getLogger(ReplicaSetSmokeTest::class.java)

private fun MongoDatabase.hello(): Document = runCommand(Document("hello", 1))

class ReplicaSetSmokeTest : StringSpec() {
    init {
        "hello returns the name of a replica set whose primary accepts writes" {
            TestMongo.database().use { db ->
                val hello = db.database.hello()
                val setName = hello.getString("setName")
                setName.shouldNotBeBlank()
                hello.getBoolean("isWritablePrimary") shouldBe true
                log.info("test replica set ready setName={} startupMs={}", setName, TestMongo.startupMs)
            }
        }

        "two calls of the fixture return different databases on the same container" {
            val containerId = TestMongo.container.containerId
            TestMongo.database().use { first ->
                TestMongo.database().use { second ->
                    first.name shouldNotBe second.name
                    UUID.fromString(first.name).toString() shouldBe first.name
                    UUID.fromString(second.name).toString() shouldBe second.name
                    TestMongo.container.containerId shouldBe containerId
                    first.database.hello().getString("me") shouldBe second.database.hello().getString("me")

                    first.database.getCollection<Document>("items").insertOne(Document("n", 1))
                    first.database.getCollection<Document>("items").countDocuments() shouldBe 1
                    second.database.getCollection<Document>("items").countDocuments() shouldBe 0
                }
            }
        }

        "the server accepts fail point commands" {
            TestMongo.client().use { client ->
                val reply = client.getDatabase("admin")
                    .runCommand<Document>(Document("configureFailPoint", "failCommand").append("mode", "off"))
                reply.getDouble("ok") shouldBe 1.0
            }
        }
    }
}
