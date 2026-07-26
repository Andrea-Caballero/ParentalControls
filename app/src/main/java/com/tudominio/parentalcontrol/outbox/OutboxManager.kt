package com.tudominio.parentalcontrol.outbox

import android.content.Context
import android.util.Log
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.data.model.OutboxEntity
import com.tudominio.parentalcontrol.data.model.TimeRequestEntity
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first

/**
 * Manager para la outbox de solicitudes offline.
 *
 * Encola solicitudes cuando no hay conexión y las sincroniza cuando se recupera.
 *
 * `database` is Hilt-injected (`@Singleton @Inject constructor`) so the
 * `OutboxDrainerTest` no longer needs to reach into a private static
 * field on ParentalDatabase — Hilt's `SingletonComponent` provides the
 * test's in-memory instance via the standard `@HiltAndroidTest` /
 * `TestInstallIn` flow.
 */
@Singleton
class OutboxManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: ParentalDatabase
) {

    companion object {
        private const val TAG = "OutboxManager"

        private const val MAX_RETRIES = 3

        /**
         * Convenience accessor for non-Hilt call sites (legacy Workers
         * constructed by WorkManager outside the `@HiltWorker` graph, or
         * unit tests that don't bootstrap Hilt). Resolves the singleton
         * via the [OutboxManagerEntryPoint] bridge to `SingletonComponent`.
         *
         * Production code that runs inside an `@AndroidEntryPoint`,
         * `@HiltViewModel`, or `@HiltWorker` context MUST inject the
         * manager directly — do not call this method.
         */
        fun getInstance(context: Context): OutboxManager {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                OutboxManagerEntryPoint::class.java
            )
            return entryPoint.outboxManager()
        }
    }

    private val outboxDao = database.outboxDao()
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Encola una solicitud de tiempo extra para envío posterior.
     */
    suspend fun enqueueTimeRequest(request: TimeRequestEntity): Boolean {
        return try {
            val payload = """
                {
                    "request_id": "${request.request_id}",
                    "device_id": "${request.device_id}",
                    "minutes_requested": ${request.minutes_requested},
                    "reason": "${request.reason}",
                    "created_at": ${request.created_at}
                }
            """.trimIndent()

            val dedupKey = "time_request_${request.request_id}"

            val outboxItem = OutboxEntity(
                tipo = "TIME_REQUEST",
                payload_json = payload,
                dedup_key = dedupKey,
                created_at = Instant.now().toString(),
                server_date = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
            )

            outboxDao.insertOutboxItem(outboxItem)

            Log.d(TAG, "Time request enqueued: ${request.request_id}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error enqueueing time request: ${e.message}")
            false
        }
    }

    /**
     * Encola un evento genérico para envío posterior.
     */
    suspend fun enqueueEvent(eventType: String, payload: Map<String, Any>): Boolean {
        return try {
            val jsonPayload = payload.entries.joinToString(",") { (k, v) ->
                "\"$k\": ${if (v is String) "\"$v\"" else v}"
            }

            val dedupKey = "${eventType}_${System.currentTimeMillis()}"

            val outboxItem = OutboxEntity(
                tipo = eventType,
                payload_json = "{ $jsonPayload }",
                dedup_key = dedupKey,
                created_at = Instant.now().toString(),
                server_date = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
            )

            outboxDao.insertOutboxItem(outboxItem)

            Log.d(TAG, "Event enqueued: $eventType")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error enqueueing event: ${e.message}")
            false
        }
    }

    /**
     * Returns the list of pending outbox rows (processed = 0) that are still
     * under the retry budget, ordered by `created_at` ASC. Used by
     * [com.tudominio.parentalcontrol.workers.OutboxDrainer] to drive the drain.
     */
    suspend fun getPendingItems(
        maxAttempts: Int = MAX_RETRIES,
        limit: Int = 50
    ): List<OutboxEntity> {
        return try {
            outboxDao.getPendingItems(maxAttempts, limit)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading pending items: ${e.message}")
            emptyList()
        }
    }

    /**
     * Marks the given outbox row as processed and stamps the timestamp. Used
     * by the drainer on Success and PermanentFailure branches.
     */
    suspend fun markProcessed(id: UUID, processedAt: String) {
        try {
            outboxDao.markProcessed(id, processedAt)
        } catch (e: Exception) {
            Log.e(TAG, "Error marking item processed: ${e.message}")
        }
    }

    /**
     * Increments the retries counter for a transient-failure outbox row.
     */
    suspend fun incrementRetries(id: UUID) {
        try {
            outboxDao.incrementRetries(id)
        } catch (e: Exception) {
            Log.e(TAG, "Error incrementing retries: ${e.message}")
        }
    }

    /**
     * Obtiene el número de elementos pendientes.
     */
    suspend fun getPendingCount(): Int {
        return try {
            outboxDao.getPendingCountFlow().first()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting pending count: ${e.message}")
            0
        }
    }

    /**
     * Limpia items fallidos.
     */
    suspend fun cleanupFailedItems() {
        try {
            outboxDao.deleteFailedItems(MAX_RETRIES)
            Log.d(TAG, "Cleaned up failed items")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up: ${e.message}")
        }
    }
}

/**
 * Hilt [EntryPoint] that exposes [OutboxManager] from the
 * `SingletonComponent` to non-Hilt call sites that must resolve the
 * singleton from a raw `Context` (Workers constructed by `WorkManager`
 * outside the `@HiltWorker` graph, plain unit tests).
 *
 * Injecting the manager via `@Inject` is the preferred path. Use this
 * bridge only when DI is unavailable.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface OutboxManagerEntryPoint {
    fun outboxManager(): OutboxManager
}
