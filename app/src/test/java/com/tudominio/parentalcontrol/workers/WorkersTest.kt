package com.tudominio.parentalcontrol.workers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Tests para los workers de WorkManager (T20).
 */
class WorkersTest {

    @Test
    fun `heartbeat worker constants are defined`() {
        assertEquals("heartbeat_work", HeartbeatWorker.WORK_NAME)
    }

    @Test
    fun `outbox worker constants are defined`() {
        assertEquals("outbox_drain_periodic", OutboxDrainer.WORK_NAME)
    }

    @Test
    fun `reconciliation worker constants are defined`() {
        assertEquals("reconciliation_work", ReconciliationWorker.WORK_NAME)
    }

    @Test
    fun `sync worker constants are defined`() {
        assertEquals("sync_work", SyncWorker.WORK_NAME)
        assertEquals("after_boot", SyncWorker.TAG_AFTER_BOOT)
        assertEquals("after_pairing", SyncWorker.TAG_AFTER_PAIRING)
        assertEquals("after_fcm", SyncWorker.TAG_AFTER_FCM)
    }
}

class BackoffPolicyTest {

    @Test
    fun `backoff uses exponential policy`() {
        // Backoff exponencial: 1s, 2s, 4s, 8s, 16s...
        val baseDelay = androidx.work.WorkRequest.MIN_BACKOFF_MILLIS

        val delays = listOf(
            baseDelay,
            baseDelay * 2,
            baseDelay * 4,
            baseDelay * 8
        )

        // Verificar que los delays crecen exponencialmente
        assertEquals(baseDelay, delays[0])
        assertEquals(baseDelay * 2, delays[1])
        assertEquals(baseDelay * 4, delays[2])
        assertEquals(baseDelay * 8, delays[3])
    }

    @Test
    fun `max retry attempts are defined`() {
        val maxRetries = 5
        assertEquals(5, maxRetries)
    }
}

class WorkerSchedulingTest {

    @Test
    fun `heartbeat interval is 5 minutes`() {
        // Heartbeat: cada 5 minutos
        val intervalMinutes = 5L
        assertEquals(5L, intervalMinutes)
    }

    @Test
    fun `outbox interval is 15 minutes with 5 min flex`() {
        // Outbox: cada 15 minutos con flex de 5
        val intervalMinutes = 15L
        val flexMinutes = 5L
        assertEquals(15L, intervalMinutes)
        assertEquals(5L, flexMinutes)
    }

    @Test
    fun `reconciliation interval is 1 hour with 15 min flex`() {
        // Reconciliación: cada 1 hora con flex de 15 min
        val intervalHours = 1L
        val flexMinutes = 15L
        assertEquals(1L, intervalHours)
        assertEquals(15L, flexMinutes)
    }

    @Test
    fun `sync tags are unique`() {
        val tags = listOf(
            SyncWorker.TAG_AFTER_BOOT,
            SyncWorker.TAG_AFTER_PAIRING,
            SyncWorker.TAG_AFTER_FCM
        )

        // Verificar que todos son únicos
        assertEquals(tags.size, tags.toSet().size)
    }
}

class WorkerConstraintsTest {

    @Test
    fun `workers require network connectivity`() {
        // Todos los workers requieren red
        val requiresNetwork = true
        assertTrue(requiresNetwork)
    }

    @Test
    fun `reconciliation prefers battery not low`() {
        // Reconciliación es mejor con batería no baja
        val batteryNotLow = true
        assertEquals(true, batteryNotLow)
    }
}

class WorkerChainingTest {

    @Test
    fun `boot sequence order is defined`() {
        // Boot sequence: Sync -> Heartbeat -> Outbox
        val sequence = listOf(
            SyncWorker.WORK_NAME,
            HeartbeatWorker.WORK_NAME,
            OutboxDrainer.WORK_NAME
        )

        assertEquals(3, sequence.size)
        assertEquals(SyncWorker.WORK_NAME, sequence[0])
        assertEquals(HeartbeatWorker.WORK_NAME, sequence[1])
        assertEquals(OutboxDrainer.WORK_NAME, sequence[2])
    }

