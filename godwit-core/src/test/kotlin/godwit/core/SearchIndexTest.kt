package godwit.core

import com.mongodb.MongoCommandException
import com.mongodb.WriteConcern
import com.mongodb.kotlin.client.MongoCollection
import godwit.core.fixtures.CommandRecorder
import godwit.core.fixtures.atlasMongo
import godwit.core.internal.StepContext
import godwit.core.internal.ensureSearchIndex
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bson.Document
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private val dynamic = Document("mappings", Document("dynamic", true))

private fun MongoCollection<Document>.searchIndex(name: String): Document? =
    listSearchIndexes().name(name).firstOrNull()

/** `ensureSearchIndex` against the Atlas local image, which serves Atlas Search. */
@Tags("Atlas")
class SearchIndexTest : StringSpec() {
    init {
        "ensureSearchIndex creates the index once, and a second call finds it" {
            atlasMongo.client("search-create").use { client ->
                val products = client.getDatabase(
                    UUID.randomUUID().toString()
                ).getCollection("products", Document::class.java)
                products.insertOne(Document("name", "kettle"))

                products.ensureSearchIndex("product-search", dynamic) shouldBe true
                products.ensureSearchIndex("product-search", Document("mappings", Document("dynamic", false))) shouldBe
                    false

                products.listSearchIndexes().toList().map { it.getString("name") } shouldBe listOf("product-search")
            }
        }

        "with awaitReady it returns once the index is queryable" {
            atlasMongo.client("search-await").use { client ->
                val products = client.getDatabase(
                    UUID.randomUUID().toString()
                ).getCollection("products", Document::class.java)
                products.insertOne(Document("name", "kettle"))

                products.ensureSearchIndex("product-search", dynamic, awaitReady = 2.minutes) shouldBe true

                products.searchIndex("product-search")!!.getBoolean("queryable") shouldBe true
            }
        }

        "it waits for an existing index that is still building, without creating it again" {
            atlasMongo.client("search-building").use { client ->
                val products = client.getDatabase(
                    UUID.randomUUID().toString()
                ).getCollection("products", Document::class.java)
                products.insertOne(Document("name", "kettle"))
                products.createSearchIndex("product-search", dynamic)
                products.searchIndex("product-search")!!.getBoolean("queryable") shouldBe false

                products.ensureSearchIndex("product-search", dynamic, awaitReady = 2.minutes) shouldBe false

                products.searchIndex("product-search")!!.getBoolean("queryable") shouldBe true
            }
        }

        "SearchIndexNotReadyException when the index is not queryable before awaitReady passes" {
            atlasMongo.client("search-not-ready").use { client ->
                val products = client.getDatabase(
                    UUID.randomUUID().toString()
                ).getCollection("products", Document::class.java)
                products.insertOne(Document("name", "kettle"))

                val notReady = shouldThrow<SearchIndexNotReadyException> {
                    products.ensureSearchIndex("product-search", dynamic, awaitReady = 1.milliseconds)
                }

                notReady.collection shouldBe "products"
                notReady.name shouldBe "product-search"
                notReady.waited shouldBe 1.milliseconds
                notReady.message shouldBe "Search index product-search on products was not queryable after 1ms"
            }
        }

        "the outside step's member checks the lock between polls" {
            atlasMongo.client("search-check-lock").use { client ->
                val database = client.getDatabase(UUID.randomUUID().toString())
                database.getCollection("products", Document::class.java).insertOne(Document("name", "kettle"))
                val checks = mutableListOf<String>()
                val scope = OutsideTransactionScope(
                    "001-initial-setup",
                    database,
                    StepContext {
                        checks += "checkLock"
                        throw LockLostException("001-initial-setup")
                    }
                )

                shouldThrow<LockLostException> {
                    scope.ensureSearchIndex("products", "product-search", dynamic, awaitReady = 2.minutes)
                }

                checks shouldBe listOf("checkLock")
            }
        }

        "the outside step's member creates through the majority collection; the create carries no write concern" {
            val recorder = CommandRecorder()
            atlasMongo.client("search-write-concern", recorder).use { client ->
                val database = client.getDatabase(UUID.randomUUID().toString()).withWriteConcern(WriteConcern.W1)
                database.getCollection("products", Document::class.java).insertOne(Document("name", "kettle"))
                val scope = OutsideTransactionScope("001-initial-setup", database, StepContext {})

                scope.ensureSearchIndex("products", "product-search", dynamic) shouldBe true

                scope.collection("products").writeConcern shouldBe WriteConcern.MAJORITY
                val create = recorder.commands("createSearchIndexes").single()
                create.command.containsKey("writeConcern") shouldBe false
            }
        }

        "a create that raced another create of the name counts as existing" {
            atlasMongo.client("search-race-other").use { other ->
                val databaseName = UUID.randomUUID().toString()
                val products = other.getDatabase(databaseName).getCollection("products", Document::class.java)
                products.insertOne(Document("name", "kettle"))
                val recorder = CommandRecorder(onSucceeded = { command ->
                    // Another process creates the index, with another definition, between the look and the create.
                    if (command.name == "aggregate") {
                        products.createSearchIndex("product-search", Document("mappings", Document("dynamic", false)))
                    }
                })
                atlasMongo.client("search-race", recorder).use { client ->
                    val racing = client.getDatabase(databaseName).getCollection("products", Document::class.java)

                    ensureSearchIndex(racing, "product-search", dynamic, null, 1.milliseconds) {} shouldBe false
                    recorder.commands("createSearchIndexes").single().errorCode shouldBe 68
                }
            }
        }

        "an error other than a name that exists propagates" {
            atlasMongo.client("search-missing-collection").use { client ->
                val missing = client.getDatabase(
                    UUID.randomUUID().toString()
                ).getCollection("missing", Document::class.java)

                shouldThrow<MongoCommandException> {
                    missing.ensureSearchIndex("product-search", dynamic)
                }.code shouldBe
                    26
            }
        }
    }
}
