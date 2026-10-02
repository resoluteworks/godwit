rootProject.name = "godwit-stubs"

include("godwit-core", "godwit-test")
project(":godwit-core").projectDir = file("api-stubs/godwit-core")
project(":godwit-test").projectDir = file("api-stubs/godwit-test")

// neg-check.sh compiles one bad snippet at a time in this module: ./gradlew :neg-check:compileKotlin -PnegSnippet=<dir>
if (providers.gradleProperty("negSnippet").isPresent) {
    include("neg-check")
}
