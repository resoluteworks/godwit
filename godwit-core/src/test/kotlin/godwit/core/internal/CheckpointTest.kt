package godwit.core.internal

import com.mongodb.MongoClientSettings
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.bson.BsonBinary
import org.bson.BsonBoolean
import org.bson.BsonDateTime
import org.bson.BsonDbPointer
import org.bson.BsonDecimal128
import org.bson.BsonDocument
import org.bson.BsonDocumentReader
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
import org.bson.codecs.DecoderContext
import org.bson.codecs.configuration.CodecRegistries
import org.bson.types.Decimal128
import org.bson.types.ObjectId
import java.time.Instant
import java.util.Date
import java.util.UUID

/** The codecs of a client without a UUID representation. */
private val defaultCodecs = MongoClientSettings.getDefaultCodecRegistry()

/** The `_id` type classes, how "Committed batch" prints a `lastId`, and the checkpoint a history document holds. */
class CheckpointTest : StringSpec() {
    init {
        "typeClass names every type an _id can have by its \$type alias, every number number and a symbol string" {
            val classes = mapOf<BsonValue, String>(
                BsonDouble(1.5) to "number",
                BsonInt32(1) to "number",
                BsonInt64(1) to "number",
                BsonDecimal128(Decimal128(1)) to "number",
                BsonString("a") to "string",
                BsonSymbol("s") to "string",
                BsonDocument() to "object",
                BsonBinary(byteArrayOf(1)) to "binData",
                BsonObjectId() to "objectId",
                BsonBoolean.TRUE to "bool",
                BsonDateTime(0) to "date",
                BsonNull.VALUE to "null",
                BsonDbPointer("shop.orders", ObjectId()) to "dbPointer",
                BsonJavaScript("1") to "javascript",
                BsonJavaScriptWithScope("1", BsonDocument()) to "javascriptWithScope",
                BsonTimestamp(1, 1) to "timestamp",
                BsonMinKey() to "minKey",
                BsonMaxKey() to "maxKey"
            )

            // MongoDB refuses an array, a regular expression and undefined as _id.
            classes.keys.map { it.bsonType }.toSet() shouldBe BsonType.entries.toSet() -
                setOf(BsonType.END_OF_DOCUMENT, BsonType.ARRAY, BsonType.REGULAR_EXPRESSION, BsonType.UNDEFINED)
            classes.forEach { (value, alias) -> typeClass(value) shouldBe alias }
            ID_TYPE_CLASSES shouldBe classes.values.toSet()
        }

        "the other-type filter lists the \$type aliases outside the run's class, symbol leaving with string" {
            fun aliases(type: String) =
                otherIdTypes(type).getDocument("_id").getArray("\$type").map { it.asString().value }

            aliases("number") shouldBe listOf(
                "string",
                "symbol",
                "object",
                "binData",
                "objectId",
                "bool",
                "date",
                "null",
                "dbPointer",
                "javascript",
                "javascriptWithScope",
                "timestamp",
                "minKey",
                "maxKey"
            )
            aliases("string") shouldBe listOf("number") + aliases("number").drop(2)
            aliases("objectId") shouldBe listOf("number", "string", "symbol") + aliases("number").drop(2) - "objectId"
        }

        "isNaN is true for a double or decimal NaN only" {
            isNaN(BsonDouble(Double.NaN)) shouldBe true
            isNaN(BsonDecimal128(Decimal128.NaN)) shouldBe true
            isNaN(BsonDecimal128(Decimal128.NEGATIVE_NaN)) shouldBe true
            isNaN(BsonDouble(Double.NEGATIVE_INFINITY)) shouldBe false
            isNaN(BsonDouble(2.5)) shouldBe false
            isNaN(BsonDecimal128(Decimal128(3))) shouldBe false
            isNaN(BsonInt32(0)) shouldBe false
            isNaN(BsonString("NaN")) shouldBe false
        }

        "a lastId prints as an ObjectId's hex string, a string as it is, anything else as relaxed Extended JSON" {
            loggable(BsonObjectId(ObjectId("66fcf2a19b1e8a0012a1c4bc"))) shouldBe "66fcf2a19b1e8a0012a1c4bc"
            loggable(BsonString("mkt-1042")) shouldBe "mkt-1042"
            loggable(BsonInt32(499)) shouldBe "499"
            loggable(BsonInt64(10_049)) shouldBe "10049"
            loggable(BsonDouble(2.5)) shouldBe "2.5"
            loggable(BsonDouble(Double.NaN)) shouldBe """{"${'$'}numberDouble": "NaN"}"""
            loggable(BsonDecimal128(Decimal128(3))) shouldBe """{"${'$'}numberDecimal": "3"}"""
            loggable(BsonDateTime(Instant.parse("2026-10-02T10:14:05.120Z").toEpochMilli())) shouldBe
                """{"${'$'}date": "2026-10-02T10:14:05.12Z"}"""
            loggable(BsonDocument("region", BsonString("eu")).append("n", BsonInt32(7))) shouldBe
                """{"region": "eu", "n": 7}"""
        }

        "the checkpoint of a history document keeps lastId's BSON type; an absent counts reads as empty" {
            val lastId = ObjectId("66fcf2a19b1e8a0012a1c0d4")
            Document("_id", "006-order-totals").checkpoint(defaultCodecs).shouldBeNull()
            Document(
                "checkpoint",
                Document("lastId", lastId).append("batches", 40).append("counts", Document("ordersUpdated", 20_000L))
            ).checkpoint(defaultCodecs) shouldBe Checkpoint(BsonObjectId(lastId), 40, mapOf("ordersUpdated" to 20_000L))
            Document("checkpoint", Document("lastId", 41L).append("batches", 1)).checkpoint(defaultCodecs) shouldBe
                Checkpoint(BsonInt64(41), 1, emptyMap())
            Document(
                "checkpoint",
                Document("lastId", Date(0)).append("batches", 2)
            ).checkpoint(defaultCodecs)!!.lastId shouldBe
                BsonDateTime(0)
        }

        "a UUID lastId reads back as the binary it was, with the UUID representation of the codecs that decoded it" {
            val id = UUID.fromString("0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f")
            for (representation in listOf(UuidRepresentation.STANDARD, UuidRepresentation.JAVA_LEGACY)) {
                val codecs = CodecRegistries.withUuidRepresentation(defaultCodecs, representation)
                val lastId = BsonBinary(id, representation)
                val stored = BsonDocument("checkpoint", BsonDocument("lastId", lastId).append("batches", BsonInt32(3)))
                val decoded = codecs.get(Document::class.java).decode(
                    BsonDocumentReader(stored),
                    DecoderContext.builder().build()
                )

                decoded.get("checkpoint", Document::class.java)["lastId"] shouldBe id
                decoded.checkpoint(codecs) shouldBe Checkpoint(lastId, 3, emptyMap())
            }
        }
    }
}
