package godwit.core.internal

import godwit.core.HistoryState
import godwit.core.MigrationKind
import godwit.core.Origin
import godwit.core.StepKind
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.bson.BsonInt32
import org.bson.BsonObjectId
import org.bson.Document
import org.bson.types.ObjectId
import java.time.Instant
import java.util.Date
import kotlin.time.Duration.Companion.milliseconds

private val startedAt = Instant.parse("2026-10-02T10:14:00.231Z")
private val finishedAt = Instant.parse("2026-10-02T10:14:03.012Z")
private val lastId = ObjectId("66fcf2a19b1e8a0012a1c0d4")

/** Every field a history document can hold, as the docs' examples show them. */
private val full = Document("_id", "006-order-totals")
    .append("kind", "ONCE")
    .append("description", "Order totals")
    .append("steps", listOf("OUTSIDE_TRANSACTION", "IN_BATCHES"))
    .append("state", "FAILED")
    .append("origin", "RAN")
    .append("attempts", 2)
    .append("transactionRetries", 3)
    .append("counts", Document("ordersUpdated", 20_000L).append("written", 7))
    .append("durationMs", 2710L)
    .append("startedAt", Date.from(startedAt))
    .append("finishedAt", Date.from(finishedAt))
    .append(
        "lastError",
        Document("type", "java.lang.NullPointerException").append("message", "no price")
            .append("stack", "java.lang.NullPointerException: no price").append("step", "IN_BATCHES")
            .append("at", Date.from(finishedAt))
    )
    .append("checkpoint", Document("lastId", lastId).append("batches", 40).append("counts", Document("n", 1L)))
    .append("runCount", 214L)
    .append("lastRunAt", Date.from(finishedAt))
    .append("supersedes", listOf("001-a", "002-b"))
    .append("outOfOrder", true)
    .append("reason", "built by hand")
    .append("holder", "shop-7f9c4/1")
    .append("owner", "5b0f3c6e-2a41-4f7e-9d1c-0e8a7b6c5d4f")
    .append("runId", "0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f")
    .append("godwitVersion", "0.1.0")
    .append("v", 1)

class HistoryEntriesTest : StringSpec() {
    init {
        "a document with every field maps to a HistoryEntry field by field, the checkpoint's lastId keeping its type" {
            val entry = full.toHistoryEntry()

            entry.id shouldBe "006-order-totals"
            entry.kind shouldBe MigrationKind.Once
            entry.state shouldBe HistoryState.FAILED
            entry.origin shouldBe Origin.RAN
            entry.description shouldBe "Order totals"
            entry.steps shouldBe listOf(StepKind.OUTSIDE_TRANSACTION, StepKind.IN_BATCHES)
            entry.attempts shouldBe 2
            entry.transactionRetries shouldBe 3
            entry.counts shouldBe mapOf("ordersUpdated" to 20_000L, "written" to 7L)
            entry.duration shouldBe 2710.milliseconds
            entry.startedAt shouldBe startedAt
            entry.finishedAt shouldBe finishedAt
            val lastError = entry.lastError.shouldNotBeNull()
            lastError.type shouldBe "java.lang.NullPointerException"
            lastError.message shouldBe "no price"
            lastError.stack shouldBe "java.lang.NullPointerException: no price"
            lastError.step shouldBe StepKind.IN_BATCHES
            lastError.at shouldBe finishedAt
            val checkpoint = entry.checkpoint.shouldNotBeNull()
            checkpoint.lastId shouldBe BsonObjectId(lastId)
            checkpoint.batches shouldBe 40
            entry.runCount shouldBe 214L
            entry.lastRunAt shouldBe finishedAt
            entry.supersedes shouldBe listOf("001-a", "002-b")
            entry.outOfOrder shouldBe true
            entry.reason shouldBe "built by hand"
            entry.holder shouldBe "shop-7f9c4/1"
            entry.runId shouldBe "0199a4c2-7b1e-7c3d-9f00-3b2a1c4d5e6f"
            entry.godwitVersion shouldBe "0.1.0"
        }

        "absent fields read as null, 0, empty or false" {
            val entry = Document("_id", "003-file-store").append("kind", "ONCE").append("state", "APPLIED")
                .append("origin", "ADOPTED").toHistoryEntry()

            entry.description.shouldBeNull()
            entry.steps.shouldBeEmpty()
            entry.attempts shouldBe 0
            entry.transactionRetries shouldBe 0
            entry.counts shouldBe emptyMap<String, Long>()
            entry.duration.shouldBeNull()
            entry.startedAt.shouldBeNull()
            entry.finishedAt.shouldBeNull()
            entry.lastError.shouldBeNull()
            entry.checkpoint.shouldBeNull()
            entry.runCount.shouldBeNull()
            entry.lastRunAt.shouldBeNull()
            entry.supersedes.shouldBeEmpty()
            entry.outOfOrder shouldBe false
            entry.reason.shouldBeNull()
            entry.holder.shouldBeNull()
            entry.runId.shouldBeNull()
            entry.godwitVersion.shouldBeNull()
        }

        "a lastError without a message or a step, and a checkpoint with a number lastId" {
            val entry = Document(
                "_id",
                "007-x"
            ).append("kind", "ONCE").append("state", "FAILED").append("origin", "RAN")
                .append("lastError", Document("type", "java.lang.Error").append("at", Date.from(finishedAt)))
                .append("checkpoint", Document("lastId", 41).append("batches", 1))
                .toHistoryEntry()

            val lastError = entry.lastError.shouldNotBeNull()
            lastError.message.shouldBeNull()
            lastError.stack.shouldBeNull()
            lastError.step.shouldBeNull()
            entry.checkpoint.shouldNotBeNull().lastId shouldBe BsonInt32(41)
        }

        "the kind carries a repeatable's stored revision, empty before a run applies it" {
            fun kind(kind: String, revision: String?) =
                Document("_id", "x").append("kind", kind).append("state", "RUNNING").append("origin", "RAN")
                    .apply { revision?.let { append("revision", it) } }.toHistoryEntry().kind

            kind("REPEATABLE", "2026-10-01") shouldBe MigrationKind.Repeatable("2026-10-01")
            kind("REPEATABLE", null) shouldBe MigrationKind.Repeatable("")
            kind("EVERY_START", null) shouldBe MigrationKind.EveryStart
        }
    }
}
