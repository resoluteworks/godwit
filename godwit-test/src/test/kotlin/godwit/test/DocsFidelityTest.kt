package godwit.test

import com.example.shop.docs.testing.productPriceRiseWithoutSession
import com.example.shop.docs.transactions_and_sessions.orderPaymentStatusEscaping
import com.example.shop.services.CustomerService
import godwit.core.MigrationFailedException
import godwit.core.blockOf
import godwit.core.docs.Output
import godwit.core.docs.Runner
import godwit.core.docs.StubPaymentGateway
import godwit.core.docs.exceptionMismatch
import godwit.core.docs.quotedBlocks
import godwit.core.docs.quotes
import godwit.core.migration
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.bson.Document

/**
 * The quotes of godwit-core's DocsFidelityTest whose scenario needs the test kit: a step that forgets its session, run
 * through `testGodwit()`, whose client has `SessionEscapeDetector` installed. Each scenario is the docs' own code.
 */
class DocsFidelityTest : StringSpec() {
    init {
        val checks: Map<String, (List<String>) -> Unit> = mapOf(
            "docs/dependencies.md block 4" to { quoted ->
                val db = testGodwit()
                db.database.getCollection("customers", Document::class.java)
                    .insertOne(Document("email", "customer1@example.com"))
                val customers = CustomerService(db.database)
                val failure = shouldThrow<MigrationFailedException> {
                    db.godwit.runIsolated(
                        migration("005-customer-external-ids").inTransaction {
                            count("customersLinked", customers.withoutExternalUserId().size)
                        }
                    )
                }
                exceptionMismatch(quoted, failure).shouldBeNull()
            },
            "docs/testing.md block 2" to { quoted ->
                val db = testGodwit()
                db.database.getCollection("products", Document::class.java).insertOne(Document("priceMinor", 1000L))
                val failure = shouldThrow<MigrationFailedException> {
                    db.godwit.runIsolated(productPriceRiseWithoutSession)
                }
                val escape = failure.cause.shouldBeInstanceOf<SessionEscapeError>()
                quotes(quoted.joinToString("\n"), escape.message.orEmpty()) shouldBe true
            },
            "docs/transactions-and-sessions.md block 1" to { quoted ->
                val db = testGodwit()
                db.database.getCollection("orders", Document::class.java).insertOne(Document("paymentId", "pay-1"))
                val failure = shouldThrow<MigrationFailedException> {
                    db.godwit.runIsolated(orderPaymentStatusEscaping(StubPaymentGateway()))
                }
                exceptionMismatch(quoted, failure).shouldBeNull()
            }
        )

        "every quote that godwit-core's DocsFidelityTest leaves to the test kit is checked here" {
            val testKit = quotedBlocks.filter { (it.check as? Output)?.runner == Runner.TEST_KIT }
            testKit.map { it.toString() }.toSet() shouldBe checks.keys
        }

        for ((quote, check) in checks) {
            "$quote is what the docs' code produces under the test kit" {
                val (doc, ordinal) = quote.split(" block ")
                withClue(quote) { check(blockOf(doc, ordinal.toInt()).lines) }
            }
        }
    }
}
