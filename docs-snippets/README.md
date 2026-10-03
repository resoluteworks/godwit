# docs-snippets

A standalone Gradle build (its own `settings.gradle.kts` and wrapper) that compiles every `kotlin` block in the
repository's `README.md` and `docs/**/*.md`. A documented example that does not compile misleads readers, so the docs
are held to the same standard as code: each block is copied verbatim into a `.kt` file here, and the build compiles it.
Blocks that show code which godwit rejects at compile time are kept in `neg/`, and each one is proved to fail.

## Layout

| Path | What |
|---|---|
| `src/main/kotlin/com/example/` | The example online shop (`shop`, `filestore`) and, under `shop/docs/<doc_slug>/`, one package per doc holding the snippets that doc shows |
| `neg/` | Blocks that must not compile. The first line of each file is `// expect: <compiler message>`, or `// expect: OK` for the control that must compile. The doc block that shows the code follows a line reading exactly `This does not compile:` |
| `neg-check/` | The module that compiles one `neg/` file at a time. It is part of the build only when `-PnegSnippet=<dir>` is set |
| `DOMAIN.md` | The example domain and the rules for writing a snippet: collections, classes, canonical migrations, file names |

## The godwit API

The snippets compile against the real `godwit-core` and `godwit-test`. `settings.gradle.kts` includes the root build
(`includeBuild("..")`), and Gradle substitutes the dependencies on `works.resolute:godwit-test` with the root build's
module, built from source, so a snippet sees exactly the API an app sees. The public API is the source and
KDoc of `../godwit-core/src/main/kotlin/godwit/core/` and `../godwit-test/src/main/kotlin/godwit/test/`; the ABI dumps
`../godwit-core/api/godwit-core.api` and `../godwit-test/api/godwit-test.api` list every public declaration, and
`./gradlew :dokkaGenerate` at the root renders it.

## Commands

Run from the repository root. All of them work offline from the Gradle cache, once a build with network access has
resolved the dependencies of both builds (`./gradlew test`, then `./gradlew -p docs-snippets compileKotlin`).

| Command | Proves |
|---|---|
| `scripts/check-docs.sh` | Everything below, one after another, stopping at the first failure |
| `(cd docs-snippets && ./gradlew --offline compileKotlin)` | godwit-core and godwit-test build from the root build, and the example shop and every snippet compile against them |
| `./gradlew -p docs-snippets test` | Every Kotest spec the snippets hold runs for real, against the containers `testGodwit()` starts; `OwnClusterSpec` runs against `TEST_MONGO_URI`, or, when it is not set, an Atlas local container the build starts for the task and stops when the build ends. Needs Docker; not part of `check-docs.sh` |
| `scripts/neg-check.sh` | Every `neg/` file meets its `// expect:` line: it compiles, or it fails with the stated message |
| `scripts/check-snippets.sh` | Every `kotlin` block in `README.md` and `docs/**/*.md` appears, after whitespace normalisation, in a compiled file or (after `This does not compile:`) in `neg/` |
| `scripts/check-links.sh` | Every relative link and `#anchor` in `README.md` and `docs/**/*.md` resolves |
| `scripts/check-content.sh` | The content rules hold across `README.md`, `docs/`, `docs-snippets/` and `scripts/` |

## Adding or changing a doc example

1. Write the example in the doc.
2. Put the same lines in a file under `src/main/kotlin/com/example/shop/docs/<doc_slug>/` (rules in `DOMAIN.md`), or in
   `neg/` with its `// expect:` line when the doc marks the block "This does not compile:".
3. Run `scripts/check-docs.sh`.
