package com.tudominio.parentalcontrol.data.repository

import android.content.Context
import android.util.Log
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
import kotlinx.coroutines.flow.first

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
            status = "pending",
            created_at = now.toString(),
            responded_at = null,
            parent_response = null
        )

        try {
            // Guardar localmente
            timeRequestDao.insertRequest(request)

            // Guardar throttle
            saveLastRequestTime(now)

            // Intentar enviar al servidor (offline-safe)
            val enqueued = outboxManager.enqueueTimeRequest(request)

            // Trigger an immediate one-shot OutboxDrainer so the parent sees
            // the request within seconds, not at the next 15-min periodic
            // tick. `enqueueUniqueWork(REPLACE)` collapses concurrent taps.
            if (enqueued) {
                WorkScheduler.scheduleOneTimeOutboxDrain(context)
            }

            Log.d(TAG, "Time request created: $requestId, enqueued=$enqueued")

            return TimeRequestResult.Success(requestId, isSent = enqueued)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating time request: ${e.message}")
            return TimeRequestResult.Error(e.message ?: "Unknown error")
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
     */
    suspend fun processApproval(
        requestId: String,
        approvedMinutes: Int,
        expiresAt: Long? = null
    ): GrantResult {
        try {
            val request = timeRequestDao.getRequestById(requestId)
                ?: return GrantResult.Error("Request not found")
            val deviceId = request.device_id

            timeRequestDao.updateRequestStatus(
                requestId = requestId,
                status = "approved",
                respondedAt = timeProvider.wallInstant().toString()
            )

            // Crear grant idempotente (source='extra_time')
            val grantId = "extra_time_$requestId"
            val now = timeProvider.wallInstant()
            val expires = expiresAt?.let { Instant.ofEpochMilli(it) } ?: now.plusSeconds(approvedMinutes * 60L)

            val grant = GrantEntity(
                id = grantId,
                device_id = deviceId,
                request_id = requestId,
                scope = "extra_time",
                minutes = approvedMinutes,
                source = "extra_time",
                granted_at = now.toString(),
                expires_at = expires.toString()
            )

            grantDao.insertGrant(grant)

            Log.d(TAG, "Grant created from approval: $grantId, expires=$expires")

            return GrantResult.Success(grantId, expires)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing approval: ${e.message}")
            return GrantResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Procesa una respuesta de denegación.
     */
    suspend fun processDenial(requestId: String) {
        try {
            timeRequestDao.updateRequestStatus(
                requestId = requestId,
                status = "denied",
                respondedAt = timeProvider.wallInstant().toString()
            )
            Log.d(TAG, "Request denied: $requestId")
        } catch (e: Exception) {
            Log.e(TAG, "Error processing denial: ${e.message}")
        }
    }

    /**
     * Verifica si el grant de tiempo extra está activo.
     */
    suspend fun hasActiveExtraTimeGrant(): Boolean {
        val now = timeProvider.wallInstant().toString()
        return try {
            val extraGrants = grantDao.getGrantsForScope("extra_time").first()
            val rewardGrants = grantDao.getGrantsForScope("reward").first()
            val allGrants = extraGrants + rewardGrants
            allGrants.any { it.expires_at > now }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Reactive stream of all `extra_time` grants in Room. The
     * [TimeExtraViewModel] observes this so the home screen updates
     * immediately when a new grant is created (e.g., by the
     * post-boot pullApprovedRequests after a parent approve).
     */
    fun observeExtraTimeGrants(): Flow<List<GrantEntity>> =
        grantDao.getGrantsForScope("extra_time")

    /**
     * Obtiene el grant de tiempo extra activo.
     */
    suspend fun getActiveExtraTimeGrant(): GrantEntity? {
        val now = timeProvider.wallInstant().toString()
        return try {
            val extraGrants = grantDao.getGrantsForScope("extra_time").first()
            val rewardGrants = grantDao.getGrantsForScope("reward").first()
            val allGrants = extraGrants + rewardGrants
            allGrants.firstOrNull { it.expires_at > now }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Obtiene el saldo total de tiempo extra (extra_time + rewards).
     */
    suspend fun getTotalAvailableMinutes(): Long {
        val now = timeProvider.wallInstant().toString()
        return try {
            val extraGrants = grantDao.getGrantsForScope("extra_time").first()
            val rewardGrants = grantDao.getGrantsForScope("reward").first()
            val allGrants = extraGrants + rewardGrants

            allGrants
                .filter { it.expires_at > now }
                .sumOf { it.minutes }.toLong()
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
