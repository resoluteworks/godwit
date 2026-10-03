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

// godwit-test's specs run on this module's test fixtures (the replica set with fail points, the crash harness,
// LogCapture, CommandRecorder). This variant hands them a jar of the compiled test classes and resources. Its own
// capability keeps it out of every request that does not ask for it by name, as godwit-test's test dependency does.
val testFixturesJar = tasks.register<Jar>("testFixturesJar") {
    archiveClassifier = "test-fixtures"
    from(sourceSets.test.map { it.output })
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
