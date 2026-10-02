package godwit.buildlogic

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

private val expected = setOf("org.mongodb:mongodb-driver-kotlin-sync", "org.slf4j:slf4j-api")

class VerifyRuntimeDependenciesTest : StringSpec() {
    init {
        "the Kotlin standard library is left out and the rest is sorted" {
            directDependencies(
                listOf(
                    "org.slf4j:slf4j-api:2.0.17",
                    "$KOTLIN_STDLIB:2.4.20",
                    "org.mongodb:mongodb-driver-kotlin-sync:5.7.0"
                )
            ) shouldBe listOf("org.mongodb:mongodb-driver-kotlin-sync:5.7.0", "org.slf4j:slf4j-api:2.0.17")
        }

        "exactly the expected modules pass, whatever their versions" {
            runtimeDependencyProblem(
                listOf("org.mongodb:mongodb-driver-kotlin-sync:5.7.0", "org.slf4j:slf4j-api:2.0.17"),
                expected
            ) shouldBe null
        }

        "a missing module fails and is named" {
            runtimeDependencyProblem(listOf("org.slf4j:slf4j-api:2.0.17"), expected) shouldBe
                "godwit-core must depend at runtime on exactly " +
                "[org.mongodb:mongodb-driver-kotlin-sync, org.slf4j:slf4j-api] besides $KOTLIN_STDLIB; " +
                "missing: [org.mongodb:mongodb-driver-kotlin-sync], unexpected: []"
        }

        "an extra module fails and is named" {
            runtimeDependencyProblem(
                listOf(
                    "org.mongodb:mongodb-driver-kotlin-sync:5.7.0",
                    "org.slf4j:slf4j-api:2.0.17",
                    "org.jetbrains.kotlin:kotlin-reflect:2.4.20"
                ),
                expected
            ) shouldBe
                "godwit-core must depend at runtime on exactly " +
                "[org.mongodb:mongodb-driver-kotlin-sync, org.slf4j:slf4j-api] besides $KOTLIN_STDLIB; " +
                "missing: [], unexpected: [org.jetbrains.kotlin:kotlin-reflect]"
        }
    }
}
