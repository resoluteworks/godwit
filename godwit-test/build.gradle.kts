plugins {
    id("common-conventions")
    id("test-conventions")
    id("publish-conventions")
}

description =
    "Test kit for godwit migrations: a shared MongoDB container, runner-path helpers and session escape detection."

dependencies {
    val testContainersVersion = providers.gradleProperty("testContainersVersion").get()
    val slf4jVersion = providers.gradleProperty("slf4jVersion").get()

    api(project(":godwit-core"))
    implementation("org.testcontainers:testcontainers-mongodb:$testContainersVersion")
    // The container trace goes through slf4j, which godwit-core and Testcontainers already bring at runtime.
    implementation("org.slf4j:slf4j-api:$slf4jVersion")

    // The specs run on godwit-core's test fixtures: the replica set with fail points, LogCapture, CommandRecorder.
    testImplementation(project(":godwit-core")) {
        capabilities { requireCapability("works.resolute:godwit-core-test-fixtures") }
    }
}

// testGodwit() runs the images godwit's own tests run against; the code reads them from this resource.
tasks.processResources {
    val images = mapOf(
        "mongoImage" to providers.gradleProperty("mongoImage").get(),
        "atlasLocalImage" to providers.gradleProperty("atlasLocalImage").get()
    )
    inputs.properties(images)
    filesMatching("godwit/test/internal/images.properties") {
        expand(images)
    }
}
