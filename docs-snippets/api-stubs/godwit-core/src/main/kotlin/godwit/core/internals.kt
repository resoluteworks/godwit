package godwit.core

import java.net.InetAddress

// Implementation details. Not public API: excluded from API.md.

internal fun defaultHolder(): String {
    val host = System.getenv("HOSTNAME")
        ?: runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("unknown-host")
    return "$host/${ProcessHandle.current().pid()}"
}
