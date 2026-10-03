package godwit.core

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.ne
import com.mongodb.client.model.Filters.type
import com.mongodb.kotlin.client.MongoClient
import godwit.core.fixtures.GodwitFixture
import godwit.core.fixtures.LogCapture
import godwit.core.fixtures.TestMongo
import godwit.core.fixtures.line
import godwit.core.fixtures.probe
import godwit.core.fixtures.seeded
import godwit.core.internal.ID_TYPE_CLASSES
import godwit.core.internal.otherIdTypes
import godwit.core.internal.typeClass
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.bson.BsonArray
import org.bson.BsonBinary
import org.bson.BsonBoolean
import org.bson.BsonDateTime
import org.bson.BsonDbPointer
import org.bson.BsonDecimal128
import org.bson.BsonDocument
import org.bson.BsonDouble
import org.bson.BsonInt32
import org.bson.BsonInt64
import org.bson.BsonJavaScript
import org.bson.BsonJavaScriptWithScope
import org.bson.BsonMaxKey
import org.bson.BsonMinKey
import org.bson.BsonNull
import org.bson.BsonObjectId
import org.bson.BsonString
import org.bson.BsonSymbol
import org.bson.BsonTimestamp
import org.bson.BsonType
import org.bson.BsonValue
import org.bson.Document
import org.bson.UuidRepresentation
import org.bson.conversions.Bson
import org.bson.types.Decimal128
import org.bson.types.ObjectId
import java.util.UUID

/** The cause's message when [collection]'s pending documents have `_id`s of the run's type and of [other]. */
private fun mixed(collection: String, runType: String, other: String) =
    "The documents of $collection that match pending have _ids of two types, $runType and $other. " +
        "inBatches pages by _id with \$gt, which compares values of one BSON type (all numeric types count as one), " +
        "so it would skip the documents of the other type. Select one _id type per migration with " +
        "Filters.type(\"_id\", ...) in pending; a migration that has a checkpoint keeps the type of its lastId."

/** Documents whose `_id`s are [ids], with their BSON types, through the test's own client. */
private fun GodwitFixture.insert(collection: String, ids: List<BsonValue>) =
    database.getCollection(collection, BsonDocument::class.java).insertMany(ids.map { BsonDocument("_id", it) })

/** The `lastId` of [id]'s stored checkpoint with its BSON type; null when it has no checkpoint. */
private fun GodwitFixture.storedLastId(id: String): BsonValue? =
    database.getCollection("godwit-history", BsonDocument::class.java)
        .find(eq("_id", id)).first().getDocument("checkpoint", null)?.get("lastId")

/**
 * A run of [id] that was interrupted after a page whose last `_id` was [lastId]: a RUNNING document with that
 * checkpoint, as the crashed run left it.
 */
private fun GodwitFixture.plantInterrupted(id: String, lastId: BsonValue) {
    database.getCollection("godwit-history", BsonDocument::class.java).insertOne(
        BsonDocument("_id", BsonString(id))
            .append("kind", BsonString("ONCE"))
            .append("steps", BsonArray(listOf(BsonString("IN_BATCHES"))))
            .append("state", BsonString("RUNNING"))
            .append("origin", BsonString("RAN"))
            .append("attempts", BsonInt32(1))
            .append("owner", BsonString("token-of-a-crashed-run"))
            .append(
                "checkpoint",
                BsonDocument("lastId", lastId).append("batches", BsonInt32(1)).append("counts", BsonDocument())
            )
    )
}

