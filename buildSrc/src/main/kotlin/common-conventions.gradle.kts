plugins {
    kotlin("jvm")
    id("jacoco")
    id("com.github.nbaztec.coveralls-jacoco")
    id("org.jetbrains.dokka")
    id("org.jmailen.kotlinter")
}

repositories {
    mavenCentral()
}

group = "works.resolute"
version = providers.gradleProperty("godwitVersion").get()

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors = true
    }
}

java {
    withSourcesJar()
    withJavadocJar()
}

// The Javadoc jar holds the Dokka HTML of the module. A module without main classes yields a jar that holds only the
// manifest, so the publication never lacks the artifact that Maven Central requires.
tasks.named<Jar>("javadocJar") {
    from(tasks.named("dokkaGeneratePublicationHtml"))
}

jacoco {
    // The org.jacoco.core jar on the buildSrc classpath is the single JaCoCo pin; its VERSION carries a
    // build timestamp (0.8.15.2026...) that the published agent and ant artifacts do not.
    toolVersion = org.jacoco.core.JaCoCo.VERSION.substringBeforeLast(".")
}

dokka {
    dokkaPublications.html {
        suppressInheritedMembers.set(true)
    }
}
