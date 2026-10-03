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

The root build compiles part of the shop too: the `docsShop` source set of `../godwit-core/build.gradle.kts` takes
the files it lists (the canonical migrations, the services and configuration they use, the file store and a few doc
packages) from `src/main/kotlin`, unchanged and never linted or reformatted, into godwit-core's tests. There
`DocsFidelityTest` runs the scenarios behind every output the docs quote (log lines, history documents, exception
messages, problem lines), and the stack frames the docs quote name those files by line. A change to one of them
changes what the docs must quote: run `./gradlew :godwit-core:test --tests godwit.core.DocsFidelityTest` from the
repository root after it.

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
| `scripts/check-docs.sh` | The compile, `neg-check.sh`, `check-snippets.sh`, `check-links.sh` and `check-content.sh` rows below, one after another, stopping at the first failure |
| `(cd docs-snippets && ./gradlew --offline compileKotlin)` | godwit-core and godwit-test build from the root build, and the example shop and every snippet compile against them |
| `./gradlew -p docs-snippets test` | Every Kotest spec the snippets hold runs for real, against the containers `testGodwit()` starts; `OwnClusterSpec` runs against `TEST_MONGO_URI`, or, when it is not set, an Atlas local container the build starts for the task and stops when the build ends. Needs Docker; not part of `check-docs.sh` |
| `scripts/neg-check.sh` | Every `neg/` file meets its `// expect:` line: it compiles, or it fails with the stated message |
| `scripts/check-snippets.sh` | Every `kotlin` block in `README.md` and `docs/**/*.md` appears, after whitespace normalisation, in a compiled file or (after `This does not compile:`) in `neg/` |
| `scripts/check-links.sh` | Every relative link and `#anchor` in `README.md` and `docs/**/*.md` resolves |
| `scripts/check-content.sh` | The content rules hold in every `.kt`, `.kts`, `.md` and `.sh` file of the repository, and `README.md` and `docs/**` outside `docs/development` name none of this build's packages (`com.example.shop.docs`) |

## Adding or changing a doc example

1. Write the example in the doc.
2. Put the same lines in a file under `src/main/kotlin/com/example/shop/docs/<doc_slug>/` (rules in `DOMAIN.md`), or in
   `neg/` with its `// expect:` line when the doc marks the block "This does not compile:".
3. Run `scripts/check-docs.sh`.
4. A block in another language, or an inline span that quotes an output, is checked by `DocsFidelityTest` instead:
   `../godwit-core/src/test/kotlin/godwit/core/docs/DocsQuotes.kt` lists every one in doc order with how it is
   checked, so adding, removing or moving one means updating that list. Run
   `./gradlew :godwit-core:test --tests godwit.core.DocsFidelityTest` from the repository root.
