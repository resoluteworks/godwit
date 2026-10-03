# Design decisions

This page indexes every design decision behind godwit, one record per decision: what was decided, the options that
were considered, why this one, what it costs, and the page that explains it in depth. The records are numbered and the
numbers are stable, so a discussion, a commit or a review comment can cite them ("DD-12").

## Contents

| # | Decision | In depth |
|---|---|---|
| [DD-1](#dd-1-a-migration-is-a-chained-value) | A migration is a chained value | [Declaring migrations](declaring-migrations.md) |
| [DD-2](#dd-2-dependencies-are-function-parameters) | Dependencies are function parameters | [Dependencies](dependencies.md) |
| [DD-3](#dd-3-a-fixed-two-phase-step-model) | A fixed two-phase step model | [Declaring migrations](declaring-migrations.md) |
| [DD-4](#dd-4-step-names-state-the-guarantee) | Step names state the guarantee | [Declaring migrations](declaring-migrations.md) |
| [DD-5](#dd-5-an-explicit-session-checked-in-tests) | An explicit `session`, checked in tests | [Transactions and sessions](transactions-and-sessions.md) |
| [DD-6](#dd-6-flat-global-ids-and-no-migrations-in-libraries) | Flat global ids, and no migrations in libraries | [Ordering and validation](ordering-and-validation.md), [Libraries and modules](libraries-and-modules.md) |
| [DD-7](#dd-7-out-of-order-migrations-fail-by-default) | Out-of-order migrations fail by default | [Ordering and validation](ordering-and-validation.md) |
| [DD-8](#dd-8-a-plain-list-validated-before-any-io) | A plain `List<Migration>`, validated before any I/O | [Ordering and validation](ordering-and-validation.md) |
| [DD-9](#dd-9-wait-for-a-busy-lock-with-a-timeout) | Wait for a busy lock, with a timeout | [Locking](locking.md) |
| [DD-10](#dd-10-one-history-document-per-migration-updated-in-place) | One history document per migration, updated in place | [History and reports](history-and-reports.md) |
| [DD-11](#dd-11-roll-forward-only) | Roll forward only | [Failure and recovery](failure-and-recovery.md) |
| [DD-12](#dd-12-every-start-and-repeatable-migrations) | Every-start and repeatable migrations | [Repeatable migrations](repeatable-migrations.md) |
| [DD-13](#dd-13-library-paged-batches) | Library-paged batches | [Batched backfills](batched-backfills.md) |
| [DD-14](#dd-14-adoption-through-an-application-supplied-hook) | Adoption through an application-supplied hook | [Adopting an existing database](adopting-an-existing-database.md) |
| [DD-15](#dd-15-squashing-through-supersedes) | Squashing through `supersedes` | [Squashing migrations](squashing-migrations.md) |
| [DD-16](#dd-16-a-test-kit-that-drives-the-real-runner) | A test kit that drives the real runner | [Testing migrations](testing.md) |
| [DD-17](#dd-17-standalone-servers-fail-only-when-a-transaction-is-due) | Standalone servers fail only when a transaction is due | [Transactions and sessions](transactions-and-sessions.md) |
| [DD-18](#dd-18-logging-through-slf4j-api-2-only) | Logging through slf4j-api 2 only | [Configuration](configuration.md) |
| [DD-19](#dd-19-names-of-artifacts-packages-and-collections) | Names of artifacts, packages and collections | [Configuration](configuration.md) |
| [DD-20](#dd-20-receivers-not-context-parameters) | Receivers, not context parameters | [Transactions and sessions](transactions-and-sessions.md) |
| [DD-21](#dd-21-godwit-is-the-library-and-nothing-around-it) | godwit is the library and nothing around it | [Implementation plan](development/implementation-plan.md) |
| [DD-22](#dd-22-unknown-applied-ids-warn-by-default) | Unknown applied ids warn by default | [Ordering and validation](ordering-and-validation.md) |
| [DD-23](#dd-23-one-driver-version-as-an-api-dependency) | One driver version, as an `api` dependency | [Architecture](architecture.md) |
| [DD-24](#dd-24-three-ddl-helpers) | Three DDL helpers | [Outside-transaction steps](outside-transaction-steps.md) |
| [DD-25](#dd-25-0x-until-proven-in-production) | 0.x until proven in production | [Implementation plan](development/implementation-plan.md) |
| [DD-26](#dd-26-jvm-21-and-kotlin-24) | JVM 21 and Kotlin 2.4 | [README](../README.md#requirements) |

[Decisions that follow from these](#decisions-that-follow-from-these) lists the narrower decisions each page records.

## DD-1. A migration is a chained value

**Decision.** A migration is a value built by chained calls:
`migration(id).outsideTransaction { }.inTransaction { prepared -> }`. Nothing is a class, nothing is discovered.

**Options considered.**

- A class per migration, extending a base class per shape, found by scanning the classpath or listed by hand.
- A block builder that registers migrations as a side effect of running it.
- A chained value (chosen).

**Rationale.** The outside step's result reaches the transaction as a typed lambda parameter, not as a mutable field
set in one method and read in another. The compiler checks the shape: a draft without a step is not a `Migration`, and
nothing can follow the transactional step. A migration is one expression with one indentation level, and the list
that holds it is the one place that states the run order: nothing repeats the id or the order elsewhere, and no start
pays for a scan.

**Consequences.** A migration value that is not added to the list compiles and never runs; only an IDE unused-symbol
inspection or the coverage convention test in [testing](testing.md#every-migration-has-a-test) catches it. Shapes
other than "outside, then transactional" cannot be expressed (DD-3).

**In depth.** [Declaring migrations](declaring-migrations.md#a-chained-value-not-a-block-builder-or-a-class-per-migration).

## DD-2. Dependencies are function parameters

**Decision.** A migration that needs a service is a function that takes the service. The application's list is built
by a function that takes everything its migrations need. godwit has no dependency injection and no integration with
any container.

**Options considered.**

- A container lookup inside a step (`get<IdentityProvider>()`), resolved at run time.
- A typed dependency bag: one class with every service, a type parameter on each declaration.
- Dependencies passed as untyped arguments and matched to migrations by type at run time.
- Function parameters (chosen).

**Rationale.** A missing dependency is a compile error at the call that builds the list, not a failure on the start
where the migration happens to be due. The signature documents what a migration uses, and a test passes a fake with
no setup. Most migrations need no service at all, and they stay plain values.

**Consequences.** Adding a migration that needs a new service changes the list function's signature, and the compiler
points at every caller (startup and tests). Kotlin does not warn about an unused parameter, so a dependency that a
migration stops using lingers until someone notices. An expensive service is made lazy by the application with
`lazy`, not by godwit.

**In depth.** [Dependencies](dependencies.md#design-decisions).

## DD-3. A fixed two-phase step model

**Decision.** A migration has an optional outside step, then an optional `inTransaction` or `inBatches` step, and at
least one step. A retry runs the outside step again, then the transactional step.

**Options considered.**

- One step with a "transactional" flag.
- Free sequences of steps, with progress stored per step and a retry resuming at the failed step.
- Two fixed phases (chosen).

**Rationale.** With two fixed phases a retry has one rule, the history document needs no step index, and the prepared
value is always computed in the same run that uses it. With free sequences, the value produced by step k-1 would have
to be stored or recomputed, and a code change between runs could shift what step k means.

**Consequences.** The outside step must be idempotent, and on a retry it repeats all of its work; for thousands of
HTTP calls that is slow, though correct. Data then schema (backfill a field, then build a unique index on it) takes two
migrations.

**In depth.** [Declaring migrations](declaring-migrations.md#a-fixed-two-phase-shape),
[outside-transaction steps](outside-transaction-steps.md#at-least-once-without-per-call-progress).

## DD-4. Step names state the guarantee

**Decision.** The steps are `outsideTransaction`, `inTransaction` and `inBatches`.

**Options considered.**

- Names by content: `schema { }`, `data { }`.
- Names by order: `prepare { }`, `migrate { }`.
- Names by guarantee (chosen).

**Rationale.** What decides whether a migration is correct is which code may run more than once and what commits with
the history record. Content names put the boundary in the wrong place: an HTTP call is not schema and must run outside
a transaction, and a server-side `updateMany` with an idempotent filter is data that can run outside one. Order names
say nothing about repetition.

**Consequences.** Names are longer than `up` or `data`, and every reader sees the guarantee at the call site.

**In depth.** [Declaring migrations](declaring-migrations.md#step-names-state-the-guarantee).

## DD-5. An explicit session, checked in tests

**Decision.** `session` is a property of the transactional step's scope. The author passes it to every driver call
and every service call. `godwit-test` ships `SessionEscapeDetector`, which fails a test whose transactional step runs a
command without it.

**Options considered.**

- A session-bound collection type from godwit that adds the session itself.
- An ambient session, through a thread-local or a Kotlin context parameter.
- An explicit session plus a test-time detector (chosen).

**Rationale.** The explicit form is the driver's own, works unchanged with application services that take a
`ClientSession`, and shows at each call site what is inside the transaction. A wrapper would mirror the driver's
operations and overloads release after release, and services built on the application's own `MongoDatabase` would
bypass it. An ambient session hides exactly what a reviewer needs to see.

**Consequences.** A forgotten `session` compiles. The call then runs outside the transaction: it is not rolled back,
and it can block on the transaction's own writes. The detector catches it only on code paths a test executes, and it
relies on the driver marking in-transaction commands with the session's `lsid` and `autocommit: false`, which an
integration test pins, and on godwit opening each of a step's transactions with its own read, tagged with the comment
`{godwit: <migration id>}`. The
services a step calls must be built from the `MongoClient` passed to `Godwit`.

**In depth.** [Transactions and sessions](transactions-and-sessions.md#an-explicit-session),
[testing](testing.md#the-detector-is-a-test-time-check).

## DD-6. Flat global ids, and no migrations in libraries

**Decision.** An id is one string, unique in the database, and it is the `_id` of the migration's history document.
There are no groups and no per-owner prefixes. godwit does not support migrations shipped inside libraries: a library that owns
collections exposes idempotent setup functions, and the application calls them from its own migrations.

**Options considered.**

- A prefix per library or module, each with its own ordering track.
- A version number plus a name.
- The class or property name as the id.
- Timestamps as ids.
- Flat ids, with libraries shipping setup functions (chosen).

**Rationale.** Order is a property of the whole database. With one track per owner, a fresh database runs the owners'
migrations in a different order than production did, and no check can compare two tracks. A library upgrade that adds
migrations changes production without appearing in the application's diff. An idempotent setup function, called from
the application's own migration, gives the library's schema a place in the one list and a record in history.

**Consequences.** A library's schema change needs one migration in each application that uses it. Library authors
write setup functions that are safe to run on every version of their schema.

**In depth.** [Ordering and validation](ordering-and-validation.md#flat-global-ids),
[libraries and modules](libraries-and-modules.md#libraries-ship-setup-functions-not-migrations).

## DD-7. Out-of-order migrations fail by default

**Decision.** A pending once-only migration listed before an applied once-only migration fails `migrate` with
`PlanConflictException` (`OutOfOrder.FAIL`). `OutOfOrder.RUN` is a per-database setting that runs it and records
`outOfOrder: true`. Separately, `validateMigrations` checks that numeric prefixes strictly increase in list order.

**Options considered.**

- Run out-of-order migrations silently.
- Warn and run.
- Fail, with a per-database override (chosen), plus a pure numbering check.

**Rationale.** A database that applies the same migrations in a different order than every other database is the one
way identical code produces different data, and whether that matters depends on what the migrations touch, which godwit
cannot know. Failing makes the person who deploys decide. The numbering check catches the most common cause, two
branches that both add the next number, in a unit test.

**Consequences.** Deploying branches to a shared environment needs `OutOfOrder.RUN` there, or a database reset.

**In depth.** [Ordering and validation](ordering-and-validation.md#out-of-order-fails-by-default).

## DD-8. A plain list, validated before any I/O

**Decision.** The container is a plain `List<Migration>`. `migrate`, `status` and `requireUpToDate` validate it before
any I/O, and `validateMigrations(list)` runs the same pure check in a unit test.

**Options considered.**

- A registry type or group object that migrations are added to.
- Discovery by scanning.
- A plain list (chosen).

**Rationale.** A list is the one structure every Kotlin reader knows. Its order is the run order, and building it is
ordinary code: a function, a `+`, an `if` for a setting. Validation needs nothing but the list, so it can fail fast at
startup and in a test without a database.

**Consequences.** Every rule that depends on the database (out of order, unknown ids, squashes, untracked databases)
is checked separately against history.

**In depth.** [Ordering and validation](ordering-and-validation.md#checks-on-the-list),
[concepts](concepts.md#a-migration-is-a-value).

## DD-9. Wait for a busy lock, with a timeout

**Decision.** A process that finds work due and the lock held waits, polling every 250 ms to 5 s with jitter and
logging the holder every 10 s, for up to `LockConfig.waitTimeout` (10 minutes by default), then fails with
`LockTimeoutException`. `Duration.ZERO` fails at once. After acquiring, it reads history again. When nothing is due, it
takes no lock at all.

**Options considered.**

- Fail fast and let the orchestrator restart the process.
- Skip migrating and start.
- Wait with a timeout (chosen).

**Rationale.** A rolling deploy starts several instances together. Failing fast turns every deploy with a migration
into restart loops. Skipping starts new code on an old schema, the one outcome a migration tool exists to prevent.
Waiting costs a few seconds and usually finds the work done.

**Consequences.** Instances start later while a long migration runs; `waitTimeout` and the orchestrator's startup
probe must both exceed the longest migration.

**In depth.** [Locking](locking.md#wait-with-a-timeout-when-the-lock-is-busy).

## DD-10. One history document per migration, updated in place

**Decision.** `godwit-history` holds one document per migration, `_id` = id, updated in place: kind, state (`RUNNING`,
`FAILED`, `APPLIED`), origin (`RAN`, `ADOPTED`, `SUPERSEDED`, `MARKED`), attempts, transaction retries, counters,
duration, last error, checkpoint, run count and revision for repeatables, `supersedes`, holder, owner token, run id,
godwit version and format version.

**Options considered.**

- A run log: one document per `migrate` call.
- Append-only attempts: one document per attempt of each migration.
- One document per migration (chosen).

**Rationale.** The database enforces the rule that matters: a once-only migration's `RUNNING` marker is an upsert
filtered on `state != APPLIED`, so an applied document makes it fail with a duplicate key and no run can start an
applied migration. The fenced `APPLIED` write targets one known document. Every start reads the whole collection in
one query.

**Consequences.** A document shows the last run, plus `attempts` and the last error. The history of every attempt lives
in the log lines, which carry the run id.

**In depth.** [History and reports](history-and-reports.md#one-document-per-migration-updated-in-place).

## DD-11. Roll forward only

**Decision.** There are no down migrations and no automatic rollback. A failure keeps what committed and retries on
the next start; a wrong result is corrected by a new migration; `markApplied(id, reason)` is the one audited way to
skip a migration.

**Options considered.**

- A down step per migration.
- Roll forward only, with an audited escape hatch (chosen).

**Rationale.** A down step must undo a change against data that has moved on since, and it runs for the first time in
production, during an incident.
A failed transactional step already rolls back on its own, and an outside step converges when it runs again.

**Consequences.** Rolling back a release means running older code on a newer schema, so migrations in a rolling deploy
only add. godwit warns about the newer ids and continues (DD-22). The escape hatch is narrow: `markApplied` takes the
lock, refuses a repeatable or every-start migration, and on a `Godwit` built with `adoptApplied` refuses while adoption
has not ended (DD-14), so a mark from the `Godwit` that adopts cannot keep the hook from recording the applied ids; a
`Godwit` built from the same configuration without the hook is the deliberate path for a repair.

**In depth.** [Failure and recovery](failure-and-recovery.md#no-down-or-rollback-hooks).

## DD-12. Every-start and repeatable migrations

**Decision.** `everyStart(id)` runs on every start; `repeatable(id, revision)` runs when the revision the application
sets differs from the stored one. Both are listed after every once-only migration (validated) and run after every
pending once-only one. A repeatable at its current revision keeps the lock-free fast path; an every-start migration
takes the lock on every start. Neither runs under `Target.Before` or `Target.Through`, and neither can use `inBatches`.
Their `RUNNING` marker is an unconditional upsert on `{_id}`.

**Options considered.**

- One factory with a flag.
- A kind decided by where the migration sits in the list.
- A checksum of the migration's code to detect change.
- Two named factories and an application-owned revision (chosen).

**Rationale.** The kind is on the first line of the migration, and the two names carry two costs: one says "this takes
the lock on every start". A lambda has no stable source text at run time and its bytecode changes with the compiler,
so a checksum would rerun migrations after upgrades that changed nothing. Running last means a fresh database and a
production database run the repeatable against the same schema.

**Consequences.** The application changes the revision in the same commit as the code. Revisions are compared for
equality: an older release started after a newer one applies its own revision again. A run that loses the lock can
make a repeatable run once more at the same revision ([architecture](architecture.md#edge-cases)). A repeatable or
every-start migration deleted from the code leaves an `APPLIED` history document that reports as unknown applied;
godwit has no API to remove it, and the documented procedure is to delete that document by hand
([repeatable migrations](repeatable-migrations.md#deleting-a-repeatable-or-every-start-migration)).

**In depth.** [Repeatable migrations](repeatable-migrations.md#design-decisions).

## DD-13. Library-paged batches

**Decision.** `inBatches(collection, pending, batchSize) { docs -> }` pages through the collection by `_id`, runs one
transaction per page and commits a checkpoint with each page. The author writes what happens to one page.

**Options considered.**

- Author-paged: the step receives the last checkpoint and returns the next.
- No primitive: a loop in an outside step.
- Library-paged (chosen).

**Rationale.** Every author would otherwise re-implement paging, and the common mistakes (paging with `skip`,
re-reading from the start, a checkpoint of the wrong type) loop forever or miss documents. A loop in an outside step
never ends when one document cannot stop matching its filter. With the checkpoint inside each page's transaction, every
page commits exactly once and a resumed run continues after the last committed page.

**Consequences.** The `_id`s of the documents that match `pending` must share one BSON type (all numeric types count as
one). godwit checks every page, and before the last commit the documents left, and fails the migration naming both
types; `pending` can select one type. Documents inserted during the run below the checkpoint are not visited, so the
application writes new documents in the new shape.

**In depth.** [Batched backfills](batched-backfills.md#godwit-pages-the-author-writes-the-page).

## DD-14. Adoption through an application-supplied hook

**Decision.** `GodwitConfig(adoptApplied = { db -> Set<String> })` returns the ids already applied to a database
migrated by another tool, or by hand. godwit calls it under the lock, on every start that has work due while its
history holds nothing but `ADOPTED` documents (an empty history included). It records the declared once-only ids (and
ids named in a `supersedes` list) that history does not hold yet as `ADOPTED`, with idempotent upserts that only
insert (in one transaction on a replica set; one at a time, last-listed first, on a standalone server), and logs the
rest. A history document of another origin (`RAN`, `SUPERSEDED`, `MARKED`) ends adoption: while one exists, the hook
is not called. godwit decides from the history each start reads, so deleting every such document by hand reopens
adoption. While the hook can still run, the out-of-order and partial-squash checks are evaluated under the lock, after
the hook has run and its ids are recorded, never before the lock. The adopted ids must be a prefix of the once-only
list. A database with collections, no history and nothing adopted is refused (`UntrackedDatabase.REFUSE`; `RUN_ALL` opts
in).

**Options considered.**

- A reader built into godwit for each other tool's record format.
- A declarative description of the old record (collection, id field, applied filter).
- An explicit second startup call, or reconciliation on every start.
- A hook called only while history is empty, its ids recorded all at once.
- A hook called until the first document of another origin, recording only what is missing (chosen).

**Rationale.** godwit stays independent of every other tool: any record the application can read can be adopted, and a
record kept by hand has no format to build a reader for. Once a migration has run, been recorded as superseded or been
marked, godwit's history is the only source of truth. Until then, calling the hook again makes an interrupted adoption
complete itself. On a replica set the ids commit in one transaction, so an interrupted adoption simply happens again. On
a standalone server, which has no transactions, a crash can leave `004` and `003` recorded and `002` and `001` missing,
and a hook called only on an empty history would never run again to fill them in. Recording is an upsert of the ids
history lacks, so a repeated call changes nothing that is recorded. The order checks wait for the hook because the
partial state is itself a gap. Checked before the lock, it would throw `PlanConflictException` under the default
`OutOfOrder.FAIL` on every start before the hook could complete the adoption. Under `OutOfOrder.RUN` the pre-lock plan
reports no conflict; the missing ids are not run because the plan that runs is made under the lock, after the hook has
recorded them. Writing the ids last-listed first keeps a partial adoption visible as a gap or a partial supersede, not
as a shorter prefix, to a start that no longer has the hook. The guard exists because the two
failures are not symmetric: a wrong collection name in the hook imports nothing, everything looks pending, and running
every migration over live data overwrites fields and fails on unique indexes. A refusal costs one exception and one line
of configuration.

**Consequences.** The application writes and tests a few lines of Kotlin, and can leave the hook configured until
every database is adopted. The hook runs on every start that takes the lock until a document of another origin
exists, so it must be a pure read: no writes, no side effects, the same result for the same old record; each of those
starts pays for its read under the lock. A start with nothing due takes the fast path and does not call it. Adoption
only adds: an id recorded on an earlier call stays recorded when the hook stops returning it. A `MARKED` document ends
adoption and turns off the untracked-database guard, so a mark made before the hook has recorded every applied id would
let the ids it has not recorded run on the next start, over the live data. `markApplied` therefore throws
`IllegalStateException` and writes nothing while `adoptApplied` is set and history holds no document whose origin is
other than `ADOPTED` (an empty history included); the check runs under the lock `markApplied` takes, on the same
history read. A deliberate manual repair, such as recording what was applied after history was lost on a database that
still has the old record, marks from a `Godwit` built from the same configuration with `adoptApplied = null`, so that
the marks land in the same history and lock collections, after stopping every instance. godwit never writes to the old
record.

**In depth.** [Adopting an existing database](adopting-an-existing-database.md#the-application-supplies-the-applied-ids).

## DD-15. Squashing through supersedes

**Decision.** A baseline migration declares the ids it replaces: `migration(id, supersedes = listOf(...))`. Where every
replaced id is applied, the baseline is recorded `SUPERSEDED` without running; where none is, it runs; where only some
are, `migrate` throws `PlanConflictException`. The decision is made while the baseline has no `APPLIED` history
document; once it has one, the stored list only keeps the old ids known.

**Options considered.**

- A baseline setting that trusts a number (treat everything up to N as applied).
- Keeping every old migration forever.
- Editing the first migration into the end state.
- Recording the baseline by hand on each environment.
- `supersedes` on the new migration (chosen).

**Rationale.** The decision belongs to each database's history, not to a number in code. A partial squash is refused
because the missing migrations are no longer in the list, so only the previous release can apply them.

**Consequences.** The baseline's history document stores its `supersedes` list, so the code can drop the argument once
every database has recorded the baseline. A recorded baseline is exempt from the out-of-order policy.

**In depth.** [Squashing migrations](squashing-migrations.md#supersedes-on-the-new-migration).

## DD-16. A test kit that drives the real runner

**Decision.** `godwit-test` provides `testGodwit()` (one shared replica-set container per test JVM, a new database
named by a UUID per call, `SessionEscapeDetector` installed), `rerun`, `runIsolated`, `forget`, `shouldHaveApplied` and
the detector. `Target.Before` and `Target.Through` live in `godwit-core`.

**Options considered.**

- An in-memory fake of the driver.
- Extracting each step body into a function and testing the function.
- A container per spec, or one database cleaned between tests.
- The real runner against a real server, one container per JVM (chosen).

**Rationale.** What goes wrong with migrations is the runner's territory: the history record in the same transaction,
a retried body, DDL inside a transaction, a unique index that rejects the data, a service on the wrong client. None of
it exists in a fake, and a step body extracted into a function and tested alone skips all of it. A UUID database makes
isolation free: no cleanup, parallel specs.

**Consequences.** Tests need Docker. `godwit-test` depends on no test framework: its helpers throw `AssertionError`.
`forget`, which deletes history, exists only in the test kit.

**In depth.** [Testing migrations](testing.md#design-decisions).

## DD-17. Standalone servers fail only when a transaction is due

**Decision.** When a transactional step is due and the server is a standalone `mongod`, `migrate` throws
`TransactionsUnsupportedException` before taking the lock, with instructions for a single-node replica set.
Outside-only migrations run on a standalone server.

**Options considered.**

- A non-transactional mode for standalone servers.
- Failing on every standalone server.
- Failing only when a transactional step is due (chosen).

**Rationale.** A non-transactional mode would remove the exactly-once guarantee without the reader of a migration
being able to tell. Failing always would block lists that never need a transaction. A single-node replica set costs one
flag and one command.

**Consequences.** The check that can refuse a call runs `hello` only when a transactional step is due, so a start with
nothing due stays one query. Adoption sends `hello` too when it has ids to record, to choose between one transaction
and one write per id; the call reuses that answer, so it sends `hello` at most once.

**In depth.** [Transactions and sessions](transactions-and-sessions.md#fail-on-a-standalone-server-only-when-needed).

## DD-18. Logging through slf4j-api 2 only

**Decision.** godwit logs to the slf4j logger `godwit` with the slf4j 2 fluent key-value API, and depends on
`slf4j-api` only.

**Options considered.**

- A Kotlin logging wrapper library.
- `java.util.logging`.
- No logging, only the returned report.
- slf4j-api 2 (chosen).

**Rationale.** Every JVM logging backend binds slf4j, so godwit adds no backend and no wrapper to an application. The
key-value API gives structured fields (`id`, `attempts`, `durationMs`) that a JSON encoder emits as fields. The report
covers what one call did; logs cover what happens while it runs, including waiting for the lock.

**Consequences.** Messages are fixed strings with key-value pairs; their text and keys are part of godwit's documented
behaviour. A backend that ignores key-value pairs prints the message without the fields.

**In depth.** [Configuration](configuration.md#logging), [history and reports](history-and-reports.md#log-lines).

## DD-19. Names of artifacts, packages and collections

**Decision.** Maven artifacts `works.resolute:godwit-core` and `works.resolute:godwit-test`; packages `godwit.core`
and `godwit.test`; collections `godwit-history` and `godwit-lock`.

**Options considered.**

- One artifact with the test kit inside it.
- Collections in camel case, or under a shared prefix such as `_migrations`.
- A core artifact and a test artifact, kebab-case collections (chosen).

**Rationale.** Production code never depends on the test kit, Testcontainers or the destructive `forget`. The
collection names sort together and say what owns them. Both collection names are configurable for a database that
needs others.

**Consequences.** Tools that clear collections between tests skip `Godwit.bookkeepingCollections`.

**In depth.** [Configuration](configuration.md#godwitconfig), [history and reports](history-and-reports.md#the-history-collection).

## DD-20. Receivers, not context parameters

**Decision.** Step bodies are lambdas with a receiver (`TransactionScope.() -> Unit`). godwit uses no context
parameters, and its helpers are members or extensions of the scopes.

**Options considered.**

- Kotlin context parameters for the scope or the session.
- Receivers (chosen).

**Rationale.** A step body has one scope, and one implicit receiver gives every unqualified call it needs
(`collection("orders")`, `count(...)`, `session`) with the oldest, best-known Kotlin feature for it. Context parameters
would put a newer language feature into every public signature of godwit without enabling a single call that a
receiver cannot make. The session stays an explicit argument at each call site either way (DD-5).

**Consequences.** The scope is one implicit receiver; an application's own extension functions on the scopes work as
helpers.

**In depth.** [Transactions and sessions](transactions-and-sessions.md#an-explicit-session),
[declaring migrations](declaring-migrations.md#steps).

## DD-21. godwit is the library and nothing around it

**Decision.** godwit is `godwit-core`, `godwit-test`, their documentation, build, CI and release. It has no framework
integrations, no CLI, no multi-tenant runner and no listener API.

**Options considered.**

- Integrations with web or DI frameworks.
- A CLI that runs migrations outside the application.
- The library only (chosen).

**Rationale.** A migration list is Kotlin code that needs the application's services, so the application is the place
that runs it: one call at startup. An integration would pin godwit to a framework's lifecycle and versions for what is
one line of the application's own code.

**Consequences.** An application that serves several databases loops over them and calls `migrate` for each. Metrics
come from the returned report.

**In depth.** [Implementation plan](development/implementation-plan.md), [concepts](concepts.md#one-call-to-migrate).

## DD-22. Unknown applied ids warn by default

**Decision.** An `APPLIED` history id that the list does not know logs a warning and is returned in
`report.unknownApplied` (`UnknownApplied.WARN`); `UnknownApplied.FAIL` makes it a `PlanConflictException`. Ids named in
the stored `supersedes` list of any recorded superseding migration count as known.

**Options considered.**

- Fail by default.
- Ignore them.
- Warn by default, fail on request (chosen).

**Rationale.** An applied id the list does not know is the normal state while an older release runs after a rollback,
and during a rolling deploy. Failing would make rollbacks impossible exactly when they are needed. Counting stored
`supersedes` ids as known lets the code delete the list after a squash without warnings.

**Consequences.** The check runs before the fast path, so `UnknownApplied.FAIL` also stops a start with nothing to do.

**In depth.** [Ordering and validation](ordering-and-validation.md#unknown-ids-warn-by-default).

## DD-23. One driver version, as an api dependency

**Decision.** godwit-core declares `api("org.mongodb:mongodb-driver-kotlin-sync:5.7.0")`, and CI tests that one
version.

**Options considered.**

- `implementation` scope, hiding the driver behind godwit types.
- A matrix of driver versions in CI.
- One version, `api` scope (chosen).

**Rationale.** The driver's types (`MongoCluster`, `ClientSession`, `MongoDatabase`, `Bson`) are in godwit's public
API, so they belong on the consumer's compile classpath. `MongoCluster` exists from 5.2, and 5.7.0 is the version godwit
is built and tested against. One version keeps CI to one target.

**Consequences.** An application on a newer 5.x driver resolves to its own version through Gradle's conflict
resolution, which godwit does not test. Exponential backoff between transaction retries exists only from driver 5.12,
so at 5.7.0 the driver retries a transaction body immediately; godwit pauses between attempts itself.

**In depth.** [Architecture](architecture.md#compatibility).

## DD-24. Three DDL helpers

**Decision.** `ensureCollection`, `ensureSearchIndex` (with `awaitReady`, calling `checkLock()` between polls) and
`dropIndexIfExists`, as members of the outside step's scope and as public `MongoDatabase` and `MongoCollection`
extensions.

**Options considered.**

- An `ensureIndex` helper.
- A declarative schema that godwit diffs against the database.
- No helpers.
- Helpers that compare existing options or definitions.
- Three helpers for the three calls that fail when repeated (chosen).

**Rationale.** These are the common DDL calls whose raw form fails when it runs a second time. `createIndex` with an
identical specification is already a no-op, and a conflicting one has no safe automatic answer. As scope members they
do not resolve inside a transactional step, where DDL fails at run time; as extensions, a library's setup function uses
them without godwit's runner.

**Consequences.** An existing collection's options and an existing search index's definition are neither compared
nor changed; a change of options is an explicit `collMod` or `updateSearchIndex` in a new migration. Vector search
indexes are not covered.

**In depth.** [Outside-transaction steps](outside-transaction-steps.md#three-helpers-and-no-wrapper-over-the-index-api).

## DD-25. 0.x until proven in production

**Decision.** The first release of `godwit-core` and `godwit-test` is `0.1.0`. godwit releases 0.x versions until it
has run in a production application, then releases `1.0.0`. During 0.x a breaking change raises the minor version
(`0.1.0` to `0.2.0`); from `1.0.0` on it raises the major version.

**Options considered.**

- `1.0.0` as the first release, because the API is designed and documented in full before it.
- `0.1.0`, and 0.x until a production application has run it (chosen).

**Rationale.** A design that is complete on paper and covered by tests has still not met a production database, a
rolling deploy or an adoption of a real record. A version number is a promise about stability, and godwit makes it
once production has tested the promise, not before.

**Consequences.** An application that uses godwit during 0.x checks each new minor version for breaking changes
before it upgrades. History documents record the version that wrote them (`godwitVersion: "0.1.0"`); a change of the
document format still gets a new `v`, whatever the version number.

**In depth.** [Implementation plan](development/implementation-plan.md#decisions-settled-before-p0).

## DD-26. JVM 21 and Kotlin 2.4

**Decision.** godwit's bytecode targets Java 21 (`jvmToolchain(21)`), and a consuming application compiles with
Kotlin 2.4 or later. godwit is built with Kotlin 2.4.20, without lowering `apiVersion` or `languageVersion`.

**Options considered.**

- Bytecode for Java 17, to reach applications that have not moved to 21.
- Compiling with `apiVersion` and `languageVersion` 2.2, so older Kotlin compilers can read godwit's metadata.
- Java 21 and Kotlin 2.4 (chosen).

**Rationale.** Java 21 is a long-term support release and the toolchain the build and CI use, so the bytecode matches
what is tested, and it runs on every later JDK. Lowering the Kotlin language and API versions would hold godwit's own
code back and add a compatibility matrix that nothing tests, for consumers nobody has asked to support.

**Consequences.** An application on Java 17 cannot load godwit's classes, and one that compiles with Kotlin older
than 2.4 is not supported: nothing tests it. The consumer smoke project of the release compiles with Kotlin 2.4.
Lowering either baseline later does not break existing consumers.

**In depth.** [README](../README.md#requirements),
[implementation plan](development/implementation-plan.md#decisions-settled-before-p0).

## Decisions that follow from these

Each page records the narrower decisions that follow from the ones above:

| Decision | Follows from | Recorded in |
|---|---|---|
| The `APPLIED` record commits in the step's transaction | DD-3, DD-10 | [Transactions and sessions](transactions-and-sessions.md#the-applied-record-commits-with-the-data) |
| The driver's `withTransaction` retry loop, wrapped to count and log attempts | DD-5 | [Transactions and sessions](transactions-and-sessions.md#the-drivers-retry-loop) |
| Snapshot, majority and primary for every transaction, not configurable | DD-3 | [Transactions and sessions](transactions-and-sessions.md#fixed-concerns) |
| No DDL in transactions | DD-24 | [Transactions and sessions](transactions-and-sessions.md#no-ddl-in-transactions) |
| No checksums of migration code | DD-1, DD-12 | [Declaring migrations](declaring-migrations.md#no-checksums-of-migration-code) |
| No dependency container, no typed bag, no lazy providers | DD-2 | [Dependencies](dependencies.md#no-di-container) |
| A leased lock document on server time, a heartbeat and an owner token; no TTL index | DD-9 | [Locking](locking.md#a-leased-document-with-server-time-a-heartbeat-and-an-owner-token) |
| No lock when nothing is due | DD-9 | [Locking](locking.md#no-lock-when-nothing-is-due) |
| A report object and log lines, no listener API | DD-18, DD-21 | [History and reports](history-and-reports.md#a-report-object-and-log-lines-no-listener-api) |
| Stop at the first failure; retry on the next start, not inside `migrate` | DD-11 | [Failure and recovery](failure-and-recovery.md#stop-at-the-first-failure) |
| `FAILED` written outside the transaction, fenced on the owner token and `RUNNING` | DD-10 | [Failure and recovery](failure-and-recovery.md#failed-written-outside-the-transaction-fenced) |
| Paging by `_id`, one transaction per page with the checkpoint inside | DD-13 | [Batched backfills](batched-backfills.md#paging-by-_id) |
| Only declared ids are adopted, as a prefix; godwit never writes to the old record | DD-14 | [Adopting an existing database](adopting-an-existing-database.md#only-declared-ids-are-imported) |
| The hook runs until something other than adoption is recorded; the order checks wait for it | DD-7, DD-14 | [Adopting an existing database](adopting-an-existing-database.md#the-hook-runs-until-something-other-than-adoption-is-recorded), [architecture](architecture.md#one-migrate-call) |
| `markApplied` refuses until adoption ends | DD-11, DD-14 | [Adopting an existing database](adopting-an-existing-database.md#markapplied-refuses-until-adoption-ends) |
| A recorded baseline is exempt from the out-of-order policy | DD-7, DD-15 | [Squashing migrations](squashing-migrations.md#a-recording-is-not-a-run) |
| One list per database in a multi-module application | DD-6, DD-8 | [Libraries and modules](libraries-and-modules.md#one-list-per-database-in-a-multi-module-app) |
| One immutable configuration; every default is a production value | DD-21 | [Configuration](configuration.md#one-immutable-config-every-default-a-production-value) |
| Lock settings validated when the configuration is built | DD-9 | [Configuration](configuration.md#the-relations-between-lock-settings-are-checked-when-the-config-is-built) |
| A pure planner; conflicts checked before the fast path, except those adoption can still resolve; the untracked guard under the lock | DD-7, DD-8, DD-14 | [Architecture](architecture.md#a-pure-planner), [architecture](architecture.md#one-migrate-call) |
| The `RUNNING` marker committed outside the step's transaction | DD-10 | [Architecture](architecture.md#the-marker-outside-the-transaction) |
| Server time for the lease, client time for history timestamps | DD-9, DD-10 | [Architecture](architecture.md#server-time-for-the-lease-client-time-for-history) |
| `org.bson.Document` for bookkeeping, on the default codec registry | DD-23 | [Architecture](architecture.md#orgbsondocument-for-bookkeeping) |

## See also

- [README](../README.md): what godwit is and how to start.
- [Concepts](concepts.md): the mental model the decisions serve.
- [Implementation plan](development/implementation-plan.md): how godwit is built, phase by phase.
