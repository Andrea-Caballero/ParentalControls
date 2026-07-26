package com.tudominio.parentalcontrol.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.health.HealthMonitor
import com.tudominio.parentalcontrol.reconciliation.UsageStatsReconciler
import com.tudominio.parentalcontrol.sync.SyncManager
import com.tudominio.parentalcontrol.sync.SyncResult
import com.tudominio.parentalcontrol.time.TimeProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

@HiltWorker
class HeartbeatWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncManager: SyncManager,
    private val healthMonitor: HealthMonitor,
    private val timeProvider: TimeProvider
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "HeartbeatWorker"
        const val WORK_NAME = "heartbeat_work"
        private const val INTERVAL_MINUTES = 5L
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Ejecutando heartbeat")

        return@withContext try {
            val batteryLevel = getBatteryLevel()
            val healthResult = healthMonitor.performHealthCheck()
            val enforcementLevel = healthResult.enforcementLevel.name
            val clockOffsetMs = timeProvider.wallTimeMillis() - System.currentTimeMillis()

            Log.d(TAG, "Heartbeat: battery=$batteryLevel%, enforcement=$enforcementLevel, clockOffset=$clockOffsetMs ms")

            val sent = syncManager.sendHeartbeat(enforcementLevel)

            if (sent) {
                Log.d(TAG, "Heartbeat enviado exitosamente")
                Result.success()
            } else {
                Log.w(TAG, "Heartbeat falló, reintentando")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error en heartbeat: ${e.message}")
            Result.retry()
        }
    }

    private fun getBatteryLevel(): Int {
        return try {
            val batteryIntent = applicationContext.registerReceiver(
                null,
                android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            )
            val level = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100) / scale else -1
        } catch (e: Exception) {
            -1
        }
    }
}

@HiltWorker
class ReconciliationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val database: ParentalDatabase,
    private val syncManager: SyncManager
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "ReconciliationWorker"
        const val WORK_NAME = "reconciliation_work"
        private const val INTERVAL_HOURS = 1L
        private const val FLEX_MINUTES = 15L
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Ejecutando reconciliación")

        return@withContext try {
            val reconciler = UsageStatsReconciler(applicationContext, database)
            val result = reconciler.reconcileToday()

            when (result) {
                is UsageStatsReconciler.ReconciliationResult.NoPermission -> {
                    Log.w(TAG, "Sin permiso de UsageStats")
                    Result.failure(workDataOf("reason" to "no_usage_stats_permission"))
                }
                is UsageStatsReconciler.ReconciliationResult.Success -> {
                    Log.d(TAG, "Reconciliación exitosa: wasUpdated=${result.wasUpdated}")
                    syncManager.drainOutbox()
                    Result.success()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error en reconciliación: ${e.message}")
            Result.retry()
        }
    }
}

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncManager: SyncManager
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "SyncWorker"
        const val WORK_NAME = "sync_work"
        const val TAG_AFTER_BOOT = "after_boot"
        const val TAG_AFTER_PAIRING = "after_pairing"
        const val TAG_AFTER_FCM = "after_fcm"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Ejecutando sync completo")

        return@withContext try {
            val result = syncManager.sync()

            when (result) {
                is SyncResult.Success -> {
                    Log.d(TAG, "Sync exitoso")
                    Result.success()
                }
                is SyncResult.PartialSuccess -> {
                    Log.w(TAG, "Sync parcial: ${result.failedItems} items")
                    Result.success()
                }
                is SyncResult.Offline -> {
                    Log.d(TAG, "Offline, reintentando...")
                    Result.retry()
                }
                is SyncResult.Error -> {
                    Log.e(TAG, "Error en sync: ${result.message}")
                    if (runAttemptCount < 5) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Excepción en sync: ${e.message}")
            if (runAttemptCount < 5) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}
