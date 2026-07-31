package com.tudominio.parentalcontrol.workers

import android.content.Context
import android.util.Log
import androidx.work.*
import com.tudominio.parentalcontrol.BuildConfig
import java.util.concurrent.TimeUnit

object WorkScheduler {

    private const val TAG = "WorkScheduler"

    /**
     * Cadence for the shared-mock dev fast-poll one-shot. WorkManager
     * refuses `PeriodicWorkRequest` intervals below 15 minutes, so the
     * fast-poll chain is implemented as a `OneTimeWorkRequest` that
     * re-arms itself via `enqueueUniqueWork(REPLACE)` after every
     * successful run. 10 seconds gives the parent dashboard a fresh
     * snapshot well within the "10-30s after Pedir tiempo" target the
     * dev experience requires.
     */
    private const val FAST_POLL_INTERVAL_SECONDS = 10L
    private const val POST_PAIRING_UNIQUE_WORK_NAME = "sync_work_after_pairing"

    fun scheduleAllPeriodicWork(context: Context) {
        Log.d(TAG, "Programando todos los workers periódicos")

        scheduleHeartbeat(context)
        scheduleOutboxDrainer(context)
        scheduleReconciliation(context)
        // Spec scenario "scheduleAllPeriodicWork enqueues all four periodic
        // workers" — SolicitudesPollingWorker runs alongside the existing
        // three so the Solicitudes tab stays fresh even when the parent
        // never opens it. See `fix-parent-solicitudes-auto-poll`.
        scheduleSolicitudesPolling(context)

        Log.d(TAG, "Todos los workers periódicos programados (4)")
    }

    fun scheduleHeartbeat(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<HeartbeatWorker>(
            5, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(HeartbeatWorker.WORK_NAME)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                HeartbeatWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )

        Log.d(TAG, "Heartbeat programado")
    }

    fun scheduleOutboxDrainer(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<OutboxDrainer>(
            15, TimeUnit.MINUTES,
            5, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(OutboxDrainer.WORK_TAG)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                OutboxDrainer.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )

        Log.d(TAG, "Outbox drainer programado")
    }

    /**
     * Fires an immediate one-shot drain of the outbox. Used after enqueuing a
     * row in places where we don't want to wait for the 15-minute periodic
     * tick — e.g., when the child taps "Enviar solicitud" on
     * `ExtraTimeScreen` and we want the parent to see the request within
     * seconds, not minutes.
     *
     * `ExistingWorkPolicy.REPLACE` collapses concurrent taps into a single
     * drain — the worker re-reads the outbox table on each run, so coalescing
     * is safe and saves WorkManager scheduling overhead.
     *
     * Shared-mock dev (`USE_SHARED_MOCK=true`) bypasses the network
     * constraint because the shared server is reachable through its configured
     * mock URL. This gate intentionally does not inspect `USE_MOCK_SUPABASE`:
     * the shared server and the in-process mock engine are independent modes.
     */
    fun scheduleOneTimeOutboxDrain(context: Context) {
        val requiredNetworkType =
            if (BuildConfig.USE_SHARED_MOCK) {
                Log.d(TAG, "Shared-mock one-time outbox drain bypassing network constraint")
                NetworkType.NOT_REQUIRED
            } else {
                NetworkType.CONNECTED
            }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(requiredNetworkType)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<OutboxDrainer>()
            .setConstraints(constraints)
            .addTag(OutboxDrainer.WORK_NAME)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                OutboxDrainer.WORK_NAME_ONESHOT,
                ExistingWorkPolicy.REPLACE,
                workRequest,
            )

