rootProject.name = "godwit-docs-snippets"

// The root build, so every snippet compiles against the real godwit-core and godwit-test. Gradle substitutes the
// dependencies on works.resolute:godwit-core and works.resolute:godwit-test with its modules.
includeBuild("..")

// neg-check.sh compiles one bad snippet at a time in this module: ./gradlew :neg-check:compileKotlin -PnegSnippet=<dir>
if (providers.gradleProperty("negSnippet").isPresent) {
    include("neg-check")
}
