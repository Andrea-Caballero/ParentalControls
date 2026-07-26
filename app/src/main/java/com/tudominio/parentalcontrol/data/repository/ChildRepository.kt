package com.tudominio.parentalcontrol.data.repository

import com.tudominio.parentalcontrol.domain.model.AppPolicy
import com.tudominio.parentalcontrol.domain.model.BlockedState
import com.tudominio.parentalcontrol.domain.model.Policy
import com.tudominio.parentalcontrol.domain.model.TimeRequest
import com.tudominio.parentalcontrol.domain.model.TimeRequestStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Repositorio para operaciones del niño.
 */
class ChildRepository {

    private var cachedPolicy: Policy? = null
    private var lastSync: Instant? = null
    private val pendingRequests = mutableListOf<TimeRequest>()
    private val usageToday = mutableMapOf<String, Int>()

    suspend fun getPolicy(): Policy = withContext(Dispatchers.IO) {
        // En implementación real, llamar a /functions/v1/get-policy
        cachedPolicy ?: Policy(
            deviceId = "mock-device",
            version = 1,
            deviceState = "ACTIVE",
            dailyScreenTimeMinutes = 120,
            schedules = emptyList(),
            categoryLimits = emptyList(),
            appPolicies = listOf(
                AppPolicy(
                    packageName = "com.instagram.android",
                    state = "LIMITED",
                    dailyLimitMinutes = 30,
                    allowedWindows = emptyList(),
                    category = "social"
                ),
                AppPolicy(
                    packageName = "com.whatsapp",
                    state = "ALLOWED",
                    dailyLimitMinutes = null,
                    allowedWindows = emptyList(),
                    category = "communication"
                ),
                AppPolicy(
                    packageName = "com.tiktok",
                    state = "LIMITED",
                    dailyLimitMinutes = 20,
                    allowedWindows = emptyList(),
                    category = "entertainment"
                )
            ),
            categoryAssignments = mapOf(
                "com.instagram.android" to "social",
                "com.whatsapp" to "communication",
                "com.tiktok" to "entertainment"
            ),
            grants = emptyList()
        ).also { cachedPolicy = it }
    }

    suspend fun checkBlocked(packageName: String, policy: Policy?): BlockedState =
        withContext(Dispatchers.IO) {
            val p = policy ?: getPolicy()

            // Verificar schedules
            val now = java.time.LocalTime.now()
            val dayOfWeek = java.time.DayOfWeek.from(java.time.LocalDate.now())
            
            for (schedule in p.schedules) {
                if (schedule.days.contains(dayOfWeek.name)) {
                    val from = java.time.LocalTime.parse(schedule.from)
                    val to = java.time.LocalTime.parse(schedule.to)
                    if (now.isAfter(from) && now.isBefore(to)) {
                        return@withContext BlockedState.SCHEDULE_BLOCK
                    }
                }
            }

            // Verificar app policy
            val appPolicy = p.appPolicies.find { it.packageName == packageName }
            when (appPolicy?.state) {
                "BLOCKED" -> return@withContext BlockedState.APP_BLOCKED
                "LIMITED" -> {
                    val used = usageToday[packageName] ?: 0
                    val limit = appPolicy.dailyLimitMinutes ?: return@withContext BlockedState.NOT_BLOCKED
                    if (used >= limit) {
                        return@withContext BlockedState.LIMIT_EXCEEDED
                    }
                }
                "ALLOWED", null -> { /* OK */ }
            }

            // Verificar tiempo total
            val totalUsed = usageToday.values.sum()
            if (totalUsed >= p.dailyScreenTimeMinutes) {
                return@withContext BlockedState.TIME_EXCEEDED
            }

            BlockedState.NOT_BLOCKED
        }

    suspend fun requestTime(
        packageName: String?,
        minutesRequested: Int,
        reason: String?
    ): TimeRequest = withContext(Dispatchers.IO) {
        // En implementación real, insertar en time_requests via Supabase
        val request = TimeRequest(
            id = "req-${System.currentTimeMillis()}",
            deviceId = "mock-device",
            packageName = packageName,
            minutesRequested = minutesRequested,
            reason = reason,
            status = TimeRequestStatus.PENDING,
            createdAt = Instant.now().toString()
        )
        pendingRequests.add(request)
        request
    }

    suspend fun getPendingRequests(): List<TimeRequest> = withContext(Dispatchers.IO) {
        // Filtrar solo pendientes
        pendingRequests.filter { it.status == TimeRequestStatus.PENDING }
    }

    suspend fun recordUsage(packageName: String, minutes: Int) = withContext(Dispatchers.IO) {
        usageToday[packageName] = (usageToday[packageName] ?: 0) + minutes
        
        // En implementación real, guardar en usage_logs via Supabase
    }

    suspend fun getAppsUsage(): List<Pair<String, Int>> = withContext(Dispatchers.IO) {
        usageToday.toList()
    }

    suspend fun getParentMessage(): String? = withContext(Dispatchers.IO) {
        // En implementación real, verificar outbox o grants para mensajes del padre
        null
    }

    suspend fun sendHeartbeat(
        batteryLevel: Int?,
        isCharging: Boolean,
        appInForeground: String?
    ) = withContext(Dispatchers.IO) {
        // En implementación real, llamar a /functions/v1/heartbeat
        lastSync = Instant.now()
    }

    fun isPolicyStale(): Boolean {
        val last = lastSync ?: return true
        return ChronoUnit.MINUTES.between(last, Instant.now()) > 5
    }
}
