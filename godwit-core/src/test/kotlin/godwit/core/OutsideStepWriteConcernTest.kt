package godwit.core

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.ReadConcern
import com.mongodb.ReadPreference
import com.mongodb.WriteConcern
import com.mongodb.client.model.Filters.empty
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes.ascending
import com.mongodb.client.model.Updates.set
import com.mongodb.event.CommandListener
import com.mongodb.kotlin.client.ClientSession
import com.mongodb.kotlin.client.MongoClient
import com.mongodb.kotlin.client.MongoDatabase
import godwit.core.fixtures.CommandRecorder
import godwit.core.fixtures.RecordedCommand
import godwit.core.fixtures.TestMongo
import godwit.core.fixtures.seeded
import godwit.core.internal.StepContext
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.bson.BsonDocument
import org.bson.BsonString
import org.bson.Document
import org.bson.codecs.configuration.CodecRegistries
import java.util.concurrent.TimeUnit

private const val APP_NAME = "outside-write-concern"

/** The commands that write or change the schema, by the name the driver sends them under. */
private val writeCommands = setOf("create", "createIndexes", "insert", "update", "delete", "dropIndexes", "collMod")

/** godwit's own collections, whose commands carry majority whatever the step does. */
private val bookkeeping = setOf("godwit-history", "godwit-lock")

/** The collection a command names: the value of its first key, such as `{insert: "carts", ...}`. */
private val RecordedCommand.target: String get() = command.getString(name).value

/** The write concern the command carries, or null when it carries none. */
private val RecordedCommand.writeConcern: BsonDocument? get() = command.getDocument("writeConcern", null)

/** The commands [recorder] saw that write to or change the app's collections, in the order they were sent. */
private fun appWrites(recorder: CommandRecorder): List<RecordedCommand> =
    recorder.commands.filter { it.name in writeCommands && it.target !in bookkeeping }

/** A client on the test replica set whose write concern is `w:1`, as an app's can be, observed by [listener]. */
private fun w1Client(listener: CommandListener): MongoClient = MongoClient.create(
    MongoClientSettings.builder()
        .applyConnectionString(ConnectionString(TestMongo.connectionString))
        .applicationName(APP_NAME)
        .writeConcern(WriteConcern.W1)
        .addCommandListener(listener)
        .build()
)

/** An app service built on the app's database, as the shop's services are: it writes with that database's settings. */
private class CartEvents(database: MongoDatabase) {
    private val events = database.getCollection("cart-events", Document::class.java)

    fun record(kind: String) {
        events.insertOne(Document("kind", kind))
    }

    fun record(session: ClientSession, kind: String) {
        events.insertOne(session, Document("kind", kind))
    }
}

/**
 * The outside step's scope writes with majority write concern whatever the app's client sets, so its writes are
 * majority-committed before godwit records the migration APPLIED with majority. An app service keeps its own write
 * concern, and a transactional step's writes commit with the transaction's majority commit.
 */
