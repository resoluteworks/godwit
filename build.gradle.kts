import org.jmailen.gradle.kotlinter.tasks.LintTask

plugins {
    base
    jacoco
    id("com.github.nbaztec.coveralls-jacoco")
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

// Coveralls gets one report for the whole repository: the JaCoCo execution data of both modules' test and atlasTest
// tasks over the main classes of both modules, uploaded once by coverallsJacoco (make test). Each module keeps its own
// report, which scripts/coverage-gate.py reads. The report depends on no test task, so it reads whatever execution data
// the last runs left.
val coverageModules = listOf("godwit-core", "godwit-test")

jacoco {
    // The single JaCoCo pin, as in common-conventions.
    toolVersion = org.jacoco.core.JaCoCo.VERSION.substringBeforeLast(".")
}

val jacocoMergedReport = tasks.register<JacocoReport>("jacocoMergedReport") {
    group = "verification"
    description = "Writes one JaCoCo report over the main classes and execution data of godwit-core and godwit-test."
    dependsOn(coverageModules.map { ":$it:classes" })
    val executionFiles = coverageModules.flatMap { module ->
        listOf("test", "atlasTest").map { task -> layout.projectDirectory.file("$module/build/jacoco/$task.exec") }
    }
    executionData.setFrom(files(executionFiles).filter { it.exists() })
    classDirectories.setFrom(coverageModules.map { layout.projectDirectory.dir("$it/build/classes/kotlin/main") })
    sourceDirectories.setFrom(coverageModules.map { layout.projectDirectory.dir("$it/src/main/kotlin") })
    reports {
        xml.required = true
        html.required = false
    }
}

coverallsJacoco {
    reportPath = jacocoMergedReport.get().reports.xml.outputLocation.get().asFile.path
    reportSourceSets = coverageModules.map { layout.projectDirectory.dir("$it/src/main/kotlin").asFile }
}

tasks.named("coverallsJacoco") {
    dependsOn(jacocoMergedReport)
}
