package com.tudominio.parentalcontrol.data.repository

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.data.model.GrantEntity
import com.tudominio.parentalcontrol.data.model.TimeRequestEntity
import com.tudominio.parentalcontrol.outbox.OutboxManager
import com.tudominio.parentalcontrol.time.DefaultTimeProvider
import com.tudominio.parentalcontrol.time.TimeProvider
import com.tudominio.parentalcontrol.workers.WorkScheduler
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Repositorio para manejar solicitudes de tiempo extra.
 *
 * §0.4 paso 6: El grant de tiempo extra levanta límites pero no desbloquea blocked ni allow_only.
 *
 * Offline-first: encola solicitudes en outbox si no hay conexión.
 *
 * `database` and `outboxManager` are Hilt-injected (PR 4 of
 * `align-with-guia-fedora44`). Use the [TimeExtraRepositoryEntryPoint]
 * bridge only for non-Hilt call sites.
 */
@Singleton
class TimeExtraRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: ParentalDatabase,
    private val outboxManager: OutboxManager,
    private val timeProvider: TimeProvider = DefaultTimeProvider(context)
) {
    companion object {
        private const val TAG = "TimeExtraRepository"

        // Throttle local (mínimo 5 minutos entre solicitudes)
        private const val THROTTLE_MIN_MINUTES = 5L

        // Máximo minutos que se pueden pedir de una vez
        private const val MAX_REQUEST_MINUTES = 120L

        // Duración default del grant (30 minutos)
        private const val DEFAULT_GRANT_DURATION_MINUTES = 30L

        // Status values written to `time_requests.status`. Keep these in
        // sync with [com.tudominio.parentalcontrol.domain.model.RequestStatus]
        // (PENDING / APPROVED / DENIED) — the [TimeRequestDao.getPendingRequestsFlow]
        // query and the Supabase filter both expect UPPERCASE. The previous
        // lowercase writes here silently orphaned new requests from the
        // pending-list reactive flow.
        private const val STATUS_PENDING = "PENDING"
        private const val STATUS_APPROVED = "APPROVED"
        private const val STATUS_DENIED = "DENIED"

        /**
         * Convenience accessor for non-Hilt call sites. Production code
         * inside `@AndroidEntryPoint` / `@HiltViewModel` should inject the
         * repository directly via `@Inject TimeExtraRepository`.
         */
        fun getInstance(context: Context): TimeExtraRepository {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                TimeExtraRepositoryEntryPoint::class.java
            )
            return entryPoint.timeExtraRepository()
        }
    }

    private val timeRequestDao = database.timeRequestDao()
    private val grantDao = database.grantDao()

    /**
     * Crea una solicitud de tiempo extra.
     *
     * Offline: encola en outbox para sync posterior.
     *
     * Atomicity contract: the local `time_requests` row and the
     * matching `outbox` row commit together in a single
     * `withTransaction` block. The throttle SharedPreferences stamp
     * is bumped only AFTER the transaction commits, so a failure in
     * either write rolls both back AND leaves the throttle
     * untouched. The pre-fix code wrote the throttle before the
     * enqueue, so an enqueue failure locked the user out of
     * making another request for the next 5 minutes even though no
     * request ever reached the device.
     */
    suspend fun createTimeRequest(
        deviceId: String,
        minutes: Int,
        reason: String?
    ): TimeRequestResult {
        // Validaciones
        if (minutes <= 0 || minutes > MAX_REQUEST_MINUTES) {
            return TimeRequestResult.InvalidMinutes
        }

        // Verificar throttle local
        if (isThrottled()) {
            val waitMinutes = getWaitTimeMinutes()
            return TimeRequestResult.Throttled(waitMinutes)
        }

        // Crear la solicitud
        val requestId = generateRequestId()
        val now = timeProvider.wallInstant().toEpochMilli()

        val request = TimeRequestEntity(
            request_id = requestId,
            device_id = deviceId,
            package_name = null,
            minutes_requested = minutes,
            reason = reason ?: "",
            status = STATUS_PENDING,
            created_at = now.toString(),
            responded_at = null,
            parent_response = null
        )

        return try {
            // Transactional pair: the local row and the outbox row
            // commit together, or neither commits. The DAO insert
            // and the outbox enqueue live in the same Room DB so a
            // single `withTransaction` is sufficient to make them
            // atomic from the consumer's point of view.
            database.withTransaction {
                timeRequestDao.insertRequest(request)

                val enqueued = outboxManager.enqueueTimeRequest(request)
                if (!enqueued) {
                    // Outbox enqueue is allowed to return false (e.g.,
                    // dedup-key hit on a retry) — the local row is
                    // then meaningless because the request was
                    // already in the outbox. Roll back so the user
                    // does not see a phantom PENDING row.
                    throw IllegalStateException("Outbox enqueue returned false")
                }
            }

            // Both writes committed — bump the throttle AFTER the
            // transaction. SharedPreferences are not transactional;
            // writing inside the transaction would leak a stamp on
            // rollback. Scheduling the one-shot drain lives here too
            // so a failed enqueue never schedules a no-op drain.
            saveLastRequestTime(now)
            WorkScheduler.scheduleOneTimeOutboxDrain(context)

            Log.d(TAG, "Time request created: $requestId, enqueued=true")

            TimeRequestResult.Success(requestId, isSent = true)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating time request: ${e.message}")
            TimeRequestResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Obtiene las solicitudes de tiempo del dispositivo.
     */
    fun getRequestsForDevice(deviceId: String): Flow<List<TimeRequestEntity>> {
        return timeRequestDao.getRequestsForDeviceFlow(deviceId)
    }

    /**
     * Obtiene solicitudes pendientes.
     */
    fun getPendingRequests(): Flow<List<TimeRequestEntity>> {
        return timeRequestDao.getPendingRequestsFlow()
    }

    /**
     * Procesa una respuesta de aprobación.
     *
     * §0.4 paso 6: Crea grant idempotente con source='extra_time'.
     *
     * The request-status update and the grant insert are wrapped in a single
     * Room `withTransaction { ... }` so they commit together or roll back
     * together. Without the transaction boundary a crash between the two
     * writes would leave the request marked APPROVED with no grant, or
     * a grant created against a request still flagged PENDING — the
     * classic two-writer divergence that the parent UI then has to
     * reconcile on next boot.
     *
     * Goal 3 (request identity preservation): the local request is
     * looked up via
     * [com.tudominio.parentalcontrol.data.db.TimeRequestDao.getRequestByRequestIdOrServerId]
     * so the same call resolves both the post-v9 reconciliation
     * (server's id lives in `time_requests.server_id`) and the
     * pre-v9 reconciliation (server's id was renamed into
     * `time_requests.request_id`). The matched local id is what we
     * stamp onto the resulting grant's `request_id` so the soft-FK
     * stays consistent with the local primary key.
     */
    suspend fun processApproval(
        requestId: String,
        approvedMinutes: Int,
        expiresAt: Long? = null
    ): GrantResult {
        return try {
            database.withTransaction {
                val request = timeRequestDao.getRequestByRequestIdOrServerId(requestId)
                    ?: return@withTransaction GrantResult.Error("Request not found")
                val localRequestId = request.request_id
                val deviceId = request.device_id

                timeRequestDao.updateRequestStatus(
                    requestId = localRequestId,
                    status = STATUS_APPROVED,
                    respondedAt = timeProvider.wallInstant().toString()
                )

                // Crear grant idempotente (source='extra_time').
                // The grant's request_id is the LOCAL primary key, not
                // the server's id that the parent saw — the pre-v9
                // reconciliation renamed request_id to match the
                // server, so this branch is correct on both install
                // paths. Post-v9 keeps request_id as the client id
                // and writes the server's id to server_id; this
                // branch is also correct on that path.
                val grantId = "extra_time_$localRequestId"
                val now = timeProvider.wallInstant()
                val expires = expiresAt?.let { Instant.ofEpochMilli(it) }
                    ?: now.plusSeconds(approvedMinutes * 60L)

                val grant = GrantEntity(
                    id = grantId,
                    device_id = deviceId,
                    request_id = localRequestId,
                    scope = "extra_time",
                    minutes = approvedMinutes,
                    source = "extra_time",
                    granted_at = now.toString(),
                    expires_at = expires.toString()
                )

                grantDao.insertGrant(grant)

                Log.d(TAG, "Grant created from approval: $grantId, expires=$expires")

                GrantResult.Success(grantId, expires)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing approval: ${e.message}")
            GrantResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Procesa una respuesta de denegación.
     */
    suspend fun processDenial(requestId: String) {
        try {
            timeRequestDao.updateRequestStatus(
                requestId = requestId,
                status = STATUS_DENIED,
                respondedAt = timeProvider.wallInstant().toString()
            )
            Log.d(TAG, "Request denied: $requestId")
        } catch (e: Exception) {
            Log.e(TAG, "Error processing denial: ${e.message}")
        }
    }

    /**
     * Verifica si el grant de tiempo extra está activo.
     *
     * Scoped to [deviceId] so two paired devices in the same family
     * never see each other's grants. The pre-fix code queried the DAO
     * with `scope` only — that returned every device's grants and
     * surfaced a phantom "you have extra time" state on devices that
     * had never asked for any.
     */
    suspend fun hasActiveExtraTimeGrant(deviceId: String): Boolean {
        val now = timeProvider.wallInstant().toString()
        return try {
            grantDao.getActiveGrantsForScopeOnce(deviceId, "extra_time", now).isNotEmpty() ||
                grantDao.getActiveGrantsForScopeOnce(deviceId, "reward", now).isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Reactive stream of the calling device's `extra_time` grants in
     * Room. The [TimeExtraViewModel] observes this so the home screen
     * updates immediately when a new grant is created (e.g., by the
     * post-boot pullApprovedRequests after a parent approve).
     *
     * Scoped to [deviceId] — see [hasActiveExtraTimeGrant] for the
     * cross-device rationale.
     */
    fun observeExtraTimeGrants(deviceId: String): Flow<List<GrantEntity>> =
        grantDao.getGrantsForScopeFlow(deviceId, "extra_time")

    /**
     * Obtiene el grant de tiempo extra activo.
     *
     * Scoped to [deviceId]; see [hasActiveExtraTimeGrant].
     */
    suspend fun getActiveExtraTimeGrant(deviceId: String): GrantEntity? {
        val now = timeProvider.wallInstant().toString()
        return try {
            grantDao.getActiveGrantsForScopeOnce(deviceId, "extra_time", now).firstOrNull()
                ?: grantDao.getActiveGrantsForScopeOnce(deviceId, "reward", now).firstOrNull()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Obtiene el saldo total de tiempo extra (extra_time + rewards).
     *
     * Scoped to [deviceId]; see [hasActiveExtraTimeGrant]. The
     * `expires_at > now` filter is now pushed into SQL so the DAO
     * returns only the rows that actually contribute to the balance.
     */
    suspend fun getTotalAvailableMinutes(deviceId: String): Long {
        val now = timeProvider.wallInstant().toString()
        return try {
            val extraMinutes =
                grantDao.getActiveGrantsForScopeOnce(deviceId, "extra_time", now)
                    .sumOf { it.minutes }
            val rewardMinutes =
                grantDao.getActiveGrantsForScopeOnce(deviceId, "reward", now)
                    .sumOf { it.minutes }
            (extraMinutes + rewardMinutes).toLong()
        } catch (e: Exception) {
            0L
        }
    }

    // ============ Throttle ============

    private fun isThrottled(): Boolean {
        val prefs = context.getSharedPreferences("time_extra_prefs", Context.MODE_PRIVATE)
        val lastRequest = prefs.getLong("last_request_time", 0)
        val now = System.currentTimeMillis()
        val elapsed = (now - lastRequest) / 60_000 // minutos

        return elapsed < THROTTLE_MIN_MINUTES
    }

    private fun getWaitTimeMinutes(): Long {
        val prefs = context.getSharedPreferences("time_extra_prefs", Context.MODE_PRIVATE)
        val lastRequest = prefs.getLong("last_request_time", 0)
        val now = System.currentTimeMillis()
        val elapsed = (now - lastRequest) / 60_000
        return THROTTLE_MIN_MINUTES - elapsed
    }

    private fun saveLastRequestTime(time: Long) {
        val prefs = context.getSharedPreferences("time_extra_prefs", Context.MODE_PRIVATE)
        prefs.edit().putLong("last_request_time", time).apply()
    }

    private fun generateRequestId(): String {
        return "req_${timeProvider.elapsedRealtime()}_${(Math.random() * 10000).toInt()}"
    }
}

/**
 * Resultado de crear una solicitud.
 */
sealed class TimeRequestResult {
    data class Success(val requestId: String, val isSent: Boolean) : TimeRequestResult()
    data class Throttled(val waitMinutes: Long) : TimeRequestResult()
    data object InvalidMinutes : TimeRequestResult()
    data class Error(val message: String) : TimeRequestResult()
}

/**
 * Resultado de procesar un approval.
 */
sealed class GrantResult {
    data class Success(val grantId: String, val expiresAt: Instant) : GrantResult()
    data class Error(val message: String) : GrantResult()
}

/**
 * Hilt [EntryPoint] that exposes [TimeExtraRepository] from the
 * `SingletonComponent` to non-Hilt call sites.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TimeExtraRepositoryEntryPoint {
    fun timeExtraRepository(): TimeExtraRepository
}