/** `_id` paging over each BSON type, and the two checks that turn a collection of mixed `_id` types into a failure. */
class BatchesIdTypeTest : StringSpec() {
    init {
        "ObjectId, string and number _ids each page in _id order, the checkpoint keeping the _id's BSON type" {
            val cases = mapOf(
                "object-ids" to (1..5).map { BsonObjectId(ObjectId("%024x".format(it))) },
                "strings" to (1..5).map { BsonString("mkt-$it") },
                "numbers" to (1..5).map { BsonInt32(it) }
            )
            val logged = mapOf(
                "object-ids" to
                    listOf("000000000000000000000002", "000000000000000000000004", "000000000000000000000005"),
                "strings" to listOf("mkt-2", "mkt-4", "mkt-5"),
                "numbers" to listOf("2", "4", "5")
            )
            GodwitFixture(config = seeded).use { f ->
                for ((name, ids) in cases) {
                    withClue(name) {
                        val id = "006-$name"
                        f.insert(name, ids)
                        val seen = mutableListOf<List<Any?>>()
                        val checkpoints = mutableListOf<BsonValue?>()
                        val totals = migration(id).inBatches(name, Document(), batchSize = 2) { page ->
                            seen += page.map { it["_id"] }
                            checkpoints += f.storedLastId(id)
                            probe(name, page)
                        }

                        LogCapture().use { logs ->
                            f.godwit.migrate(totals)[id].batches shouldBe 3
                            logs.events("Committed batch").map { it.line } shouldBe
                                logged.getValue(name).mapIndexed { index, lastId ->
                                    "Committed batch id=$id batch=${index + 1} lastId=$lastId"
                                }
                        }

                        seen.map { it.size } shouldBe listOf(2, 2, 1)
                        seen.flatten().map { it.toString() } shouldBe
                            ids.map(::decoded)
                        checkpoints shouldBe listOf(null, ids[1], ids[3])
                        f.collection(name).countDocuments(eq("probe", 1)) shouldBe 5L
                    }
                }
            }
        }

        "int32, int64, double and decimal _ids page as one type: a checkpoint of one numeric type continues" {
            GodwitFixture(config = seeded).use { f ->
                val ids = listOf(
                    BsonInt32(1),
                    BsonInt64(2),
                    BsonDouble(2.5),
                    BsonDecimal128(Decimal128(3)),
                    BsonInt32(4),
                    BsonInt64(5),
                    BsonDouble(6.5)
                )
                f.insert("orders", ids.shuffled())
                val checkpoints = mutableListOf<BsonValue?>()
                val totals = migration("006-order-totals").inBatches("orders", Document(), batchSize = 3) { page ->
                    checkpoints += f.storedLastId("006-order-totals")
                    probe("orders", page)
                    count("ordersUpdated", page.size)
                }

                LogCapture().use { logs ->
                    val outcome = f.godwit.migrate(totals)["006-order-totals"]

                    outcome.batches shouldBe 3
                    outcome.count("ordersUpdated") shouldBe 7L
                    logs.events("Committed batch").map { it.line } shouldBe listOf(
                        "Committed batch id=006-order-totals batch=1 lastId=2.5",
                        "Committed batch id=006-order-totals batch=2 lastId=5",
                        "Committed batch id=006-order-totals batch=3 lastId=6.5"
                    )
                }
                checkpoints shouldBe listOf(null, BsonDouble(2.5), BsonInt64(5))
                f.collection("orders").countDocuments(eq("probe", 1)) shouldBe 7L
            }
        }

        "a page that mixes _id types fails the run before the step sees it, naming both types" {
            GodwitFixture(config = seeded).use { f ->
                f.insert("orders", listOf(BsonInt32(1), BsonInt32(2)) + ('a'..'e').map { BsonString("$it") })
                var stepRan = false
                val totals = migration("006-order-totals").inBatches("orders", Document(), batchSize = 3) {
                    stepRan = true
                }

                val failure = shouldThrow<MigrationFailedException> { f.godwit.migrate(totals) }

                failure.step shouldBe StepKind.IN_BATCHES
                failure.cause.shouldBeInstanceOf<IllegalStateException>().message shouldBe
                    mixed("orders", "number", "string")
                failure.message shouldBe "Migration 006-order-totals failed in IN_BATCHES: " +
                    mixed("orders", "number", "string")
                stepRan shouldBe false
                val stored = f.stored("006-order-totals").shouldNotBeNull()
                stored.getString("state") shouldBe "FAILED"
                stored.containsKey("checkpoint") shouldBe false
            }
        }

        "another _id type before the last commit fails the run; narrowed to its checkpoint's type, it applies" {
            GodwitFixture(config = seeded).use { f ->
                val strings = (1..7).map { BsonString("s$it") }
                val objectIds = (1..3).map { BsonObjectId(ObjectId("%024x".format(it))) }
                f.insert("orders", strings + objectIds)
                val seen = mutableListOf<String>()
                fun totals(pending: Bson) =
                    migration("006-order-totals").inBatches("orders", pending, batchSize = 3) { page ->
                        seen += page.map { it["_id"].toString() }
                        probe("orders", page)
                        count("ordersUpdated", page.size)
                    }

                // Pages 1 and 2 hold strings and commit. Page 3 holds the last string and would be the last page: the
                // check, which runs before the step sees that page, finds the ObjectId orders.
                val first = LogCapture().use { logs ->
                    val failure = shouldThrow<MigrationFailedException> { f.godwit.migrate(totals(Document())) }
                    logs.events("Migration failed").single().line shouldBe
                        "Migration failed id=006-order-totals step=IN_BATCHES attempts=1 " +
                        "error=java.lang.IllegalStateException: " + mixed("orders", "string", "objectId")
                    failure
                }
                first.step shouldBe StepKind.IN_BATCHES
                first.cause!!.message shouldBe mixed("orders", "string", "objectId")
                seen shouldBe strings.take(6).map { it.value }
                f.storedLastId("006-order-totals") shouldBe BsonString("s6")
                f.stored("006-order-totals")!!.get("checkpoint", Document::class.java).getInteger("batches") shouldBe 2
                f.collection("orders").countDocuments(eq("probe", 1)) shouldBe 6L

                // Narrowed to the other type, the run would resume after a string and find nothing: it fails instead.
                seen.clear()
                val wrong = shouldThrow<MigrationFailedException> {
                    f.godwit.migrate(totals(type("_id", BsonType.OBJECT_ID)))
                }
                wrong.cause!!.message shouldBe mixed("orders", "string", "objectId")
                seen.shouldBeEmpty()
                val failed = f.stored("006-order-totals").shouldNotBeNull()
                failed.getString("state") shouldBe "FAILED"
                failed.getInteger("attempts") shouldBe 2
                f.storedLastId("006-order-totals") shouldBe BsonString("s6")

                // Narrowed to the type of its checkpoint, it applies; a new migration takes the ObjectId orders.
                seen.clear()
                val objectIdTotals = migration("017-object-id-order-totals")
                    .inBatches("orders", type("_id", BsonType.OBJECT_ID), batchSize = 3) { page ->
                        seen += page.map { it["_id"].toString() }
                        probe("orders", page)
                        count("ordersUpdated", page.size)
                    }
                val report = f.godwit.migrate(totals(type("_id", BsonType.STRING)), objectIdTotals)

                seen shouldBe listOf("s7") + objectIds.map { it.value.toHexString() }
                report["006-order-totals"].attempts shouldBe 3
                report["006-order-totals"].batches shouldBe 3
                report["006-order-totals"].count("ordersUpdated") shouldBe 7L
                report["017-object-id-order-totals"].batches shouldBe 1
                f.collection("orders").countDocuments(eq("probe", 1)) shouldBe 10L
            }
        }

        "the check before the last commit finds a pending _id of every other type" {
            val others = listOf<Pair<String, BsonValue>>(
                "string" to BsonString("x"),
                "string" to BsonSymbol("s"),
                "object" to BsonDocument("a", BsonInt32(1)),
                "binData" to BsonBinary(byteArrayOf(1)),
                "objectId" to BsonObjectId(),
                "bool" to BsonBoolean.TRUE,
                "date" to BsonDateTime(0),
                "null" to BsonNull.VALUE,
                "dbPointer" to BsonDbPointer("shop.orders", ObjectId()),
                "javascript" to BsonJavaScript("1"),
                "javascriptWithScope" to BsonJavaScriptWithScope("1", BsonDocument()),
                "timestamp" to BsonTimestamp(1, 1),
                "minKey" to BsonMinKey(),
                "maxKey" to BsonMaxKey()
            )
            others.map { it.first }.toSet() + "number" shouldBe ID_TYPE_CLASSES
            // Runs of numbers resumed after 0, each meeting one _id of another type; a run of strings meeting a number.
            val cases = others.map { (type, other) ->
                Triple(type, BsonInt32(0) as BsonValue, listOf(BsonInt32(1), BsonInt32(2), other))
            } + Triple("number", BsonString("a"), listOf(BsonString("b"), BsonInt32(1)))
            for ((other, lastId, ids) in cases) {
                withClue("$other ${ids.last()}") {
                    GodwitFixture(config = seeded).use { f ->
                        f.insert("orders", ids)
                        f.plantInterrupted("006-order-totals", lastId)
                        val totals = migration("006-order-totals").inBatches("orders", Document(), batchSize = 10) {
                            probe("orders", it)
                        }

                        val failure = shouldThrow<MigrationFailedException> { f.godwit.migrate(totals) }

                        failure.cause!!.message shouldBe mixed("orders", typeClass(lastId), other)
                        f.collection("orders").countDocuments(eq("probe", 1)) shouldBe 0L
                        f.storedLastId("006-order-totals") shouldBe lastId
                    }
                }
            }
        }

        "the check for other _id types reads no document of the run's type, for every type an _id can have" {
            val samples = mapOf<String, List<BsonValue>>(
                "number" to listOf(
                    BsonDouble(Double.NaN),
                    BsonInt32(1),
                    BsonInt64(2),
                    BsonDouble(2.5),
                    BsonDecimal128(Decimal128(3))
                ),
                "string" to listOf(BsonString("a"), BsonSymbol("b"), BsonString("c")),
                "object" to listOf(BsonDocument("a", BsonInt32(1)), BsonDocument("b", BsonInt32(2))),
                "binData" to listOf(BsonBinary(byteArrayOf(1)), BsonBinary(UUID.randomUUID())),
                "objectId" to listOf(BsonObjectId(), BsonObjectId()),
                "bool" to listOf(BsonBoolean.FALSE, BsonBoolean.TRUE),
                "date" to listOf(BsonDateTime(0), BsonDateTime(1)),
                "null" to listOf(BsonNull.VALUE),
                "dbPointer" to listOf(
                    BsonDbPointer("shop.orders", ObjectId()),
                    BsonDbPointer("shop.carts", ObjectId())
                ),
                "javascript" to listOf(BsonJavaScript("1"), BsonJavaScript("2")),
                "javascriptWithScope" to listOf(BsonJavaScriptWithScope("1", BsonDocument())),
                "timestamp" to listOf(BsonTimestamp(1, 1), BsonTimestamp(2, 1)),
                "minKey" to listOf(BsonMinKey()),
                "maxKey" to listOf(BsonMaxKey())
            )
            samples.keys shouldBe ID_TYPE_CLASSES
            GodwitFixture(config = seeded).use { f ->
                for ((type, ids) in samples) {
                    withClue(type) {
                        ids.forEach { typeClass(it) shouldBe type }
                        f.insert("ids-$type", ids)
                        val explained = f.database.runCommand(
                            Document(
                                "explain",
                                Document("find", "ids-$type").append("filter", otherIdTypes(type)).append("limit", 1)
                            ).append("verbosity", "executionStats")
                        )
                        val stats = explained.get("executionStats", Document::class.java)

                        stats.getInteger("nReturned") shouldBe 0
                        stats.getInteger("totalDocsExamined") shouldBe 0
                    }
                }
            }
        }

        "the check for other _id types runs outside any transaction, between the last page's two transactions" {
            GodwitFixture(appName = "batches-type-check", config = seeded).use { f ->
                f.insert("orders", (1..3).map { BsonInt32(it) })
                val totals = migration("006-order-totals").inBatches("orders", Document(), batchSize = 2) { page ->
                    probe("orders", page)
                }
                f.recorder.clear()

                f.godwit.migrate(totals)["006-order-totals"].batches shouldBe 2

                val trail = f.recorder.commands.filter {
                    it.name == "commitTransaction" ||
                        (it.name == "find" && it.command.getString("find").value == "orders")
                }
                trail.map {
                    when {
                        it.name == "commitTransaction" -> "commit"
                        it.command.containsKey("txnNumber") -> "page read"
                        else -> "check"
                    }
                } shouldBe listOf("page read", "commit", "page read", "commit", "check", "page read", "commit")
                val check = trail[4].command
                check.getDocument("filter") shouldBe
                    BsonDocument("\$and", BsonArray(listOf(BsonDocument(), otherIdTypes("number"))))
                check.getDocument("readConcern").getString("level").value shouldBe "majority"
                f.collection("orders").countDocuments(eq("probe", 1)) shouldBe 3L
            }
        }

        "string and symbol _ids page as one type, because a symbol compares as a string" {
            GodwitFixture(config = seeded).use { f ->
                val ids = listOf(BsonString("a"), BsonSymbol("b"), BsonString("c"), BsonSymbol("d"), BsonString("e"))
                f.insert("orders", ids.shuffled())
                val seen = mutableListOf<String>()
                val checkpoints = mutableListOf<BsonValue?>()
                val totals = migration("006-order-totals").inBatches("orders", Document(), batchSize = 2) { page ->
                    seen += page.map { it["_id"].toString() }
                    checkpoints += f.storedLastId("006-order-totals")
                    probe("orders", page)
                }

                f.godwit.migrate(totals)["006-order-totals"].batches shouldBe 3

                seen shouldBe listOf("a", "b", "c", "d", "e")
                checkpoints shouldBe listOf(null, BsonSymbol("b"), BsonSymbol("d"))
                f.collection("orders").countDocuments(eq("probe", 1)) shouldBe 5L
            }
        }

        "a NaN _id sorts before every other number, and the page after it continues with every other number" {
            for (nan in listOf<BsonValue>(BsonDouble(Double.NaN), BsonDecimal128(Decimal128.NaN))) {
                withClue(nan) {
                    GodwitFixture(config = seeded).use { f ->
                        val ids = listOf(
                            nan,
                            BsonDouble(Double.NEGATIVE_INFINITY),
                            BsonInt32(1),
                            BsonInt64(2),
                            BsonDouble(Double.POSITIVE_INFINITY)
                        )
                        f.insert("orders", ids.shuffled())
                        val seen = mutableListOf<String>()
                        val checkpoints = mutableListOf<BsonValue?>()
                        val totals = migration("006-order-totals")
                            .inBatches("orders", Document(), batchSize = 1) { page ->
                                seen += page.map { it["_id"].toString() }
                                checkpoints += f.storedLastId("006-order-totals")
                                probe("orders", page)
                                count("ordersUpdated", page.size)
                            }

                        val outcome = f.godwit.migrate(totals)["006-order-totals"]

                        seen shouldBe listOf("NaN", "-Infinity", "1", "2", "Infinity")
                        checkpoints.take(2) shouldBe listOf(null, nan)
                        outcome.batches shouldBe 5
                        outcome.count("ordersUpdated") shouldBe 5L
                        f.collection("orders").countDocuments(eq("probe", 1)) shouldBe 5L
                    }
                }
            }
        }

        "pending renders and the page decodes with the app's codecs; UUID _ids keep their subtype in the checkpoint" {
            for (representation in listOf(UuidRepresentation.STANDARD, UuidRepresentation.JAVA_LEGACY)) {
                withClue(representation) {
                    TestMongo.database().use { db ->
                        val settings = MongoClientSettings.builder()
                            .applyConnectionString(ConnectionString(TestMongo.connectionString))
                            .applicationName("batches-uuid")
                            .uuidRepresentation(representation)
                            .build()
                        MongoClient.create(settings).use { client ->
                            uuidPages(db.name, client, representation)
                        }
                    }
                }
            }
        }
    }
}

