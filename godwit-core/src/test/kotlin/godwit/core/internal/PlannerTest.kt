package godwit.core.internal

import com.mongodb.client.model.Filters.exists
import godwit.core.GodwitConfig
import godwit.core.HistoryState
import godwit.core.Migration
import godwit.core.Origin
import godwit.core.OutOfOrder
import godwit.core.Target
import godwit.core.UnknownApplied
import godwit.core.everyStart
import godwit.core.migration
import godwit.core.repeatable
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

private fun once(id: String, supersedes: List<String> = emptyList()): Migration =
    migration(id, supersedes = supersedes).outsideTransaction { }

private fun onceInTransaction(id: String): Migration = migration(id).inTransaction { }

private fun rep(id: String, revision: String = "r2"): Migration = repeatable(id, revision).inTransaction { }

private fun every(id: String): Migration = everyStart(id).outsideTransaction { }

private fun record(
    id: String,
    state: HistoryState = HistoryState.APPLIED,
    origin: Origin = Origin.RAN,
    kind: StoredKind = StoredKind.ONCE,
    revision: String? = null,
    supersedes: List<String> = emptyList()
) = HistoryRecord(id, kind, state, origin, revision, supersedes)

private fun applied(vararg ids: String, origin: Origin = Origin.RAN) = ids.map { record(it, origin = origin) }

private fun config(outOfOrder: OutOfOrder = OutOfOrder.FAIL, unknownApplied: UnknownApplied = UnknownApplied.WARN) =
    GodwitConfig(outOfOrder = outOfOrder, unknownApplied = unknownApplied, holder = "test/1")

private fun planOf(
    migrations: List<Migration>,
    history: List<HistoryRecord>,
    target: Target = Target.Latest,
    outOfOrder: OutOfOrder = OutOfOrder.FAIL,
    unknownApplied: UnknownApplied = UnknownApplied.WARN,
    adopting: Boolean = false
): Plan = plan(migrations, history, target, config(outOfOrder, unknownApplied), adopting)

private val Plan.dueIds: List<String> get() = due.map { it.migration.id }

/** One combination of the inputs that are not data: the target, both policies and whether adoption can still run. */
private data class Setting(
    val target: Target,
    val outOfOrder: OutOfOrder,
    val unknownApplied: UnknownApplied,
    val adopting: Boolean
) {
    fun plan(migrations: List<Migration>, history: List<HistoryRecord>): Plan =
        plan(migrations, history, target, config(outOfOrder, unknownApplied), adopting)
}

/** Every setting with one of [targets]. */
private fun settings(vararg targets: Target): List<Setting> = targets.flatMap { target ->
    OutOfOrder.entries.flatMap { outOfOrder ->
        UnknownApplied.entries.flatMap { unknownApplied ->
            listOf(false, true).map { adopting -> Setting(target, outOfOrder, unknownApplied, adopting) }
        }
    }
}

/** A history state for the matrix: no document, or one in a state with an origin (and a stored revision). */
private data class Stored(val state: HistoryState?, val origin: Origin = Origin.RAN, val revision: String? = null) {
    override fun toString() = state?.let { "$it/$origin" + (revision?.let { r -> " revision=$r" } ?: "") } ?: "missing"
}

private val storedOnce = listOf(Stored(null)) +
    listOf(HistoryState.RUNNING, HistoryState.FAILED).map { Stored(it) } +
    Origin.entries.map { Stored(HistoryState.APPLIED, it) }

private val storedRerunnable = listOf(Stored(null)) +
    listOf(HistoryState.RUNNING, HistoryState.FAILED).flatMap { listOf(Stored(it), Stored(it, revision = "r2")) } +
    listOf(null, "r1", "r2").map { Stored(HistoryState.APPLIED, revision = it) }

/**
 * Each history state paired with each stored kind: a document keeps its id when the migration changes kind, and holds
 * the kind of the run that last wrote it.
 */
private fun List<Stored>.withEveryKind(): List<Pair<Stored, StoredKind>> =
    flatMap { stored -> StoredKind.entries.map { stored to it } }

