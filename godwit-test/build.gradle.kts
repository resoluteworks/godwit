plugins {
    id("common-conventions")
    id("test-conventions")
    id("publish-conventions")
}

description =
    "Test kit for godwit migrations: a shared MongoDB container, runner-path helpers and session escape detection."

dependencies {
    val testContainersVersion = providers.gradleProperty("testContainersVersion").get()

    api(project(":godwit-core"))
    implementation("org.testcontainers:testcontainers-mongodb:$testContainersVersion")
}
