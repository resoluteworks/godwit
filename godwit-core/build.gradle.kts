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
