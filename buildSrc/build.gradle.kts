import java.util.Properties

plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

val rootProperties = Properties().apply {
    rootDir.parentFile.resolve("gradle.properties").inputStream().use { load(it) }
}

fun rootProperty(name: String): String =
    rootProperties.getProperty(name) ?: error("$name missing from gradle.properties")

val kotlinVersion = rootProperty("kotlinVersion")
val kotestVersion = rootProperty("kotestVersion")

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
    implementation("org.jacoco:org.jacoco.core:0.8.15")
    implementation("org.jetbrains.dokka:dokka-gradle-plugin:2.2.0")
    implementation("com.github.nbaztec:coveralls-jacoco-gradle-plugin:1.2.20")
    implementation("com.gradleup.nmcp:com.gradleup.nmcp.gradle.plugin:1.6.2")
    implementation("com.gradleup.nmcp.aggregation:com.gradleup.nmcp.aggregation.gradle.plugin:1.6.2")
    implementation("org.jmailen.gradle:kotlinter-gradle:5.7.0")
    implementation("org.jetbrains.kotlinx:binary-compatibility-validator:0.18.2")

    testImplementation("io.kotest:kotest-runner-junit5-jvm:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
}

tasks.test {
    useJUnitPlatform()
}

// Gradle builds only the jar of buildSrc for the main build. The tests compile against that jar, so they run right
// after it, and a failing test fails the main build.
tasks.jar {
    finalizedBy(tasks.test)
}
