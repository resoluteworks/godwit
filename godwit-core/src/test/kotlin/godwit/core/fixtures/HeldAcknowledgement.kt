package godwit.core.fixtures

/**
 * Holds the reply of the first write [isTarget] picks before its majority acknowledgement, after the write applied,
 * until the client gives up on it: a commit or record that applies on the server and times out on a client with
 * `timeoutMS`. A later write [isTarget] picks runs as usual. [isTarget] sees every command the client starts until it
 * picks one.
 */
class HeldAcknowledgement(private val isTarget: (RecordedCommand) -> Boolean) {
    @Volatile
    private var held: RecordedCommand? = null

    @Volatile
    private var hang: AutoCloseable? = null

    val recorder = CommandRecorder(
        onStarted = {
            if (held == null && isTarget(it)) {
                held = it
                hang = TestMongo.failPoint("hangBeforeWaitingForWriteConcern", "alwaysOn")
            }
        },
        onFailed = { if (it === held) hang?.close() }
    )
}
