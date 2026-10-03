package godwit.core.docs

/** A fenced block of a published doc, in a language other than kotlin: the [ordinal]-th of [doc], from 1. */
class QuotedBlock(val doc: String, val ordinal: Int, val first: String, val check: Check) {
    override fun toString() = "$doc block $ordinal"
}

/** An inline code span of a published doc's prose that quotes an output. */
class QuotedInline(val doc: String, val text: String, val check: Check) {
    override fun toString() = "$doc `$text`"
}

private const val README = "README.md"
private const val ADOPTING = "docs/adopting-an-existing-database.md"
private const val ARCHITECTURE = "docs/architecture.md"
private const val BATCHED = "docs/batched-backfills.md"
private const val CONCEPTS = "docs/concepts.md"
private const val CONFIGURATION = "docs/configuration.md"
private const val DECLARING = "docs/declaring-migrations.md"
private const val DEPENDENCIES = "docs/dependencies.md"
private const val FAILURE = "docs/failure-and-recovery.md"
private const val HISTORY = "docs/history-and-reports.md"
private const val LIBRARIES = "docs/libraries-and-modules.md"
private const val LOCKING = "docs/locking.md"
private const val ORDERING = "docs/ordering-and-validation.md"
private const val REPEATABLE = "docs/repeatable-migrations.md"
private const val SQUASHING = "docs/squashing-migrations.md"
private const val TESTING = "docs/testing.md"
private const val TRANSACTIONS = "docs/transactions-and-sessions.md"

private val buildFile = NotOutput("a build file")
private val pseudocode = NotOutput("pseudocode of godwit's algorithm")
private val command = NotOutput("a command godwit sends, in mongosh syntax")
private val shellQuery = NotOutput("a query an operator runs in mongosh")
private val sketch = NotOutput("a sketch of a rejected design, not godwit API")
private val fields = NotOutput("the fields of a stored document and their types")

private fun block(doc: String, ordinal: Int, first: String, check: Check) = QuotedBlock(doc, ordinal, first, check)

/**
 * Every fenced block of the published docs in a language other than kotlin, in doc order, with how it is checked. A
 * block added, removed or moved in the docs no longer lines up with this list, which fails DocsFidelityTest until the
 * list says what the block is.
 */
