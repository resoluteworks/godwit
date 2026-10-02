// The root project is the example app (com.example.shop, com.example.filestore) and every docs snippet
// (src/main/kotlin/com/example/shop/docs/<doc_slug>/). It sees godwit only through the public API of the two
// library modules, exactly as an app does.
plugins {
    kotlin("jvm") version "2.4.20"
}

allprojects {
    repositories { mavenCentral() }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> { jvmToolchain(21) }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":godwit-test"))
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("io.kotest:kotest-runner-junit5:6.2.5")
    implementation("io.kotest:kotest-assertions-core:6.2.5")
    implementation("io.mockk:mockk:1.14.9")
}
