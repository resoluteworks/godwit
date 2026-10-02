package godwit.core

import godwit.core.internal.defaultHolder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import java.net.UnknownHostException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class GodwitConfigTest : StringSpec() {
    init {
        "the defaults are the recommended production values" {
            val config = GodwitConfig(holder = "shop-7f9c4/1")
            config.historyCollection shouldBe "godwit-history"
            config.lockCollection shouldBe "godwit-lock"
            config.outOfOrder shouldBe OutOfOrder.FAIL
            config.unknownApplied shouldBe UnknownApplied.WARN
            config.untrackedDatabase shouldBe UntrackedDatabase.REFUSE
            config.adoptApplied shouldBe null
            config.slowTransactionWarning shouldBe 20.seconds
            config.lock shouldBe LockConfig(60.seconds, 20.seconds, 10.seconds, 10.minutes)
        }

        "the holder defaults to <hostname>/<pid> of this process" {
            GodwitConfig().holder shouldEndWith "/${ProcessHandle.current().pid()}"
        }

        "the holder's host is HOSTNAME, else the local host name, else unknown-host" {
            defaultHolder(environment = { "shop-7f9c4" }, localHostName = { error("not called") }, pid = 1) shouldBe
                "shop-7f9c4/1"
            defaultHolder(environment = { null }, localHostName = { "laptop.local" }, pid = 42) shouldBe
                "laptop.local/42"
            val noDns: () -> String = { throw UnknownHostException("no dns") }
            defaultHolder(environment = { null }, localHostName = noDns, pid = 7) shouldBe "unknown-host/7"
        }

        "a lease must be positive" {
            shouldThrow<IllegalArgumentException> { LockConfig(lease = Duration.ZERO) }.message shouldBe
                "lease must be positive"
        }

        "the safety margin is at least 0 and below the lease" {
            for (safetyMargin in listOf((-1).seconds, 60.seconds)) {
                shouldThrow<IllegalArgumentException> { LockConfig(safetyMargin = safetyMargin) }.message shouldBe
                    "safetyMargin must be at least 0 and below lease"
            }
            LockConfig(lease = 3.seconds, heartbeat = 1.seconds, safetyMargin = Duration.ZERO).safetyMargin shouldBe
                Duration.ZERO
        }

        "the heartbeat is positive and below lease minus safety margin" {
            for (heartbeat in listOf(Duration.ZERO, 50.seconds)) {
                shouldThrow<IllegalArgumentException> { LockConfig(heartbeat = heartbeat) }.message shouldBe
                    "heartbeat must be positive and below lease minus safetyMargin"
            }
            LockConfig(lease = 3.seconds, heartbeat = 1.seconds, safetyMargin = 1.seconds).heartbeat shouldBe 1.seconds
        }

        "the wait timeout is not negative; zero fails at the first refusal" {
            shouldThrow<IllegalArgumentException> { LockConfig(waitTimeout = (-1).seconds) }.message shouldBe
                "waitTimeout must not be negative"
            LockConfig(waitTimeout = Duration.ZERO).waitTimeout shouldBe Duration.ZERO
        }
    }
}
