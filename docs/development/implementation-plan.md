# Implementation plan

This page describes how godwit itself is built: nine phases, P0 to P8, from an empty repository to a release on Maven
Central. Each phase states its goal, the files it adds, the behaviours it implements, the tests that ship with it, the
verification gate that closes it, and the measurable outcome that proves it, with the log line or metric to look at.
The behaviour every phase implements is specified by the user documentation; when a phase and a doc disagree, the
phase stops and the disagreement is resolved before code is written.

## Contents

- [Rules for every phase](#rules-for-every-phase)
- [Decisions settled before P0](#decisions-settled-before-p0)
- [Overview](#overview)
- [P0. Project skeleton](#p0-project-skeleton)
- [P1. Declarations, validation and the planner](#p1-declarations-validation-and-the-planner)
- [P2. History store and lease lock](#p2-history-store-and-lease-lock)
- [P3. Runner for once-only migrations](#p3-runner-for-once-only-migrations)
- [P4. Batched steps](#p4-batched-steps)
- [P5. Every-start and repeatable migrations](#p5-every-start-and-repeatable-migrations)
- [P6. Adoption, squashes, unknown ids, markApplied and targets](#p6-adoption-squashes-unknown-ids-markapplied-and-targets)
- [P7. The godwit-test kit](#p7-the-godwit-test-kit)
- [P8. Documentation and release](#p8-documentation-and-release)
- [Risk register](#risk-register)

## Rules for every phase

- All work is committed directly to main after its gate passes locally; CI on the push to main is the last gate.
- **Tests ship with the code, in the same commit.** Every branch of the code a phase adds is covered. Pure code
  (validation, the planner, error classification) gets plain unit tests over values; code that talks to MongoDB gets
  integration tests against a real replica set. A bug found later ships with the test that would have caught it.
- **Branch coverage is 100 %, enforced.** `scripts/coverage-gate.py` reads the JaCoCo XML report and fails on any
  uncovered branch in `godwit-core` or `godwit-test` main code that is not listed, with a reason, in
  `coverage-exceptions.txt`. The list starts empty; an entry needs a review comment explaining why no test can reach
  the branch.
- **Gates run on exit codes.** Each command in a gate runs only after the previous one exited 0. A gate is never one
  shell line joined with `;`. In CI each command is its own workflow step, so a failure stops the job; locally, run them
  one at a time or through a `make` target, which stops at the first failing line.
- **The docs stay true.** Every ```kotlin block in `README.md` and `docs/` compiles in the `docs-snippets/` build,
  against the real `godwit-core` and `godwit-test` from the root build, and every block marked "This does not
  compile:" fails to compile (`docs-snippets/neg/`). `scripts/check-docs.sh` runs every docs check. A phase that
  changes a documented behaviour changes the doc and its snippet in the same commit.
- **Every outcome leaves a trace.** Each phase names the log line, report field or test output that proves it worked.
  godwit's log lines are the catalogue in the `Godwit` KDoc; tests assert on them through a Logback `ListAppender`
  attached to the `godwit` logger.

## Decisions settled before P0

These decisions shape P0 and every later phase. Each is settled: the docs and the public API's KDoc state the outcome,
and the last column links to where.

| Decision | Outcome | Recorded in |
|---|---|---|
| JVM baseline | bytecode for Java 21 (`jvmToolchain(21)`) | [DD-26](../design-decisions.md#dd-26-jvm-21-and-kotlin-24), [README](../../README.md#requirements) |
| Kotlin consumers | Kotlin 2.4 or later; godwit is built with 2.4.20 without lowering `apiVersion` or `languageVersion` | [DD-26](../design-decisions.md#dd-26-jvm-21-and-kotlin-24), [README](../../README.md#requirements) |
| First version number | `0.1.0`; 0.x until godwit has run in a production application, then `1.0.0`. History examples show `godwitVersion: "0.1.0"` | [DD-25](../design-decisions.md#dd-25-0x-until-proven-in-production) |
| Log events for lock trouble | three WARN events, `Lock renewal failed`, `Lost migration lock` and `Lock release failed`, each with `runId`, `holder` and `error` or `reason` | [history and reports](../history-and-reports.md#log-lines), [locking](../locking.md#losing-the-lock-mid-run), the `Godwit` KDoc |
| Cause of `LockLostException` | an optional cause: when a step fails and the lock is lost at the same moment, the step's exception is the cause, and the fenced `FAILED` write records it as `lastError` unless another run has taken the migration over | [failure and recovery](../failure-and-recovery.md#lock-lost), [architecture](../architecture.md#running-one-migration) |
| Deleting a repeatable or every-start migration from the list | the documented manual procedure for 0.1.0: delete its `godwit-history` document; no core API | [repeatable migrations](../repeatable-migrations.md#deleting-a-repeatable-or-every-start-migration), [DD-12](../design-decisions.md#dd-12-every-start-and-repeatable-migrations) |
| When the adoption hook runs | on every start that has work due while history holds only `ADOPTED` documents, until a document of another origin exists; recording is an idempotent upsert of the ids history lacks; the out-of-order and partial-supersede checks wait until the hook has run under the lock | [DD-14](../design-decisions.md#dd-14-adoption-through-an-application-supplied-hook), [adopting an existing database](../adopting-an-existing-database.md#the-hook-runs-until-something-other-than-adoption-is-recorded) |
| `markApplied` before adoption has ended | refused: with `adoptApplied` set and history empty or holding only `ADOPTED` documents, `markApplied` throws `IllegalStateException` ("adoption has not ended on this database; run migrate() first so the adoptApplied hook adopts, or call markApplied from a Godwit built without adoptApplied") and writes nothing; the check runs under the lock, on the same history read; a manual repair marks from a `Godwit` built from the same configuration with `adoptApplied = null` (same history and lock collections), after stopping every instance | [DD-14](../design-decisions.md#dd-14-adoption-through-an-application-supplied-hook), [adopting an existing database](../adopting-an-existing-database.md#markapplied-refuses-until-adoption-ends) |

## Overview

| Phase | Delivers | Main risk it retires |
|---|---|---|
| P0 | a two-module build that lints, tests against a replica set in CI, measures coverage, generates docs and publishes locally | toolchain and CI surprises |
| P1 | the whole public API surface, validation and the pure planner; the docs snippets compile against it | API shape and planning rules |
| P2 | the history store and the lease lock | concurrency between processes |
| P3 | `migrate`, `status`, `requireUpToDate`, `history` for once-only migrations; DDL helpers; error guidance | exactly-once and crash recovery |
| P4 | `inBatches` | resumable large backfills |
| P5 | `everyStart` and `repeatable` | run-again semantics and the fast path |
| P6 | adoption, the untracked guard, `supersedes`, unknown applied ids, `markApplied`, `Target` | taking over existing databases |
| P7 | `testGodwit()`, `rerun`, `runIsolated`, `forget`, `shouldHaveApplied`, `SessionEscapeDetector` | testability for users |
| P8 | docs verified against the implementation, Dokka, the release | shipping |

Internal components follow [architecture](../architecture.md#components): validation and the planner are pure; the
history store, the lock, the topology check, adoption and the runner do the I/O. Internal code lives in
`godwit.core.internal`, with Kotlin `internal` visibility.

## P0. Project skeleton

**Goal.** An empty, publishable two-module build: it compiles, lints, runs an integration test against a MongoDB
replica set locally and in CI, reports coverage, generates API docs and publishes to Maven Local.

**Files.**

| Path | Content |
|---|---|
| `settings.gradle.kts` | `rootProject.name = "godwit"`, `include("godwit-core", "godwit-test")`. `docs-snippets/` stays a build of its own (below) |
| `gradle.properties` | every version, in `key = value` form so the Makefile can include it (below) |
| `gradle/wrapper/*`, `gradlew`, `gradlew.bat` | Gradle 9.7.1, `validateDistributionUrl=true` |
| `build.gradle.kts` | root: `base`, `org.jetbrains.dokka`, `com.gradleup.nmcp.aggregation`; `group = "works.resolute"`; Dokka output to `docs/dokka`; `dokka(project(...))` and `nmcpAggregation(project(...))` for both published modules; Central Portal credentials from `SONATYPE_PUBLISH_USERNAME` and `SONATYPE_PUBLISH_PASSWORD`, `publishingType = "AUTOMATIC"` |
| `buildSrc/build.gradle.kts` | `kotlin-dsl`; reads `kotlinVersion` from the root `gradle.properties`; plugin classpath: Kotlin Gradle plugin, `org.jacoco.core` 0.8.15, Dokka 2.2.0, `coveralls-jacoco` 1.2.20, nmcp 1.6.2 (both plugins), kotlinter 5.7.0, binary-compatibility-validator |
| `buildSrc/src/main/kotlin/common-conventions.gradle.kts` | `kotlin("jvm")`, `jacoco`, `coveralls-jacoco`, Dokka, kotlinter; `jvmToolchain(21)`; `allWarningsAsErrors`; `withSourcesJar()`, `withJavadocJar()`; group and version from `godwitVersion`; no default dependencies (no `kotlin-reflect`, no serialization library, no logging backend) |
| `buildSrc/src/main/kotlin/test-conventions.gradle.kts` | Kotest (JUnit 5 runner, assertions, property), MockK, Logback, Testcontainers and Awaitility as `testImplementation`; `useJUnitPlatform()`; `test` excludes the `Atlas` Kotest tag, `atlasTest` runs only it; `jacocoTestReport` (XML and HTML) after `test`; `test` depends on `lintKotlin` |
| `buildSrc/src/main/kotlin/publish-conventions.gradle.kts` | `maven-publish`, `signing`, `com.gradleup.nmcp`; publication `mavenJava`; POM with name, the module's required `description`, Apache-2.0 license, SCM `resoluteworks/godwit`, developer; GPG signing on the maintainer's machine |
| `godwit-core/build.gradle.kts` | the three convention plugins; `description`; `api("org.mongodb:mongodb-driver-kotlin-sync:$mongoDriverVersion")`, `implementation("org.slf4j:slf4j-api:$slf4jVersion")`; the `verifyRuntimeDependencies` task (below) wired into `check` |
| `godwit-test/build.gradle.kts` | the three convention plugins; `description`; `api(project(":godwit-core"))`, `implementation("org.testcontainers:testcontainers-mongodb:$testContainersVersion")` |
| `godwit-core/src/test/kotlin/godwit/core/fixtures/TestMongo.kt` | one replica-set container per test JVM (`org.testcontainers.mongodb.MongoDBContainer`, image from `mongoImage`, `--setParameter enableTestCommands=1` for fail points), a client per call, a UUID database per call |
| `godwit-core/src/test/kotlin/godwit/core/fixtures/ReplicaSetSmokeTest.kt` | runs `hello` against the fixture and asserts a replica set name |
| `scripts/coverage-gate.py`, `coverage-exceptions.txt` | the branch-coverage gate |
| `Makefile` | below |
| `.github/workflows/ci.yml` | below; it runs `scripts/check-docs.sh` |
| `.github/workflows/publish-docs.yml` | on a `v*` tag: `./gradlew :dokkaGenerate`, then deploy `docs/` to GitHub Pages |
| `LICENSE` | the Apache License 2.0 text |
| `.editorconfig` | `root = true`, UTF-8, final newline, trimmed trailing whitespace, `max_line_length = 120`, 4-space indent for `*.kt` and `*.kts`, ktlint `intellij_idea` style without trailing commas |
| `.sdkmanrc` | `java=21.0.2-tem` |

The documentation build exists before P0 and P0 leaves it as it is: `docs-snippets/` is a standalone Gradle build (its
own `settings.gradle.kts` and wrapper) that compiles the example shop and every docs snippet against godwit's public
API, with the snippets that must not compile in `docs-snippets/neg/`; P1 points it at the real modules. The checks are
in `scripts/`: `check-docs.sh` runs the compile, `neg-check.sh`, `check-snippets.sh`, `check-links.sh` and
`check-content.sh` in that order and stops at the first failure
([docs-snippets/README.md](../../docs-snippets/README.md)). P0 wires `scripts/check-docs.sh` into the Makefile and CI.

`gradle.properties`:

```properties
godwitVersion = 0.1.0

kotlinVersion = 2.4.20

mongoDriverVersion = 5.7.0

slf4jVersion = 2.0.17

kotestVersion = 6.2.5

mockkVersion = 1.14.9

testContainersVersion = 2.0.5

awaitilityVersion = 4.3.0

logbackVersion = 1.5.32

mongoImage = mongo:8.0.17

atlasLocalImage = mongodb/mongodb-atlas-local:8.0
```

`Makefile`. Each recipe line runs only when the previous one exited 0:

```make
include gradle.properties
-include .env
export

test:
	./gradlew clean test
	./gradlew atlasTest
	python3 scripts/coverage-gate.py
	./gradlew coverallsJacoco

check-docs:
	scripts/check-docs.sh

publish-local:
	./gradlew publishToMavenLocal

publish:
	./gradlew publishAggregationToCentralPortal

release: test check-docs publish-local publish
	git tag v$(godwitVersion)
	git push origin v$(godwitVersion)
```

`.github/workflows/ci.yml`. GitHub Actions runs each step only when the previous one succeeded:

```yaml
name: ci
on:
  push:
    branches: [main]
  pull_request:
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew lintKotlin
      - run: ./gradlew apiCheck
      - run: ./gradlew test
      - run: ./gradlew atlasTest
      - run: python3 scripts/coverage-gate.py
      - run: ./gradlew -p docs-snippets compileKotlin
      - run: scripts/check-docs.sh
      - run: ./gradlew coverallsJacoco
        env:
          COVERALLS_REPO_TOKEN: ${{ secrets.COVERALLS_REPO_TOKEN }}
```

`ubuntu-latest` has Docker, which Testcontainers needs. `check-docs.sh` builds offline, so the step before it resolves
the `docs-snippets` build's dependencies into the Gradle cache.

**Behaviours.**

- `verifyRuntimeDependencies` resolves `godwit-core`'s `runtimeClasspath` and fails unless its direct dependencies are
  exactly `org.mongodb:mongodb-driver-kotlin-sync` and `org.slf4j:slf4j-api` (plus the Kotlin standard library). It
  prints the list it found.
- The test fixture starts one container per JVM and gives each test a database named by a random UUID.

**Tests.** `ReplicaSetSmokeTest`: `hello` returns a `setName`, and two calls of the fixture return different database
names on the same container.

**Gate.** Each command after the previous exits 0:

```sh
./gradlew lintKotlin
./gradlew test
./gradlew :godwit-core:verifyRuntimeDependencies
./gradlew :dokkaGenerate
make publish-local
gh run watch --exit-status
```

**Measurable outcome.** CI is green on the push of P0 to `main`, with the smoke test running against a real replica
set. Traces: the smoke test logs `test replica set ready setName=docker-rs startupMs=<n>`; `verifyRuntimeDependencies`
prints `runtime dependencies: org.mongodb:mongodb-driver-kotlin-sync:5.7.0, org.slf4j:slf4j-api:2.0.17`;
`~/.m2/repository/works/resolute/godwit-core/0.1.0/godwit-core-0.1.0.pom` exists and lists those two runtime
dependencies and the Kotlin standard library; the CI log's docs step ends with `check-docs: all checks passed`.

## P1. Declarations, validation and the planner

**Goal.** The complete public API of both modules compiles, the docs compile against it, and every decision about what
to run is a pure function, tested exhaustively before any I/O exists.

**Files.** The public signatures and their KDoc start as the files in `docs-snippets/api-stubs/`, which the docs
already compile against: P1 moves each file into the real module, keeps every signature, and replaces each `TODO()`
body with the implementation or with a `NotImplementedError` naming the phase that implements it.

| Path | Content |
|---|---|
| `godwit-core/src/main/kotlin/godwit/core/Declaration.kt` | `MigrationKind`, `StepKind`, `Migration`, `migration`, `everyStart`, `repeatable`, `MigrationDraft`, `OutsideTransactionMigration`, and `TransactionalMigration`, the internal subclass that `inTransaction` and `inBatches` return (a subclass of the sealed `Migration` lives in its package) |
| `.../internal/StepBodies.kt` | the internal step holders the runner reads: an outside step and its value's type, then an `inTransaction` or `inBatches` step |
| `.../Scopes.kt`, `Ddl.kt` | the scopes and DDL helpers' signatures; bodies that need I/O throw `NotImplementedError("P3")` |
| `.../Validation.kt`, `.../internal/Validation.kt` | `validateMigrations`; the rules, the target check and the check `migrate` runs before any I/O |
| `.../Godwit.kt`, `GodwitConfig.kt`, `Reports.kt`, `Exceptions.kt` | the public types of the stubs; `migrate`, `status` and `requireUpToDate` validate, then, like `Godwit`'s other I/O methods, throw `NotImplementedError` naming their phase |
| `.../internal/DefaultHolder.kt` | the default `GodwitConfig.holder`, `<hostname>/<pid>` |
| `.../internal/Plan.kt` | the plan: due migrations in run order, superseded records to write, ids a target stops before, ids up to date, conflicts, unknown applied ids, `needsTransactions`, `untracked`, `nothingDue`, the status's pending ids |
| `.../internal/HistoryRecord.kt` | a history document as plain Kotlin data, the planner's input |
| `.../internal/Planner.kt` | `plan(migrations, history, target, config, adopting): Plan`, and `adoptionCanRun(config, history)`, the `adopting` input |
| `godwit-test/src/main/kotlin/godwit/test/*.kt` | `TestGodwit.kt`, `RunnerPath.kt`, `SessionEscapeDetector.kt`: `testGodwit`, `TestGodwit`, the runner-path helpers, `SessionEscapeDetector`, `SessionEscapeError`: signatures, bodies `NotImplementedError("P7")` |
| `godwit-core/api/godwit-core.api`, `godwit-test/api/godwit-test.api` | the ABI dumps (`./gradlew apiDump`) |
| `docs-snippets/settings.gradle.kts` | `includeBuild("..")` in place of the two `api-stubs` projects, so the docs build compiles against the root build's `godwit-core` and `godwit-test` |
| `docs-snippets/build.gradle.kts`, `docs-snippets/neg-check/build.gradle.kts` | `implementation("works.resolute:godwit-test")` in place of `project(":godwit-test")`; Gradle substitutes the included build's module |
| `docs-snippets/api-stubs/`, `docs-snippets/API.md`, `scripts/gen-api.sh` | deleted: the real modules carry the signatures and KDoc, the ABI dumps and Dokka list the public API, and `docs-snippets/README.md` and `DOMAIN.md` point there |

**Behaviours.**

- Declarations: a draft is not a `Migration`; `outsideTransaction` returns a complete migration; nothing follows the
  transactional step; `steps` lists one or two entries.
- `validateMigrations`: rules 1 to 8 of [ordering and validation](../ordering-and-validation.md#checks-on-the-list),
  every problem reported at once, with exactly the problem lines that page shows.
- `Target` (rule 9, checked by `migrate`): `Before` and `Through` name a once-only id in the list, or
  `InvalidMigrationsException`.
- Planner, per [architecture](../architecture.md#one-migrate-call):
  - due: missing or not `APPLIED`; a repeatable whose stored revision differs; every-start always, under
    `Target.Latest` only;
  - order: once-only migrations in list order, then repeatable and every-start ones in list order;
  - conflicts: out of order under `OutOfOrder.FAIL` (a pending once-only migration listed before an applied once-only
    migration, the applied ids a later superseding migration not yet `APPLIED` names counting in its place; repeatable
    and every-start documents and unknown ids never count; a recorded baseline is exempt),
    partial supersede (evaluated only while the baseline has no `APPLIED` document), unknown applied under
    `UnknownApplied.FAIL`, an adoption gap through the out-of-order policy;
  - `adopting` (an input: `adoptApplied` is set and every history document is `ADOPTED`, or there is none): the
    out-of-order and partial-supersede conflicts are left out, so the plan made before the lock, and `status()`, never
    report what the hook may still fill; the plan made under the lock after the hook passes `false` and checks every
    conflict;
  - unknown applied ids sorted by id; ids in any recorded superseding migration's stored `supersedes` list count as
    known; `FAILED` or `RUNNING` history for an undeclared id is neither reported nor run;
  - superseded records: all replaced ids `APPLIED` means record, none means run, some means conflict; a partially
    superseded migration is that conflict only, never also out of order, because `OutOfOrder.RUN` would not run it;
  - targets: `Target.Before` and `Target.Through` reach the once-only migrations up to their id; only those run, are
    recorded, or can be out of order or partially superseded, and the ones they stop before that are due or to be
    recorded are `pending`;
  - `needsTransactions` when any due migration has a transactional step; `untracked` when history is empty and
    `adopting` is false (`status()` with a hook configured never reports it).
  - the same plan backs `status()`: every-start migrations are never pending.

**Tests.**

| Spec | Covers |
|---|---|
| `DeclarationTest` | every factory and step combination; `steps`, `kind`, `supersedes`; `toString` is the id |
| `ValidationTest` | one failing list per rule, the exact problem text, all problems in one exception, a valid list passes; `Target` errors |
| `PlannerTest` | table-driven over kind x history state x origin x policy x target x `adopting`: due, order, conflicts, unknown ids, supersede outcomes, `needsTransactions`, `untracked`, status pending; with `adopting`, a gap and a partial supersede are not conflicts and their ids are due |
| `PlannerPropertyTest` | 10,000 generated lists and histories (kotest-property): an `APPLIED` once-only migration is never due; due order follows list order; a repeatable or every-start migration never runs before a due once-only one; no target runs a repeatable; an `APPLIED` repeatable or every-start document never makes a once-only migration out of order; listing the replaced ids of a superseding migration not yet `APPLIED` in its place changes nothing about out of order before it |
| `GodwitConfigTest` | the defaults, the default holder, each `require` in `LockConfig` |
| `ReportsTest`, `ExceptionsTest` | `count`, `report[id]`, `isUpToDate`; every exception message as the docs quote it |
| `PhaseStubsTest`, `TestKitTest` | every I/O body throws `NotImplementedError` naming its phase; `SessionEscapeError`'s message |
| `neg-check.sh` | every "This does not compile:" block fails with its expected message; one control snippet compiles |

**Gate.**

```sh
./gradlew lintKotlin
./gradlew apiCheck
./gradlew :godwit-core:test
python3 scripts/coverage-gate.py
./gradlew -p docs-snippets compileKotlin
scripts/check-docs.sh
```

**Measurable outcome.** Branch coverage of `godwit.core.internal.Validation*` and `godwit.core.internal.Planner*` is
100 % (the gate's output lists each class with `branches=<covered>/<total>`). `scripts/check-docs.sh`, run against the
real modules with `docs-snippets/api-stubs/` gone, reports every kotlin block found and every neg expectation held, and
ends with `check-docs: all checks passed`. `PlannerPropertyTest` logs `planner invariants held lists=10000`.

## P2. History store and lease lock

**Goal.** godwit's two collections behave exactly as [architecture](../architecture.md#lock-operations) specifies,
under concurrency, crashes and lost renewals.

**Files.** `godwit-core/src/main/kotlin/godwit/core/internal/Bookkeeping.kt` (both collections on the default codec
registry, majority read and write concern, primary reads), `HistoryStore.kt`, `MongoLock.kt`, `Heartbeat.kt`,
`Log.kt` (the event catalogue as one function per event, slf4j fluent API, including the three lock events).

**Behaviours.**

- History store, per [architecture](../architecture.md#history-writes):
  - `readAll()`: one `find`, majority read concern, primary.
  - once-only `RUNNING` marker: conditional upsert on `state != APPLIED`, `$inc attempts`, owner token, run id; a
    duplicate key (11000) means another run applied it: skip, report it up to date.
  - repeatable and every-start marker: unconditional pipeline upsert on `{_id}`; `attempts` restarts at 1 after
    `APPLIED`; `revision` is written only when the run applies.
  - the fenced `APPLIED` record, outside a transaction or on the step's session: matches `{_id, owner, state: RUNNING}`;
    0 matched means the lock was lost.
  - `FAILED` with `lastError` (`type`, `message`, stack capped at 8 KB, `step`, `at`), outside any transaction, fenced
    on `{_id, owner, state: RUNNING}`; it reports whether it matched.
  - `ADOPTED` records: an upsert on `{_id}` whose fields are all in `$setOnInsert` (`steps: []`, `attempts: 0`, no
    `counts`), so an existing document of any state is left unchanged. The records of one adoption call, only for ids
    the history read under the lock lacks, in one `withTransaction` with `checkLock()` before the commit; on a
    standalone server one write per id, `checkLock()` before each, last-listed first (the reverse of the once-only
    order, each migration preceded by the ids its `supersedes` list names, in that list's order).
  - `SUPERSEDED` and `MARKED` records: conditional upsert on `state != APPLIED`; `steps: []`, `attempts: 0`, no
    `counts`.
- Lock:
  - acquire: `findOneAndUpdate` with a pipeline on `$$NOW`, upsert, owner token per acquisition; 11000 means held.
  - wait: poll every 250 ms to 5 s with jitter; log `Waiting for migration lock` with the holder every 10 s; throw
    `LockTimeoutException` at `waitTimeout`; `Duration.ZERO` fails at the first refusal.
  - heartbeat: a daemon thread renews every `heartbeat`; it checks the local deadline before each renewal; a renewal
    that throws logs `Lock renewal failed` (`runId`, `holder`, `error`) and is retried at the next tick; a renewal
    that matches 0 documents, or a deadline that passes, marks the lock lost for good and logs `Lost migration lock`
    (`runId`, `holder`, `reason` `NOT_OWNER` or `DEADLINE_PASSED`) once per run, from the heartbeat thread or from
    `checkLock()`, whichever sees it first.
  - `checkLock()`: no I/O; throws `LockLostException` once the lock is lost or the local deadline
    (`lease - safetyMargin` after the last renewal was sent, on the monotonic clock) has passed.
  - release: fenced on the owner token, sets `expiresAt` and `releasedAt` to `$$NOW`; a release that throws logs `Lock
    release failed` (`runId`, `holder`, `error`) and is not retried.
  - every lock operation has a 5 s client-side timeout.

**Tests.** All integration tests use short timings (`LockConfig(lease = 3.seconds, heartbeat = 1.seconds,
safetyMargin = 1.seconds)`).

| Spec | Covers |
|---|---|
| `HistoryStoreTest` | each write's filter, update and fence; the 11000 skip; the repeatable reset of `attempts`; `lastError` cap; a stale owner's `APPLIED` and `FAILED` writes match 0; a `FAILED` write on an `APPLIED` document of the same owner matches 0; the adoption records commit together or not at all; recording adopted ids twice leaves the first records unchanged; an adoption transaction over an id that another run has just adopted commits without error and changes nothing; an adoption write over a `RUNNING` document leaves it `RUNNING`; a lost lock before the adoption commit records nothing and throws `LockLostException` |
| `MongoLockTest` | first-ever concurrent acquire (the loser sees 11000 and polls); re-acquire by the same owner; acquire after expiry; release by a stale owner changes nothing; a release that throws (a `failCommand` fail point) logs `Lock release failed` and the lease ends on its own; `waitTimeout` and `Duration.ZERO`; the lock document deleted by hand (the next renewal marks the lock lost and logs `Lost migration lock reason=NOT_OWNER`, the next acquire recreates it) |
| `HeartbeatTest` | renewal keeps the lock past three leases; a renewal that fails once logs `Lock renewal failed` and the next one keeps the lock; a renewal blocked by a fail point (`failCommand` with `blockConnection`, scoped to the holder's `appName`) makes `checkLock()` throw before another process can acquire, with exactly one `Lost migration lock reason=DEADLINE_PASSED` |
| `LockContentionTest` | 8 threads, each with its own client and owner, acquire and release 50 times; a shared counter proves at most one holder at any moment |
| `LogCatalogueTest` | the wait, acquire, renewal-failed, lost and release-failed events carry their documented keys |

**Gate.**

```sh
./gradlew lintKotlin
./gradlew :godwit-core:test
python3 scripts/coverage-gate.py
```

**Measurable outcome.** `LockContentionTest` observes a maximum of 1 concurrent holder over 400 acquisitions. After a
holder's process is killed, another acquires within `lease` plus one poll interval. Traces: `Waiting for migration
lock holder=... holderRunId=... expiresAt=... waitedMs=...` every 10 s while waiting, then `Acquired migration lock
runId=... lockWaitMs=<n>`; `Lost migration lock runId=... holder=... reason=DEADLINE_PASSED` once in `HeartbeatTest`.

## P3. Runner for once-only migrations

**Goal.** `migrate`, `status`, `requireUpToDate` and `history` work for once-only migrations with outside and
`inTransaction` steps, with the exactly-once and at-least-once guarantees holding through every crash window.

**Files.** `godwit-core/src/main/kotlin/godwit/core/internal/Runner.kt`, `Transactions.kt` (the wrapper around the
driver's `withTransaction`), `Topology.kt`, `ErrorGuidance.kt`, `Scopes.kt` (scope implementations); the bodies of
`Godwit.migrate`, `status`, `requireUpToDate`, `history`; `Ddl.kt` bodies. Tests add
`godwit-core/src/test/kotlin/godwit/core/crash/CrashMain.kt` and `CrashHarness.kt`.

**Behaviours.**

- The `migrate` sequence of [architecture](../architecture.md#one-migrate-call): validate; read history; plan;
  conflicts before the fast path; the fast path (one history read, no lock, `report.lockWait == null`); the topology
  check only when a transactional step is due; acquire; read history again; plan again; run; release in `finally`.
- Running one migration, per [architecture](../architecture.md#running-one-migration): marker; `Resuming interrupted
  migration` when the previous state was `RUNNING`; the outside step with its own counters; `checkLock()` between
  steps; `inTransaction` through `ClientSession.withTransaction` with snapshot read concern, majority write concern and
  primary reads; a fresh scope and counters per attempt; `Retrying transaction` and `transactionRetries`; `Slow
  transaction` above `slowTransactionWarning`; the fenced `APPLIED` record as the last write of the transaction; an
  outside-only migration records `APPLIED` after its step.
- The transaction wrapper: a pause before each attempt after the first (5 ms, x1.5 per attempt, at most 500 ms, with
  jitter); `Retrying transaction` for the first retry of a transaction, then at most every 10 s, with `error` the code
  name and code of the previous body's error or `commit`; every retry counted in `transactionRetries`.
- Topology: `hello` before the lock when a transactional step is due, and again under the lock when the plan made there
  has a transactional step due that the first one did not.
- Failure: the runner catches `Throwable` around steps, so an `Error` from a step (such as `SessionEscapeError`) fails
  the migration too; `FAILED` is written outside any transaction, fenced on the owner and `RUNNING`; when that write
  matches nothing, the runner reads the document on the primary and treats `APPLIED` by this run as applied (a commit
  whose reply failed), anything else as a lost lock; `MigrationFailedException` carries the step, the report so far,
  the cause and the guidance line of [architecture](../architecture.md#error-guidance); a failed `FAILED` write leaves
  the document `RUNNING` and is attached as a suppressed exception; a lost lock writes nothing more and throws
  `LockLostException`, except that a step error that coincides with the lock loss is still written with the fenced
  `FAILED` write (it matches only while no other run has taken the document over) and becomes the
  `LockLostException`'s cause; the run stops at the first failure.
- DDL helpers: `ensureCollection` (48 counts as existing), `dropIndexIfExists` (drops only what `listIndexes` shows,
  so it returns false for a missing index on every server version; 27 from a concurrent drop counts as gone),
  `ensureSearchIndex` (create unless a search index with the name exists, a concurrent create of the name counting as
  existing; with `awaitReady`, poll until queryable, also for an existing index that is not queryable yet, calling
  `checkLock()` between polls in the scope form; `SearchIndexNotReadyException` when the wait ends).
- `status()` runs validation and the planner with no lock and no writes; `requireUpToDate` throws
  `PendingMigrationsException` unless up to date; `history()` maps every document to `HistoryEntry`, sorted by id.
- Every log event of the catalogue that applies to once-only migrations, with its documented keys.

**Tests.**

| Spec | Covers |
|---|---|
| `RunnerTest` | outside-only, transactional-only and two-step migrations; the prepared value reaches the transaction, and the same instance reaches every driver retry (a `TransientTransactionError` fail point with a prepared value the body would drain or a one-shot `Sequence`); counters from both steps add up; report fields; stop at the first failure; `status`, `requireUpToDate`, `history` |
| `FastPathTest` | nothing due: exactly one command (`find` on `godwit-history`) and no command on `godwit-lock`, observed by a command listener; `lockWait` null |
| `CrashWindowTest` | a child JVM (`CrashMain`) runs a scenario and is killed with `destroyForcibly()` at a point marked by a command listener in the child: after the marker, mid outside step, after the outside step, inside the transaction, and after the commit and before the release. The parent then runs `migrate` and asserts the resume, the `attempts` count, and that every transactional effect (an `$inc` probe) is 1 |
| `TransactionRetryTest` | `failCommand` fail points: a `TransientTransactionError` re-runs the body with fresh counters and `attempt` 2, after a pause; an `UnknownTransactionCommitResult` retries only the commit; a transient error on commit logs `error=commit`; a body that conflicts for 5 s logs `Retrying transaction` at most once per 10 s and counts every retry; a commit that applies and then times out on the client (`timeoutMS`, a blocked majority acknowledgement) ends `APPLIED`, not `FAILED`, with the `$inc` probe at 1, also for an outside-only `APPLIED` record; `txRetries` in the report and log |
| `ErrorGuidanceTest` | 251 and 290 after a long attempt (the 60 s threshold is an internal constructor parameter set low in the test), 388, 263, an index build on an existing collection in a transaction, a session from another client; each message carries its guidance, and an unknown cause carries none |
| `LockLossTest` | a heartbeat blocked by a fail point during a long step: the step's next `checkLock()` throws, the transaction aborts, history is unchanged, `LockLostException` without a cause; a step that throws its own error once the deadline has passed: `LockLostException` whose cause is that error, and the document `FAILED` with it as `lastError`; the same after another run's marker took the document: the cause is kept and the `FAILED` write matches nothing; an `inTransaction` call that fails with a network error once the deadline has passed (a `failCommand` fail point with `closeConnection`, which the driver labels `TransientTransactionError` inside a transaction, as it does a server selection timeout): the driver runs the body again, its first `checkLock()` throws, history is unchanged, `LockLostException` without a cause; the same call on a client with `timeoutMS` failing with `MongoOperationTimeoutException`, which `withTransaction` does not retry: `LockLostException` whose cause is the timeout |
| `TopologyTest` | a standalone container: `TransactionsUnsupportedException` listing the due transactional migrations, before the lock; an outside-only list runs; a transactional repeatable that becomes due under the lock still throws `TransactionsUnsupportedException` |
| `DdlHelpersTest` | each helper twice in a row; concurrent `ensureCollection` from two threads; `dropIndexIfExists` of a missing index returns false, also on a MongoDB 8.3 or later image |
| `SearchIndexTest` (tag `Atlas`) | create, exists, wait until queryable, wait for an existing index that is still building, `SearchIndexNotReadyException`, `checkLock()` between polls |
| `LogCatalogueTest` | every event this phase emits, with its level and keys |

**Gate.**

```sh
./gradlew lintKotlin
./gradlew apiCheck
./gradlew :godwit-core:test
./gradlew :godwit-core:atlasTest
python3 scripts/coverage-gate.py
```

**Measurable outcome.** Every crash window ends `APPLIED`, with every `$inc` probe at exactly 1. The fast path issues
exactly one command; `FastPathTest` logs `fastPath calls=100 p50Ms=<n> p95Ms=<n>` and fails when p50 exceeds 20 ms
against the local container. Traces: `Running migration id=... attempt=1`, `Applied migration id=... attempts=1
txRetries=0 batches=0 durationMs=...`, `Migrations up to date runId=... checked=... durationMs=...`, and `Resuming
interrupted migration id=... attempts=2` in `CrashWindowTest`.

## P4. Batched steps

**Goal.** `inBatches` changes a collection of any size, one transaction per page, and resumes after a crash with
every page committed exactly once.

**Files.** `godwit-core/src/main/kotlin/godwit/core/internal/Batches.kt`; checkpoint writes in `HistoryStore.kt`.

**Behaviours.** Per [batched backfills](../batched-backfills.md) and [architecture](../architecture.md#checkpoint-writes):

- each page, in one transaction: find `pending` and `_id > checkpoint`, sorted by `_id`, limit `batchSize`; call the
  step (never with an empty page); write the checkpoint (`lastId`, `batches`, counts so far) to the history document;
- the page with fewer than `batchSize` documents is the last, and its transaction also writes the fenced `APPLIED`
  record;
- a retry runs the outside step again, then resumes after the stored checkpoint;
- every page's `_id` type is checked against the run's type (the checkpoint's `lastId` on a resumed run, otherwise the
  first page's; all numeric types count as one), and before the last commit, also after an empty first page, no
  document matching `pending` may have an `_id` of another type; either failure is `MigrationFailedException` in
  `IN_BATCHES` naming both types;
- the body returns the new checkpoint, and the loop takes it only after `withTransaction` returns, so a commit retry
  never skips a page;
- `Committed batch id=... batch=... lastId=...` at DEBUG per page.

**Tests.**

| Spec | Covers |
|---|---|
| `BatchesTest` | sizes 0, 1, `batchSize - 1`, `batchSize`, `batchSize + 1` and an exact multiple; `pending` re-evaluated per page (a document that stops matching is skipped); counters across pages; the outside step runs first; the step never sees an empty page |
| `BatchesCrashTest` | the child JVM is killed after page k commits; the next `migrate` resumes at page k + 1; an `$inc` probe per document is exactly 1 |
| `BatchesIdTypeTest` | ObjectId, string and number `_id`s each work; mixed int32 and int64 `_id`s page as one type; a mixed collection fails on a page and before the last commit, naming both types; a resumed run whose checkpoint has the type that sorts first and whose `pending` was narrowed to the other type fails instead of applying |
| `BatchesCommitRetryTest` | a transient error on a page's commit re-runs the page: every page is processed once, and the checkpoint advances only after the commit |
| `BatchesLockLossTest` | the lock is lost while a page commits: the page rolls back, the takeover resumes after the last committed page |

**Gate.**

```sh
./gradlew lintKotlin
./gradlew :godwit-core:test
python3 scripts/coverage-gate.py
```

**Measurable outcome.** A kill-and-resume run over 10,050 documents with `batchSize = 500` ends with every probe at 1
and `Applied migration ... steps=[IN_BATCHES] ... batches=21`. Trace: one `Committed batch` DEBUG line per page, with
`batch` numbers continuing across the restart.

## P5. Every-start and repeatable migrations

**Goal.** Migrations that run again do so under the same guarantees, and a repeatable at its current revision costs
nothing at startup.

**Files.** Changes in `Runner.kt`, `HistoryStore.kt` and `Planner.kt` (already covered for planning in P1).

**Behaviours.** Per [repeatable migrations](../repeatable-migrations.md): run after every due once-only migration; the
unconditional marker; `runCount`, `lastRunAt` and `revision` written when the run applies; a repeatable at its stored
revision is skipped without the lock; an every-start migration takes the lock on every `Target.Latest` start; neither
runs under `Target.Before` or `Target.Through`; revisions are compared for equality.

**Tests.**

| Spec | Covers |
|---|---|
| `RepeatablesTest` | first run, unchanged revision (skipped), changed revision (runs, `runCount` 2), a failed run retried, an older revision applied again, `attempts` restarting at 1 |
| `EveryStartTest` | runs on every start, `runCount` grows by one per start, never under a target |
| `RepeatablesConcurrencyTest` | 4 processes start after a revision change; the repeatable runs once |
| `RepeatablesFastPathTest` | only a repeatable at its revision: no command on `godwit-lock`; with an every-start migration: one acquire and one release per start |

**Gate.**

```sh
./gradlew lintKotlin
./gradlew :godwit-core:test
python3 scripts/coverage-gate.py
```

**Measurable outcome.** A start whose list ends with an up-to-date repeatable sends 0 commands to `godwit-lock`; four
concurrent starts after a revision change raise `runCount` by exactly 1. Trace: `Applied migration id=...
kind=REPEATABLE ...` once per revision change, and `Migrations up to date` on every other start.

## P6. Adoption, squashes, unknown ids, markApplied and targets

**Goal.** godwit takes over databases migrated by another tool, or by hand, refuses the ones it cannot judge, squashes
safely, and offers the audited escape hatch.

**Files.** `godwit-core/src/main/kotlin/godwit/core/internal/Adoption.kt`; changes in `Runner.kt`, `HistoryStore.kt`;
`Godwit.markApplied`.

**Behaviours.**

- Adoption, per [adopting an existing database](../adopting-an-existing-database.md) and
  [DD-14](../design-decisions.md#dd-14-adoption-through-an-application-supplied-hook): the hook runs under the lock on
  every start that takes it while every history document is `ADOPTED` (or there is none), and not while a document of
  another origin exists (deleting every such document reopens adoption); `checkLock()` after it returns; the declared
  once-only ids and ids named in a `supersedes` list that history does not hold yet are written `ADOPTED` with
  insert-only upserts, in one `withTransaction` with `checkLock()` before the commit (one write per id, `checkLock()`
  before each, last-listed first with each migration preceded by the ids its `supersedes` list names, on a standalone
  server), before the plan is checked; ids recorded earlier are kept, never removed; other ids are
  logged as ignored; `Adopted applied migrations` on every call, `adopted` listing the ids that call recorded; the
  out-of-order and partial-supersede checks wait for the plan made after the hook; a gap left after the hook follows
  the out-of-order policy; an exception from the hook propagates, the lock is released and nothing is recorded.
- The untracked guard, under the lock: collections other than godwit's and `system.*`, no history, nothing adopted:
  `UntrackedDatabaseException` under `REFUSE`, everything runs under `RUN_ALL`.
- Squashes, per [squashing migrations](../squashing-migrations.md): `Recorded superseded migration` with the stored
  `supersedes` list; a partial squash is a conflict.
- Unknown applied ids: WARN and `report.unknownApplied`, or `PlanConflictException` under `FAIL`, checked before the
  fast path.
- `markApplied(id, reason)`: waits for the lock; `RUNNING`, `FAILED` or missing becomes `APPLIED` with origin `MARKED`
  (a missing document is recorded once-only); an `APPLIED` once-only document is unchanged; a `REPEATABLE` or
  `EVERY_START` document in any state, `APPLIED` included, and a blank reason throw `IllegalArgumentException` without
  writing; with `adoptApplied` set and history empty or holding
  only `ADOPTED` documents, `IllegalStateException` without writing, checked under the lock on the same history read
  ([DD-14](../design-decisions.md#dd-14-adoption-through-an-application-supplied-hook)); `Marked migration applied` at
  WARN.
- `Target.Before` and `Target.Through` stop the run; `report.pending` lists what they left due.

**Tests.**

| Spec | Covers |
|---|---|
| `AdoptionTest` | full prefix; empty result (guard); undeclared ids ignored; `supersedes` ids imported; a gap under `FAIL` and `RUN`; after a refused gap the next start calls the hook again, records only the ids the corrected record adds and removes none; the hook throws; `system.*` collections ignored; standalone server with a transactional migration due; an error that is not transient in the adoption transaction (nothing recorded, the exception propagates, the next start calls the hook again); a transient one (a `failCommand` fail point with the `TransientTransactionError` label on its first write) is retried in the same call and every id is recorded; on a standalone server, records written last-listed first, each migration preceded by the ids its `supersedes` list names; an interrupted standalone adoption (a `failCommand` fail point on the third write of an outside-only list) completes on the next start under `OutOfOrder.FAIL` and under `OutOfOrder.RUN`: no `PlanConflictException`, no migration runs out of order, every id `ADOPTED`; the same partial adoption started without the hook: `PlanConflictException` under `FAIL`; an interrupted standalone adoption of ids a `supersedes` list names: the baseline counts as due before the lock, so the next start reaches the hook and completes the adoption, and a start without the hook refuses the partial supersede under both policies; `status()` on a partially adopted database lists the missing ids as pending and reports no problem; the hook (a counting fake) is not called while history holds a `RAN`, a `SUPERSEDED` or a `MARKED` document, and is called again once every such document is deleted; the first once-only id marked before the first start from a `Godwit` built without the hook: the hook is not called and the next start runs the migrations listed after it; a later id marked that way: `PlanConflictException` under `FAIL`; a start with nothing due does not call it |
| `AdoptionConcurrencyTest` | 4 processes start on the same unadopted database whose list has a migration left to run: the hook runs once; with a list that adoption covers entirely, every waiting process calls it again and records nothing; on a standalone container whose history holds a partial adoption, a process that reads it before the lock waits for the holder and ends without `PlanConflictException` |
| `AdoptionCrashTest` | `CrashMain` (P3) killed while adoption records its ids: on a replica set the next start finds all of them recorded or none, calls the hook and records what is missing; on a standalone server it finds a partial adoption and completes it, as in `AdoptionTest` |
| `UntrackedGuardTest` | `REFUSE` names the collections; `RUN_ALL` runs everything; an empty database runs everything |
| `SupersedesTest` | all applied (recorded, not run), none (runs), some (conflict); an `APPLIED` baseline is not evaluated again (a database the baseline built that ran three of the old six under an older release); the stored list keeps old ids known after they leave the code; a recorded baseline is exempt from the out-of-order policy |
| `UnknownAppliedTest` | WARN with the sorted ids in the report and the log; `FAIL` on a start with nothing due |
| `MarkAppliedTest` | each starting state; a repeatable and an every-start document refused, each when `FAILED` and when `APPLIED` (an `APPLIED` repeatable at its current revision included), with the document unchanged; blank reason; waits for a held lock; with `adoptApplied` set, refused with `IllegalStateException` and its exact message on an empty history, and on a history that holds only `ADOPTED` documents (the id's own `ADOPTED` document included); succeeds once a `RAN` document exists; succeeds from a `Godwit` built with `config.copy(adoptApplied = null)` on an empty history and on one with only `ADOPTED` documents, with a custom `historyCollection` and `lockCollection` that the mark writes to and locks; on every refusal the history collection is unchanged (no document written or modified), no `Marked migration applied` line is logged and the lock is released |
| `TargetTest` | `Before`, `Through`, `pending` in the report; no repeatable or every-start migration runs |

**Gate.**

```sh
./gradlew lintKotlin
./gradlew :godwit-core:test
python3 scripts/coverage-gate.py
```

**Measurable outcome.** Four concurrent first starts on an unadopted database with a migration left to run call the hook
once and log one `Adopted applied migrations adopted=[...] ignored=[...]` line. An interrupted standalone adoption ends
with every id `ADOPTED` and no `PlanConflictException`, under both out-of-order policies, also for a process that read
the partial adoption before the lock; started without the hook, the same partial adoption is refused under `FAIL`, and
a partial supersede under both policies. The hook is not called on any start while a `RAN`, `SUPERSEDED` or `MARKED`
document exists, and is called again once they are deleted. `status()` on a partial adoption lists the missing ids as
pending with no problem. `markApplied` on a `Godwit` with `adoptApplied` throws `IllegalStateException` and writes
nothing while history is empty or holds only `ADOPTED` documents; marked from a `Godwit` built without the hook before
the first start, the first id ends adoption and the next start runs the migrations after it, as the docs warn.
Traces: `Recorded superseded
migration id=... supersedes=[...]`, `Unknown applied migrations ids=[...]`, `Marked migration applied id=... reason=...
holder=...`.

## P7. The godwit-test kit

**Goal.** An application tests its migrations through the real runner with one call per test, and a forgotten
`session` fails the test.

**Files.** `godwit-test/src/main/kotlin/godwit/test/TestGodwit.kt`, `RunnerPath.kt`, `SessionEscapeDetector.kt`,
`internal/SharedContainers.kt`; tests under `godwit-test/src/test/kotlin/godwit/test/`; in
`docs-snippets/build.gradle.kts`, a `test` task that runs the Kotest specs its main source set compiles.

**Behaviours.** Per [testing](../testing.md):

- `testGodwit(config, atlasSearch)`: one replica-set container per JVM (the Atlas local image when `atlasSearch`), each
  started at most once, stopped when the JVM exits; a database named by a random UUID per call; a client with
  `SessionEscapeDetector` installed; a `Godwit` on that client.
- `forget(id)` deletes the history document; `rerun` forgets and runs a once-only id with `Target.Through(id)` and
  out-of-order allowed, or a repeatable with `Target.Latest`, with the guard and adoption off; `runIsolated` runs one
  migration with the guard and adoption off and other history ids ignored; `shouldHaveApplied` throws
  `AssertionError` unless the id is `APPLIED`.
- `SessionEscapeDetector`: records, per thread, the `lsid` and `txnNumber` of the command that opens godwit's
  transaction (`startTransaction: true`); until a commit or abort for that `lsid`, a command on that thread with
  another `lsid` or without `autocommit: false` throws `SessionEscapeError` from the listener, naming the command and
  the collection; a commit or abort of another session does not end the window.

**Tests.**

| Spec | Covers |
|---|---|
| `TestGodwitTest` | two calls share one container and get different databases; `atlasSearch` starts the second image once |
| `RunnerPathTest` | `forget`, `rerun` (counts 0 on the second run, also after `runIsolated` on a database with other collections), `runIsolated` on a database with unrelated data, `shouldHaveApplied` passing and failing |
| `SessionEscapeDetectorTest` | an escape matrix: `find`, `insert`, `update`, `delete`, `aggregate` and `bulkWrite` without the session, in `inTransaction` and in `inBatches`, called directly and through a service, and a service that starts a session and a transaction of its own; each fails with `MigrationFailedException` whose cause is `SessionEscapeError` naming the command and collection, and an escape after the service's own commit is still caught. No false positive: the same calls with the session, commands on another thread, outside steps, godwit's own marker, record and checkpoint writes |
| `DriverContractTest` | pins what the detector relies on: commands sent with the transaction's session carry its `lsid` and `autocommit: false` in `CommandStartedEvent`, the first one also `startTransaction: true`, and an `Error` thrown from a listener reaches the caller |
| `ExampleShopTest` | the shop's canonical spec from the docs, run for real (`docs-snippets` tests) |

**Gate.**

```sh
./gradlew lintKotlin
./gradlew apiCheck
./gradlew :godwit-test:test
./gradlew -p docs-snippets test
python3 scripts/coverage-gate.py
```

**Measurable outcome.** The detector fails every case of the escape matrix and none of the controls (the test prints
`escape matrix caught=<n>/<n> falsePositives=0`). Trace: `godwit-test container started image=... startupMs=...`
appears once per test JVM.

## P8. Documentation and release

**Goal.** The documentation describes the implementation exactly, and `works.resolute:godwit-core` and
`works.resolute:godwit-test` are on Maven Central.

**Files.** `README.md` (the status line names the released version), `docs/**` (any correction found while
verifying), `docs-snippets` (every snippet executed where it is a test), `consumer-smoke/` (a separate Gradle build,
not part of `settings.gradle.kts`, that depends on `works.resolute:godwit-core` and `godwit-test` by coordinates and
contains the README's example and test).

**Behaviours.**

- Every log line, history document, exception message and problem text quoted in the docs matches what the
  implementation produces: `DocsFidelityTest` runs the scenario behind each quoted output and compares the text,
  ignoring run ids, times and durations. Its scenarios include the lock-loss timelines of
  [locking](../locking.md#losing-the-lock-mid-run) and [failure and recovery](../failure-and-recovery.md#lock-lost),
  with the three lock events (`Lock renewal failed`, `Lost migration lock`, `Lock release failed`) and the driver's
  error text they quote; where the driver's text differs, every occurrence in the docs changes together.
- A final documentation pass runs once P1 to P7 have merged and the code has settled, before the release:
  - Every page of `README.md` and `docs/**` is re-read against the implementation.
  - Every design decision that changed during P1 to P7 is updated in [design decisions](../design-decisions.md) and in each doc that explains it.
  - The pass ends with the same three reviews as the initial docs (conformance with the decisions, MongoDB correctness, client code and walkability from the README), each finding fixed or rejected with a reason.
  - The release waits for this pass.
- No `NotImplementedError` and no `TODO(` remains in main code.
- Dokka output in `docs/dokka`, published by `publish-docs.yml` on the release tag.

**Tests.** `DocsFidelityTest`; the `docs-snippets` specs; a consumer smoke project outside the build that depends on
the artifacts from Maven Local, contains the README's example and test, and runs them.

**Gate.**

```sh
./gradlew lintKotlin
./gradlew apiCheck
./gradlew test
./gradlew atlasTest
python3 scripts/coverage-gate.py
scripts/check-docs.sh
! grep -rn -e 'TODO(' -e 'NotImplementedError' godwit-core/src/main godwit-test/src/main
./gradlew :dokkaGenerate
make publish-local
./gradlew -p consumer-smoke test
make release
curl -sf https://repo1.maven.org/maven2/works/resolute/godwit-core/0.1.0/godwit-core-0.1.0.pom
```

**Measurable outcome.** The final `curl` exits 0, and the published POM lists the two runtime dependencies and the
Kotlin standard library. The consumer smoke project's README test passes against the published coordinates. Traces:
`scripts/check-docs.sh` reports every kotlin block found, every neg expectation held and every link resolved; the
GitHub Pages site serves the Dokka output.

## Risk register

| # | Risk | Impact | Mitigation | Signal |
|---|---|---|---|---|
| 1 | A forgotten `session` compiles; the call escapes the transaction | Writes not rolled back; a step blocks on its own transaction | `SessionEscapeDetector` in every `testGodwit()` client (P7); docs show the session on every call | `SessionEscapeError` in a user's test; `escape matrix caught=n/n` in godwit's CI |
| 2 | The detector depends on driver behaviour (`autocommit: false` on in-transaction commands; errors from listeners reaching the caller) | A driver upgrade silently disables the detector | `DriverContractTest` pins both (P7) and runs on every build | `DriverContractTest` fails |
| 3 | DDL through the raw driver inside `inTransaction` compiles | Runtime failure in production | the helpers do not resolve there; error guidance for 263 and index builds (P3) | `MigrationFailedException` with the DDL guidance |
| 4 | Services built on a second `MongoClient` | The first transactional step fails | guidance on "ClientSession from same MongoClient" (P3); docs | the guidance line in the exception |
| 5 | The runner's state machine (adoption, prefix check, supersedes, guard, repeatables, batches, takeover) has interacting paths | A combination misbehaves in production only | the pure planner with property tests (P1); the crash-window, takeover and adoption matrices (P3 to P6); 100 % branch coverage | the coverage gate; matrix failures |
| 6 | Crash-window tests are timing-sensitive | Flaky CI hides real failures | kill points marked by command listeners, not sleeps; short lock timings through `LockConfig`; Awaitility with explicit timeouts | a test that fails then passes on rerun is treated as a bug |
| 7 | Driver 5.7.0 retries a transaction body without backoff (backoff exists from 5.12) | Write conflicts with heavy application traffic, or with a dead holder's open transaction after a takeover, retry in a tight loop | godwit's body wrapper pauses between attempts and rate-limits the `Retrying transaction` WARN (P3); `transactionRetries` makes retries visible; batch sizes are the lever | `txRetries` in the `Applied migration` line |
| 8 | CI tests one server release (`mongoImage`) while the docs state MongoDB 4.4 or later | An older server behaves differently | before the release, run `./gradlew test -PmongoImage=mongo:4.4` once; the README states the lowest version that passes | that run's exit code |
| 9 | The Atlas local image is large and slow to start | Slow or flaky CI | only `Atlas`-tagged specs use it, in their own `atlasTest` task and CI step | `atlasTest` duration |
| 10 | Clock skew on the primary | A lease ends early or late | the lease uses `$$NOW` on one server; the safety margin absorbs small skew; the docs require NTP | `Lost migration lock ... reason=DEADLINE_PASSED` |
| 11 | A migration value that is never listed compiles and never runs | A change silently missing from a release | the coverage convention test in [testing](../testing.md#every-migration-has-a-test) | that test fails when the list and the tests disagree |
| 12 | Topology is checked before adoption | A standalone server with transactional migrations refuses even when adoption would cover them | documented as an edge case in [adopting an existing database](../adopting-an-existing-database.md#edge-cases) | `TransactionsUnsupportedException` on a first start |
| 13 | Revisions are compared for equality, not order | An older release applies its own revision again during a rollback | documented in [repeatable migrations](../repeatable-migrations.md#edge-cases) | `Applied migration ... kind=REPEATABLE` from the older release |
| 14 | A deleted repeatable or every-start migration keeps an `APPLIED` document | An unknown-applied warning on every start | the documented manual deletion; see [the settled decisions](#decisions-settled-before-p0) | `Unknown applied migrations ids=[...]` |
| 15 | Kotlin metadata compatibility for consumers on older compilers | A consumer cannot compile against godwit | [DD-26](../design-decisions.md#dd-26-jvm-21-and-kotlin-24); the consumer smoke project compiles with the Kotlin version the README states | the smoke project's build |
| 16 | Publishing depends on the maintainer's GPG key and Central Portal credentials in `.env` | A release blocked or half-published | `make release` runs `publish-local` (which signs) before `publish`; nmcp publishes the aggregation in one deployment | `curl` on the published POM |
| 17 | The adoption hook runs again on every start until a migration runs | A slow or side-effecting hook slows or disturbs those starts | documented as a pure read ([adopting an existing database](../adopting-an-existing-database.md#leaving-the-hook-in-place)); `AdoptionTest` pins when it is and is not called | `Adopted applied migrations` on more than one start |

## See also

- [Architecture](../architecture.md): the components and algorithms these phases implement.
- [Design decisions](../design-decisions.md): the reasoning behind each behaviour.
- [README](../../README.md): what godwit is and how an application uses it.
