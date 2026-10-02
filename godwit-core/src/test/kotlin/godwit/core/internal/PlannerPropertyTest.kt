package godwit.core.internal

import com.mongodb.client.model.Filters.exists
import godwit.core.GodwitConfig
import godwit.core.HistoryState
import godwit.core.Migration
import godwit.core.MigrationDraft
import godwit.core.MigrationKind
import godwit.core.Origin
import godwit.core.OutOfOrder
import godwit.core.Target
import godwit.core.UnknownApplied
import godwit.core.everyStart
import godwit.core.migration
import godwit.core.repeatable
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.checkAll
import org.slf4j.LoggerFactory
import kotlin.random.Random

private const val LISTS = 10_000

private val log = LoggerFactory.getLogger(PlannerPropertyTest::class.java)

/** One generated input of the planner: a valid list, a history, a target, the policies and the adopting flag. */
private data class Case(
    val migrations: List<Migration>,
    val history: List<HistoryRecord>,
    val target: Target,
    val outOfOrder: OutOfOrder,
    val unknownApplied: UnknownApplied,
    val adopting: Boolean
) {
    fun plan(history: List<HistoryRecord> = this.history): Plan = plan(
        migrations,
        history,
        target,
        GodwitConfig(outOfOrder = outOfOrder, unknownApplied = unknownApplied, holder = "t/1"),
        adopting
    )

    override fun toString(): String =
        "list=${migrations.map { "$it:${it.kind}${if (it.supersedes.isEmpty()) "" else "<-${it.supersedes}"}" }} " +
            "history=$history target=$target outOfOrder=$outOfOrder unknownApplied=$unknownApplied adopting=$adopting"
}

private fun <T> Random.pick(values: List<T>): T = values[nextInt(values.size)]

private fun Random.chance(percent: Int): Boolean = nextInt(100) < percent

/** A complete migration from [draft] with a random step shape; only once-only migrations may page with inBatches. */
private fun Random.withSteps(draft: MigrationDraft, batches: Boolean): Migration =
    when (nextInt(if (batches) 5 else 3)) {
        0 -> draft.outsideTransaction { }
        1 -> draft.inTransaction { }
        2 -> draft.outsideTransaction { 1 }.inTransaction { }
        3 -> draft.inBatches("c", exists("x", false)) { }
        else -> draft.outsideTransaction { }.inBatches("c", exists("x", false)) { }
    }

private fun Random.state(): HistoryState? =
    pick(listOf(null, null, HistoryState.RUNNING, HistoryState.FAILED, HistoryState.APPLIED))

private fun Random.record(
    id: String,
    kind: StoredKind,
    state: HistoryState,
    revision: String? = null,
    supersedes: List<String> = emptyList()
) = HistoryRecord(id, kind, state, pick(Origin.entries), revision, supersedes)

private val cases = arbitrary { source ->
    val random = source.random
    val migrations = mutableListOf<Migration>()
    val history = mutableListOf<HistoryRecord>()
    var number = 0
    var old = 0

    repeat(random.nextInt(0, 9)) { index ->
        number += random.nextInt(1, 4)
        val id = if (random.chance(15)) "plain-$index" else "%03d-m$index".format(number)
        val supersedes = if (random.chance(20)) List(random.nextInt(1, 4)) { "old-${old++}" } else emptyList()
        migrations += random.withSteps(migration(id, supersedes = supersedes), batches = true)
        random.state()?.let { state ->
            val kind = if (random.chance(5)) StoredKind.REPEATABLE else StoredKind.ONCE
            val stored = if (state == HistoryState.APPLIED && random.chance(50)) supersedes else emptyList()
            history += random.record(id, kind, state, supersedes = stored)
        }
        for (replaced in supersedes) {
            if (random.chance(60)) history += random.record(replaced, StoredKind.ONCE, HistoryState.APPLIED)
        }
    }
    repeat(random.nextInt(0, 4)) { index ->
        val id = "rep-$index"
        migrations += random.withSteps(repeatable(id, revision = random.pick(listOf("r1", "r2"))), batches = false)
        val revision = random.pick(listOf(null, "r1", "r2"))
        random.state()?.let { history += random.record(id, StoredKind.REPEATABLE, it, revision) }
    }
    repeat(random.nextInt(0, 3)) { index ->
        val id = "every-$index"
        migrations += random.withSteps(everyStart(id), batches = false)
        random.state()?.let { history += random.record(id, StoredKind.EVERY_START, it) }
    }
    repeat(random.nextInt(0, 3)) { index ->
        val state = random.state() ?: HistoryState.APPLIED
        history += random.record("gone-$index", random.pick(StoredKind.entries), state)
    }

    val onceOnly = migrations.filter { it.kind == MigrationKind.Once }.map { it.id }
    val target = when {
        onceOnly.isEmpty() || random.chance(50) -> Target.Latest
        random.chance(50) -> Target.Before(random.pick(onceOnly))
        else -> Target.Through(random.pick(onceOnly))
    }
    Case(
        migrations,
        history,
        target,
        random.pick(OutOfOrder.entries),
        random.pick(UnknownApplied.entries),
        random.nextBoolean()
    )
}

