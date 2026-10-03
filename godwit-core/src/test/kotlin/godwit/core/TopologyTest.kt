package godwit.core

import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates.set
import godwit.core.fixtures.CommandRecorder
import godwit.core.fixtures.CountingHook
import godwit.core.fixtures.GodwitFixture
import godwit.core.fixtures.collection
import godwit.core.fixtures.plantAdopted
import godwit.core.fixtures.standaloneMongo
import godwit.core.internal.transactionsSupported
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.bson.Document
import java.util.UUID

/** `{_id: "godwit-history", ...}` held by another process for 30 s, by the server's clock. */
private fun heldLock() = listOf(
    Document(
        "\$set",
        Document("owner", "token-of-another-process")
            .append("holder", "shop-older/1")
            .append("runId", "run-of-another-process")
            .append("acquiredAt", "\$\$NOW")
            .append("refreshedAt", "\$\$NOW")
            .append("expiresAt", Document("\$add", listOf("\$\$NOW", 30_000L)))
    )
)

/** The topology check: against a standalone `mongod`, which has no transactions, and its one `hello` per call. */
class TopologyTest : StringSpec() {
    init {
        "a call whose plans before and under the lock both have a transactional step due sends hello once" {
            GodwitFixture(appName = "topology-replica-set").use { f ->
                val orderStatus = migration("004-order-status").inTransaction { }

                f.godwit.migrate(orderStatus).ran.map { it.id } shouldBe listOf("004-order-status")

                val names = f.recorder.commands.map { it.name }
                names.count { it == "hello" } shouldBe 1
                names.indexOf("hello") shouldBeLessThan names.indexOf("findAndModify")
            }
        }

        "adoption with ids to record asks hello under the lock when the plan before it had no transactional step" {
            val hook = CountingHook("001-initial-setup")
            GodwitFixture(appName = "topology-adoption", config = GodwitConfig(adoptApplied = hook)).use { f ->
                val list = listOf(
                    migration("001-initial-setup").outsideTransaction { error("adopted") },
                    migration("002-carts").outsideTransaction { ensureCollection("carts") }
                )

                f.godwit.migrate(list).recorded.map { it.id } shouldBe listOf("001-initial-setup")

                val names = f.recorder.commands.map { it.name }
                names.count { it == "hello" } shouldBe 1
                names.indexOf("hello") shouldBeGreaterThan names.indexOf("findAndModify")
                val adoption = f.recorder.commands("update").first { it.collection == "godwit-history" }
                adoption.command.getBoolean("startTransaction").value shouldBe true
            }
        }

        "adoption does not ask hello again when the plan before the lock asked, nor when it has nothing to record" {
            val adopting = GodwitConfig(adoptApplied = CountingHook("001-a"))
            GodwitFixture(appName = "topology-asked", config = adopting).use { f ->
                val list = listOf(
                    migration("001-a").inTransaction { error("adopted") },
                    migration("002-b").inTransaction { }
                )

                f.godwit.migrate(list).recorded.map { it.id } shouldBe listOf("001-a")

                val names = f.recorder.commands.map { it.name }
                names.count { it == "hello" } shouldBe 1
                names.indexOf("hello") shouldBeLessThan names.indexOf("findAndModify")
            }
            GodwitFixture(appName = "topology-nothing", config = adopting).use { f ->
                f.history.plantAdopted("001-a")
                val list = listOf(
                    migration("001-a").outsideTransaction { error("adopted") },
                    migration("002-b").outsideTransaction { }
                )

                f.godwit.migrate(list).ran.map { it.id } shouldBe listOf("002-b")

                f.recorder.commands("hello").shouldBeEmpty()
            }
        }

        "hello tells a replica set member and a mongos, which run transactions, from a standalone server" {
            transactionsSupported(Document("setName", "docker-rs").append("isWritablePrimary", true)) shouldBe true
            transactionsSupported(Document("msg", "isdbgrid")) shouldBe true
            transactionsSupported(Document("isWritablePrimary", true)) shouldBe false
            transactionsSupported(Document("msg", "something else")) shouldBe false
        }

        "a transactional step due on a standalone server: TransactionsUnsupportedException before the lock" {
            val recorder = CommandRecorder()
            standaloneMongo.client("topology-refused", recorder).use { client ->
                val databaseName = UUID.randomUUID().toString()
                val godwit = Godwit(client, databaseName, GodwitConfig(holder = "topology/1"))
                val setup = migration("001-initial-setup").outsideTransaction { ensureCollection("customers") }
                val orderStatus = migration("004-order-status").inTransaction { }
                val linking = migration("005-customer-external-ids").outsideTransaction { }.inTransaction { }

                val refused = shouldThrow<TransactionsUnsupportedException> {
                    godwit.migrate(setup, orderStatus, linking)
                }

                refused.due shouldBe listOf("004-order-status", "005-customer-external-ids")
                refused.message shouldBe
                    "Migrations [004-order-status, 005-customer-external-ids] need transactions, " +
                    "which a standalone mongod does not support. Run a single-node replica set: start mongod with " +
                    "--replSet rs0, then run rs.initiate() once."
                recorder.commands.map { it.name } shouldBe listOf("find", "hello")
                client.getDatabase(databaseName).listCollectionNames().toList().shouldBeEmpty()
            }
        }

        "an outside-only list runs on a standalone server, without asking it about transactions" {
            val recorder = CommandRecorder()
            standaloneMongo.client("topology-outside-only", recorder).use { client ->
                val databaseName = UUID.randomUUID().toString()
                val godwit = Godwit(client, databaseName, GodwitConfig(holder = "topology/1"))
                val setup = migration("001-initial-setup").outsideTransaction { ensureCollection("customers") }
                val carts = migration("002-carts").outsideTransaction { ensureCollection("carts") }

                godwit.migrate(setup, carts).ran.map { it.id } shouldBe listOf("001-initial-setup", "002-carts")
                godwit.history().map { it.state } shouldBe listOf(HistoryState.APPLIED, HistoryState.APPLIED)
                recorder.commands("hello").shouldBeEmpty()
            }
        }

        "a transactional repeatable that becomes due under the lock still throws TransactionsUnsupportedException" {
            val databaseName = UUID.randomUUID().toString()
            standaloneMongo.client("topology-under-lock-setup").use { setup ->
                val database = setup.getDatabase(databaseName)
                val history = database.getCollection("godwit-history", Document::class.java)
                val lock = database.getCollection("godwit-lock", Document::class.java)
                history.insertOne(
                    Document("_id", "reference-countries").append("kind", "REPEATABLE").append("revision", "2026-10-01")
                        .append("state", "APPLIED").append("origin", "RAN").append("attempts", 1)
                )
                lock.updateOne(eq("_id", "godwit-history"), heldLock(), UpdateOptions().upsert(true))
                var olderReleaseRan = false
                val recorder = CommandRecorder(onSucceeded = { command ->
                    // While this process waits for the lock, a process of the older release applies its revision of
                    // the repeatable and releases the lock.
                    if (command.name == "findAndModify" && !olderReleaseRan) {
                        olderReleaseRan = true
                        history.updateOne(eq("_id", "reference-countries"), set("revision", "2026-09-01"))
                        lock.updateOne(
                            eq("_id", "godwit-history"),
                            listOf(Document("\$set", Document("expiresAt", "\$\$NOW").append("releasedAt", "\$\$NOW")))
                        )
                    }
                })
                standaloneMongo.client("topology-under-lock", recorder).use { client ->
                    val godwit = Godwit(client, databaseName, GodwitConfig(holder = "topology/1"))
                    val carts = migration("002-carts").outsideTransaction { ensureCollection("carts") }
                    val countries = repeatable("reference-countries", "2026-10-01").inTransaction { }

                    val refused = shouldThrow<TransactionsUnsupportedException> { godwit.migrate(carts, countries) }

                    refused.due shouldBe listOf("reference-countries")
                    olderReleaseRan shouldBe true
                    recorder.commands("hello").size shouldBe 1
                    history.find(eq("_id", "002-carts")).firstOrNull() shouldBe null
                    database.listCollectionNames().toList().contains("carts") shouldBe false
                    val released = lock.find().first()
                    released.getString("holder") shouldBe "topology/1"
                    released.containsKey("releasedAt") shouldBe true
                }
            }
        }
    }
}
