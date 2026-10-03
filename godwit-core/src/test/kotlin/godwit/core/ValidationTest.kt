package godwit.core

import com.mongodb.client.model.Filters.exists
import com.mongodb.kotlin.client.MongoCluster
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.MockKException
import io.mockk.mockk

private const val ID_RULE = "ids match [A-Za-z0-9][A-Za-z0-9._-]{0,127}"
private const val LAST_RULE = "list repeatable and every-start migrations after every once-only migration"

private fun once(id: String, supersedes: List<String> = emptyList()): Migration =
    migration(id, supersedes = supersedes).outsideTransaction { }

private fun rep(id: String, revision: String = "2026-10-01"): Migration = repeatable(id, revision).inTransaction { }

private fun every(id: String): Migration = everyStart(id).outsideTransaction { }

private val initialSetup = once("001-initial-setup")
private val carts = once("002-carts")
private val fileStore = once("003-file-store")
private val orderTotals = once("006-order-totals")
private val referenceCountries = rep("reference-countries")
private val bootstrapCustomers = every("bootstrap-customers")

/** The shop's list at release 1.3, which is valid. */
private val shopList = listOf(
    initialSetup,
    carts,
    fileStore,
    once("004-order-status"),
    once("005-customer-external-ids"),
    orderTotals,
    referenceCountries,
    bootstrapCustomers
)

/** The problem lines [validateMigrations] reports for [migrations], or an empty list when it passes. */
private fun problems(migrations: List<Migration>): List<String> = runCatching { validateMigrations(migrations) }.fold(
    onSuccess = { emptyList() },
    onFailure = { (it as InvalidMigrationsException).problems }
)

private val godwit = Godwit(mockk<MongoCluster>(), "shop", GodwitConfig(holder = "shop-7f9c4/1"))