private fun Stored.recordFor(id: String, kind: StoredKind): List<HistoryRecord> =
    state?.let { listOf(record(id, it, origin, kind, revision)) } ?: emptyList()

private const val ANCHOR = "001-anchor"
private val anchor = once(ANCHOR)

/** What the plan holds for one migration in a single-migration case. */
private enum class Outcome { DUE, PENDING, UP_TO_DATE, NONE }

private fun Plan.outcomeOf(id: String): Outcome = when (id) {
    in dueIds -> Outcome.DUE
    in pending -> Outcome.PENDING
    in upToDate -> Outcome.UP_TO_DATE
    else -> Outcome.NONE
}

class PlannerTest : StringSpec() {
    init {
        // The matrix: kind x stored kind x history state x origin, each case checked under every target, policy and
        // adopting value.
        // The applied anchor listed first gives the targets a once-only id and keeps every case free of conflicts.

        for ((stored, kind) in storedOnce.withEveryKind()) {
            "once-only, $stored, kind $kind: due unless APPLIED, whatever origin and kind; Before leaves it pending" {
                val subject = once("002-subject")
                val history = applied(ANCHOR) + stored.recordFor(subject.id, kind)
                for (setting in settings(Target.Latest, Target.Through(subject.id), Target.Before(subject.id))) {
                    withClue(setting) {
                        val plan = setting.plan(listOf(anchor, subject), history)
                        val outcome = when {
                            stored.state == HistoryState.APPLIED -> Outcome.UP_TO_DATE
                            setting.target is Target.Before -> Outcome.PENDING
                            else -> Outcome.DUE
                        }
                        plan.outcomeOf(subject.id) shouldBe outcome
                        plan.outcomeOf(ANCHOR) shouldBe Outcome.UP_TO_DATE
                        plan.conflicts shouldBe emptyList()
                        plan.statusPending shouldBe if (outcome == Outcome.DUE) listOf(subject.id) else emptyList()
                        plan.due.all { !it.outOfOrder } shouldBe true
                    }
                }
            }
        }

        for ((stored, kind) in storedRerunnable.withEveryKind()) {
            "repeatable at r2, $stored, kind $kind: due unless a repeatable APPLIED r2, under Target.Latest only" {
                val subject = rep("reference-countries", revision = "r2")
                val history = applied(ANCHOR) + stored.recordFor(subject.id, kind)
                for (setting in settings(Target.Latest, Target.Before(ANCHOR), Target.Through(ANCHOR))) {
                    withClue(setting) {
                        val plan = setting.plan(listOf(anchor, subject), history)
                        val atRevision = stored.state == HistoryState.APPLIED && stored.revision == "r2"
                        plan.outcomeOf(subject.id) shouldBe when {
                            atRevision && kind == StoredKind.REPEATABLE -> Outcome.UP_TO_DATE
                            setting.target == Target.Latest -> Outcome.DUE
                            else -> Outcome.NONE
                        }
                        plan.pending shouldBe emptyList()
                        plan.conflicts shouldBe emptyList()
                        plan.statusPending shouldBe plan.dueIds
                    }
                }
            }

            "every-start, $stored, kind $kind: due on every Target.Latest call, never pending, never under a target" {
                val subject = every("bootstrap-customers")
                val history = applied(ANCHOR) + stored.recordFor(subject.id, kind)
                for (setting in settings(Target.Latest, Target.Before(ANCHOR), Target.Through(ANCHOR))) {
                    withClue(setting) {
                        val plan = setting.plan(listOf(anchor, subject), history)
                        val latest = setting.target == Target.Latest
                        plan.outcomeOf(subject.id) shouldBe if (latest) Outcome.DUE else Outcome.NONE
                        plan.statusPending shouldBe emptyList()
                        plan.nothingDue shouldBe !latest
                    }
                }
            }
        }

        "order: once-only migrations in list order, then repeatable and every-start ones in list order" {
            val list = listOf(rep("rep-a"), once("001-a"), every("every-b"), once("002-b"), rep("rep-c"))
            val plan = planOf(list, emptyList())
            plan.dueIds shouldBe listOf("001-a", "002-b", "rep-a", "every-b", "rep-c")
            plan.statusPending shouldBe listOf("001-a", "002-b", "rep-a", "rep-c")
            plan.upToDate shouldBe emptyList()
        }

        "up to date: applied once-only ids and repeatables at their revision, in list order" {
            val list = listOf(once("001-a"), once("002-b"), once("003-c"), rep("rep-a", "r2"), rep("rep-b", "r2"))
            val history = applied("001-a", "003-c") +
                record("rep-a", kind = StoredKind.REPEATABLE, revision = "r2") +
                record("rep-b", kind = StoredKind.REPEATABLE, revision = "r1")
            val plan = planOf(list, history, outOfOrder = OutOfOrder.RUN)
            plan.upToDate shouldBe listOf("001-a", "003-c", "rep-a")
            plan.dueIds shouldBe listOf("002-b", "rep-b")
        }

        "out of order under FAIL: a conflict naming the first applied once-only migration listed after it" {
            val list = listOf(once("006-order-totals"), once("007-product-slugs"), once("008-cart-currency"))
            val history = applied("006-order-totals", "008-cart-currency")
            val plan = planOf(list, history)
            plan.conflicts shouldBe listOf(
                "007-product-slugs is pending, but 008-cart-currency, listed after it, is applied " +
                    "(out of order; OutOfOrder.RUN runs it)"
            )
            plan.due shouldBe listOf(DueMigration(list[1], listOf("008-cart-currency")))
        }

        "out of order under RUN: no conflict, and the due migration carries the applied ids listed after it" {
            val list = listOf(once("001-a"), once("002-b"), once("003-c"), once("004-d"), once("005-e"))
            val history = applied("001-a", "003-c", "005-e") + record("004-d", HistoryState.FAILED)
            val plan = planOf(list, history, outOfOrder = OutOfOrder.RUN)
            plan.conflicts shouldBe emptyList()
            plan.due.map { it.migration.id to it.appliedAfter } shouldBe
                listOf("002-b" to listOf("003-c", "005-e"), "004-d" to listOf("005-e"))
            plan.due.map { it.outOfOrder } shouldBe listOf(true, true)
        }

        "out of order: each pending migration listed before an applied one is a conflict, in list order" {
            val list = listOf(once("001-a"), once("002-b"), once("003-c"))
            planOf(list, applied("003-c")).conflicts shouldBe listOf(
                "001-a is pending, but 003-c, listed after it, is applied (out of order; OutOfOrder.RUN runs it)",
                "002-b is pending, but 003-c, listed after it, is applied (out of order; OutOfOrder.RUN runs it)"
            )
        }

        "repeatable and every-start documents and unknown ids never make a once-only migration out of order" {
            val list = listOf(once("001-a"), once("002-b"), rep("reference-countries"), every("bootstrap-customers"))
            val history = listOf(
                record("reference-countries", kind = StoredKind.REPEATABLE, revision = "r2"),
                record("bootstrap-customers", kind = StoredKind.EVERY_START),
                record("009-gone")
            )
            val plan = planOf(list, history)
            plan.conflicts shouldBe emptyList()
            plan.dueIds shouldBe listOf("001-a", "002-b", "bootstrap-customers")
            plan.due.all { it.appliedAfter.isEmpty() } shouldBe true
        }

        "a declared once-only id whose document a repeatable wrote does not count as applied once-only" {
            val list = listOf(once("001-a"), once("002-b"))
            val history = listOf(record("002-b", kind = StoredKind.REPEATABLE, revision = "r1"))
            val plan = planOf(list, history)
            plan.conflicts shouldBe emptyList()
            plan.dueIds shouldBe listOf("001-a")
            plan.upToDate shouldBe listOf("002-b")
        }

        "only migrations the target reaches can conflict; what it stops before is pending" {
            val list = listOf(once("001-a"), once("002-b"), once("003-c"), once("004-d"))
            val history = applied("004-d")
            val before = planOf(list, history, Target.Before("002-b"))
            before.dueIds shouldBe listOf("001-a")
            before.pending shouldBe listOf("002-b", "003-c")
            before.conflicts shouldBe listOf(
                "001-a is pending, but 004-d, listed after it, is applied (out of order; OutOfOrder.RUN runs it)"
            )
            val through = planOf(list, history, Target.Through("002-b"), OutOfOrder.RUN)
            through.due.map { it.migration.id to it.appliedAfter } shouldBe
                listOf("001-a" to listOf("004-d"), "002-b" to listOf("004-d"))
            through.pending shouldBe listOf("003-c")
            through.upToDate shouldBe listOf("004-d")
        }

        "a target that names no once-only migration in the list is a precondition the planner refuses" {
            shouldThrow<IllegalArgumentException> {
                planOf(listOf(once("001-a"), rep("rep")), emptyList(), Target.Before("rep"))
            }.message shouldBe "the target names rep, which is not a once-only migration in the list"
        }

        "while adopting, an adoption gap is no conflict and its id is due; after the hook, it follows the policy" {
            val list =
                listOf(once("001-initial-setup"), once("002-carts"), once("003-file-store"), once("004-order-status"))
            val history = applied("001-initial-setup", "003-file-store", origin = Origin.ADOPTED)
            for (outOfOrder in OutOfOrder.entries) {
                val adopting = planOf(list, history, outOfOrder = outOfOrder, adopting = true)
                adopting.conflicts shouldBe emptyList()
                adopting.dueIds shouldBe listOf("002-carts", "004-order-status")
                adopting.untracked shouldBe false
            }
            planOf(list, history).conflicts shouldBe listOf(
                "002-carts is pending, but 003-file-store, listed after it, is applied " +
                    "(out of order; OutOfOrder.RUN runs it)"
            )
            planOf(list, history, outOfOrder = OutOfOrder.RUN).due.first() shouldBe
                DueMigration(list[1], listOf("003-file-store"))
        }

        "supersede: every replaced id APPLIED records the baseline without running it" {
            val replaced = listOf("001-initial-setup", "002-carts", "003-file-store")
            val baseline = once("100-baseline", supersedes = replaced)
            val list = listOf(baseline, rep("reference-countries"))
            for (adopting in listOf(false, true)) {
                val plan = planOf(list, applied(*replaced.toTypedArray(), origin = Origin.ADOPTED), adopting = adopting)
                plan.superseded shouldBe listOf(baseline)
                plan.dueIds shouldBe listOf("reference-countries")
                plan.conflicts shouldBe emptyList()
                plan.statusPending shouldBe listOf("reference-countries")
            }
        }

        "supersede: no replaced id APPLIED runs the baseline, also after a failed run" {
            val baseline = once("100-baseline", supersedes = listOf("001-initial-setup", "002-carts"))
            for (history in listOf(emptyList(), listOf(record("100-baseline", HistoryState.FAILED)))) {
                val plan = planOf(listOf(baseline), history + record("002-carts", HistoryState.FAILED))
                plan.dueIds shouldBe listOf("100-baseline")
                plan.superseded shouldBe emptyList()
                plan.conflicts shouldBe emptyList()
            }
        }

        "supersede: some replaced ids APPLIED is a conflict naming them, or due while adopting" {
            val replaced = listOf(
                "001-initial-setup",
                "002-carts",
                "003-file-store",
                "004-order-status",
                "005-customer-external-ids",
                "006-order-totals"
            )
            val baseline = once("100-baseline", supersedes = replaced)
            val history =
                applied(*replaced.take(4).toTypedArray()) + record("005-customer-external-ids", HistoryState.FAILED)
            for (outOfOrder in OutOfOrder.entries) {
                val plan = planOf(listOf(baseline), history, outOfOrder = outOfOrder)
                plan.conflicts shouldBe listOf(
                    "100-baseline supersedes 6 migrations, but only 001-initial-setup, 002-carts, 003-file-store, " +
                        "004-order-status are applied. Deploy the previous release first."
                )
                plan.dueIds shouldBe listOf("100-baseline")
                plan.superseded shouldBe emptyList()

                val adopting = planOf(listOf(baseline), history, outOfOrder = outOfOrder, adopting = true)
                adopting.conflicts shouldBe emptyList()
                adopting.dueIds shouldBe listOf("100-baseline")
                adopting.nothingDue shouldBe false
            }
        }

        "supersede: an APPLIED baseline is settled and its list is not evaluated again" {
            val baseline = once("100-baseline", supersedes = listOf("001-initial-setup", "002-carts", "003-file-store"))
            val history = applied("001-initial-setup") +
                record("100-baseline", supersedes = listOf("001-initial-setup", "002-carts", "003-file-store"))
            val plan = planOf(listOf(baseline), history)
            plan.conflicts shouldBe emptyList()
            plan.upToDate shouldBe listOf("100-baseline")
            plan.nothingDue shouldBe true
            plan.unknownApplied shouldBe emptyList()
        }

        "a recorded baseline is exempt from the out-of-order policy; a later pending migration is not" {
            val baseline = once("006-baseline", supersedes = listOf("001-a", "002-b"))
            val list = listOf(baseline, once("007-c"), once("008-d"))
            val exempt = planOf(list, applied("001-a", "002-b", "007-c", "008-d"))
            exempt.superseded shouldBe listOf(baseline)
            exempt.conflicts shouldBe emptyList()
            exempt.nothingDue shouldBe false

            planOf(list, applied("001-a", "002-b", "008-d")).conflicts shouldBe
                listOf(
                    "007-c is pending, but 008-d, listed after it, is applied (out of order; OutOfOrder.RUN runs it)"
                )
        }

        "a partially superseded baseline is one conflict, not also an out-of-order one" {
            val list = listOf(once("100-baseline", supersedes = listOf("001-a", "002-b")), once("101-c"))
            planOf(list, applied("001-a", "101-c")).conflicts shouldBe listOf(
                "100-baseline supersedes 2 migrations, but only 001-a are applied. Deploy the previous release first."
            )
        }

        "a target stops before superseded records and partial squashes too" {
            val recordable = once("002-baseline", supersedes = listOf("old-1"))
            val partial = once("003-baseline", supersedes = listOf("old-2", "old-3"))
            val list = listOf(once("001-a"), recordable, partial)
            val plan = planOf(list, applied("old-1", "old-2"), Target.Through("001-a"))
            plan.dueIds shouldBe listOf("001-a")
            plan.superseded shouldBe emptyList()
            plan.pending shouldBe listOf("002-baseline", "003-baseline")
            // The partial squash is beyond the target; the applied ids the squashes name still put 001-a out of order.
            plan.conflicts shouldBe listOf(
                "001-a is pending, but old-1, listed after it, is applied (out of order; OutOfOrder.RUN runs it)"
            )
        }

        "a gap before a rename about to be recorded is out of order, as it is before the old id" {
            val renamed = once("002-carts-v2", supersedes = listOf("002-carts"))
            val list = listOf(once("001-initial-setup"), renamed)
            val history = applied("002-carts", origin = Origin.ADOPTED)
            val fail = planOf(list, history)
            fail.conflicts shouldBe listOf(
                "001-initial-setup is pending, but 002-carts, listed after it, is applied " +
                    "(out of order; OutOfOrder.RUN runs it)"
            )
            fail.superseded shouldBe listOf(renamed)

            val run = planOf(list, history, outOfOrder = OutOfOrder.RUN)
            run.conflicts shouldBe emptyList()
            run.due shouldBe listOf(DueMigration(list[0], listOf("002-carts")))
            run.superseded shouldBe listOf(renamed)

            for (outOfOrder in OutOfOrder.entries) {
                val adopting = planOf(list, history, outOfOrder = outOfOrder, adopting = true)
                adopting.conflicts shouldBe emptyList()
                adopting.dueIds shouldBe listOf("001-initial-setup")
            }
        }

        "a squash about to be recorded decides out of order as the list with the old migration does" {
            val plain = listOf(once("001-a"), once("002-b"), once("003-c"), once("004-d"), once("005-e"))
            val renamed = plain.take(3) + once("004-d-v2", supersedes = listOf("004-d")) + plain.drop(4)
            val history = applied("001-a", "002-b", "004-d")
            for (outOfOrder in OutOfOrder.entries) {
                for (adopting in listOf(false, true)) {
                    withClue("outOfOrder=$outOfOrder adopting=$adopting") {
                        val control = planOf(plain, history, outOfOrder = outOfOrder, adopting = adopting)
                        val plan = planOf(renamed, history, outOfOrder = outOfOrder, adopting = adopting)
                        plan.conflicts shouldBe control.conflicts
                        plan.due shouldBe control.due
                        plan.superseded.map { it.id } shouldBe listOf("004-d-v2")
                    }
                }
            }
            planOf(renamed, history).conflicts shouldBe listOf(
                "003-c is pending, but 004-d, listed after it, is applied (out of order; OutOfOrder.RUN runs it)"
            )
            val run = planOf(renamed, history, outOfOrder = OutOfOrder.RUN)
            run.due.map { it.migration.id to it.appliedAfter } shouldBe
                listOf("003-c" to listOf("004-d"), "005-e" to emptyList())
        }

        "an interrupted standalone adoption of ids a supersedes list names is a gap to a start without the hook" {
            val baseline = once("100-baseline", supersedes = listOf("001-x", "002-x", "003-x"))
            val list = listOf(once("000-pre"), baseline)
            // Written last-listed first, each migration preceded by the ids its supersedes list names: the three ids
            // the baseline replaces landed, 000-pre did not.
            val history = applied("001-x", "002-x", "003-x", origin = Origin.ADOPTED)
            planOf(list, history).conflicts shouldBe listOf(
                "000-pre is pending, but 001-x, listed after it, is applied (out of order; OutOfOrder.RUN runs it)"
            )
            planOf(list, history, outOfOrder = OutOfOrder.RUN).due shouldBe
                listOf(DueMigration(list[0], listOf("001-x", "002-x", "003-x")))
            for (outOfOrder in OutOfOrder.entries) {
                val adopting = planOf(list, history, outOfOrder = outOfOrder, adopting = true)
                adopting.conflicts shouldBe emptyList()
                adopting.dueIds shouldBe listOf("000-pre")
                adopting.superseded shouldBe listOf(baseline)
            }
        }

        "a replaced id that is not APPLIED puts no earlier migration out of order" {
            val baseline = once("100-baseline", supersedes = listOf("old-1", "old-2"))
            val list = listOf(once("001-a"), baseline)
            val history = listOf(record("old-1", HistoryState.FAILED), record("old-2", HistoryState.RUNNING))
            val plan = planOf(list, history)
            plan.conflicts shouldBe emptyList()
            plan.due shouldBe listOf(DueMigration(list[0]), DueMigration(baseline))
        }

        "an APPLIED superseding migration counts as itself for out of order; its list is not evaluated" {
            val baseline = once("100-baseline", supersedes = listOf("old-1", "old-2"))
            val list = listOf(once("001-a"), baseline)
            val history = applied("old-1") + record("100-baseline", supersedes = listOf("old-1", "old-2"))
            planOf(list, history, outOfOrder = OutOfOrder.RUN).due shouldBe
                listOf(DueMigration(list[0], listOf("100-baseline")))
            planOf(list, history).conflicts shouldBe listOf(
                "001-a is pending, but 100-baseline, listed after it, is applied (out of order; OutOfOrder.RUN runs it)"
            )
        }

        "unknown applied: APPLIED ids no declared id or supersedes list names, sorted; other states are ignored" {
            val list = listOf(once("001-a"), once("100-baseline", supersedes = listOf("old-declared")), rep("rep"))
            val history = applied("001-a", "zeta", "alpha", "old-declared", "old-stored") +
                record("099-squash", supersedes = listOf("old-stored")) +
                record("failed-gone", HistoryState.FAILED) +
                record("running-gone", HistoryState.RUNNING) +
                record("gone-repeatable", kind = StoredKind.REPEATABLE, revision = "r1")
            val warn = planOf(list, history)
            warn.unknownApplied shouldBe listOf("099-squash", "alpha", "gone-repeatable", "zeta")
            warn.conflicts shouldBe emptyList()
            warn.dueIds shouldBe listOf("rep")
            warn.upToDate shouldBe listOf("001-a")

            for (adopting in listOf(false, true)) {
                planOf(list, history, unknownApplied = UnknownApplied.FAIL, adopting = adopting).conflicts shouldBe
                    listOf(
                        "099-squash is applied, but the list does not declare it (UnknownApplied.FAIL)",
                        "alpha is applied, but the list does not declare it (UnknownApplied.FAIL)",
                        "gone-repeatable is applied, but the list does not declare it (UnknownApplied.FAIL)",
                        "zeta is applied, but the list does not declare it (UnknownApplied.FAIL)"
                    )
            }
        }

        "unknown applied under FAIL is a conflict on a start with nothing due" {
            val plan = planOf(
                listOf(once("006-order-totals")),
                applied("006-order-totals", "007-product-slugs"),
                unknownApplied = UnknownApplied.FAIL
            )
            plan.nothingDue shouldBe true
            plan.conflicts shouldBe
                listOf("007-product-slugs is applied, but the list does not declare it (UnknownApplied.FAIL)")
        }

        "conflicts: out of order and partial supersede in list order, then unknown applied ids" {
            val list = listOf(once("001-a"), once("002-b", supersedes = listOf("old-1", "old-2")), once("003-c"))
            val plan = planOf(list, applied("old-1", "003-c", "zz-gone"), unknownApplied = UnknownApplied.FAIL)
            plan.conflicts shouldBe listOf(
                "001-a is pending, but old-1, listed after it, is applied (out of order; OutOfOrder.RUN runs it)",
                "002-b supersedes 2 migrations, but only old-1 are applied. Deploy the previous release first.",
                "zz-gone is applied, but the list does not declare it (UnknownApplied.FAIL)"
            )
        }

        "needsTransactions when a due migration has a transactional step" {
            val outsideOnly = once("001-a")
            val inTransaction = onceInTransaction("002-b")
            val inBatches = migration("003-c").inBatches("orders", exists("x", false)) { }
            val both = migration("004-d").outsideTransaction { 1 }.inTransaction { }
            planOf(listOf(outsideOnly), emptyList()).needsTransactions shouldBe false
            planOf(listOf(outsideOnly, inTransaction), emptyList()).needsTransactions shouldBe true
            planOf(listOf(inBatches), emptyList()).needsTransactions shouldBe true
            planOf(listOf(both), emptyList()).needsTransactions shouldBe true
            planOf(listOf(outsideOnly, inTransaction), applied("002-b"), outOfOrder = OutOfOrder.RUN)
                .needsTransactions shouldBe false
            planOf(listOf(outsideOnly, rep("rep")), emptyList(), Target.Through("001-a")).needsTransactions shouldBe
                false
            planOf(emptyList(), emptyList()).needsTransactions shouldBe false
        }

        "untracked when history is empty and adoption cannot run" {
            planOf(listOf(once("001-a")), emptyList()).untracked shouldBe true
            planOf(listOf(once("001-a")), emptyList(), adopting = true).untracked shouldBe false
            planOf(listOf(once("001-a")), applied("001-a")).untracked shouldBe false
        }

        "nothing due: no migration to run and no squash to record" {
            planOf(
                listOf(once("001-a"), rep("rep", "r2")),
                applied("001-a") +
                    record("rep", kind = StoredKind.REPEATABLE, revision = "r2")
            ).nothingDue shouldBe true
            planOf(listOf(once("001-a")), emptyList()).nothingDue shouldBe false
        }

        "adoption can run while the hook is set and every document was adopted, an empty history included" {
            val hook = GodwitConfig(adoptApplied = { emptySet() }, holder = "test/1")
            adoptionCanRun(config(), emptyList()) shouldBe false
            adoptionCanRun(hook, emptyList()) shouldBe true
            adoptionCanRun(hook, applied("001-a", "002-b", origin = Origin.ADOPTED)) shouldBe true
            for (origin in listOf(Origin.RAN, Origin.SUPERSEDED, Origin.MARKED)) {
                adoptionCanRun(
                    hook,
                    applied("001-a", origin = Origin.ADOPTED) + applied("002-b", origin = origin)
                ) shouldBe
                    false
            }
        }
    }
}
