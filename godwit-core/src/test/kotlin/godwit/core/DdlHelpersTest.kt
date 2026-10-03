package godwit.core

import com.mongodb.MongoCommandException
import com.mongodb.client.model.CreateCollectionOptions
import com.mongodb.client.model.Indexes.ascending
import godwit.core.fixtures.CommandRecorder
import godwit.core.fixtures.GodwitFixture
import godwit.core.fixtures.TestMongo
import godwit.core.fixtures.newerMongo
import godwit.core.fixtures.race
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bson.Document
import java.util.UUID

/** The idempotent DDL helpers, as extensions and as members of the outside step's scope. */
class DdlHelpersTest : StringSpec() {
    init {
        "ensureCollection creates a collection once and leaves an existing one, and its options, alone" {
            TestMongo.database().use { db ->
                val capped = CreateCollectionOptions().capped(true).sizeInBytes(4096)

                db.database.ensureCollection("events", capped) shouldBe true
                db.database.ensureCollection("events") shouldBe false
                db.database.ensureCollection("events", CreateCollectionOptions()) shouldBe false

                val stats = db.database.runCommand(
                    Document("listCollections", 1).append("filter", Document("name", "events"))
                )
                stats.get("cursor", Document::class.java).getList("firstBatch", Document::class.java).single()
                    .get("options", Document::class.java).getBoolean("capped") shouldBe true
            }
        }

        "ensureCollection counts a create that raced another one (NamespaceExists, 48) as existing" {
            TestMongo.database().use { db ->
                val recorder = CommandRecorder(onSucceeded = { command ->
                    // Another process creates the collection, with other options, between the look and the create.
                    if (command.name == "listCollections") {
                        db.database.createCollection("carts", CreateCollectionOptions().capped(true).sizeInBytes(4096))
                    }
                })
                TestMongo.client("ddl-race-create", recorder).use { client ->
                    client.getDatabase(db.name).ensureCollection("carts") shouldBe false
                    recorder.commands("create").single().errorCode shouldBe 48
                }
            }
        }

        "ensureCollection from two threads at once: neither fails, and the collection exists" {
            TestMongo.database().use { db ->
                val names = (0 until 20).map { "race-$it" }

                val results = race(names.size, { db.database.ensureCollection(names[it]) }) {
                    db.database.ensureCollection(names[it])
                }

                results.forEachIndexed { round, (first, second) ->
                    withClue(names[round]) { (first || second) shouldBe true }
                }
                db.database.listCollectionNames().toList().sorted() shouldBe names.sorted()
            }
        }

        "ensureCollection passes on an error other than NamespaceExists" {
            TestMongo.database().use { db ->
                shouldThrow<MongoCommandException> { db.database.ensureCollection("bad\$name") }.code shouldBe 73
            }
        }

        "dropIndexIfExists drops an index once, and returns false for an index that does not exist" {
            TestMongo.database().use { db ->
                val orders = db.database.getCollection("orders", Document::class.java)
                orders.createIndex(ascending("status"))

                orders.dropIndexIfExists("status_1") shouldBe true
                orders.dropIndexIfExists("status_1") shouldBe false
                orders.listIndexes().toList().map { it.getString("name") } shouldBe listOf("_id_")
                db.database.getCollection("missing", Document::class.java).dropIndexIfExists("status_1") shouldBe false
            }
        }

        "dropIndexIfExists counts a drop that raced another one (IndexNotFound, 27) as gone" {
            TestMongo.database().use { db ->
                val orders = db.database.getCollection("orders", Document::class.java)
                orders.createIndex(ascending("status"))
                val recorder = CommandRecorder(onSucceeded = { command ->
                    if (command.name == "listIndexes") orders.dropIndex("status_1")
                })
                TestMongo.client("ddl-race-drop", recorder).use { client ->
                    client.getDatabase(db.name).getCollection("orders", Document::class.java)
                        .dropIndexIfExists("status_1") shouldBe false
                    recorder.commands("dropIndexes").single().errorCode shouldBe 27
                }
            }
        }

        "dropIndexIfExists passes on an error other than IndexNotFound" {
            TestMongo.database().use { db ->
                val orders = db.database.getCollection("orders", Document::class.java)
                orders.insertOne(Document("status", "PAID"))

                shouldThrow<MongoCommandException> { orders.dropIndexIfExists("_id_") }.code shouldBe 72
            }
        }

        "dropIndexIfExists of a missing index returns false on MongoDB 8.3 too, where dropIndexes itself succeeds" {
            newerMongo.client("ddl-newer").use { client ->
                val database = client.getDatabase(UUID.randomUUID().toString())
                val orders = database.getCollection("orders", Document::class.java)
                orders.insertOne(Document("status", "PAID"))
                orders.createIndex(ascending("status"))

                orders.dropIndexIfExists("status_1") shouldBe true
                orders.dropIndexIfExists("status_1") shouldBe false
                database.runCommand(
                    Document("dropIndexes", "orders").append("index", "status_1")
                ).getDouble("ok") shouldBe
                    1.0
                database.drop()
            }
        }

        "on MongoDB 8.3 a drop that raced another one succeeds, so dropIndexIfExists returns true for both" {
            val databaseName = UUID.randomUUID().toString()
            newerMongo.client("ddl-newer-other").use { other ->
                val otherOrders = other.getDatabase(databaseName).getCollection("orders", Document::class.java)
                otherOrders.insertOne(Document("status", "PAID"))
                otherOrders.createIndex(ascending("status"))
                val recorder = CommandRecorder(onSucceeded = { command ->
                    // Another process drops the index between the look and the drop, and its call returns true.
                    if (command.name == "listIndexes") otherOrders.dropIndexIfExists("status_1") shouldBe true
                })
                newerMongo.client("ddl-newer-race", recorder).use { client ->
                    client.getDatabase(databaseName).getCollection("orders", Document::class.java)
                        .dropIndexIfExists("status_1") shouldBe true
                    recorder.commands("dropIndexes").single().errorCode shouldBe null
                }
                otherOrders.listIndexes().toList().map { it.getString("name") } shouldBe listOf("_id_")
                other.getDatabase(databaseName).drop()
            }
        }

        "the outside step's members run the helpers on the migrated database, each twice in a row" {
            GodwitFixture().use { f ->
                val results = mutableListOf<Boolean>()
                val ddl = migration("012-ddl").outsideTransaction {
                    results += ensureCollection("orders")
                    results += ensureCollection("orders")
                    collection("orders").createIndex(ascending("status"))
                    results += dropIndexIfExists("orders", "status_1")
                    results += dropIndexIfExists("orders", "status_1")
                }

                f.godwit.migrate(ddl)

                results shouldBe listOf(true, false, true, false)
            }
        }
    }
}
