package godwit.test.internal

import godwit.test.testGodwit

/** The line [main] prints before the container's id. */
const val CONTAINER_ID_LINE = "godwit-test container id"

/**
 * A child JVM's whole life: `testGodwit()` starts the shared replica set container, the JVM prints the container's id
 * and exits normally, which runs the shutdown hooks. SharedContainersTest checks that the container is gone after it.
 */
fun main() {
    testGodwit()
    println("$CONTAINER_ID_LINE ${SharedContainers.replicaSet.container.containerId}")
}
