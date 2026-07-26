package com.tudominio.parentalcontrol.receiver

import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.tudominio.parentalcontrol.auth.AuthResult
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.auth.StoredSession
import com.tudominio.parentalcontrol.workers.HeartbeatWorker
import com.tudominio.parentalcontrol.workers.OutboxDrainer
import com.tudominio.parentalcontrol.workers.ReconciliationWorker
import com.tudominio.parentalcontrol.workers.SyncWorker
import com.tudominio.parentalcontrol.workers.WorkScheduler
import com.tudominio.parentalcontrol.workers.WorkerInitializer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [BootReceiver].
 *
 * Focus of `fix-boot-workers-respect-session-gate`: every WorkManager
 * work scheduled from [BootReceiver.onBootCompleted] MUST be gated on
 * a restored session. With a non-null session the boot path enqueues
 * the periodic OutboxDrainer re-arm and the after-boot sync chain;
 * with a null session it cancels ONLY the persisted after-boot sync
 * chain (`sync_work_after_boot`) — NOT the periodic OutboxDrainer /
 * ReconciliationWorker (those are configured with `KEEP` and MUST
 * survive across reboots per spec) and NOT the after-pairing schedule
 * (`sync_work_after_pairing`, distinct unique-work name).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BootReceiverTest {

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    /**
     * Pins the invariant from
     * `openspec/changes/feature-boot-restore-session-before-sync`:
     * when the boot path finds a valid [StoredSession] on disk, the
     * `sync_work_after_boot` unique-work chain must be enqueued. The
     * [WorkerInitializer] branch is what gates this; the new
     * `onBootCompleted_with_session_enqueues_outbox_drainer_and_after_boot_chain`
     * test below additionally pins the OutboxDrainer re-arm.
     */
    @Test
    fun onBootCompleted_with_restored_session_enqueues_sync_after_boot() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val receiver = BootReceiver()
        val mockAuthManager: DeviceAuthManager = mockk()
        val storedSession = StoredSession(
            accessToken = "test-access-token",
            refreshToken = "test-refresh-token",
            expiresAt = 0L,
            deviceId = "test-device",
            userId = "test-user"
        )

        mockkObject(DeviceAuthManager.Companion)
        every { DeviceAuthManager.getInstance(any()) } returns mockAuthManager
        every { mockAuthManager.restoreSession() } returns storedSession
        // PR verification 2026-07-14: stub `authenticateOrCreate()` so the
        // boot coroutine can complete. `BootReceiver.onBootCompleted` calls
        // it after `restoreSession` returns non-null (see BootReceiver.kt:81)
        // to rehydrate the access token. Without this stub the unstubbed
        // suspend call throws inside `GlobalScope.launch`, killing the
        // coroutine before `WorkerInitializer.initialize` and the
        // `sync_work_after_boot` chain get enqueued — which is exactly
        // the RED signal these tests pin.
        coEvery { mockAuthManager.authenticateOrCreate() } returns AuthResult.Success(
            deviceId = storedSession.deviceId ?: "test-device",
            accessToken = storedSession.accessToken,
            refreshToken = storedSession.refreshToken,
            expiresAt = storedSession.expiresAt
        )

        val bootIntent = android.content.Intent(BootReceiver.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, bootIntent)

        // WorkerInitializer.initialize(...) is invoked from inside a
        // GlobalScope coroutine; wait for the coroutine to enqueue work
        // before querying WorkManager.
        Thread.sleep(1000L)

        val workManager = WorkManager.getInstance(context)
        val infos = workManager
            .getWorkInfosForUniqueWork("${SyncWorker.WORK_NAME}_after_boot")
            .get()

        // WorkScheduler.scheduleSyncAfterBoot enqueues a 3-step chain
        // (SyncWorker -> HeartbeatWorker -> OutboxDrainer) under the
        // unique-work name "sync_work_after_boot"; getWorkInfosForUniqueWork
        // returns one WorkInfo per chain step.
        assertEquals(
            "Expected the sync_work_after_boot chain (3 steps) to be enqueued when restoreSession returns a session",
            3,
            infos.size
        )

        // Pin the SET of workers in the chain (not the order, which
        // WorkManager doesn't guarantee on a `getWorkInfosForUniqueWork`
        // query). The public `WorkInfo` API doesn't expose the worker
        // class, so we identify each step by its unique tag (each
        // `WorkScheduler.scheduleSyncAfterBoot` step adds its own
        // `WORK_NAME` tag). Asserting the set catches the historical
        // drift where `ReconciliationWorker.WORK_NAME` was substituted
        // for `HeartbeatWorker.WORK_NAME` (the design.md diagram and
        // code always said Heartbeat; only the spec text wrote
        // Reconciliation). If anyone swaps the chain contents again,
        // this test fails with a clear diff showing which worker was
        // added or removed.
        val infoByTag = infos.flatMap { info -> info.tags.map { it to info } }.toMap()
        assertNotNull(
            "sync_work_after_boot chain must contain a SyncWorker step (tag=${SyncWorker.TAG_AFTER_BOOT}). " +
                "Got tags: ${infos.flatMap { it.tags }}",
            infoByTag[SyncWorker.TAG_AFTER_BOOT]
        )
        assertNotNull(
            "sync_work_after_boot chain must contain a HeartbeatWorker step (tag=${HeartbeatWorker.WORK_NAME}). " +
                "Got tags: ${infos.flatMap { it.tags }}",
            infoByTag[HeartbeatWorker.WORK_NAME]
        )
        assertNotNull(
            "sync_work_after_boot chain must contain an OutboxDrainer step (tag=${OutboxDrainer.WORK_NAME}). " +
                "Got tags: ${infos.flatMap { it.tags }}",
            infoByTag[OutboxDrainer.WORK_NAME]
        )
    }

    /**
     * Pins the inverse invariant: when restoreSession returns null,
     * no sync chain is enqueued and the receiver logs a warning. This
     * prevents the pre-PR#7 failure mode where SyncWorker would
     * short-circuit to Offline against a DISCONNECTED SupabaseClient.
     */
    @Test
    fun onBootCompleted_with_null_restored_session_skips_sync_and_logs_warning() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val receiver = BootReceiver()
        val mockAuthManager: DeviceAuthManager = mockk()

        mockkObject(DeviceAuthManager.Companion)
        every { DeviceAuthManager.getInstance(any()) } returns mockAuthManager
        every { mockAuthManager.restoreSession() } returns null

        val bootIntent = android.content.Intent(BootReceiver.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, bootIntent)

        // Wait for the GlobalScope coroutine to finish (it either calls
        // WorkerInitializer.initialize or Log.w) before asserting.
        Thread.sleep(1000L)

        val workManager = WorkManager.getInstance(context)
        val infos = workManager
            .getWorkInfosForUniqueWork("${SyncWorker.WORK_NAME}_after_boot")
            .get()

        assertEquals(
            "Expected NO sync_work_after_boot work to be enqueued when restoreSession returns null",
            0,
            infos.size
        )

        val warnings = ShadowLog.getLogs()
            .filter { it.tag == "BootReceiver" && it.type == android.util.Log.WARN }
        assertTrue(
            "Expected a Log.w from BootReceiver containing 'no stored session' but got: $warnings",
            warnings.any { it.msg.contains("no stored session") }
        )
    }

    /**
     * Pins the `boot-worker-lifecycle` spec scenario "Session present
     * schedules the sync chain" plus the `outbox-drain` delta scenario
     * "Session restored at boot re-arms the chain".
     *
     * GIVEN a session is restored at boot
     * WHEN BootReceiver.onBootCompleted runs
     * THEN WorkScheduler.scheduleOutboxDrainer MUST be invoked (so the
     *      periodic drain survives the reboot)
     * AND WorkerInitializer.initialize MUST be invoked (so the
     *      sync_work_after_boot chain runs)
     * AND no WorkScheduler.cancelWork calls SHALL be issued.
     *
     * The `verifyOrder` assertion pins the gate contract: scheduleOutboxDrainer
     * MUST be invoked AFTER restoreSession returns a non-null session.
     * Before the fix, scheduleOutboxDrainer was called UNCONDITIONALLY (before
     * the session check), so this verifyOrder assertion fails — RED.
     */
    @Test
    fun onBootCompleted_with_session_enqueues_outbox_drainer_and_after_boot_chain() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val receiver = BootReceiver()
        val mockAuthManager: DeviceAuthManager = mockk()
        val storedSession = StoredSession(
            accessToken = "test-access-token",
            refreshToken = "test-refresh-token",
            expiresAt = 0L,
            deviceId = "test-device",
            userId = "test-user"
        )

        mockkObject(DeviceAuthManager.Companion)
        every { DeviceAuthManager.getInstance(any()) } returns mockAuthManager
        every { mockAuthManager.restoreSession() } returns storedSession
        // Same token-rehydrate stub as the sibling test above: see the
        // BootReceiver.kt:81 comment in `onBootCompleted_with_restored_session_enqueues_sync_after_boot`.
        coEvery { mockAuthManager.authenticateOrCreate() } returns AuthResult.Success(
            deviceId = storedSession.deviceId ?: "test-device",
            accessToken = storedSession.accessToken,
            refreshToken = storedSession.refreshToken,
            expiresAt = storedSession.expiresAt
        )

        // Mock WorkScheduler and WorkerInitializer so the test asserts the
        // exact call surface from BootReceiver, not the WorkManager database
        // contents (already covered by the pre-existing tests). Stubbing
        // WorkerInitializer.initialize as a no-op prevents its body from
        // re-calling WorkScheduler.scheduleOutboxDrainer via
        // scheduleAllPeriodicWork, so the `verify(exactly = 1)` below
        // asserts the direct BootReceiver call only.
        mockkObject(WorkScheduler)
        mockkObject(WorkerInitializer)
        every { WorkerInitializer.initialize(any(), any()) } returns Unit

        val bootIntent = android.content.Intent(BootReceiver.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, bootIntent)

        // WorkerInitializer.initialize(...) and the gated scheduleOutboxDrainer
        // are dispatched from inside a GlobalScope coroutine; wait for it to
        // finish before verifying the recorded call order.
        Thread.sleep(1000L)

        // 1. OutboxDrainer re-arm MUST be enqueued exactly once.
        verify(exactly = 1) { WorkScheduler.scheduleOutboxDrainer(context) }
        // 2. After-boot sync chain MUST be initialized exactly once with
        //    isAfterBoot=true (which inside WorkerInitializer schedules the
        //    sync_work_after_boot unique-work chain).
        verify(exactly = 1) { WorkerInitializer.initialize(context, true) }
        // 3. Gate contract: scheduleOutboxDrainer MUST be invoked AFTER
        //    restoreSession returns a non-null session. Before the fix,
        //    scheduleOutboxDrainer ran UNCONDITIONALLY (line 57 in the
        //    receiver), so this verifyOrder assertion fails.
        verifyOrder {
            mockAuthManager.restoreSession()
            WorkScheduler.scheduleOutboxDrainer(context)
            WorkerInitializer.initialize(context, true)
        }
        // 4. No cancelWork calls SHALL be issued when a session is restored.
        verify(exactly = 0) { WorkScheduler.cancelWork(any(), any()) }
    }

    /**
     * Pins the `boot-worker-lifecycle` spec scenario "No session cancels
     * persisted works and skips scheduling" plus the `outbox-drain` delta
     * scenario "Cancellation at boot with no session".
     *
     * GIVEN the device has just emitted BOOT_COMPLETED
     * AND DeviceAuthManager.restoreSession() returns null
     * WHEN BootReceiver.onBootCompleted runs
     * THEN WorkScheduler.cancelWork MUST be called EXACTLY ONCE, with the
     *      unique-work name `${SyncWorker.WORK_NAME}_after_boot`
     *      (the post-boot sync chain — its only step on the boot path is
     *      one-shot; the periodic OutboxDrainer / ReconciliationWorker
     *      AND the after-pairing schedule MUST be left alone per spec
     *      scenarios "Periodic workers stay scheduled across reboots" and
     *      "After-pairing schedule survives a reboot").
     *
     * Before the fix, the else branch cancelled three names — including the
     * two periodic unique-work names that the spec says MUST be preserved —
     * so the `verify(exactly = 0) { WorkScheduler.cancelWork(context, any()) }`
     * negative assertion below fails — RED.
     */
    @Test
    fun onBootCompleted_without_session_cancels_only_after_boot_chain() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val receiver = BootReceiver()
        val mockAuthManager: DeviceAuthManager = mockk()

        mockkObject(DeviceAuthManager.Companion)
        every { DeviceAuthManager.getInstance(any()) } returns mockAuthManager
        every { mockAuthManager.restoreSession() } returns null

        mockkObject(WorkScheduler)

        val bootIntent = android.content.Intent(BootReceiver.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, bootIntent)

        // The else branch runs inside a GlobalScope coroutine; wait for it
        // before verifying.
        Thread.sleep(1000L)

        // 1. The boot path MUST cancel the after-boot sync chain (its
        //    unique-work name is "${SyncWorker.WORK_NAME}_after_boot").
        verify(exactly = 1) {
            WorkScheduler.cancelWork(context, "${SyncWorker.WORK_NAME}_after_boot")
        }
        // 2. The boot path MUST NOT cancel the periodic reconciliation
        //    schedule (its unique-work name is ReconciliationWorker.WORK_NAME).
        verify(exactly = 0) {
            WorkScheduler.cancelWork(context, ReconciliationWorker.WORK_NAME)
        }
        // 3. The boot path MUST NOT cancel the periodic outbox drainer
        //    schedule (its unique-work name is OutboxDrainer.WORK_NAME).
        verify(exactly = 0) {
            WorkScheduler.cancelWork(context, OutboxDrainer.WORK_NAME)
        }
        // 4. Total cancel calls bounded: exactly 1, no extra cancels.
        verify(exactly = 1) { WorkScheduler.cancelWork(context, any()) }
    }

    /**
     * Pins the `boot-worker-lifecycle` spec scenario "After-pairing schedule
     * survives a reboot".
     *
     * GIVEN the user paired the device before the last reboot
     *  AND `scheduleSyncAfterPairing` enqueued `${SyncWorker.WORK_NAME}_after_pairing`
     *  AND DeviceAuthManager.restoreSession() returns null at this boot
     * WHEN BootReceiver.onBootCompleted runs
     * THEN `${SyncWorker.WORK_NAME}_after_pairing` SHALL remain scheduled
     *      in the WorkManager database
     * AND WorkScheduler.cancelWork SHALL NOT be called for the
     *      `${SyncWorker.WORK_NAME}_after_pairing` name.
     *
     * BootReceiver cancels the after-boot chain by name. The after-pairing
     * chain uses a DISTINCT unique-work name (`${SyncWorker.WORK_NAME}_after_pairing`)
     * and MUST be left alone. WorkScheduler is mocked so the cancel call
     * surface can be asserted at the API level (the underlying
     * WorkManager.cancelUniqueWork would otherwise be a no-op on this name
     * since the production cancel list never targets it).
     *
     * Note on RED signal: this test does NOT fail under the current buggy
     * code — the bug cancels `sync_work_after_boot`, `reconciliation_work`,
     * and `outbox_drain_periodic`, none of which targets
     * `${SyncWorker.WORK_NAME}_after_pairing`. The test serves as a
     * regression guard: a future change that adds `after_pairing` to the
     * cancel list would be caught here.
     */
    @Test
    fun onBootCompleted_without_session_preserves_after_pairing_work() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val workManager = WorkManager.getInstance(context)

        // Pre-condition: simulate the after-pairing schedule that survived
        // the reboot by enqueuing the after_pairing unique work.
        val afterPairingRequest = OneTimeWorkRequestBuilder<SyncWorker>().build()
        workManager.enqueueUniqueWork(
            "${SyncWorker.WORK_NAME}_after_pairing",
            ExistingWorkPolicy.REPLACE,
            afterPairingRequest
        )

        // Sanity check: the pre-condition is satisfied — the work is in the DB.
        val preInfos = workManager
            .getWorkInfosForUniqueWork("${SyncWorker.WORK_NAME}_after_pairing")
            .get()
        assertEquals(
            "Test pre-condition: after_pairing work should be enqueued before boot",
            1,
            preInfos.size
        )

        // Trigger boot with no session. WorkScheduler is mocked so we can
        // verify the call surface at the API level.
        mockkObject(WorkScheduler)
        val receiver = BootReceiver()
        val mockAuthManager: DeviceAuthManager = mockk()
        mockkObject(DeviceAuthManager.Companion)
        every { DeviceAuthManager.getInstance(any()) } returns mockAuthManager
        every { mockAuthManager.restoreSession() } returns null

        val bootIntent = android.content.Intent(BootReceiver.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, bootIntent)

        // Wait for the GlobalScope coroutine in the else branch.
        Thread.sleep(1000L)

        // 1. WorkScheduler.cancelWork MUST NOT be called for the
        //    after_pairing name (it is a distinct schedule that the boot
        //    cancel MUST leave alone).
        verify(exactly = 0) {
            WorkScheduler.cancelWork(context, "${SyncWorker.WORK_NAME}_after_pairing")
        }
        // 2. The after_pairing work MUST still be in the WorkManager DB.
        val postInfos = workManager
            .getWorkInfosForUniqueWork("${SyncWorker.WORK_NAME}_after_pairing")
            .get()
        assertEquals(
            "Expected after_pairing schedule to survive boot without session",
            1,
            postInfos.size
        )
    }

    /**
     * Pins the `boot-worker-lifecycle` spec scenario "Periodic workers stay
     * scheduled across reboots" and the `outbox-drain` delta scenario
     * "Periodic OutboxDrainer schedule is preserved across reboot".
     *
     * GIVEN `OutboxDrainer` is scheduled as a periodic worker with
     *      ExistingPeriodicWorkPolicy.KEEP (its WORK_NAME is the unique
     *      periodic entry name `outbox_drain_periodic`)
     *  AND DeviceAuthManager.restoreSession() returns null at this boot
     * WHEN BootReceiver.onBootCompleted runs
     * THEN WorkScheduler.cancelWork SHALL NOT be called for
     *      `OutboxDrainer.WORK_NAME`
     * AND the periodic `OutboxDrainer` entry SHALL remain in the
     *      WorkManager database.
     *
     * WorkScheduler is mocked so the cancel call surface can be asserted
     * at the API level (asserting against the real WorkManager DB is
     * unreliable in this test harness: the periodic OutboxDrainer worker
     * fails on enqueue because Hilt is not initialised in unit tests,
     * so the WorkInfo state transitions to FAILED, which is a terminal
     * state — `cancelUniqueWork` is a no-op on terminal states and the
     * state never transitions to CANCELLED, so a real-DB cancel assertion
     * would not RED-fail under the buggy code).
     *
     * Under the current buggy implementation the else branch cancels
     * `outbox_drain_periodic`, so the `verify(exactly = 0)` below fails — RED.
     */
    @Test
    fun onBootCompleted_without_session_preserves_periodic_outbox_drainer() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val workManager = WorkManager.getInstance(context)

        // Pre-condition: enqueue the periodic OutboxDrainer via the SAME
        // unique-periodic name and policy used by WorkScheduler.scheduleOutboxDrainer.
        val periodicRequest = PeriodicWorkRequestBuilder<OutboxDrainer>(
            15, TimeUnit.MINUTES
        ).build()
        workManager.enqueueUniquePeriodicWork(
            OutboxDrainer.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest
        )

        // Sanity check: the pre-condition is satisfied — the entry is in the DB.
        val preInfos = workManager
            .getWorkInfosForUniqueWork(OutboxDrainer.WORK_NAME)
            .get()
        assertEquals(
            "Test pre-condition: OutboxDrainer periodic should be enqueued before boot",
            1,
            preInfos.size
        )

        // Trigger boot with no session. WorkScheduler is mocked so we can
        // verify the cancel call surface at the API level.
        mockkObject(WorkScheduler)
        val receiver = BootReceiver()
        val mockAuthManager: DeviceAuthManager = mockk()
        mockkObject(DeviceAuthManager.Companion)
        every { DeviceAuthManager.getInstance(any()) } returns mockAuthManager
        every { mockAuthManager.restoreSession() } returns null

        val bootIntent = android.content.Intent(BootReceiver.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, bootIntent)

        // Wait for the GlobalScope coroutine in the else branch.
        Thread.sleep(1000L)

        // 1. WorkScheduler.cancelWork MUST NOT be called for the periodic
        //    OutboxDrainer name (the periodic schedule MUST survive across
        //    reboots per spec).
        verify(exactly = 0) {
            WorkScheduler.cancelWork(context, OutboxDrainer.WORK_NAME)
        }
        // 2. The periodic OutboxDrainer entry MUST still be in the
        //    WorkManager DB.
        val postInfos = workManager
            .getWorkInfosForUniqueWork(OutboxDrainer.WORK_NAME)
            .get()
        assertEquals(
            "Expected periodic OutboxDrainer to survive boot without session",
            1,
            postInfos.size
        )
    }

    /**
     * Pins the `boot-worker-lifecycle` spec scenario "Periodic workers stay
     * scheduled across reboots" applied to the periodic ReconciliationWorker.
     *
     * GIVEN `ReconciliationWorker` is scheduled as a periodic worker with
     *      ExistingPeriodicWorkPolicy.KEEP (its WORK_NAME is the unique
     *      periodic entry name `reconciliation_work`)
     *  AND DeviceAuthManager.restoreSession() returns null at this boot
     * WHEN BootReceiver.onBootCompleted runs
     * THEN WorkScheduler.cancelWork SHALL NOT be called for
     *      `ReconciliationWorker.WORK_NAME`
     * AND the periodic `ReconciliationWorker` entry SHALL remain in the
     *      WorkManager database.
     *
     * WorkScheduler is mocked so the cancel call surface can be asserted
     * at the API level. Under the current buggy implementation the else
     * branch cancels `reconciliation_work`, so the `verify(exactly = 0)`
     * below fails — RED.
     */
    @Test
    fun onBootCompleted_without_session_preserves_periodic_reconciliation() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val workManager = WorkManager.getInstance(context)

        // Pre-condition: enqueue the periodic ReconciliationWorker via the
        // SAME unique-periodic name and policy used by
        // WorkScheduler.scheduleReconciliation.
        val periodicRequest = PeriodicWorkRequestBuilder<ReconciliationWorker>(
            15, TimeUnit.MINUTES
        ).build()
        workManager.enqueueUniquePeriodicWork(
            ReconciliationWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest
        )

        // Sanity check: the pre-condition is satisfied — the entry is in the DB.
        val preInfos = workManager
            .getWorkInfosForUniqueWork(ReconciliationWorker.WORK_NAME)
            .get()
        assertEquals(
            "Test pre-condition: ReconciliationWorker periodic should be enqueued before boot",
            1,
            preInfos.size
        )

        // Trigger boot with no session. WorkScheduler is mocked so we can
        // verify the cancel call surface at the API level.
        mockkObject(WorkScheduler)
        val receiver = BootReceiver()
        val mockAuthManager: DeviceAuthManager = mockk()
        mockkObject(DeviceAuthManager.Companion)
        every { DeviceAuthManager.getInstance(any()) } returns mockAuthManager
        every { mockAuthManager.restoreSession() } returns null

        val bootIntent = android.content.Intent(BootReceiver.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, bootIntent)

        // Wait for the GlobalScope coroutine in the else branch.
        Thread.sleep(1000L)

        // 1. WorkScheduler.cancelWork MUST NOT be called for the periodic
        //    ReconciliationWorker name.
        verify(exactly = 0) {
            WorkScheduler.cancelWork(context, ReconciliationWorker.WORK_NAME)
        }
        // 2. The periodic ReconciliationWorker entry MUST still be in the
        //    WorkManager DB.
        val postInfos = workManager
            .getWorkInfosForUniqueWork(ReconciliationWorker.WORK_NAME)
            .get()
        assertEquals(
            "Expected periodic ReconciliationWorker to survive boot without session",
            1,
            postInfos.size
        )
    }
}
