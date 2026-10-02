package godwit.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.net.http.HttpTimeoutException
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private val emptyReport =
    MigrationReport("run", emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null, Duration.ZERO)

private const val LIFETIME_GUIDANCE = "The transaction ran past the server's transaction lifetime " +
    "(transactionLifetimeLimitSeconds, 60 s by default). Process the documents with inBatches, or move work that " +
    "needs no atomicity to outsideTransaction."

/** The exception messages, as the docs quote them. */
class ExceptionsTest : StringSpec() {
    init {
        "InvalidMigrationsException lists every problem" {
            val exception = InvalidMigrationsException(listOf("rep has a blank revision", "batched has batchSize 0"))
            exception.message shouldBe "Invalid migrations:\n- rep has a blank revision\n- batched has batchSize 0"
            exception.shouldBeInstanceOf<GodwitException>()
        }

        "PlanConflictException lists every conflict" {
            val line = "007-product-slugs is applied, but the list does not declare it (UnknownApplied.FAIL)"
            PlanConflictException(listOf(line)).message shouldBe
                "Migrations cannot run against this database:\n- $line"
        }

        "UntrackedDatabaseException names the collections and both ways forward" {
            UntrackedDatabaseException(listOf("customers", "orders", "products", "schema-log")).message shouldBe
                "The database has collections [customers, orders, products, schema-log] but no godwit history. " +
                "Configure GodwitConfig.adoptApplied to adopt the migrations already applied, or set " +
                "UntrackedDatabase.RUN_ALL to run every migration."
        }

        "MigrationFailedException carries the cause's message, then the guidance line when there is one" {
            val cause = HttpTimeoutException("request timed out")
            val plain = MigrationFailedException("005-x", StepKind.OUTSIDE_TRANSACTION, emptyReport, cause, null)
            plain.message shouldBe "Migration 005-x failed in OUTSIDE_TRANSACTION: request timed out"
            plain.cause shouldBeSameInstanceAs cause
            plain.report shouldBeSameInstanceAs emptyReport
            plain.id shouldBe "005-x"
            plain.step shouldBe StepKind.OUTSIDE_TRANSACTION

            val guided = MigrationFailedException(
                "007-customer-email-lower",
                StepKind.IN_TRANSACTION,
                emptyReport,
                IllegalStateException("Command failed with error 251 (NoSuchTransaction)"),
                LIFETIME_GUIDANCE
            )
            guided.message shouldBe "Migration 007-customer-email-lower failed in IN_TRANSACTION: " +
                "Command failed with error 251 (NoSuchTransaction)\n$LIFETIME_GUIDANCE"
        }

        "LockTimeoutException names the holder, or nobody when the lock was released as the wait ended" {
            val holder = LockHolder("shop-7f9c4/1", "run", Instant.EPOCH, Instant.EPOCH)
            LockTimeoutException(holder, 2.minutes + 214.milliseconds).message shouldBe
                "Waited 2m 0.214s for the migration lock, held by shop-7f9c4/1"
            LockTimeoutException(null, 10.minutes).message shouldBe "Waited 10m for the migration lock, held by nobody"
        }

        "LockLostException names the migration it was running, and keeps a step error as its cause" {
            val alone = LockLostException(null)
            alone.message shouldBe "Lost the migration lock"
            alone.cause shouldBe null

            val cause = IllegalStateException("step failed")
            val withStep = LockLostException("006-order-totals", cause)
            withStep.message shouldBe "Lost the migration lock while running 006-order-totals"
            withStep.cause shouldBeSameInstanceAs cause
            withStep.id shouldBe "006-order-totals"
        }

        "TransactionsUnsupportedException lists the due migrations and the fix" {
            TransactionsUnsupportedException(listOf("004-order-status", "reference-countries")).message shouldBe
                "Migrations [004-order-status, reference-countries] need transactions, which a standalone mongod " +
                "does not support. Run a single-node replica set: start mongod with --replSet rs0, then run " +
                "rs.initiate() once."
        }

        "PendingMigrationsException lists what is pending and the problems" {
            PendingMigrationsException(listOf("009-order-payment-status"), emptyList()).message shouldBe
                "The database is not up to date. Pending: [009-order-payment-status]. Problems: []"
        }

        "SearchIndexNotReadyException names the index, the collection and the wait" {
            val exception = SearchIndexNotReadyException("products", "product-search", 5.minutes)
            exception.message shouldBe "Search index product-search on products was not queryable after 5m"
            exception.waited shouldBe 5.minutes
        }
    }
}
