package com.example.shop.docs.locking

import com.example.shop.migrations.orderStatus
import godwit.core.GodwitConfig
import godwit.core.LockConfig
import godwit.test.testGodwit
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.bson.Document
import java.time.Instant
import java.util.Date
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class LockingTest : StringSpec({
    "a start with nothing due reads history and takes no lock" {
        val db = testGodwit()

        db.godwit.migrate(orderStatus).lockWait shouldNotBe null
        db.godwit.migrate(orderStatus).lockWait shouldBe null
    }

    "a start waits for a crashed holder's lease to end, then runs" {
        val db = testGodwit(GodwitConfig(lock = LockConfig(waitTimeout = 2.minutes)))
        db.database.getCollection("godwit-lock", Document::class.java).insertOne(
            Document("_id", "godwit-history")
                .append("owner", "token-of-a-crashed-process")
                .append("holder", "shop-dead1/1")
                .append("runId", "0199a4c1-0d2e-7a11-8c3b-5d6e7f809a1b")
                .append("expiresAt", Date.from(Instant.now().plusSeconds(10)))
        )

        val report = db.godwit.migrate(orderStatus)

        report.lockWait!! shouldBeGreaterThan 9.seconds
        report.ran.map { it.id } shouldBe listOf("004-order-status")
    }
})
