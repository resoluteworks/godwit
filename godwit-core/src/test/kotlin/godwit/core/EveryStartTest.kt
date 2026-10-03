package godwit.core

import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates.setOnInsert
import godwit.core.fixtures.GodwitFixture
import godwit.core.fixtures.LogCapture
import godwit.core.fixtures.keyValues
import godwit.core.fixtures.line
import godwit.core.fixtures.referenceCountries
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith

private val setup = migration("001-initial-setup").outsideTransaction { ensureCollection("customers") }

private val carts = migration("002-carts").outsideTransaction { ensureCollection("carts") }

private val seed = listOf("ops@shop.example", "demo@shop.example")

/**
 * The shop's `bootstrap-customers`: the outside step finds or creates each seed user at the identity provider, a map
 * standing in for it, and hands the ids to one transaction that inserts only the missing customers.
 */
private fun bootstrapCustomers(identity: MutableMap<String, String>) =
    everyStart("bootstrap-customers", description = "Seed customers from configuration")
        .outsideTransaction {
            seed.associateWith { email -> identity.getOrPut(email) { "user-${identity.size + 1}" } }
        }
        .inTransaction { externalIds ->
            val customers = collection("customers")
            val created = seed.count { email ->
                val insert = setOnInsert("externalId", externalIds.getValue(email))
                val result = customers.updateOne(session, eq("email", email), insert, UpdateOptions().upsert(true))
                result.upsertedId != null
            }
            count("customersCreated", created)
        }

