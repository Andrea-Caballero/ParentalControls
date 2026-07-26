package com.tudominio.parentalcontrol.push

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.*

/**
 * Servicio FCM para recibir pushes de alta prioridad.
 * 
 * Importante (§0.1): FCM es señal, no dato.
 * - Nunca confiamos en el payload para tomar decisiones de enforcement
 * - El payload solo dispara sync, los datos reales vienen del servidor
 * 
 * NOTA: Esta clase usa Firebase Messaging cuando está disponible.
 * En builds sin Firebase, se usa un stub.
 */
class FcmPushService {

    companion object {
        private const val TAG = "FcmPushService"
        
        // Work request tags
        const val WORK_TAG_SYNC = "fcm_sync_work"
        const val WORK_TAG_TOKEN_REGISTER = "fcm_token_register"
        
        // Intent action para mensajes FCM
        const val ACTION_FCM_MESSAGE = "com.tudominio.parentalcontrol.FCM_MESSAGE"
        
        // Keys del payload (solo para debugging, no para decisiones)
        const val KEY_PRIORITY = "priority"
        const val KEY_MESSAGE_ID = "message_id"
        
        /**
         * Procesa un mensaje FCM.
         * Llama desde FirebaseMessagingService real o desde el stub.
         */
        fun processMessage(context: Context, data: Map<String, String>, priority: String) {
            Log.d(TAG, "Push procesado, prioridad: $priority")
            
            // §0.1: NUNCA confiamos en el payload para enforcement
            // Solo disparamos sync
            
            val isHighPriority = priority == "high"
            
            if (isHighPriority) {
                FcmWorkHelper.enqueueHighPrioritySync(context)
            } else {
                FcmWorkHelper.enqueueNormalSync(context)
            }
        }
        
        /**
         * Procesa un nuevo token.
         */
        fun processNewToken(context: Context, token: String) {
            Log.d(TAG, "Nuevo token FCM: ${token.substring(0, minOf(20, token.length))}...")
            saveToken(context, token)
            FcmWorkHelper.enqueueTokenRegistration(context, token)
        }
        
        // ============ Token Storage (delegated from FcmTokenManager) ============
        
        private const val PREFS_NAME = "fcm_token_prefs"
        private const val KEY_TOKEN = "current_fcm_token"
        private const val KEY_TOKEN_TIME = "token_timestamp"
        
        fun saveToken(context: Context, token: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TOKEN, token)
                .putLong(KEY_TOKEN_TIME, System.currentTimeMillis())
                .apply()
        }
        
        fun getStoredToken(context: Context): String? {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_TOKEN, null)
        }
        
        fun isTokenStale(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val timestamp = prefs.getLong(KEY_TOKEN_TIME, 0)
            val age = System.currentTimeMillis() - timestamp
            return age > 30 * 24 * 60 * 60 * 1000L
        }
    }
}

/**
 * Stub del servicio FCM cuando Firebase no está configurado.
 */
class FcmMessagingStub : android.app.Service() {
    override fun onBind(intent: Intent?) = null
}

/**
 * Wrapper para WorkManager que survive Doze.
 */
object FcmWorkHelper {
    private const val TAG = "FcmWorkHelper"

    fun enqueueHighPrioritySync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<FcmSyncWorkerStub>()
            .setConstraints(constraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(FcmPushService.WORK_TAG_SYNC)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "${FcmPushService.WORK_TAG_SYNC}_high",
                ExistingWorkPolicy.REPLACE,
                workRequest
            )

        Log.d(TAG, "Sync de alta prioridad encolado")
    }

    /**
     * Encola sync normal (background).
     */
    fun enqueueNormalSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<FcmSyncWorkerStub>(
            15, java.util.concurrent.TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .addTag(FcmPushService.WORK_TAG_SYNC)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                FcmPushService.WORK_TAG_SYNC,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
    }

    /**
     * Encola registro de token.
     */
    fun enqueueTokenRegistration(context: Context, token: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val inputData = workDataOf(
            FcmTokenWorkerStub.KEY_TOKEN to token
        )

        val workRequest = OneTimeWorkRequestBuilder<FcmTokenWorkerStub>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                java.util.concurrent.TimeUnit.MILLISECONDS
            )
            .addTag(FcmPushService.WORK_TAG_TOKEN_REGISTER)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                FcmPushService.WORK_TAG_TOKEN_REGISTER,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
    }
}

class FcmTokenWorkerStub(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_TOKEN = "fcm_token"
    }

    override suspend fun doWork(): Result {
        val token = inputData.getString(KEY_TOKEN) ?: return Result.failure()

        return try {
            val clientProvider = com.tudominio.parentalcontrol.network.SupabaseClientProvider.getInstance(applicationContext)
            val result = clientProvider.registerPushToken(token)

            if (result.isSuccess) {
                FcmPushService.saveToken(applicationContext, token)
                return Result.success()
            }

            enqueueTokenToOutbox(token)
            Result.success()
        } catch (e: Exception) {
            enqueueTokenToOutbox(token)
            Result.success()
        }
    }

    private suspend fun enqueueTokenToOutbox(token: String) {
        try {
            val syncManager = com.tudominio.parentalcontrol.sync.SyncManager.getInstance(applicationContext)
            syncManager.enqueue(
                com.tudominio.parentalcontrol.sync.OutboxEventType.HEARTBEAT,
                mapOf(
                    "type" to "FCM_TOKEN_UPDATE",
                    "token" to token,
                    "registered_at" to java.time.Instant.now().toString()
                ),
                dedupKey = "fcm_token_update"
            )
        } catch (_: Exception) {
        }
    }
}

/**
 * Worker para sincronizar tras recibir push.
 * §0.1: No confiamos en el payload - sync completo desde el servidor.
 */
class FcmSyncWorkerStub(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "Ejecutando sync worker")
        
        return try {
            val syncManager = com.tudominio.parentalcontrol.sync.SyncManager.getInstance(applicationContext)
            val result = syncManager.sync()
            
            when (result) {
                is com.tudominio.parentalcontrol.sync.SyncResult.Success -> {
                    Log.d(TAG, "Sync completado exitosamente")
                    Result.success()
                }
                is com.tudominio.parentalcontrol.sync.SyncResult.PartialSuccess -> {
                    Log.w(TAG, "Sync parcial: ${result.failedItems} items fallidos")
                    Result.success()
                }
                is com.tudominio.parentalcontrol.sync.SyncResult.Offline -> {
                    Log.d(TAG, "Offline, reintentando...")
                    Result.retry()
                }
                is com.tudominio.parentalcontrol.sync.SyncResult.Error -> {
                    Log.e(TAG, "Error en sync: ${result.message}")
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Excepción en sync: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "FcmSyncWorkerStub"
    }
}