/** [value] as a step receives it in a `Document`, printed. */
private fun decoded(value: BsonValue): String = Document.parse(BsonDocument("_id", value).toJson())["_id"].toString()

/**
 * Pages six customers with UUID `_id`s through [client], whose UUID [representation] encodes them, with a `pending`
 * that names a UUID and so renders only with the client's codecs. The second page fails once; the run that resumes
 * reads its checkpoint back with the subtype the `_id`s have, and continues after it.
 */
private fun uuidPages(databaseName: String, client: MongoClient, representation: UuidRepresentation) {
    val godwit = Godwit(client, databaseName, seeded.copy(holder = "batches-uuid/1"))
    val customers = client.getDatabase(databaseName).getCollection("customers", Document::class.java)
    // The _id index orders the binaries by their bytes, which the representation decides.
    val ids = (1..6).map { UUID.randomUUID() }.sortedWith(compareBy(BYTES) { BsonBinary(it, representation).data })
    val excludedOwner = UUID.randomUUID()
    customers.insertMany(
        ids.mapIndexed { index, id ->
            Document("_id", id).append("ownerId", if (index == 2) excludedOwner else UUID.randomUUID())
        }
    )
    var broken = true
    val seen = mutableListOf<List<Any?>>()
    val linking = migration("005-customer-links")
        .inBatches("customers", ne("ownerId", excludedOwner), batchSize = 2) { page ->
            seen += page.map { it["_id"] }
            probe("customers", page)
            if (broken && seen.size == 2) error("the second page fails once")
        }

    shouldThrow<MigrationFailedException> { godwit.migrate(linking) }
    godwit.history().single().checkpoint.shouldNotBeNull().lastId shouldBe BsonBinary(ids[1], representation)
    broken = false
    godwit.migrate(linking)["005-customer-links"].batches shouldBe 3

    seen shouldBe listOf(
        listOf(ids[0], ids[1]),
        listOf(ids[3], ids[4]),
        listOf(ids[3], ids[4]),
        listOf(ids[5])
    )
    customers.countDocuments(eq("probe", 1)) shouldBe 5L
    customers.find(eq("_id", ids[2])).first().containsKey("probe") shouldBe false
}

/** Byte arrays in the order the server sorts binaries of one length and subtype: unsigned, byte by byte. */
private val BYTES = Comparator<ByteArray> { a, b -> java.util.Arrays.compareUnsigned(a, b) }
