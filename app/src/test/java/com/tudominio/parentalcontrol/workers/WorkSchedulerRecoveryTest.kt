package com.tudominio.parentalcontrol.workers

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WorkSchedulerRecoveryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).build()
        )
    }

    private fun postPairingWork(): List<WorkInfo> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("sync_work_after_pairing")
            .get()

    @Test
    fun recovery_enqueues_post_pairing_work_when_none_exists() {
        assertTrue(postPairingWork().isEmpty())
        WorkScheduler.scheduleSyncAfterPairingRecovery(context)

        val info = postPairingWork().single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertTrue(info.tags.contains(SyncWorker.WORK_NAME))
        assertTrue(info.tags.contains(SyncWorker.TAG_AFTER_PAIRING))
    }

    @Test
    fun recovery_keep_preserves_existing_unfinished_work_id() {
        WorkScheduler.scheduleSyncAfterPairing(context)
        val originalId = postPairingWork().single().id
        WorkScheduler.scheduleSyncAfterPairingRecovery(context)
        val infos = postPairingWork()
        assertEquals(1, infos.size)
        assertEquals(originalId, infos.single().id)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
    }

    @Test
    fun recovery_keep_replaces_terminal_chain_with_fresh_work() {
        WorkScheduler.scheduleSyncAfterPairing(context)
        val originalId = postPairingWork().single().id
        WorkManager.getInstance(context).cancelWorkById(originalId).result.get()
        assertEquals(WorkInfo.State.CANCELLED, postPairingWork().single().state)
        WorkScheduler.scheduleSyncAfterPairingRecovery(context)
        val infos = postPairingWork()
        assertEquals(1, infos.size)
        assertNotEquals(originalId, infos.single().id)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
    }
}