val quotedBlocks: List<QuotedBlock> = listOf(
    block(README, 1, "godwitVersion=<version>", buildFile),
    block(README, 2, "val godwitVersion: String by project", buildFile),
    block(README, 3, "INFO  godwit - Acquired migration lock", logs(readme, "first start", exact = true)),
    block(README, 4, "INFO  godwit - Migrations up to date", logs(readme, "later start", exact = true)),

    block(
        ADOPTING,
        1,
        "{ \"changeId\": \"create-carts\"",
        NotOutput("a row of another tool's record, which the hook reads")
    ),
    block(ADOPTING, 2, "INFO  godwit - Adopted applied migrations", logs(adoptionByTwoInstances, "shop-7f9c4/1")),
    block(ADOPTING, 3, "db.getCollection(\"godwit-history\")", shellQuery),
    block(ADOPTING, 4, "The database has collections", message(untrackedDatabases, "the hook adopts nothing")),
    block(ADOPTING, 5, "INFO  godwit - Acquired migration lock", logs(adoptionByTwoInstances, "shop-7f9c4/1")),
    block(ADOPTING, 6, "INFO  godwit - Waiting for migration lock", logs(adoptionByTwoInstances, "shop-2b8e1/1")),
    block(ADOPTING, 7, "java.lang.IllegalStateException", exception(markBeforeAdoption, "markApplied")),
    block(ADOPTING, 8, "// not godwit API", sketch),
    block(ADOPTING, 9, "// not godwit API", sketch),

    block(ARCHITECTURE, 1, "migrate(migrations, target):", pseudocode),
    block(ARCHITECTURE, 2, "markApplied(id, reason):", pseudocode),
    block(ARCHITECTURE, 3, "run(m):", pseudocode),
    block(ARCHITECTURE, 4, "stateDiagram-v2", NotOutput("a state diagram")),
    block(ARCHITECTURE, 5, "db.getCollection(\"godwit-lock\").findOneAndUpdate(", command),
    block(ARCHITECTURE, 6, "db.getCollection(\"godwit-lock\").updateOne(", command),
    block(ARCHITECTURE, 7, "db.getCollection(\"godwit-lock\").updateOne(", command),
    block(ARCHITECTURE, 8, "db.getCollection(\"godwit-lock\").findOne(", command),
    block(ARCHITECTURE, 9, "db.getCollection(\"godwit-history\").find(", command),
    block(ARCHITECTURE, 10, "db.getCollection(\"godwit-history\").findOneAndUpdate(", command),
    block(ARCHITECTURE, 11, "db.getCollection(\"godwit-history\").findOneAndUpdate(", command),
    block(ARCHITECTURE, 12, "db.getCollection(\"godwit-history\").updateOne(", command),
    block(ARCHITECTURE, 13, "db.getCollection(\"godwit-history\").updateOne(", command),
    block(ARCHITECTURE, 14, "db.getCollection(\"godwit-history\").updateOne(", command),
    block(ARCHITECTURE, 15, "pages(m, checkpoint):", pseudocode),
    block(ARCHITECTURE, 16, "db.getCollection(\"godwit-history\").updateOne(", command),
    block(ARCHITECTURE, 17, "_id                 String      migration id", fields),
    block(ARCHITECTURE, 18, "_id                 String      the history", fields),

    block(BATCHED, 1, "{", document(killedDuringPage38, "006 after the kill")),
    block(BATCHED, 2, "WARN  godwit - Resuming interrupted migration", logs(killedDuringPage38, "next start")),
    block(BATCHED, 3, "WARN  godwit - Slow transaction", logs(slowTransactions, "slow transactions")),
    block(BATCHED, 4, "ERROR godwit - Migration failed", logs(mixedIdTypes, "006 over two _id types")),

    block(CONCEPTS, 1, "history 005", NotOutput("a table of what one run of 005 writes, and with which guarantee")),
    block(CONCEPTS, 2, "migrate(list)", pseudocode),
    block(CONCEPTS, 3, "run starts", NotOutput("a state diagram")),
    block(CONCEPTS, 4, "{", document(shopReleases, "004 applied")),
    block(
        CONCEPTS,
        5,
        "INFO  godwit - Migrations up to date",
        logs(shopWithoutEveryStart, "later start", exact = true)
    ),
    block(
        CONCEPTS,
        6,
        "INFO  godwit - Acquired migration lock",
        logs(shopReleases, "start of the current release", exact = true)
    ),
    block(CONCEPTS, 7, "INFO  godwit - Waiting for migration lock", logs(rollingDeploy, "shop-2b8e1/1")),
    block(CONCEPTS, 8, "WARN  godwit - Resuming interrupted migration", logs(killedDuringPage38, "next start")),
    block(CONCEPTS, 9, "WARN  godwit - Retrying transaction", logs(shopHistory, "deploy, first start")),
    block(CONCEPTS, 10, "godwit.core.TransactionsUnsupportedException", exception(standaloneServer, "first start")),

    block(
        CONFIGURATION,
        1,
        "WARN  godwit - Running out-of-order migration",
        logs(stagingRanABranchEarly, "OutOfOrder.RUN")
    ),
    block(
        CONFIGURATION,
        2,
        "<configuration>",
        NotOutput("the Logback configuration whose pattern prints the actual lines here")
    ),
    block(CONFIGURATION, 3, "dependencies {", buildFile),
    block(CONFIGURATION, 4, "INFO  godwit - Acquired migration lock", logs(shopReleases, "release adding 004")),
    block(
        CONFIGURATION,
        5,
        "INFO  godwit - Migrations up to date",
        logs(shopWithoutEveryStart, "later start", exact = true)
    ),
    block(CONFIGURATION, 6, "// not godwit API", sketch),

    block(DECLARING, 1, "e: Initializer type mismatch", CompilerError),
    block(DECLARING, 2, "e: Unresolved reference 'inTransaction'", CompilerError),
    block(DECLARING, 3, "e: Unresolved reference 'ensureCollection'", CompilerError),
    block(DECLARING, 4, "e: Unresolved reference 'outsideTransaction'", CompilerError),
    block(DECLARING, 5, "e: Unresolved reference 'session'", CompilerError),
    block(DECLARING, 6, "INFO  godwit - Applied migration", logs(shopReleases, "release adding 004")),
    block(DECLARING, 7, "src/main/kotlin/com/example/shop/migrations/", NotOutput("a file tree")),
    block(DECLARING, 8, "godwit.core.InvalidMigrationsException", exception(invalidLists, "repeatable inBatches")),
    block(DECLARING, 9, "// A class per migration", sketch),

    block(DEPENDENCIES, 1, "e: No value passed for parameter 'gateway'", CompilerError),
    block(DEPENDENCIES, 2, "ERROR godwit - Migration failed", logsAndException(twoClients, "005 with two clients")),
    block(DEPENDENCIES, 3, "godwit.core.PendingMigrationsException", exception(workerBeforeTheShop, "requireUpToDate")),
    block(DEPENDENCIES, 4, "godwit.core.MigrationFailedException", testKit),
    block(DEPENDENCIES, 5, "migration(\"005-customer-external-ids\")", sketch),
    block(DEPENDENCIES, 6, "class ShopDeps", sketch),

    block(
        FAILURE,
        1,
        "ERROR godwit - Migration failed",
        logs(searchIndexNotReady, "first start", runner = Runner.ATLAS)
    ),
    block(
        FAILURE,
        2,
        "godwit.core.MigrationFailedException",
        exception(searchIndexNotReady, "first start", Runner.ATLAS)
    ),
    block(FAILURE, 3, "{", document(searchIndexNotReady, "001 failed", runner = Runner.ATLAS)),
    block(FAILURE, 4, "{", document(killedMidOutsideStep, "001 after the kill")),
    block(FAILURE, 5, "WARN  godwit - Resuming interrupted migration", logs(killedMidOutsideStep, "next start")),
    block(FAILURE, 6, "godwit.core.MigrationFailedException", exception(paymentStatusFragile, "009")),
    block(FAILURE, 7, "WARN  godwit - Retrying transaction", logs(shopHistory, "deploy, first start")),
    block(FAILURE, 8, "WARN  godwit - Slow transaction", logs(transactionPastItsLifetime, "007 in one transaction")),
    block(
        FAILURE,
        9,
        "godwit.core.MigrationFailedException",
        exception(transactionPastItsLifetime, "007 in one transaction")
    ),
    block(FAILURE, 10, "godwit.core.MigrationFailedException", exception(ddlInTransaction, "008 in a transaction")),
    block(
        FAILURE,
        11,
        "godwit.core.MigrationFailedException",
        exception(twoClients, "bootstrap-customers with two clients")
    ),
    block(FAILURE, 12, "DEBUG godwit - Committed batch", logs(shopHistory, "deploy, next start")),
    block(FAILURE, 13, "{", document(shopHistory, "006 failed")),
    block(FAILURE, 14, "db.orders.find({", shellQuery),
    block(FAILURE, 15, "WARN  godwit - Lost migration lock", logs(networkPartition, "shop-7f9c4/1")),
    block(FAILURE, 16, "godwit.core.LockLostException", exception(networkPartition, "partitioned holder")),
    block(FAILURE, 17, "WARN  godwit - Lost migration lock", logs(stepErrorWhenTheLockIsLost, "005")),
    block(FAILURE, 18, "godwit.core.LockLostException", exception(stepErrorWhenTheLockIsLost, "005")),
    block(FAILURE, 19, "godwit.core.LockTimeoutException", exception(lockWaitTimeout, "waiting process")),
    block(FAILURE, 20, "godwit.core.TransactionsUnsupportedException", exception(standaloneServer, "first start")),
    block(FAILURE, 21, "godwit.core.PlanConflictException", exception(stagingRanABranchEarly, "OutOfOrder.FAIL")),
    block(FAILURE, 22, "godwit.core.PlanConflictException", exception(squash, "demo database")),
    block(FAILURE, 23, "godwit.core.UntrackedDatabaseException", exception(untrackedDatabases, "a restored backup")),
    block(FAILURE, 24, "godwit.core.InvalidMigrationsException", exception(invalidLists, "a merge with two 007s")),
    block(FAILURE, 25, "godwit.core.MigrationFailedException", exception(markedByHand, "008 on production")),
    block(FAILURE, 26, "down {", sketch),

    block(HISTORY, 1, "{", document(killedMidOutsideStep, "001 after the kill")),
    block(HISTORY, 2, "{", document(shopHistory, "005 failed")),
    block(HISTORY, 3, "{", document(shopReleases, "004 applied")),
    block(HISTORY, 4, "{", document(shopHistory, "003 adopted")),
    block(HISTORY, 5, "{", document(squash, "recorded baseline")),
    block(HISTORY, 6, "{", document(markedByHand, "008 marked")),
    block(HISTORY, 7, "{", document(shopReleases, "reference-countries applied")),
    block(HISTORY, 8, "{", document(shopReleases, "bootstrap-customers after 214 starts")),
    block(HISTORY, 9, "{", document(networkPartition, "006 while the holder ran it")),
    block(HISTORY, 10, "{", document(stagingRanABranchEarly, "007 out of order")),
    block(HISTORY, 11, "001-initial-setup APPLIED", printedLines(shopHistory, "printHistory")),
    block(HISTORY, 12, "WARN  godwit - Marked migration applied", logs(markedByHand, "markApplied")),
    block(HISTORY, 13, "java.lang.IllegalStateException", exception(markBeforeAdoption, "markApplied")),
    block(HISTORY, 14, "INFO  godwit - Acquired migration lock", logs(shopHistory, "deploy, first start")),
    block(HISTORY, 15, "INFO  godwit - Acquired migration lock", logs(shopHistory, "deploy, next start")),
    block(HISTORY, 16, "// Everything not applied", shellQuery),

    block(LIBRARIES, 1, ":app", NotOutput("a module layout")),
    block(LIBRARIES, 2, "godwit.core.InvalidMigrationsException", exception(invalidLists, "a library's list appended")),
    block(LIBRARIES, 3, "godwit.core.InvalidMigrationsException", exception(invalidLists, "module lists concatenated")),
    block(
        LIBRARIES,
        4,
        "WARN  godwit - Unknown applied migrations",
        logs(unknownAppliedIds, "the customers module's own list")
    ),

    block(LOCKING, 1, "{", document(shopReleases, "lock held")),
    block(LOCKING, 2, "{", document(shopReleases, "lock released")),
    block(LOCKING, 3, "INFO  godwit - Migrations up to date", logs(shopWithoutEveryStart, "later start", exact = true)),
    block(
        LOCKING,
        4,
        "INFO  godwit - Acquired migration lock",
        logs(shopReleases, "start of the current release", exact = true)
    ),
    block(LOCKING, 5, "INFO  godwit - Waiting for migration lock", logs(rollingDeploy, "shop-2b8e1/1")),
    block(LOCKING, 6, "INFO  godwit - Acquired migration lock", logs(rollingDeploy, "shop-7f9c4/1", exact = true)),
    block(LOCKING, 7, "INFO  godwit - Waiting for migration lock", logs(rollingDeploy, "shop-2b8e1/1")),
    block(LOCKING, 8, "WARN  godwit - Lock renewal failed", logs(networkPartition, "shop-7f9c4/1")),
    block(LOCKING, 9, "WARN  godwit - Lock renewal failed", logs(networkPartition, "shop-7f9c4/1")),
    block(LOCKING, 10, "{", document(networkPartition, "006 while the holder ran it")),
    block(LOCKING, 11, "{", document(networkPartition, "006 after the takeover")),

    block(ORDERING, 1, "godwit.core.InvalidMigrationsException", exception(invalidLists, "declared twice")),
    block(ORDERING, 2, "godwit.core.InvalidMigrationsException", exception(invalidLists, "invalid id")),
    block(ORDERING, 3, "godwit.core.InvalidMigrationsException", exception(invalidLists, "declared twice")),
    block(ORDERING, 4, "godwit.core.InvalidMigrationsException", exception(invalidLists, "superseded twice")),
    block(ORDERING, 5, "godwit.core.InvalidMigrationsException", exception(invalidLists, "supersedes a declared id")),
    block(ORDERING, 6, "godwit.core.InvalidMigrationsException", exception(invalidLists, "two branches number 007")),
    block(
        ORDERING,
        7,
        "godwit.core.InvalidMigrationsException",
        exception(invalidLists, "once-only appended at the end")
    ),
    block(ORDERING, 8, "godwit.core.InvalidMigrationsException", exception(invalidLists, "blank revision")),
    block(ORDERING, 9, "godwit.core.InvalidMigrationsException", exception(invalidLists, "repeatable inBatches")),
    block(
        ORDERING,
        10,
        "godwit.core.InvalidMigrationsException",
        exception(invalidLists, "batch size above the ceiling")
    ),
    block(ORDERING, 11, "godwit.core.InvalidMigrationsException", exception(invalidLists, "target typo")),
    block(ORDERING, 12, "godwit.core.PlanConflictException", exception(stagingRanABranchEarly, "OutOfOrder.FAIL")),
    block(ORDERING, 13, "godwit.core.PlanConflictException", exception(stagingRanABranchEarly, "OutOfOrder.FAIL")),
    block(
        ORDERING,
        14,
        "WARN  godwit - Running out-of-order migration",
        logs(stagingRanABranchEarly, "OutOfOrder.RUN")
    ),
    block(ORDERING, 15, "{", document(stagingRanABranchEarly, "007 out of order")),
    block(ORDERING, 16, "WARN  godwit - Unknown applied migrations", logs(stagingRanABranchEarly, "release 1.3")),
    block(ORDERING, 17, "godwit.core.PlanConflictException", exception(stagingRanABranchEarly, "UnknownApplied.FAIL")),

    block(
        REPEATABLE,
        1,
        "INFO  godwit - Acquired migration lock",
        logs(shopReleases, "start of the current release", exact = true)
    ),
    block(REPEATABLE, 2, "{", document(shopReleases, "reference-countries bumped")),

    block(SQUASHING, 1, "INFO  godwit - Recorded superseded migration", logs(squash, "recorded")),
    block(SQUASHING, 2, "INFO  godwit - Running migration", logs(squash, "new database")),
    block(SQUASHING, 3, "{", document(squash, "recorded baseline", abbreviated = true)),
    block(SQUASHING, 4, "{", document(squash, "baseline that ran", abbreviated = true)),
    block(SQUASHING, 5, "db.getCollection(\"godwit-history\").countDocuments(", shellQuery),
    block(SQUASHING, 6, "INFO  godwit - Adopted applied migrations", logs(squash, "adoption and squash")),
    block(SQUASHING, 7, "WARN  godwit - Unknown applied migrations", logs(squash, "release N-1")),
    block(SQUASHING, 8, "// not godwit API", sketch),

    block(TESTING, 1, "val godwitVersion: String by project", buildFile),
    block(TESTING, 2, "update on products ran without the step's session", testKit),
    block(TESTING, 3, "// the extract-to-function workaround", sketch),
    block(TESTING, 4, "// not godwit API", sketch),

    block(TRANSACTIONS, 1, "godwit.core.MigrationFailedException", testKit),
    block(TRANSACTIONS, 2, "INFO  godwit - Running migration", logs(shopHistory, "deploy, first start")),
    block(TRANSACTIONS, 3, "WARN  godwit - Slow transaction", logs(slowTransactions, "slow transactions")),
    block(TRANSACTIONS, 4, "godwit.core.TransactionsUnsupportedException", exception(standaloneServer, "first start")),
    block(TRANSACTIONS, 5, "docker run", NotOutput("a shell command that starts a server"))
)

