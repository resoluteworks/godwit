package godwit.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/** The Kotlin standard library, which every Kotlin module depends on and which the check leaves out. */
const val KOTLIN_STDLIB = "org.jetbrains.kotlin:kotlin-stdlib"

/**
 * Fails unless the direct dependencies of a resolved runtime classpath ([runtimeRoot]) are exactly [expected], given
 * as `group:name`, besides the Kotlin standard library. Prints the direct dependencies it finds, with their versions.
 *
 * The root component is a task input resolved by Gradle, so the task reads no configuration at execution time.
 */
abstract class VerifyRuntimeDependencies : DefaultTask() {
    @get:Input
    abstract val expected: SetProperty<String>

    @get:Input
    abstract val runtimeRoot: Property<ResolvedComponentResult>

    @TaskAction
    fun verify() {
        val found = runtimeRoot.get().dependencies.map { dependency ->
            if (dependency !is ResolvedDependencyResult) {
                throw GradleException("unresolved runtime dependency: ${dependency.requested.displayName}")
            }
            dependency.selected.id.displayName
        }
        val direct = directDependencies(found)
        logger.lifecycle("runtime dependencies: ${direct.joinToString(", ")}")
        runtimeDependencyProblem(direct, expected.get())?.let { throw GradleException(it) }
    }
}

/** The `group:name:version` coordinates in [coordinates] without the Kotlin standard library, sorted. */
fun directDependencies(coordinates: List<String>): List<String> =
    coordinates.filterNot { it.startsWith("$KOTLIN_STDLIB:") }.sorted()

/**
 * Null when the `group:name` of [direct] (coordinates with versions) are exactly [expected]; otherwise the problem,
 * naming what is missing and what is unexpected.
 */
fun runtimeDependencyProblem(direct: List<String>, expected: Set<String>): String? {
    val modules = direct.map { it.substringBeforeLast(":") }.toSet()
    if (modules == expected) return null
    val missing = (expected - modules).sorted()
    val unexpected = (modules - expected).sorted()
    return "godwit-core must depend at runtime on exactly ${expected.sorted()} besides $KOTLIN_STDLIB; " +
        "missing: $missing, unexpected: $unexpected"
}
