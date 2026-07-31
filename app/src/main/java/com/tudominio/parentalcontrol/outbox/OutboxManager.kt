package com.tudominio.parentalcontrol.outbox

import android.content.Context
import android.util.Log
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.data.model.OutboxEntity
import com.tudominio.parentalcontrol.data.model.TimeRequestEntity
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
         * A claim is considered stale after this many seconds. The
         * drainer runs `releaseStaleClaims(olderThan = now - TTL)`
         * before each claim cycle so rows claimed by a drainer that
         * crashed before it could finalize are re-eligible. Tuned
         * generously because a slow network round-trip on a flaky
         * connection is not the same as a crashed process; the legacy
         * retry budget of 3 already catches truly stuck rows.
         */
        private const val STALE_CLAIM_TTL_SECONDS = 300L

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
            val payload = buildOutboxPayloadJson(
                mapOf(
                    "request_id" to request.request_id,
                    "device_id" to request.device_id,
                    "minutes_requested" to request.minutes_requested,
                    "reason" to request.reason,
                    "created_at" to request.created_at,
                )
            )

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
    suspend fun enqueueEvent(eventType: String, payload: Map<String, Any?>): Boolean {
        return try {
            val dedupKey = "${eventType}_${System.currentTimeMillis()}"

            val outboxItem = OutboxEntity(
                tipo = eventType,
                payload_json = buildOutboxPayloadJson(payload),
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
     * Returns the list of pending outbox rows (processed = 0, in_flight = 0)
     * that are still under the retry budget, ordered by `created_at` ASC.
     * Used by the legacy `SyncManager.drainOutbox` path; the canonical
     * `OutboxDrainer` worker uses [claimPendingItems] which atomically
     * SELECTs + claims in a single transaction.
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
     * Atomically SELECTs up to `limit` claimable rows and marks them
     * `in_flight = 1`, returning the selected rows. A subsequent call
     * will NOT see the same rows (the `in_flight = 0` filter excludes
     * them) until the caller clears the flag via [markProcessedFromClaim],
     * [incrementRetriesFromClaim], or [releaseClaim].
     *
     * Stale-claim recovery runs before the claim: any row whose
     * `in_flight_at` is older than [STALE_CLAIM_TTL_SECONDS] is
     * released, so a drainer that crashed mid-iteration does not
     * permanently strand the row.
     */
    suspend fun claimPendingItems(
        maxAttempts: Int = MAX_RETRIES,
        limit: Int = 50,
        now: String
    ): List<OutboxEntity> {
        return try {
            releaseStaleClaims(now)
            outboxDao.claimPendingItems(maxAttempts, limit, now)
        } catch (e: Exception) {
            Log.e(TAG, "Error claiming pending items: ${e.message}")
            emptyList()
        }
    }

    private suspend fun releaseStaleClaims(now: String) {
        try {
            // `now` is an ISO-8601 instant. Subtract the TTL via
            // java.time to get the cutoff. We keep the operation in
            // java.time so the SQL string compare stays a plain
            // lexicographic compare on ISO-8601 (which is monotonic).
            val cutoff = java.time.Instant.parse(now)
                .minusSeconds(STALE_CLAIM_TTL_SECONDS)
                .toString()
            outboxDao.releaseStaleClaims(cutoff)
        } catch (e: Exception) {
            // A parse failure means the caller is using a non-ISO
            // timestamp — log and skip the recovery pass; the
            // legacy retry budget catches truly stuck rows anyway.
            Log.w(TAG, "Skipping stale-claim recovery: ${e.message}")
        }
    }

    /**
     * Terminal: marks the row processed AND clears the in-flight
     * flag in one statement so the sweeper can't reclaim a row that
     * already reached the server.
     */
    suspend fun markProcessedFromClaim(id: UUID, processedAt: String) {
        try {
            outboxDao.markProcessedFromClaim(id, processedAt)
        } catch (e: Exception) {
            Log.e(TAG, "Error marking item processed: ${e.message}")
        }
    }

    /**
     * Retryable failure path: clears the in-flight flag AND bumps the
     * retry counter in one statement so the row becomes claimable again
     * (with its new retry count) on the next drain cycle.
     */
    suspend fun incrementRetriesFromClaim(id: UUID) {
        try {
            outboxDao.incrementRetriesFromClaim(id)
        } catch (e: Exception) {
            Log.e(TAG, "Error incrementing retries: ${e.message}")
        }
    }

    /**
     * Releases the in-flight flag without consuming a retry or
     * marking the row terminal. Useful for callers that need to
     * abort a claim mid-iteration (e.g., a worker cancelled before
     * processing the item).
     */
    suspend fun releaseClaim(id: UUID) {
        try {
            outboxDao.releaseClaim(id)
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing claim: ${e.message}")
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

    private fun buildOutboxPayloadJson(payload: Map<String, Any?>): String {
        return buildJsonObject {
            payload.forEach { (key, value) ->
                put(key, value.toJsonElement())
            }
        }.toString()
    }

    private fun Any?.toJsonElement(): JsonElement = when (this) {
        null -> JsonNull
        is JsonElement -> this
        is String -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        else -> JsonPrimitive(this.toString())
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
