package godwit.core.internal

/**
 * What the scopes of one step run delegate to: the step's counters and the run's lock check. An outside step has one;
 * a transactional step gets a new one for every run of its body, so its counters count the attempt that commits.
 *
 * [count] may be called from several threads of one step, so the counters are synchronised; they keep the order in
 * which their names were first counted, which is the order the "Applied migration" line prints them in.
 */
internal class StepContext(private val lockCheck: () -> Unit) {
    private val counters = LinkedHashMap<String, Long>()

    fun count(name: String, n: Long): Unit = synchronized(counters) {
        counters[name] = (counters[name] ?: 0) + n
    }

    fun checkLock(): Unit = lockCheck()

    /** The counters so far. */
    val counts: Map<String, Long> get() = synchronized(counters) { LinkedHashMap(counters) }
}

/** [first] and [second] added up per name: the names of [first] in their order, then the names only [second] has. */
internal fun addCounts(first: Map<String, Long>, second: Map<String, Long>): Map<String, Long> =
    LinkedHashMap(first).apply { second.forEach { (name, n) -> merge(name, n, Long::plus) } }