/** Every-start migrations: under the lock on every start with [Target.Latest], and never under a target. */
class EveryStartTest : StringSpec() {
    init {
        "an every-start migration runs on every start, under the lock, and runCount grows by one per start" {
            GodwitFixture().use { f ->
                val identity = mutableMapOf<String, String>()
                val list = listOf(setup, referenceCountries("2026-10-01"), bootstrapCustomers(identity))

                val first = f.godwit.migrate(list)
                first.ran.map { it.id } shouldBe
                    listOf("001-initial-setup", "reference-countries", "bootstrap-customers")

                for (start in 2..4) {
                    withClue("start $start") {
                        LogCapture().use { logs ->
                            val report = f.godwit.migrate(list)

                            report.ran.map { it.id } shouldBe listOf("bootstrap-customers")
                            report.upToDate shouldBe listOf("001-initial-setup", "reference-countries")
                            report.lockWait.shouldNotBeNull()
                            val outcome = report["bootstrap-customers"]
                            outcome.kind shouldBe MigrationKind.EveryStart
                            outcome.attempts shouldBe 1
                            outcome.counts shouldBe mapOf("customersCreated" to 0L)
                            logs.events.map { it.message } shouldBe listOf(
                                "Acquired migration lock",
                                "Running migration",
                                "Applied migration",
                                "Migrations complete"
                            )
                            logs.events("Running migration").single().line shouldBe
                                "Running migration id=bootstrap-customers kind=EVERY_START " +
                                "steps=[OUTSIDE_TRANSACTION, IN_TRANSACTION] attempt=1"
                            val applied = logs.events("Applied migration").single().line
                            applied shouldStartWith "Applied migration id=bootstrap-customers kind=EVERY_START " +
                                "steps=[OUTSIDE_TRANSACTION, IN_TRANSACTION] attempts=1 txRetries=0 batches=0 durationMs="
                            applied shouldEndWith " customersCreated=0"
                            val complete = logs.events("Migrations complete").single().keyValues
                            complete["ran"] shouldBe 1
                            complete["upToDate"] shouldBe 2
                        }
                        val stored = f.stored("bootstrap-customers").shouldNotBeNull()
                        stored.getString("kind") shouldBe "EVERY_START"
                        stored.getString("description") shouldBe "Seed customers from configuration"
                        stored.getString("state") shouldBe "APPLIED"
                        stored.getInteger("attempts") shouldBe 1
                        stored.getLong("runCount") shouldBe start.toLong()
                        stored.getDate("lastRunAt") shouldBe stored.getDate("finishedAt")
                        stored.containsKey("revision") shouldBe false
                    }
                }
                f.stored("reference-countries")!!.getLong("runCount") shouldBe 1L
                f.collection("customers").countDocuments() shouldBe 2L
                val entry = f.godwit.history().single { it.id == "bootstrap-customers" }
                entry.kind shouldBe MigrationKind.EveryStart
                entry.runCount shouldBe 4L
            }
        }

        "an every-start migration never runs under a target, and a targeted start with nothing due takes no lock" {
            GodwitFixture().use { f ->
                val list = listOf(setup, carts, referenceCountries("2026-10-01"), bootstrapCustomers(mutableMapOf()))

                val through = f.godwit.migrate(list, Target.Through("002-carts"))

                through.ran.map { it.id } shouldBe listOf("001-initial-setup", "002-carts")
                through.pending.shouldBeEmpty()
                f.stored("bootstrap-customers").shouldBeNull()
                f.recorder.clear()

                val before = f.godwit.migrate(list, Target.Before("002-carts"))

                before.ran.shouldBeEmpty()
                before.lockWait.shouldBeNull()
                f.recorder.commands.map { it.name } shouldBe listOf("find")

                f.godwit.migrate(list).ran.map { it.id } shouldBe listOf("reference-countries", "bootstrap-customers")
                f.recorder.clear()

                val targeted = f.godwit.migrate(list, Target.Through("002-carts"))

                targeted.ran.shouldBeEmpty()
                targeted.lockWait.shouldBeNull()
                f.recorder.commands.map { it.name } shouldBe listOf("find")
                f.stored("bootstrap-customers")!!.getLong("runCount") shouldBe 1L
            }
        }

        "a failed every-start run is retried on every start until it applies; status never lists it as pending" {
            GodwitFixture().use { f ->
                var providerUp = false
                val bootstrap = everyStart("bootstrap-customers").outsideTransaction {
                    if (!providerUp) throw IllegalStateException("identity provider unavailable")
                    count("usersChecked", 2)
                }
                val list = listOf(setup, bootstrap)

                LogCapture().use { logs ->
                    val failure = shouldThrow<MigrationFailedException> { f.godwit.migrate(list) }

                    failure.id shouldBe "bootstrap-customers"
                    failure.report.ran.map { it.id } shouldBe listOf("001-initial-setup")
                    logs.events("Migration failed").single().line shouldBe
                        "Migration failed id=bootstrap-customers step=OUTSIDE_TRANSACTION attempts=1 " +
                        "error=java.lang.IllegalStateException: identity provider unavailable"
                }
                val failed = f.stored("bootstrap-customers").shouldNotBeNull()
                failed.getString("state") shouldBe "FAILED"
                failed.containsKey("runCount") shouldBe false
                f.godwit.status(list).pending.shouldBeEmpty()
                f.godwit.status(list).isUpToDate shouldBe true
                f.godwit.requireUpToDate(list)

                shouldThrow<MigrationFailedException> { f.godwit.migrate(list) }
                f.stored("bootstrap-customers")!!.getInteger("attempts") shouldBe 2

                providerUp = true
                f.godwit.migrate(list)["bootstrap-customers"].attempts shouldBe 3
                val applied = f.stored("bootstrap-customers").shouldNotBeNull()
                applied.getString("state") shouldBe "APPLIED"
                applied.getLong("runCount") shouldBe 1L
                applied.containsKey("lastError") shouldBe false

                f.godwit.migrate(list)["bootstrap-customers"].attempts shouldBe 1
                f.stored("bootstrap-customers")!!.getLong("runCount") shouldBe 2L
            }
        }

        "a failed repeatable stops the call before the every-start migrations listed after it" {
            GodwitFixture().use { f ->
                val broken = repeatable("reference-countries", "2026-10-01").inTransaction { error("no countries") }

                val failure = shouldThrow<MigrationFailedException> {
                    f.godwit.migrate(setup, broken, bootstrapCustomers(mutableMapOf()))
                }

                failure.id shouldBe "reference-countries"
                failure.report.ran.map { it.id } shouldBe listOf("001-initial-setup")
                f.stored("bootstrap-customers").shouldBeNull()
                f.collection("customers").countDocuments() shouldBe 0L
            }
        }
    }
}
