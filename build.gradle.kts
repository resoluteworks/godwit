import org.jmailen.gradle.kotlinter.tasks.LintTask

plugins {
    base
    id("org.jetbrains.dokka")
    id("com.gradleup.nmcp.aggregation")
    id("org.jetbrains.kotlinx.binary-compatibility-validator")
    id("org.jmailen.kotlinter")
}

group = "works.resolute"

repositories {
    mavenCentral()
}

nmcpAggregation {
    centralPortal {
        username = System.getenv("SONATYPE_PUBLISH_USERNAME")
        password = System.getenv("SONATYPE_PUBLISH_PASSWORD")
        publishingType = "AUTOMATIC"
    }
}

dokka {
    dokkaPublications.html {
        outputDirectory.set(layout.projectDirectory.dir("docs/dokka"))
    }
}

dependencies {
    dokka(project(":godwit-core"))
    dokka(project(":godwit-test"))
    nmcpAggregation(project(":godwit-core"))
    nmcpAggregation(project(":godwit-test"))
}

// The modules lint their own source sets. This task lints the Kotlin that no source set holds: the build scripts of the
// root and of the modules, and the convention plugins. docs-snippets is a build of its own with its own checks.
val lintKotlinBuild = tasks.register<LintTask>("lintKotlinBuild") {
    source(
        fileTree(layout.projectDirectory) {
            include("*.gradle.kts", "*/*.gradle.kts", "buildSrc/**/*.kt", "buildSrc/**/*.kts")
            exclude("docs-snippets/**", "buildSrc/build/**", "buildSrc/.gradle/**", "buildSrc/.kotlin/**")
        }
    )
}

val lintKotlin = tasks.register("lintKotlin") {
    group = "formatting"
    description = "Runs lint on the Kotlin build scripts and convention plugins."
    dependsOn(lintKotlinBuild)
}

// The unit tests of the Python scripts in scripts/ run with the other tests: ./gradlew test.
val testScripts = tasks.register<Exec>("test") {
    group = "verification"
    description = "Runs the unit tests of the Python scripts in scripts/."
    workingDir = layout.projectDirectory.asFile
    commandLine("python3", "-m", "unittest", "discover", "-s", "scripts", "-p", "test_*.py")
}

tasks.check {
    dependsOn(lintKotlin, testScripts)
}
