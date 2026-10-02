package godwit.core.internal

import java.net.InetAddress

/**
 * The default [godwit.core.GodwitConfig.holder]: `<hostname>/<pid>`. The host name is the `HOSTNAME` environment
 * variable (set in containers, where it is the pod or container name), else the local host's name, else
 * `unknown-host` when the lookup fails. The environment and the lookup are parameters so a test can replace them.
 */
internal fun defaultHolder(
    environment: (String) -> String? = System::getenv,
    localHostName: () -> String = { InetAddress.getLocalHost().hostName },
    pid: Long = ProcessHandle.current().pid()
): String {
    val host = environment("HOSTNAME") ?: runCatching(localHostName).getOrDefault("unknown-host")
    return "$host/$pid"
}