        Log.d(TAG, "One-time outbox drainer programado")
    }

    fun scheduleReconciliation(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<ReconciliationWorker>(
            1, TimeUnit.HOURS,
            15, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(ReconciliationWorker.WORK_NAME)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                ReconciliationWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )

        Log.d(TAG, "Reconciliación programada")
    }

    /**
     * Schedules [SolicitudesPollingWorker] on a 5-minute cadence. Mirrors
     * [scheduleHeartbeat] but uses `ExistingPeriodicWorkPolicy.KEEP` per
     * design D3 (the worker is idempotent — replacing its schedule on every
     * `scheduleAllPeriodicWork` call would be wasteful).
     *
     * Under `BuildConfig.USE_SHARED_MOCK` (the dev build that points at a
     * shared local server), an additional `OneTimeWorkRequest` is enqueued
     * with `ExistingWorkPolicy.REPLACE` at [FAST_POLL_INTERVAL_SECONDS]
     * cadence. The gate intentionally does not inspect `USE_MOCK_SUPABASE`:
     * the shared server and the in-process mock engine are independent modes.
     * WorkManager refuses `PeriodicWorkRequest` intervals below 15 minutes,
     * so the fast-poll chain is implemented as a one-shot that the worker
     * re-arms on success (see [SolicitudesPollingWorker.doWork]). The
     * one-shot uses the same unique-work name with `REPLACE`, so it becomes
     * the active shared-mock schedule while the fast-poll chain is running.
     *
     * Production (`USE_SHARED_MOCK=false`) keeps the original 5-minute
     * behavior unchanged.
     *
     * Spec scenarios covered:
     *  - "Worker is scheduled with a 5-minute repeat interval"
     *  - "Existing periodic jobs are not duplicated"
     *  - "Shared-mock dev re-arms a fast-poll so parent sees 'Pedir
     *    tiempo' within ~10-30s" (this change).
     */
    fun scheduleSolicitudesPolling(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<SolicitudesPollingWorker>(
            5, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(SolicitudesPollingWorker.WORK_NAME)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                SolicitudesPollingWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )

        Log.d(TAG, "Solicitudes polling programado")

        // Shared-mock dev fast-poll: a separate one-shot that re-arms
        // itself every FAST_POLL_INTERVAL_SECONDS via the worker's
        // success path. Bypasses the 15-minute PeriodicWork floor by
        // using OneTimeWorkRequest + REPLACE.
        if (BuildConfig.USE_SHARED_MOCK) {
            val fastRequest = OneTimeWorkRequestBuilder<SolicitudesPollingWorker>()
                .setConstraints(constraints)
                .setInitialDelay(FAST_POLL_INTERVAL_SECONDS, TimeUnit.SECONDS)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag(SolicitudesPollingWorker.WORK_NAME)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    SolicitudesPollingWorker.WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    fastRequest,
                )

            Log.d(
                TAG,
                "Solicitudes fast-poll programado (${FAST_POLL_INTERVAL_SECONDS}s)"
            )
        }
    }

    fun scheduleSyncAfterBoot(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .addTag(SyncWorker.TAG_AFTER_BOOT)
            .build()

        // Each chain step carries its own WORK_NAME tag so observers can
        // query the WorkManager database by tag and identify which worker
        // produced each `WorkInfo` (the public `WorkInfo` API does not
        // expose the worker class). The tags are harmless in production
        // (they only add query affordances) and pin the chain shape in
        // BootReceiverTest — see SUGGESTION #3 of
        // `fix-supabase-client-provider-legacy-mock-gate/verify-report.md`
        // for the historical spec/code drift this guards against.

        val heartbeatRequest = OneTimeWorkRequestBuilder<HeartbeatWorker>()
            .setConstraints(constraints)
            .addTag(HeartbeatWorker.WORK_NAME)
            .build()

        val outboxRequest = OneTimeWorkRequestBuilder<OutboxDrainer>()
            .setConstraints(constraints)
            .addTag(OutboxDrainer.WORK_NAME)
            .build()

        WorkManager.getInstance(context)
            .beginUniqueWork(
                "${SyncWorker.WORK_NAME}_after_boot",
                ExistingWorkPolicy.REPLACE,
                syncRequest
            )
            .then(heartbeatRequest)
            .then(outboxRequest)
            .enqueue()

        Log.d(TAG, "Secuencia post-boot programada")
    }

    fun scheduleSyncOnce(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(SyncWorker.WORK_NAME)
            .addTag(SyncWorker.TAG_AFTER_FCM)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "${SyncWorker.WORK_NAME}_once",
                ExistingWorkPolicy.REPLACE,
                workRequest
            )

        Log.d(TAG, "Sync único programado")
    }

    private fun buildPostPairingSyncRequest() =
        OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(SyncWorker.WORK_NAME)
            .addTag(SyncWorker.TAG_AFTER_PAIRING)
            .build()

    private fun enqueuePostPairingSync(context: Context, policy: ExistingWorkPolicy) =
        WorkManager.getInstance(context).enqueueUniqueWork(
            POST_PAIRING_UNIQUE_WORK_NAME,
            policy,
            buildPostPairingSyncRequest()
        )

    fun scheduleSyncAfterPairing(context: Context) {
        enqueuePostPairingSync(context, ExistingWorkPolicy.REPLACE)
        Log.d(TAG, "Sync post-emparejamiento programado")
    }

    fun scheduleSyncAfterPairingRecovery(context: Context) {
        enqueuePostPairingSync(context, ExistingWorkPolicy.KEEP)
        Log.d(TAG, "Recuperación sync post-emparejamiento programada")
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWork()
        Log.d(TAG, "Todos los workers cancelados")
    }

    fun cancelWork(context: Context, workName: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName)
        Log.d(TAG, "Worker cancelado: $workName")
    }

    fun getWorkState(context: Context, workName: String) =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkLiveData(workName)

    fun triggerSyncNow(context: Context) {
        Log.d(TAG, "Forzando sync inmediato")

        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag("manual_sync")
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "manual_sync",
                ExistingWorkPolicy.REPLACE,
                syncRequest
            )
    }
}