class ValidationTest : StringSpec() {
    init {
        "a valid list passes, and so does an empty one" {
            shouldNotThrowAny { validateMigrations(shopList) }
            shouldNotThrowAny { validateMigrations(emptyList()) }
        }

        "rule 1: an id outside the format" {
            problems(listOf(migration("007 product slugs: from SKU").inTransaction { })) shouldBe
                listOf("invalid id \"007 product slugs: from SKU\": $ID_RULE")
            problems(listOf(once(""), once("-starts-with-dash"), once("a".repeat(129)))) shouldBe listOf(
                "invalid id \"\": $ID_RULE",
                "invalid id \"-starts-with-dash\": $ID_RULE",
                "invalid id \"${"a".repeat(129)}\": $ID_RULE"
            )
            problems(listOf(once("a".repeat(128)), once("2026.10.01_a-B"))) shouldBe emptyList()
        }

        "rule 1: ids named in supersedes lists too, each after the id that names it" {
            problems(
                listOf(
                    once("bad one", supersedes = listOf("bad two", "001-fine")),
                    once("100-baseline", supersedes = listOf("bad three"))
                )
            ) shouldBe listOf(
                "invalid id \"bad one\": $ID_RULE",
                "invalid id \"bad two\": $ID_RULE",
                "invalid id \"bad three\": $ID_RULE"
            )
            problems(
                listOf(
                    once("100-baseline", supersedes = listOf("old id")),
                    once("101-carts-baseline", supersedes = listOf("old id"))
                )
            ) shouldBe listOf(
                "invalid id \"old id\": $ID_RULE",
                "duplicate id old id: named in the supersedes lists of 100-baseline and 101-carts-baseline"
            )
        }

        "rule 2: the same id at two positions, also breaking the numbering" {
            problems(listOf(initialSetup, carts, fileStore, carts)) shouldBe listOf(
                "duplicate id 002-carts at positions 2 and 4",
                "002-carts is listed after 003-file-store, but its numeric prefix 2 is not greater than 3"
            )
        }

        "rule 2: an id at three positions is reported against its first" {
            problems(listOf(referenceCountries, rep("reference-countries"), rep("reference-countries"))) shouldBe
                listOf(
                    "duplicate id reference-countries at positions 1 and 2",
                    "duplicate id reference-countries at positions 1 and 3"
                )
        }

        "rule 2: an id named in two supersedes lists, or twice in one" {
            problems(
                listOf(
                    once("100-baseline", supersedes = listOf("001-initial-setup", "002-carts")),
                    once("101-carts-baseline", supersedes = listOf("002-carts"))
                )
            ) shouldBe
                listOf("duplicate id 002-carts: named in the supersedes lists of 100-baseline and 101-carts-baseline")

            problems(
                listOf(once("100-baseline", supersedes = listOf("001-initial-setup", "001-initial-setup")))
            ) shouldBe
                listOf("duplicate id 001-initial-setup: named twice in the supersedes list of 100-baseline")
        }

        "rule 2: problems follow list order, at the migration where the id repeats" {
            problems(
                listOf(once("a", supersedes = listOf("x")), once("b", supersedes = listOf("x")), once("c"), once("c"))
            ) shouldBe listOf(
                "duplicate id x: named in the supersedes lists of a and b",
                "duplicate id c at positions 3 and 4"
            )
            problems(listOf(once("a", supersedes = listOf("x")), once("a", supersedes = listOf("x")))) shouldBe listOf(
                "duplicate id a at positions 1 and 2",
                "duplicate id x: named in the supersedes lists of a and a"
            )
        }

        "rule 3: a supersedes list names a declared id" {
            problems(
                listOf(carts, once("100-baseline", supersedes = listOf("001-initial-setup", "002-carts")))
            ) shouldBe
                listOf("100-baseline supersedes 002-carts, which the list declares")
            problems(listOf(once("100-baseline", supersedes = listOf("100-baseline")))) shouldBe
                listOf("100-baseline supersedes 100-baseline, which the list declares")
        }

        "rule 4: numeric prefixes of once-only migrations strictly increase, compared as numbers" {
            problems(listOf(orderTotals, once("007-product-slugs"), once("007-cart-currency"))) shouldBe listOf(
                "007-cart-currency is listed after 007-product-slugs, but its numeric prefix 7 is not greater than 7"
            )
            problems(listOf(once("010-a"), once("10-b"))) shouldBe
                listOf("10-b is listed after 010-a, but its numeric prefix 10 is not greater than 10")
            problems(listOf(once("2026.10.01-a"), once("2025-b"))) shouldBe
                listOf("2025-b is listed after 2026.10.01-a, but its numeric prefix 2025 is not greater than 2026")
            problems(listOf(once("100-baseline"), once("007-customer-email-lower"))) shouldBe listOf(
                "007-customer-email-lower is listed after 100-baseline, but its numeric prefix 7 is not greater than 100"
            )
        }

        "rule 4: gaps, ids without a prefix and repeatable or every-start migrations are not compared" {
            problems(listOf(once("9-add-cart-index"), once("10-backfill-carts"))) shouldBe emptyList()
            problems(listOf(once("009-a"), once("010-b"))) shouldBe emptyList()
            problems(listOf(once("001-a"), once("cart-cleanup"), once("002-b"), once("1x"))) shouldBe emptyList()
            problems(listOf(once("010-a"), rep("005-countries"), every("001-every"))) shouldBe emptyList()
            problems(listOf(once("99999999999999999999-a"), once("100000000000000000000-b"))) shouldBe emptyList()
        }

        "rule 5: a once-only migration after a repeatable or every-start one" {
            problems(shopList + once("007-product-slugs")) shouldBe
                listOf("007-product-slugs is once-only but listed after reference-countries (repeatable); $LAST_RULE")
            problems(listOf(bootstrapCustomers, initialSetup, referenceCountries, carts)) shouldBe listOf(
                "001-initial-setup is once-only but listed after bootstrap-customers (every-start); $LAST_RULE",
                "002-carts is once-only but listed after bootstrap-customers (every-start); $LAST_RULE"
            )
        }

        "rule 6: a repeatable with a blank revision" {
            problems(
                listOf(rep("reference-currencies", revision = ""), rep("reference-languages", revision = " "))
            ) shouldBe
                listOf("reference-currencies has a blank revision", "reference-languages has a blank revision")
        }

        "rule 7: a repeatable or every-start migration that uses inBatches" {
            problems(
                listOf(
                    repeatable("product-search-text", revision = "2026-10-01")
                        .inBatches("products", pending = exists("searchText", false)) { },
                    everyStart("cart-expiry").outsideTransaction { }.inBatches("carts", exists("expiresAt", false)) { }
                )
            ) shouldBe listOf(
                "product-search-text is repeatable and uses inBatches, which only once-only migrations can use",
                "cart-expiry is every-start and uses inBatches, which only once-only migrations can use"
            )
        }

        "rule 8: a batch size outside 1 to 10000" {
            problems(
                listOf(
                    migration("007-customer-email-lower")
                        .inBatches("customers", pending = exists("emailLower", false), batchSize = 50_000) { },
                    migration("008-zero").inBatches("customers", exists("x", false), batchSize = 0) { }
                )
            ) shouldBe listOf(
                "007-customer-email-lower has batchSize 50000; batchSize is 1 to 10000",
                "008-zero has batchSize 0; batchSize is 1 to 10000"
            )
            problems(
                listOf(
                    migration("009-one").inBatches("customers", exists("x", false), batchSize = 1) { },
                    migration("010-max").inBatches("customers", exists("x", false), batchSize = 10_000) { }
                )
            ) shouldBe emptyList()
        }

        "every problem is reported at once, grouped by rule, in one exception" {
            val list = listOf(
                once("bad id"),
                every("bootstrap"),
                once("002-a", supersedes = listOf("001-old")),
                once("001-b", supersedes = listOf("001-old", "002-a")),
                once("001-b"),
                rep("rep", revision = ""),
                repeatable("batched", revision = "1").inBatches("c", exists("x", false), batchSize = 0) { }
            )
            val exception = shouldThrow<InvalidMigrationsException> { validateMigrations(list) }
            exception.problems shouldBe listOf(
                "invalid id \"bad id\": $ID_RULE",
                "duplicate id 001-old: named in the supersedes lists of 002-a and 001-b",
                "duplicate id 001-b at positions 4 and 5",
                "001-b supersedes 002-a, which the list declares",
                "001-b is listed after 002-a, but its numeric prefix 1 is not greater than 2",
                "001-b is listed after 001-b, but its numeric prefix 1 is not greater than 1",
                "002-a is once-only but listed after bootstrap (every-start); $LAST_RULE",
                "001-b is once-only but listed after bootstrap (every-start); $LAST_RULE",
                "rep has a blank revision",
                "batched is repeatable and uses inBatches, which only once-only migrations can use",
                "batched has batchSize 0; batchSize is 1 to 10000"
            )
            exception.message shouldBe "Invalid migrations:\n" + exception.problems.joinToString("\n") { "- $it" }
        }

        "rule 9: Target.Before and Target.Through name a once-only migration in the list" {
            val invalid = shouldThrow<InvalidMigrationsException> {
                godwit.migrate(shopList, target = Target.Before("004-order-totals"))
            }
            invalid.problems shouldBe
                listOf("Target.Before(\"004-order-totals\") names no once-only migration in the list")
            invalid.message shouldBe "Invalid migrations:\n" +
                "- Target.Before(\"004-order-totals\") names no once-only migration in the list"

            shouldThrow<InvalidMigrationsException> {
                godwit.migrate(shopList, target = Target.Through("reference-countries"))
            }.problems shouldBe
                listOf("Target.Through(\"reference-countries\") names no once-only migration in the list")

            val squashed = listOf(once("100-baseline", supersedes = listOf("001-initial-setup")), referenceCountries)
            shouldThrow<InvalidMigrationsException> {
                godwit.migrate(squashed, target = Target.Through("001-initial-setup"))
            }.problems shouldBe listOf("Target.Through(\"001-initial-setup\") names no once-only migration in the list")
        }

        "rule 9 joins the list's problems in one exception" {
            shouldThrow<InvalidMigrationsException> {
                godwit.migrate(listOf(rep("rep", revision = "")), target = Target.Before("001-a"))
            }.problems shouldBe listOf(
                "rep has a blank revision",
                "Target.Before(\"001-a\") names no once-only migration in the list"
            )
        }

        "a valid list and target pass validation and reach the database, which this cluster refuses to open" {
            for (target in listOf(
                Target.Latest,
                Target.Before("004-order-status"),
                Target.Through("006-order-totals")
            )) {
                shouldThrow<MockKException> { godwit.migrate(shopList, target) }
            }
            shouldThrow<MockKException> { godwit.migrate(initialSetup, carts) }
            shouldThrow<InvalidMigrationsException> {
                godwit.migrate(referenceCountries, referenceCountries)
            }.problems shouldBe
                listOf("duplicate id reference-countries at positions 1 and 2")
        }

        "status and requireUpToDate validate the list before any I/O" {
            val invalid = listOf(carts, initialSetup)
            val expected =
                listOf("001-initial-setup is listed after 002-carts, but its numeric prefix 1 is not greater than 2")
            shouldThrow<InvalidMigrationsException> { godwit.status(invalid) }.problems shouldBe expected
            shouldThrow<InvalidMigrationsException> { godwit.requireUpToDate(invalid) }.problems shouldBe expected

            shouldThrow<MockKException> { godwit.status(shopList) }
            shouldThrow<MockKException> { godwit.requireUpToDate(shopList) }
        }
    }
}
