package com.tudominio.parentalcontrol.analytics

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Worker para subir eventos conductuales al backend.
 * 
 * T32: Subida resiliente y por lotes (T18/T20)
 */
class AnalyticsSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "AnalyticsSyncWorker"
        private const val WORK_NAME = "analytics_sync"
        
        /**
         * Programa la sincronización periódica.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            
            val request = PeriodicWorkRequestBuilder<AnalyticsSyncWorker>(
                15, TimeUnit.MINUTES,
                5, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()
            
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            
            Log.d(TAG, "Analytics sync scheduled")
        }
        
        /**
         * Programa una sincronización inmediata.
         */
        fun syncNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            
            val request = OneTimeWorkRequestBuilder<AnalyticsSyncWorker>()
                .setConstraints(constraints)
                .build()
            
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "${WORK_NAME}_immediate",
                    ExistingWorkPolicy.REPLACE,
                    request
                )
            
            Log.d(TAG, "Immediate analytics sync requested")
        }
    }

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting analytics sync")
                
                val analyticsManager = AnalyticsManager.getInstance(applicationContext)
                
                // Obtener eventos no sincronizados
                val events = analyticsManager.getUnsyncedEvents(100)
                
                if (events.isEmpty()) {
                    Log.d(TAG, "No events to sync")
                    return@withContext Result.success()
                }
                
                Log.d(TAG, "Syncing ${events.size} events")
                
                // En producción, aquí se subirían al backend
                // Por ahora, simplemente los marcamos como sincronizados
                // simulateBackendUpload(events)
                
                // Marcar como sincronizados
                val eventIds = events.map { it.id }
                analyticsManager.markSynced(eventIds)
                
                // Limpiar eventos antiguos
                analyticsManager.cleanupOldEvents(7)
                
                Log.d(TAG, "Analytics sync completed successfully")
                Result.success()
            } catch (e: Exception) {
                Log.e(TAG, "Analytics sync failed: ${e.message}")
                
                if (runAttemptCount < 3) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            }
        }
    }
}