private fun inline(doc: String, text: String, check: Check) = QuotedInline(doc, text, check)

/**
 * Every inline span of the published docs' prose that [inlineQuotes] finds, with how it is checked. A span the docs add
 * or change is missing here, which fails DocsFidelityTest until this list says what it is.
 */
val quotedInlines: List<QuotedInline> = listOf(
    inline(
        CONFIGURATION,
        "007-product-slugs is pending, but 008-cart-currency, listed after it, is applied (out of order; " +
            "OutOfOrder.RUN runs it)",
        inlineProblem(stagingRanABranchEarly, "OutOfOrder.FAIL")
    ),
    inline(
        FAILURE,
        "WARN Resuming interrupted migration id=004-order-status attempts=2",
        inlineLog(killedMidTransaction, "next start")
    ),
    inline(
        FAILURE,
        "java.util.NoSuchElementException",
        inlineField(paymentStatusFragile, "009 failed", "lastError.type")
    ),
    inline(
        FAILURE,
        "com.mongodb.MongoTimeoutException: Timed out while waiting for a server that matches " +
            "ReadPreferenceServerSelector{readPreference=primary}...",
        inlineException(clusterUnreachable, "first history read")
    ),
    inline(
        FAILURE,
        "WARN Running out-of-order migration id=007-product-slugs appliedAfter=[008-cart-currency]",
        inlineLog(stagingRanABranchEarly, "OutOfOrder.RUN")
    ),
    inline(
        HISTORY,
        "error=java.net.http.HttpTimeoutException: request timed out",
        inlineValue(shopHistory, "deploy, first start")
    ),
    inline(
        HISTORY,
        "error=com.mongodb.MongoOperationTimeoutException: Timed out while waiting for a server that matches " +
            "WritableServerSelector...",
        inlineValue(networkPartition, "shop-7f9c4/1")
    ),
    inline(HISTORY, "error=WriteConflict (112)", inlineValue(shopHistory, "deploy, first start")),
    inline(HISTORY, "error=MongoSocketReadException (-2)", inlineValue(otherRetries, "network error")),
    inline(HISTORY, "error=commit", inlineValue(otherRetries, "commit")),
    inline(
        HISTORY,
        "Migrations up to date runId=0199a4c2-... checked=7 durationMs=6",
        inlineLog(shopWithoutEveryStart, "later start")
    ),
    inline(
        HISTORY,
        "Waiting for migration lock holder=shop-7f9c4/1 holderRunId=0199a4c2-... " +
            "expiresAt=2026-10-02T10:15:00.210Z waitedMs=10004",
        inlineLog(rollingDeploy, "shop-2b8e1/1")
    ),
    inline(
        HISTORY,
        "Acquired migration lock runId=0199a4c2-... lockWaitMs=212",
        inlineLog(shopReleases, "release adding 004")
    ),
    inline(
        HISTORY,
        "Lock renewal failed runId=0199a4c2-... holder=shop-7f9c4/1 " +
            "error=com.mongodb.MongoOperationTimeoutException: Timed out while waiting for a server that matches " +
            "WritableServerSelector...",
        inlineLog(networkPartition, "shop-7f9c4/1")
    ),
    inline(
        HISTORY,
        "Lost migration lock runId=0199a4c2-... holder=shop-7f9c4/1 reason=DEADLINE_PASSED",
        inlineLog(networkPartition, "shop-7f9c4/1")
    ),
    inline(
        HISTORY,
        "Lock release failed runId=0199a4c2-... holder=shop-7f9c4/1 " +
            "error=com.mongodb.MongoOperationTimeoutException: Timed out while waiting for a server that matches " +
            "WritableServerSelector...",
        inlineLog(networkPartition, "shop-7f9c4/1")
    ),
    inline(
        HISTORY,
        "Adopted applied migrations adopted=[001-initial-setup, 002-carts, 003-file-store] " +
            "ignored=[2025-02-cart-index-hotfix]",
        inlineLog(shopHistory, "adopting start")
    ),
    inline(
        HISTORY,
        "Recorded superseded migration id=100-baseline supersedes=[001-initial-setup, ..., 006-order-totals]",
        inlineLog(squash, "recorded")
    ),
    inline(
        HISTORY,
        "Resuming interrupted migration id=006-order-totals attempts=2",
        inlineLog(killedDuringPage38, "next start")
    ),
    inline(
        HISTORY,
        "Running out-of-order migration id=007-product-slugs appliedAfter=[008-cart-currency]",
        inlineLog(stagingRanABranchEarly, "OutOfOrder.RUN")
    ),
    inline(
        HISTORY,
        "Running migration id=004-order-status kind=ONCE steps=[IN_TRANSACTION] attempt=1",
        inlineLog(shopReleases, "release adding 004")
    ),
    inline(
        HISTORY,
        "Retrying transaction id=004-order-status attempt=2 error=WriteConflict (112)",
        inlineLog(shopHistory, "deploy, first start")
    ),
    inline(
        HISTORY,
        "Slow transaction id=007-customer-email-lower attempt=1 durationMs=24310",
        inlineLog(transactionPastItsLifetime, "007 in one transaction")
    ),
    inline(
        HISTORY,
        "Committed batch id=006-order-totals batch=41 lastId=66fcf2a19b1e8a0012a1c4bc",
        inlineLog(shopHistory, "start after the order is fixed")
    ),
    inline(
        HISTORY,
        "Applied migration id=004-order-status kind=ONCE steps=[IN_TRANSACTION] attempts=1 txRetries=0 batches=0 " +
            "durationMs=84 ordersPaid=1200 ordersPending=37",
        inlineLog(shopReleases, "release adding 004")
    ),
    inline(
        HISTORY,
        "Unknown applied migrations ids=[009-order-payment-status]",
        inlineLog(unknownAppliedIds, "without 009")
    ),
    inline(
        HISTORY,
        "Migration failed id=005-customer-external-ids step=OUTSIDE_TRANSACTION attempts=1 " +
            "error=java.net.http.HttpTimeoutException: request timed out",
        inlineLog(shopHistory, "deploy, first start")
    ),
    inline(
        HISTORY,
        "Migrations complete runId=0199a4c2-... ran=2 recorded=0 upToDate=6 lockWaitMs=212 durationMs=402",
        inlineLog(rollingDeploy, "shop-7f9c4/1")
    ),
    inline(
        LOCKING,
        "Waiting for migration lock holder=shop-7f9c4/1 expiresAt=...10:15:00.210Z waitedMs=10004",
        inlineLog(rollingDeploy, "shop-2b8e1/1")
    ),
    inline(LOCKING, "Migrations complete ... ran=0", inlineLog(nothingDueUnderTheLock, "shop-2b8e1/1")),
    inline(LOCKING, "Waiting for migration lock holder=shop-7f9c4/1", inlineLog(rollingDeploy, "shop-2b8e1/1")),
    inline(
        LOCKING,
        "WARN Resuming interrupted migration id=006-order-totals attempts=2",
        inlineLog(networkPartition, "shop-2b8e1/1")
    ),
    inline(
        LOCKING,
        "WARN Lost migration lock reason=DEADLINE_PASSED",
        inlineLog(networkPartition, "shop-7f9c4/1")
    ),
    inline(LOCKING, "WARN Lost migration lock reason=NOT_OWNER", inlineLog(lockDocumentDeleted, "deleted lock")),
    inline(ORDERING, "invalid id \"<id>\": ids match [A-Za-z0-9][A-Za-z0-9._-]{0,127}", inlineProblem(invalidLists)),
    inline(ORDERING, "duplicate id <id> at positions <i> and <j>", inlineProblem(invalidLists)),
    inline(ORDERING, "duplicate id <id>: named in the supersedes lists of <a> and <b>", inlineProblem(invalidLists)),
    inline(ORDERING, "duplicate id <id>: named twice in the supersedes list of <a>", inlineProblem(invalidLists)),
    inline(ORDERING, "<a> supersedes <id>, which the list declares", inlineProblem(invalidLists)),
    inline(
        ORDERING,
        "<id> is listed after <previous>, but its numeric prefix <n> is not greater than <m>",
        inlineProblem(invalidLists)
    ),
    inline(
        ORDERING,
        "<id> is once-only but listed after <other> (<kind>); list repeatable and every-start migrations after every " +
            "once-only migration",
        inlineProblem(invalidLists)
    ),
    inline(ORDERING, "<id> has a blank revision", inlineProblem(invalidLists)),
    inline(
        ORDERING,
        "<id> is <kind> and uses inBatches, which only once-only migrations can use",
        inlineProblem(invalidLists)
    ),
    inline(ORDERING, "<id> has batchSize <n>; batchSize is 1 to 10000", inlineProblem(invalidLists)),
    inline(ORDERING, "Target.Before(\"<id>\") names no once-only migration in the list", inlineProblem(invalidLists)),
    inline(
        ORDERING,
        "duplicate id 002-carts: named twice in the supersedes list of 100-baseline",
        inlineProblem(invalidLists, "superseded twice in one list")
    ),
    inline(REPEATABLE, "Migrations complete ... ran=0", inlineLog(nothingDueUnderTheLock, "shop-2b8e1/1")),
    inline(
        REPEATABLE,
        "Unknown applied migrations ids=[reference-countries]",
        inlineLog(unknownAppliedIds, "without reference-countries")
    ),
    inline(SQUASHING, "Running migration id=100-baseline", inlineLog(squash, "new database"))
)
