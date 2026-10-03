import java.util.Properties

// An application that uses godwit as any other does: by the coordinates of its published artifacts, from Maven Local
// (`make publish-local` at the repository root installs them), with the README's example and test as its code. It is a
// build of its own, outside the root build's settings, so nothing reaches it but what the artifacts carry. It compiles
// with the oldest Kotlin release the README supports, 2.4.0.
plugins {
    kotlin("jvm") version "2.4.0"
}

/** The version the root build publishes: the release under test. */
val godwitVersion: String = Properties()
    .apply { layout.projectDirectory.file("../gradle.properties").asFile.inputStream().use { load(it) } }
    .getProperty("godwitVersion") ?: error("godwitVersion missing from ../gradle.properties")

repositories {
    mavenLocal {
        content { includeGroup("works.resolute") }
    }
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("works.resolute:godwit-core:$godwitVersion")
    testImplementation("works.resolute:godwit-test:$godwitVersion")
    testImplementation("io.kotest:kotest-runner-junit5:6.2.5")
    testImplementation("io.kotest:kotest-assertions-core:6.2.5")
    // The README's logging setup (configuration.md), so the test prints godwit's log lines as the README shows them.
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.32")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