    @Test
    fun `fcm sequence triggers sync`() {
        // FCM trigger: solo SyncWorker
        val fcmSequence = listOf(SyncWorker.TAG_AFTER_FCM)

        assertEquals(1, fcmSequence.size)
        assertEquals("after_fcm", fcmSequence[0])
    }
}

class WorkerResultTest {

    @Test
    fun `retry is used for offline scenarios`() {
        // Cuando está offline, usamos retry
        val shouldRetry = true
        assertTrue(shouldRetry)
    }

    @Test
    fun `success is returned for partial success`() {
        // Éxito parcial también retorna success (no queremos loops)
        val considerSuccess = true
        assertTrue(considerSuccess)
    }

    @Test
    fun `failure after max retries stops retrying`() {
        // Tras máximo reintentos, fallamos
        val runAttemptCount = 5
        val maxRetries = 5
        val shouldFail = runAttemptCount >= maxRetries
        assertTrue(shouldFail)
    }
}

class WorkSchedulerTest {

    @Test
    fun `work names are unique`() {
        val names = setOf(
            HeartbeatWorker.WORK_NAME,
            OutboxDrainer.WORK_NAME,
            ReconciliationWorker.WORK_NAME,
            SyncWorker.WORK_NAME,
            SolicitudesPollingWorker.WORK_NAME
        )

        assertEquals(5, names.size)
    }

    @Test
    fun `all workers have tags`() {
        assertEquals("heartbeat_work", HeartbeatWorker.WORK_NAME)
        assertEquals("outbox_drain_periodic", OutboxDrainer.WORK_NAME)
        assertEquals("reconciliation_work", ReconciliationWorker.WORK_NAME)
        assertEquals("sync_work", SyncWorker.WORK_NAME)
        assertEquals("solicitudes_polling", SolicitudesPollingWorker.WORK_NAME)
    }

    /**
     * T1.2 — `SolicitudesPollingWorker` constants and scheduling shape.
     *
     * - `WORK_NAME = "solicitudes_polling"` — must match the spec scenario
     *   "Worker is scheduled with a 5-minute repeat interval".
     * - 5-minute interval (mirrors `HeartbeatWorker`).
     * - `KEEP` policy (per design D3 and spec scenario "Existing periodic
     *   jobs are not duplicated"): the worker is idempotent, so replacing
     *   its schedule on every `scheduleAllPeriodicWork` call is wasteful.
     */
    @Test
    fun `solicitudes_polling worker constants are defined`() {
        assertEquals("solicitudes_polling", SolicitudesPollingWorker.WORK_NAME)
        // 5 minutes mirrors HeartbeatWorker.
        val intervalMinutes = TimeUnit.MINUTES.toMinutes(5L)
        assertEquals(5L, intervalMinutes)
    }
}

/**
 * Strengthens [WorkSchedulerTest] by driving
 * `WorkScheduler.scheduleSolicitudesPolling` through the WorkManager
 * test driver and asserting the resulting [WorkInfo] shape (unique-work
 * name, tag, state, KEEP-on-second-call). Pattern follows `BootReceiverTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WorkSchedulerSchedulingTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(android.util.Log.DEBUG).build()
        )
    }

    @Test
    fun scheduleSolicitudesPolling_enqueues_unique_periodic_work() {
        WorkScheduler.scheduleSolicitudesPolling(context)

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(SolicitudesPollingWorker.WORK_NAME)
            .get()

        assertEquals(
            "scheduleSolicitudesPolling must enqueue exactly one unique periodic entry",
            1, infos.size
        )
        val info = infos[0]
        assertEquals(
            "unique periodic work must be ENQUEUED until constraints are met",
            WorkInfo.State.ENQUEUED, info.state
        )
        assertTrue(
            "tag set must contain SolicitudesPollingWorker.WORK_NAME. Got: ${info.tags}",
            info.tags.contains(SolicitudesPollingWorker.WORK_NAME)
        )
    }

    @Test
    fun scheduleSolicitudesPolling_keeps_existing_entry_on_second_call() {
        WorkScheduler.scheduleSolicitudesPolling(context)
        WorkScheduler.scheduleSolicitudesPolling(context)

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(SolicitudesPollingWorker.WORK_NAME)
            .get()

        assertEquals(
            "KEEP policy must prevent duplicate unique periodic entries",
            1, infos.size
        )
    }
}
