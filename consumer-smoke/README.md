# consumer-smoke

An application that uses godwit the way any other does: it depends on `works.resolute:godwit-core` and
`works.resolute:godwit-test` by their coordinates, at the version the root build publishes, and resolves them from
Maven Local. Its code is the README's example (`002-carts`, `004-order-status`, the list and `main`) and its test
(`MigrationsTest`), verbatim; godwit-core's `DocsFidelityTest` holds the two together. It compiles with Kotlin 2.4.0,
the oldest release the README supports.

It is a Gradle build of its own, not part of the root build's `settings.gradle.kts`, so it sees only what the
published artifacts carry: their POMs, their dependencies and their classes.

From the repository root, each command once the one before it has passed:

```sh
make publish-local
./gradlew -p consumer-smoke test
```

The test starts MongoDB in a container through `godwit-test`, so it needs Docker.
