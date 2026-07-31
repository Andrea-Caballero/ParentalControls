package com.tudominio.parentalcontrol.workers

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.tudominio.parentalcontrol.BuildConfig
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.data.model.OutboxEntity
import com.tudominio.parentalcontrol.outbox.OutboxManager
import com.tudominio.parentalcontrol.sync.OutboxSendResult
import com.tudominio.parentalcontrol.sync.SyncManager
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Unit tests for [OutboxDrainer].
 *
 * The drainer is the new periodic worker that pulls pending `outbox` rows,
 * hands them to [SyncManager.sendOutboxItem], and marks the row state
 * according to the result (processed / retries++ / processed-on-permanent).
 *
 * Mocks: [SyncManager] (its sealed-result `sendOutboxItem`) is stubbed via
 * mockk so each branch is driven deterministically. A real in-memory Room
 * database backs the outbox table so the DAO queries the worker triggers
 * see realistic data.
 *
 * The `SyncManager.httpClient` mock that used to live here is gone:
 * `httpClient` is now Hilt-injected (`@SupabaseClient` binding) and the
 * OutboxDrainer no longer reads it directly — `sendOutboxItem` does. The
 * old `drainOutbox_throws_retryable_failure_when_http_client_is_null`
 * test was deleted alongside the null-check it pinned.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OutboxDrainerTest {

    private lateinit var context: Context
    private lateinit var db: ParentalDatabase
    private lateinit var outboxManager: OutboxManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Fresh in-memory DB per test, so the rows from one test never leak
        // into the next. PR 4 of `align-with-guia-fedora44` removed the
        // `ParentalDatabase.INSTANCE` singleton, so we now construct
        // [OutboxManager] directly with the in-memory DB — no reflection
        // and no companion `getInstance` call.
        db = Room.inMemoryDatabaseBuilder(context, ParentalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        outboxManager = OutboxManager(context, db)
    }

    @After
    fun teardown() {
        runCatching { db.close() }
    }

    private fun newWorker(syncManager: SyncManager): OutboxDrainer {
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker? {
                return OutboxDrainer(appContext, workerParameters, outboxManager, syncManager)
            }
        }
        return TestListenableWorkerBuilder
            .from(context, OutboxDrainer::class.java)
            .setWorkerFactory(factory)
            .build() as OutboxDrainer
    }

    private fun insertOutboxItem(
        tipo: String = "TIME_REQUEST",
        payload: String = "{}",
        retries: Int = 0
    ): OutboxEntity {
        val item = OutboxEntity(
            id = UUID.randomUUID(),
            tipo = tipo,
            payload_json = payload,
            dedup_key = null,
            retries = retries,
            created_at = "2026-06-04T12:00:00Z",
            server_date = "2026-06-04"
        )
        runBlocking { db.outboxDao().insertOutboxItem(item) }
        return item
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    fun scheduleOneTimeOutboxDrain_gatesNetworkConstraintOnSharedMock() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.DEBUG)
                .build()
        )

        WorkScheduler.scheduleOneTimeOutboxDrain(context)

        val workInfo = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(OutboxDrainer.WORK_NAME_ONESHOT)
            .get()
            .single()
        val expectedNetworkType =
            if (BuildConfig.USE_SHARED_MOCK) {
                NetworkType.NOT_REQUIRED
            } else {
                NetworkType.CONNECTED
            }

        assertEquals(expectedNetworkType, workInfo.constraints.requiredNetworkType)
    }

    @Test
    fun drainOutbox_marks_items_as_processed_on_success() = runBlocking {
        insertOutboxItem()
        val syncManager: SyncManager = mockk(relaxed = false)
        coEvery { syncManager.sendOutboxItem(any()) } returns OutboxSendResult.Success

        val worker = newWorker(syncManager)
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        // Item is no longer pending (processed = 1). getPendingItems filters processed = 0.
        val updated = db.outboxDao().getPendingItems(10, 50)
        assertEquals(0, updated.size)
    }

    @Test
    fun drainOutbox_increments_retries_on_retryable_failure() = runBlocking {
        insertOutboxItem(retries = 0)
        val syncManager: SyncManager = mockk(relaxed = false)
        coEvery { syncManager.sendOutboxItem(any()) } returns
            OutboxSendResult.RetryableFailure(IllegalStateException("network down"))

        val worker = newWorker(syncManager)
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        val pending = db.outboxDao().getPendingItems(10, 50)
        assertEquals(1, pending.size)
        assertEquals(1, pending[0].retries)
        assertFalse("Item should not be marked processed on retryable failure", pending[0].processed)
    }

    @Test
    fun drainOutbox_marks_items_as_processed_on_permanent_failure() = runBlocking {
        insertOutboxItem()
        val syncManager: SyncManager = mockk(relaxed = false)
        coEvery { syncManager.sendOutboxItem(any()) } returns
            OutboxSendResult.PermanentFailure(statusCode = 400)

        val worker = newWorker(syncManager)
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val pending = db.outboxDao().getPendingItems(10, 50)
        assertEquals(0, pending.size)
    }

    /**
     * Goal 1: the worker MUST use the claim/in-flight mechanism so a
     * second concurrent drainer (or a re-run after a crash that
     * happened mid-iteration) does not re-send rows the first run is
     * still in the middle of sending. We prove the worker does the
     * claim by:
     *
     *  1. Inserting 2 rows.
     *  2. Stubbing `sendOutboxItem` to BLOCK on a deferred
     *     completion for the first row only. The second row returns
     *     `Success` immediately.
     *  3. Asserting that after the worker's first iteration the
     *     first row is `in_flight = 1` (still being sent).
     *  4. Completing the deferred; the worker finalizes the first
     *     row and returns success.
     */
    @Test
    fun drainOutbox_uses_claim_mechanism_to_hold_rows_during_send() = runBlocking {
        val held = insertOutboxItem()
        val immediate = insertOutboxItem()
        val syncManager: SyncManager = mockk(relaxed = false)
        val firstComplete = CompletableDeferred<Unit>()

        coEvery { syncManager.sendOutboxItem(match { it.id == held.id }) } coAnswers {
            firstComplete.await()
            OutboxSendResult.Success
        }
        coEvery { syncManager.sendOutboxItem(match { it.id == immediate.id }) } returns
            OutboxSendResult.Success

        val worker = newWorker(syncManager)

        coroutineScope {
            val workerJob = async(Dispatchers.IO) {
                worker.doWork()
            }

            // Give the worker time to start, claim both rows, and
            // reach the blocked send on `held`. `immediate` is
            // processed first (older created_at), so it should
            // already be processed and `held` should be in_flight=1.
            withTimeout(5_000) {
                while (true) {
                    val rows = db.outboxDao().selectClaimableItems(10, 10)
                    if (rows.isEmpty() && !firstComplete.isCompleted) {
                        // Both rows were claimed; immediate is
                        // already done, held is blocked on the
                        // deferred.
                        break
                    }
                    delay(20)
                }
            }

            val pending = db.outboxDao().getPendingItems(10, 50)
            assertEquals(
                "while held is mid-send, only the already-processed row " +
                    "is invisible to the legacy query — held is held by the claim",
                0,
                pending.size
            )
            assertTrue(
                "a concurrent drainer must NOT see `held` while the worker is sending it",
                db.outboxDao().selectClaimableItems(10, 10).none { it.id == held.id }
            )

            firstComplete.complete(Unit)
            val result = withTimeout(5_000) { workerJob.await() }
            assertEquals(ListenableWorker.Result.success(), result)
        }
    }
}
