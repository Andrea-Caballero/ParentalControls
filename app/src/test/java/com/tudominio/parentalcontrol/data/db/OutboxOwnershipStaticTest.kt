package com.tudominio.parentalcontrol.data.db

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxOwnershipStaticTest {
    private val dao = File("src/main/java/com/tudominio/parentalcontrol/data/db/OutboxDao.kt")
        .readText()

    @Test
    fun `claim mutations require exact in-flight token ownership`() {
        assertTrue(dao.contains("claim_token = :claimToken"))
        assertTrue(dao.contains("in_flight_at = :now, claim_token = :claimToken"))
        assertTrue(dao.contains("in_flight = 1 AND claim_token = :claimToken"))
        assertTrue(dao.contains("claim_token = NULL"))
        assertTrue(dao.contains("releaseClaims"))
    }

    @Test
    fun `stale and legacy null-token rows are cleared by stale sweep`() {
        assertTrue(dao.contains("in_flight_at < :olderThan"))
        assertTrue(dao.contains("in_flight = 1"))
        assertTrue(dao.contains("in_flight_at = NULL, claim_token = NULL"))
    }

    @Test
    fun `both drainers use ownership tokens and cancellation cleanup`() {
        val worker = File("src/main/java/com/tudominio/parentalcontrol/workers/OutboxDrainer.kt").readText()
        val direct = File("src/main/java/com/tudominio/parentalcontrol/sync/SyncManager.kt").readText()
        assertTrue(worker.contains("NonCancellable"))
        assertTrue(worker.contains("claimToken"))
        assertTrue(direct.contains("UUID.randomUUID().toString()"))
        assertTrue(direct.contains("releaseClaims"))
        assertTrue(direct.contains("deleteItemFromClaim"))
    }
}