/**
 * Outcome of [WorkerInitializer.reinitializeAfterPairing]. Pairing is
 * irreversible (server consumed the code, session persisted) so the
 * UI MUST reach Success regardless of scheduling outcome; callers use
 * this enum to decide whether to log a contextual warning.
 */
enum class PostPairingSchedulingOutcome {
    SCHEDULED,
    FAILED,
}

object WorkerInitializer {

    private const val TAG = "WorkerInitializer"

    fun initialize(context: Context, isAfterBoot: Boolean = false) {
        Log.d(TAG, "Inicializando workers (afterBoot=$isAfterBoot)")

        if (isAfterBoot) {
            WorkScheduler.scheduleSyncAfterBoot(context)
        }

        WorkScheduler.scheduleAllPeriodicWork(context)

        Log.d(TAG, "Workers inicializados")
    }

    /**
     * Re-arms the post-pairing sync. NEVER rethrows — a WorkManager
     * scheduling exception is logged and surfaced via
     * [PostPairingSchedulingOutcome.FAILED] so the pairing flow can
     * still transition to the admin gate.
     */
    fun reinitializeAfterPairing(context: Context): PostPairingSchedulingOutcome {
        Log.d(TAG, "Reinicializando tras emparejamiento")
        return try {
            WorkScheduler.scheduleSyncAfterPairing(context)
            PostPairingSchedulingOutcome.SCHEDULED
        } catch (e: Exception) {
            Log.w(
                TAG,
                "Fallo al programar sync post-emparejamiento; el emparejamiento ya es irreversible",
                e
            )
            PostPairingSchedulingOutcome.FAILED
        }
    }

    /**
     * Attempts the bounded ChildStatus recovery schedule exactly once.
     * NEVER rethrows so a persistent WorkManager failure cannot crash
     * the already-paired child screen.
     */
    fun recoverAfterPairing(context: Context): PostPairingSchedulingOutcome {
        Log.d(TAG, "Recuperando sync post-emparejamiento")
        return try {
            WorkScheduler.scheduleSyncAfterPairingRecovery(context)
            PostPairingSchedulingOutcome.SCHEDULED
        } catch (e: Exception) {
            Log.w(TAG, "Fallo al programar recuperación sync post-emparejamiento", e)
            PostPairingSchedulingOutcome.FAILED
        }
    }
}
