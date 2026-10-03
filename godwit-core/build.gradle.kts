import godwit.buildlogic.VerifyRuntimeDependencies

plugins {
    id("common-conventions")
    id("test-conventions")
    id("publish-conventions")
}

description = "Runs MongoDB schema and data migrations for Kotlin JVM applications."

dependencies {
    val mongoDriverVersion = providers.gradleProperty("mongoDriverVersion").get()
    val slf4jVersion = providers.gradleProperty("slf4jVersion").get()

    api("org.mongodb:mongodb-driver-kotlin-sync:$mongoDriverVersion")
    implementation("org.slf4j:slf4j-api:$slf4jVersion")
}

// History documents record the release that wrote them as godwitVersion; the code reads it from this resource.
tasks.processResources {
    val godwitVersion = project.version.toString()
    inputs.property("godwitVersion", godwitVersion)
    filesMatching("godwit/core/internal/godwit-version.txt") {
        expand("godwitVersion" to godwitVersion)
    }
}

// godwit-core ships with two runtime dependencies besides the Kotlin standard library. This task resolves the runtime
// classpath, prints the direct dependencies it finds and fails when they are not exactly those two.
val verifyRuntimeDependencies = tasks.register<VerifyRuntimeDependencies>("verifyRuntimeDependencies") {
    group = "verification"
    description = "Fails unless the direct runtime dependencies are the MongoDB driver and slf4j-api."
    expected.set(setOf("org.mongodb:mongodb-driver-kotlin-sync", "org.slf4j:slf4j-api"))
    runtimeRoot.set(configurations.runtimeClasspath.flatMap { it.incoming.resolutionResult.rootComponent })
}

tasks.check {
    dependsOn(verifyRuntimeDependencies)
}

// DocsFidelityTest runs the scenarios behind the outputs the docs quote, with the docs' example shop. The shop's code
// that needs neither the test kit nor a framework is compiled here from docs-snippets, unchanged, so the scenarios run
// the code the docs show, and a stack frame they quote names the file and line it comes from.
val docsShop: SourceSet = sourceSets.create("docsShop")

kotlin.sourceSets.named(docsShop.name) {
    kotlin.srcDir(rootProject.layout.projectDirectory.dir("docs-snippets/src/main/kotlin"))
    kotlin.include(
        "com/example/filestore/FileStoreSchema.kt",
        "com/example/shop/ShopConfig.kt",
        "com/example/shop/domain/Customer.kt",
        "com/example/shop/services/CustomerService.kt",
        "com/example/shop/services/IdentityProvider.kt",
        "com/example/shop/services/PaymentGateway.kt",
        "com/example/shop/migrations/0*.kt",
        "com/example/shop/migrations/reference-countries.kt",
        "com/example/shop/migrations/bootstrap-customers.kt",
        "com/example/shop/migrations/applied-before-godwit.kt",
        "com/example/shop/docs/failure_and_recovery/EmailLower.kt",
        "com/example/shop/docs/failure_and_recovery/Ordering.kt",
        "com/example/shop/docs/failure_and_recovery/PaymentStatus.kt",
        "com/example/shop/docs/history_and_reports/HistoryAdmin.kt",
        "com/example/shop/docs/testing/EscapingMigration.kt",
        "com/example/shop/docs/transactions_and_sessions/wrong-examples.kt"
    )
}

configurations.named(docsShop.implementationConfigurationName) {
    extendsFrom(configurations.api.get())
}

dependencies {
    docsShop.implementationConfigurationName(sourceSets.main.map { it.output })
    testImplementation(docsShop.output)
}

// docs-snippets is a build of its own, with its own checks and file names (002-carts.kt); its files are compiled here,
// never linted or reformatted: the docs quote them byte for byte, stack frames' line numbers included.
tasks.matching { it.name == "lintKotlinDocsShop" || it.name == "formatKotlinDocsShop" }.configureEach {
    enabled = false
}

// godwit-test's specs run on this module's test fixtures (the replica set with fail points, the crash harness,
// LogCapture, CommandRecorder, the docs' quotes and the shop they run). This variant hands them a jar of the compiled
// test and docs shop classes and resources. Its own capability keeps it out of every request that does not ask for it
// by name, as godwit-test's test dependency does.
val testFixturesJar = tasks.register<Jar>("testFixturesJar") {
    archiveClassifier = "test-fixtures"
    from(sourceSets.test.map { it.output }, docsShop.output)
}

configurations.consumable("testFixtureClasses") {
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
    outgoing.capability("${project.group}:godwit-core-test-fixtures:${project.version}")
    outgoing.artifact(testFixturesJar)
}
