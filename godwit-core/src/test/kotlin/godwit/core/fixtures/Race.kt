package godwit.core.fixtures

import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs [first] and [second] for each round from 0 until [rounds], on two threads that a barrier releases together at
 * the start of every round, so the two calls of a round reach the server at about the same moment. Returns each
 * round's pair of results, in round order. An exception in either call fails the whole race.
 */
fun <A, B> race(rounds: Int, first: (Int) -> A, second: (Int) -> B): List<Pair<A, B>> {
    val barrier = CyclicBarrier(2)
    val pool = Executors.newFixedThreadPool(2)
    try {
        val firsts = pool.submit(
            Callable {
                (0 until rounds).map { round ->
                    barrier.await()
                    first(round)
                }
            }
        )
        val seconds = pool.submit(
            Callable {
                (0 until rounds).map { round ->
                    barrier.await()
                    second(round)
                }
            }
        )
        return firsts.get(1, TimeUnit.MINUTES).zip(seconds.get(1, TimeUnit.MINUTES))
    } finally {
        pool.shutdownNow()
    }
}
