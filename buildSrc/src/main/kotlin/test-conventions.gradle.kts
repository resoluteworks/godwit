plugins {
    id("common-conventions")
}

dependencies {
    val kotestVersion = providers.gradleProperty("kotestVersion").get()
    val mockkVersion = providers.gradleProperty("mockkVersion").get()
    val logbackVersion = providers.gradleProperty("logbackVersion").get()
    val testContainersVersion = providers.gradleProperty("testContainersVersion").get()
    val awaitilityVersion = providers.gradleProperty("awaitilityVersion").get()

    testImplementation("io.kotest:kotest-runner-junit5-jvm:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
    testImplementation("io.kotest:kotest-property:$kotestVersion")
    testImplementation("io.mockk:mockk:$mockkVersion")
    testImplementation("ch.qos.logback:logback-classic:$logbackVersion")
    testImplementation("org.testcontainers:testcontainers-mongodb:$testContainersVersion")
    testImplementation("org.awaitility:awaitility-kotlin:$awaitilityVersion")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
    }
    // The test fixtures read the container images from these properties; they have no copy of the versions.
    systemProperty("godwit.mongoImage", providers.gradleProperty("mongoImage").get())
    systemProperty("godwit.mongoNewerImage", providers.gradleProperty("mongoNewerImage").get())
    systemProperty("godwit.atlasLocalImage", providers.gradleProperty("atlasLocalImage").get())
    // The version the build writes into godwit-core, which history documents carry as godwitVersion.
    systemProperty("godwit.version", providers.gradleProperty("godwitVersion").get())
}

// Specs tagged Atlas need the Atlas local image and run only in atlasTest. The test task keeps Gradle's default of
// failing when it discovers no tests.
tasks.test {
    systemProperty("kotest.tags.exclude", "Atlas")
    dependsOn("lintKotlin")
    finalizedBy("jacocoTestReport")
    // Some specs read the docs and the KDoc of the main sources, which they keep in step with the code (the log
    // catalogue, the guidance lines). A change to either, a KDoc-only one included, runs the specs again.
    inputs.dir(rootProject.layout.projectDirectory.dir("docs"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("docs")
    inputs.dir(layout.projectDirectory.dir("src/main/kotlin"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("mainSources")
}

val atlasTest = tasks.register<Test>("atlasTest") {
    description = "Runs the specs tagged Atlas against the Atlas local image."
    group = "verification"
    val testSourceSet = sourceSets.test.get()
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    systemProperty("kotest.tags.include", "Atlas")
    // A build with no Atlas-tagged spec has nothing for this task to run, and that is not a failure.
    failOnNoDiscoveredTests = false
    shouldRunAfter(tasks.test)
    finalizedBy("jacocoTestReport")
}

// One report covers both test tasks. It depends on neither, so that each can run alone, and it always has an execution
// data file to read: an empty one stands in for a task that has not run, so a module without tests still gets a report
// that lists its classes as uncovered.
val emptyExecutionData = layout.buildDirectory.file("jacoco/empty.exec")

val createEmptyExecutionData = tasks.register("createEmptyExecutionData") {
    outputs.file(emptyExecutionData)
    doLast {
        emptyExecutionData.get().asFile.apply { parentFile.mkdirs() }.writeBytes(ByteArray(0))
    }
}

tasks.jacocoTestReport {
    val testExecutionData = layout.buildDirectory.files("jacoco/test.exec", "jacoco/atlasTest.exec")
    executionData.setFrom(emptyExecutionData, testExecutionData.filter { it.exists() })
    setDependsOn(listOf(createEmptyExecutionData))
    reports {
        xml.required = true
        html.required = true
    }
}
