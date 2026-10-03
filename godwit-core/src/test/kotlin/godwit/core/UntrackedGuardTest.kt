package godwit.core

import com.mongodb.ReadPreference
import godwit.core.fixtures.CountingHook
import godwit.core.fixtures.GodwitFixture
import godwit.core.fixtures.historyDocument
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.bson.Document

private fun runs(id: String): Migration = migration(id).outsideTransaction { ensureCollection("carts") }

/**
 * The untracked-database guard, under the lock: a database with collections other than godwit's and `system.*`, no
 * history, and nothing adopted is refused under `UntrackedDatabase.REFUSE` and runs everything under `RUN_ALL`.
 */
class UntrackedGuardTest : StringSpec() {
    init {
        "REFUSE names the collections, sorted, leaving out godwit's own and system.*, and nothing runs" {
            val config = GodwitConfig(historyCollection = "shop-history", lockCollection = "shop-lock")
            GodwitFixture(config = config).use { f ->
                listOf("schema-log", "products", "orders", "customers").forEach { name ->
                    f.collection(name).insertOne(Document("seeded", true))
                }
                f.collection("system.js").insertOne(Document("_id", "f").append("value", 1))
                f.database.createCollection("shop-history")

                val refused = shouldThrow<UntrackedDatabaseException> { f.godwit.migrate(runs("002-carts")) }

                refused.collections shouldBe listOf("customers", "orders", "products", "schema-log")
                refused.message shouldBe
                    "The database has collections [customers, orders, products, schema-log] but no godwit history. " +
                    "Configure GodwitConfig.adoptApplied to adopt the migrations already applied, or set " +
                    "UntrackedDatabase.RUN_ALL to run every migration."
                f.collection("shop-history").countDocuments() shouldBe 0L
                f.database.listCollectionNames().toList().contains("carts") shouldBe false
                f.collection("shop-lock").find().first().containsKey("releasedAt") shouldBe true
                f.godwit.status(listOf(runs("002-carts"))).problems shouldBe listOf(refused.message)
            }
        }

        "the collections are listed on the primary, like history, when the app's client reads from secondaries" {
            GodwitFixture().use { f ->
                f.collection("customers").insertOne(Document("email", "a@example.com"))
                val secondaryReads = f.client.withReadPreference(ReadPreference.secondaryPreferred())
                val godwit = Godwit(secondaryReads, f.db.name, f.config)

                godwit.status(listOf(runs("002-carts"))).problems shouldHaveSize 1

                // On a direct connection the driver sends a primary read as primaryPreferred, and any other read
                // preference as it is, so the command shows which one the operation used. The driver lists
                // collections on the primary whatever the database's read preference; godwit relies on that.
                fun readPreferenceOf(name: String) = f.recorder.commands(name).single().command["\$readPreference"]
                val historyRead = readPreferenceOf("find")
                historyRead shouldNotBe null
                readPreferenceOf("listCollections") shouldBe historyRead
                // The client's own reads carry its read preference, so a listing that did would show here.
                secondaryReads.getDatabase(f.db.name).getCollection("customers", Document::class.java).find().first()
                f.recorder.commands("find").last().command["\$readPreference"] shouldNotBe historyRead
            }
        }

        "another configuration's bookkeeping collections count as the app's" {
            val config = GodwitConfig(historyCollection = "shop-history", lockCollection = "shop-lock")
            GodwitFixture(config = config).use { f ->
                f.collection("godwit-history").insertOne(historyDocument("001-initial-setup"))

                shouldThrow<UntrackedDatabaseException> { f.godwit.migrate(runs("002-carts")) }.collections shouldBe
                    listOf("godwit-history")
            }
        }

        "RUN_ALL runs every migration on a database with collections and no history" {
            GodwitFixture(config = GodwitConfig(untrackedDatabase = UntrackedDatabase.RUN_ALL)).use { f ->
                f.collection("customers").insertOne(Document("email", "a@example.com"))
                val list = listOf(runs("001-initial-setup"), runs("002-carts"))

                f.godwit.status(list).problems.shouldBeEmpty()
                f.godwit.migrate(list).ran.map { it.id } shouldBe listOf("001-initial-setup", "002-carts")
            }
        }

        "an empty database, or one that holds only system.* collections, runs everything under REFUSE" {
            GodwitFixture().use { f ->
                f.godwit.migrate(runs("001-initial-setup")).ran.map { it.id } shouldBe listOf("001-initial-setup")
            }
            GodwitFixture().use { f ->
                f.collection("system.js").insertOne(Document("_id", "f").append("value", 1))

                f.godwit.migrate(runs("001-initial-setup")).ran.map { it.id } shouldBe listOf("001-initial-setup")
            }
        }

        "a history that holds any document is not untracked, whatever its state" {
            GodwitFixture().use { f ->
                f.collection("customers").insertOne(Document("email", "a@example.com"))
                f.history.insertOne(historyDocument("001-initial-setup", HistoryState.FAILED))

                f.godwit.migrate(runs("001-initial-setup")).ran.map { it.id } shouldBe listOf("001-initial-setup")
            }
        }

        "a hook that adopted something turns the guard off; one that adopted nothing leaves it on" {
            val adopting = CountingHook("001-initial-setup")
            GodwitFixture(config = GodwitConfig(adoptApplied = adopting)).use { f ->
                f.collection("customers").insertOne(Document("email", "a@example.com"))
                val adopted = migration("001-initial-setup").outsideTransaction { error("adopted") }
                val list = listOf(adopted, runs("002-carts"))

                f.godwit.status(list).problems.shouldBeEmpty()
                f.godwit.migrate(list).ran.map { it.id } shouldBe listOf("002-carts")
            }
            GodwitFixture(config = GodwitConfig(adoptApplied = CountingHook())).use { f ->
                f.collection("customers").insertOne(Document("email", "a@example.com"))

                // status() never reports the guard while the hook can still run: only migrate calls it.
                f.godwit.status(listOf(runs("002-carts"))).problems.shouldBeEmpty()
                shouldThrow<UntrackedDatabaseException> { f.godwit.migrate(runs("002-carts")) }.collections shouldBe
                    listOf("customers")
            }
        }
    }
}