class OutsideStepWriteConcernTest : StringSpec() {
    init {
        "an outside step's writes and DDL through the scope carry majority on a w:1 client; a service keeps w:1" {
            val recorder = CommandRecorder()
            TestMongo.database().use { db ->
                w1Client(recorder).use { client ->
                    val events = CartEvents(client.getDatabase(db.name))
                    val carts = migration("002-carts").outsideTransaction {
                        ensureCollection("carts")
                        collection("carts").createIndex(ascending("customerId"))
                        collection("carts").insertOne(Document("customerId", 1))
                        collection("carts").updateMany(empty(), set("open", true))
                        collection("carts").deleteMany(eq("customerId", 2))
                        dropIndexIfExists("carts", "customerId_1")
                        database.createCollection("payments")
                        database.getCollection("orders", Document::class.java).insertOne(Document("_id", 1))
                        events.record("carts-created")
                    }

                    Godwit(client, db.name, GodwitConfig(holder = "$APP_NAME/1")).migrate(carts)

                    val writes = appWrites(recorder)
                    writes.map { "${it.name} ${it.target}" } shouldBe listOf(
                        "create carts",
                        "createIndexes carts",
                        "insert carts",
                        "update carts",
                        "delete carts",
                        "dropIndexes carts",
                        "create payments",
                        "insert orders",
                        "insert cart-events"
                    )
                    writes.forEach { write ->
                        withClue("${write.name} ${write.target}") {
                            write.writeConcern shouldBe if (write.target == "cart-events") {
                                WriteConcern.W1.asDocument()
                            } else {
                                WriteConcern.MAJORITY.asDocument()
                            }
                        }
                    }
                    db.database.getCollection("godwit-history", Document::class.java)
                        .find(eq("_id", "002-carts")).first().getString("state") shouldBe "APPLIED"
                }
            }
        }

        "the outside scope keeps every other setting of the app's database, and the app's database is unchanged" {
            val registry = CodecRegistries.fromRegistries(MongoClientSettings.getDefaultCodecRegistry())
            val settings = MongoClientSettings.builder()
                .applyConnectionString(ConnectionString(TestMongo.connectionString))
                .writeConcern(WriteConcern.W1)
                .readConcern(ReadConcern.LOCAL)
                .readPreference(ReadPreference.secondaryPreferred())
                .codecRegistry(registry)
                .timeout(30, TimeUnit.SECONDS)
                .build()
            MongoClient.create(settings).use { client ->
                val app = client.getDatabase("shop")

                val scope = OutsideTransactionScope("001-initial-setup", app, StepContext {})

                scope.database.name shouldBe "shop"
                scope.database.writeConcern shouldBe WriteConcern.MAJORITY
                scope.database.codecRegistry shouldBeSameInstanceAs app.codecRegistry
                scope.database.readConcern shouldBe ReadConcern.LOCAL
                scope.database.readPreference shouldBe ReadPreference.secondaryPreferred()
                scope.database.timeout() shouldBe 30_000L
                val carts = scope.collection("carts")
                carts.namespace.fullName shouldBe "shop.carts"
                carts.writeConcern shouldBe WriteConcern.MAJORITY
                carts.codecRegistry shouldBeSameInstanceAs app.codecRegistry
                carts.readConcern shouldBe ReadConcern.LOCAL
                carts.readPreference shouldBe ReadPreference.secondaryPreferred()
                carts.timeout() shouldBe 30_000L
                app.writeConcern shouldBe WriteConcern.W1
            }
        }

        "the scope's majority is the whole write concern: the client's wtimeoutMS and journal do not apply to it" {
            val recorder = CommandRecorder()
            val appConcern = WriteConcern.W1.withWTimeout(5, TimeUnit.SECONDS).withJournal(true)
            TestMongo.database().use { db ->
                MongoClient.create(
                    MongoClientSettings.builder()
                        .applyConnectionString(ConnectionString(TestMongo.connectionString))
                        .applicationName(APP_NAME)
                        .writeConcern(appConcern)
                        .addCommandListener(recorder)
                        .build()
                ).use { client ->
                    val events = CartEvents(client.getDatabase(db.name))
                    val carts = migration("002-carts").outsideTransaction {
                        collection("carts").insertOne(Document("customerId", 1))
                        events.record("carts-created")
                    }

                    Godwit(client, db.name, GodwitConfig(holder = "$APP_NAME/1")).migrate(carts)

                    val inserts = appWrites(recorder).associateBy { it.target }
                    inserts.keys shouldBe setOf("carts", "cart-events")
                    inserts.getValue("carts").writeConcern shouldBe BsonDocument("w", BsonString("majority"))
                    inserts.getValue("cart-events").writeConcern shouldBe appConcern.asDocument()
                }
            }
        }

        "a transactional step keeps the app's write concern: its writes carry none, and the commit carries majority" {
            for (kind in listOf(StepKind.IN_TRANSACTION, StepKind.IN_BATCHES)) {
                withClue(kind) {
                    val recorder = CommandRecorder()
                    TestMongo.database().use { db ->
                        db.database.getCollection("orders", Document::class.java).insertOne(Document("_id", 1))
                        w1Client(recorder).use { client ->
                            val events = CartEvents(client.getDatabase(db.name))
                            var outside: MongoDatabase? = null
                            var inside: MongoDatabase? = null
                            val draft = migration("004-order-status").outsideTransaction { outside = database }
                            val body: TransactionScope.() -> Unit = {
                                inside = database
                                collection("orders").updateOne(session, eq("_id", 1), set("status", "PENDING"))
                                events.record(session, "status-set")
                            }
                            val orderStatus = when (kind) {
                                StepKind.IN_TRANSACTION -> draft.inTransaction { body() }
                                else -> draft.inBatches("orders", exists("status", false), batchSize = 10) { body() }
                            }

                            Godwit(client, db.name, seeded.copy(holder = "$APP_NAME/1")).migrate(orderStatus)

                            outside.shouldNotBeNull().writeConcern shouldBe WriteConcern.MAJORITY
                            inside.shouldNotBeNull().writeConcern shouldBe WriteConcern.W1
                            val writes = appWrites(recorder)
                            writes.map { "${it.name} ${it.target}" } shouldBe
                                listOf("update orders", "insert cart-events")
                            writes.forEach { write ->
                                write.writeConcern shouldBe null
                                write.command.getBoolean("autocommit").value shouldBe false
                            }
                            recorder.commands("commitTransaction").last().writeConcern shouldBe
                                WriteConcern.MAJORITY.asDocument()
                        }
                    }
                }
            }
        }

        "database.runCommand in an outside step sends its document as written: a write concern only when it has one" {
            val recorder = CommandRecorder()
            TestMongo.database().use { db ->
                w1Client(recorder).use { client ->
                    fun ttl(days: Long) = Document("keyPattern", Document("updatedAt", 1))
                        .append("expireAfterSeconds", days * 24 * 60 * 60)
                    val cartExpiry = migration("010-cart-expiry-60-days").outsideTransaction {
                        collection("carts").createIndex(
                            ascending("updatedAt"),
                            IndexOptions().expireAfter(30L, TimeUnit.DAYS)
                        )
                        database.runCommand(Document("collMod", "carts").append("index", ttl(45)))
                        database.runCommand(
                            Document("collMod", "carts").append("index", ttl(60))
                                .append("writeConcern", Document("w", "majority"))
                        )
                    }

                    Godwit(client, db.name, GodwitConfig(holder = "$APP_NAME/1")).migrate(cartExpiry)

                    val collMods = recorder.commands("collMod")
                    collMods shouldHaveSize 2
                    collMods[0].writeConcern shouldBe null
                    collMods[1].writeConcern shouldBe WriteConcern.MAJORITY.asDocument()
                    val index = db.database.getCollection("carts", Document::class.java).listIndexes().toList()
                        .single { it.getString("name") == "updatedAt_1" }
                    index.get("expireAfterSeconds", Number::class.java).toLong() shouldBe 60L * 24 * 60 * 60
                }
            }
        }
    }
}