class PlannerPropertyTest : StringSpec() {
    init {
        "the planner's invariants hold over $LISTS generated lists and histories" {
            var lists = 0
            checkAll(LISTS, cases) { case ->
                withClue("generated lists are valid") { listProblems(case.migrations).shouldBeEmpty() }
                val plan = case.plan()
                val records = case.history.associateBy { it.id }
                val dueIds = plan.due.map { it.migration.id }
                val position = case.migrations.withIndex().associate { (index, migration) -> migration.id to index }

                withClue("an APPLIED once-only migration is never due, recorded or pending") {
                    case.migrations
                        .filter { it.kind == MigrationKind.Once && records[it.id]?.state == HistoryState.APPLIED }
                        .forEach { applied ->
                            (applied.id in dueIds) shouldBe false
                            (applied in plan.superseded) shouldBe false
                            (applied.id in plan.pending) shouldBe false
                        }
                }

                val (dueOnce, dueRerunnable) = plan.due.map { it.migration }.partition { it.kind == MigrationKind.Once }
                withClue("due order follows list order, once-only and the others each") {
                    dueOnce.map { position.getValue(it.id) } shouldBe dueOnce.map { position.getValue(it.id) }.sorted()
                    dueRerunnable.map { position.getValue(it.id) } shouldBe
                        dueRerunnable.map { position.getValue(it.id) }.sorted()
                }

                withClue("a repeatable or every-start migration never runs before a due once-only one") {
                    plan.due.map { it.migration } shouldBe dueOnce + dueRerunnable
                }

                withClue("no target runs a repeatable or every-start migration") {
                    if (case.target != Target.Latest) dueRerunnable.shouldBeEmpty()
                }

                withClue(
                    "an APPLIED repeatable or every-start document never makes a once-only migration out of order"
                ) {
                    plan.due.flatMap { it.appliedAfter }.forEach { records.getValue(it).kind shouldBe StoredKind.ONCE }
                    // Removing every APPLIED repeatable and every-start document (except one that a declared once-only
                    // id owns, which decides whether that migration is due) changes nothing about out of order.
                    val onceIds = case.migrations.filter { it.kind == MigrationKind.Once }.map { it.id }.toSet()
                    val kept = case.history.filter {
                        it.state != HistoryState.APPLIED || it.kind == StoredKind.ONCE || it.id in onceIds
                    }
                    val without = case.plan(kept)
                    fun Plan.outOfOrder() = due.filter { it.migration.kind == MigrationKind.Once }
                        .map { it.migration.id to it.appliedAfter } to conflicts.filter { "out of order" in it }
                    without.outOfOrder() shouldBe plan.outOfOrder()
                }

                withClue("a superseding migration not APPLIED counts the applied ids it names in its place") {
                    // Listing its replaced ids as plain once-only migrations in its place changes nothing about which
                    // migrations listed before it are out of order.
                    case.migrations.withIndex()
                        .filter { (_, it) -> it.supersedes.isNotEmpty() }
                        .filter { (_, it) -> records[it.id]?.state != HistoryState.APPLIED }
                        .forEach { (at, squash) ->
                            val unsquashed = case.migrations.take(at) +
                                squash.supersedes.map { migration(it).outsideTransaction { } } +
                                case.migrations.drop(at + 1)
                            val target = when (case.target) {
                                Target.Before(squash.id) -> Target.Before(squash.supersedes.first())
                                Target.Through(squash.id) -> Target.Through(squash.supersedes.last())
                                else -> case.target
                            }
                            val before = case.migrations.take(at).map { it.id }.toSet()
                            fun Plan.outOfOrderBefore() =
                                due.filter { it.migration.id in before }.map { it.migration.id to it.appliedAfter } to
                                    conflicts.filter { line -> before.any { line.startsWith("$it is pending, but ") } }
                            case.copy(migrations = unsquashed, target = target).plan().outOfOrderBefore() shouldBe
                                plan.outOfOrderBefore()
                        }
                }

                withClue("each declared id is in at most one of due, recorded, pending and up to date") {
                    val placed = dueIds + plan.superseded.map { it.id } + plan.pending + plan.upToDate
                    placed.size shouldBe placed.toSet().size
                }
                lists++
            }
            lists shouldBe LISTS
            log.info("planner invariants held lists={}", lists)
        }
    }
}
