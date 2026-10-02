package godwit.test

import com.mongodb.event.CommandStartedEvent
import com.mongodb.kotlin.client.MongoCluster
import godwit.core.Godwit
import godwit.core.GodwitConfig
import godwit.core.migration
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk

class TestKitTest : StringSpec() {
    init {
        "the error names the command and the collection, and tells the step to pass its session" {
            val error = SessionEscapeError("update", "orders")
            error.message shouldBe "update on orders ran without the step's session, outside the transaction. " +
                "Pass `session` to the driver call or the service method."
            error.command shouldBe "update"
            error.collection shouldBe "orders"
            error.shouldBeInstanceOf<AssertionError>()
        }

        "a command without a collection is named against the database" {
            SessionEscapeError("dropDatabase", null).message shouldBe "dropDatabase on the database ran without the " +
                "step's session, outside the transaction. Pass `session` to the driver call or the service method."
        }

        "every helper of the kit throws NotImplementedError naming phase P7" {
            val godwit = Godwit(mockk<MongoCluster>(), "shop", GodwitConfig(holder = "shop-7f9c4/1"))
            val migration = migration("001-initial-setup").outsideTransaction { }
            val stubs = listOf<() -> Any?>(
                { testGodwit() },
                { godwit.forget("001-initial-setup") },
                { godwit.rerun(listOf(migration), "001-initial-setup") },
                { godwit.runIsolated(migration) },
                { godwit shouldHaveApplied "001-initial-setup" },
                { SessionEscapeDetector().commandStarted(mockk<CommandStartedEvent>()) }
            )
            stubs.forEachIndexed { index, call ->
                withClue("stub $index") { shouldThrow<NotImplementedError> { call() }.message shouldBe "P7" }
            }
        }
    }
}
