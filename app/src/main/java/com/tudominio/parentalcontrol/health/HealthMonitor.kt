package com.tudominio.parentalcontrol.health

import android.content.Context
import androidx.work.*
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.data.model.OutboxEntity
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Monitor de salud del sistema.
 * Verifica periódicamente el estado de los permisos y encola alertas.
 *
 * `database` is Hilt-injected (PR 4 of `align-with-guia-fedora44`). Use
 * the [HealthMonitorEntryPoint] bridge only for non-Hilt call sites.
 */
@Singleton
class HealthMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: ParentalDatabase
) {

    companion object {
        private const val WORK_NAME = "health_check_work"
        private const val PERIODIC_INTERVAL_MINUTES = 15L

        /**
         * Convenience accessor for non-Hilt call sites. Production code
         * inside `@AndroidEntryPoint` / `@HiltViewModel` should inject the
         * monitor directly via `@Inject HealthMonitor`.
         */
        fun getInstance(context: Context): HealthMonitor {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                HealthMonitorEntryPoint::class.java
            )
            return entryPoint.healthMonitor()
        }
    }

    private val healthChecker = HealthChecker(context)

    private val _healthState = MutableStateFlow<HealthCheckResult?>(null)
    val healthState: StateFlow<HealthCheckResult?> = _healthState.asStateFlow()

    private var previousLevel: EnforcementLevel? = null

    /**
     * Programa verificaciones periódicas de salud.
     */
    fun schedulePeriodicHealthChecks() {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        val healthCheckRequest = PeriodicWorkRequestBuilder<HealthCheckWorker>(
            PERIODIC_INTERVAL_MINUTES, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .addTag("health-check")
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                healthCheckRequest
            )
    }

    /**
     * Realiza una verificación inmediata.
     */
    suspend fun performHealthCheck(): HealthCheckResult {
        val result = healthChecker.checkHealth()
        _healthState.value = result

        // Detectar cambios de estado
        if (previousLevel != null && previousLevel != result.enforcementLevel) {
            onEnforcementLevelChanged(previousLevel!!, result.enforcementLevel, result)
        }

        previousLevel = result.enforcementLevel

        return result
    }

    /**
     * Maneja cambios en el nivel de enforcement.
     */
    private suspend fun onEnforcementLevelChanged(
        oldLevel: EnforcementLevel,
        newLevel: EnforcementLevel,
        result: HealthCheckResult
    ) {
        when {
            // Degradando
            oldLevel != EnforcementLevel.DEGRADED && newLevel == EnforcementLevel.DEGRADED -> {
                enqueueDegradationAlert(result)
            }
            // Recuperando
            oldLevel == EnforcementLevel.DEGRADED && newLevel != EnforcementLevel.DEGRADED -> {
                enqueueRecoveryAlert(result)
            }
            // Obteniendo Device Owner
            newLevel == EnforcementLevel.DEVICE_OWNER -> {
                enqueueDeviceOwnerAlert()
            }
        }
    }

    /**
     * Encola una alerta de degradación.
     */
    private suspend fun enqueueDegradationAlert(result: HealthCheckResult) {
        val alertType = "health_degradation"
        val missingPerms = result.missingPermissions.joinToString(", ") { it.name }

        val alert = OutboxEntity(
            id = UUID.randomUUID(),
            tipo = alertType,
            payload_json = """
                {
                    "level": "${result.enforcementLevel}",
                    "missing_permissions": "$missingPerms",
                    "message": "La protección está degradada. Faltan: $missingPerms",
                    "recommendations": ${result.recommendations.map { it.name }}
                }
            """.trimIndent(),
            dedup_key = "health_degradation_${System.currentTimeMillis() / 86400000}",
            created_at = java.time.Instant.now().toString(),
            server_date = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
        )

        database.outboxDao().insertOutboxItem(alert)
    }

    /**
     * Encola una alerta de recuperación.
     */
    private suspend fun enqueueRecoveryAlert(result: HealthCheckResult) {
        val alert = OutboxEntity(
            id = UUID.randomUUID(),
            tipo = "health_recovery",
            payload_json = """
                {
                    "level": "${result.enforcementLevel}",
                    "message": "La protección ha sido restaurada a ${result.enforcementLevel}",
                    "timestamp": "${java.time.Instant.now()}"
                }
            """.trimIndent(),
            dedup_key = "health_recovery_${System.currentTimeMillis() / 86400000}",
            created_at = java.time.Instant.now().toString(),
            server_date = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
        )

        database.outboxDao().insertOutboxItem(alert)
    }

    /**
     * Encola una alerta de Device Owner.
     */
    private suspend fun enqueueDeviceOwnerAlert() {
        val alert = OutboxEntity(
            tipo = "device_owner_enabled",
            payload_json = """
                {
                    "level": "DEVICE_OWNER",
                    "message": "El dispositivo ahora tiene Device Owner activo",
                    "timestamp": "${java.time.Instant.now()}"
                }
            """.trimIndent(),
            dedup_key = "device_owner_${System.currentTimeMillis() / 86400000}",
            created_at = java.time.Instant.now().toString(),
            server_date = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
        )

        database.outboxDao().insertOutboxItem(alert)
    }

    /**
     * Obtiene el estado actual de salud.
     */
    fun getCurrentHealth(): HealthCheckResult? = _healthState.value
}

/**
 * Worker para verificaciones periódicas de salud.
 */
class HealthCheckWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val monitor = HealthMonitor.getInstance(applicationContext)
            val result = monitor.performHealthCheck()

            // Si está degradado, asegurar que se encole la alerta
            if (result.isDegraded) {
                Result.success()
            } else {
                Result.success()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

/**
 * Hilt [EntryPoint] that exposes [HealthMonitor] from the
 * `SingletonComponent` to non-Hilt call sites.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface HealthMonitorEntryPoint {
    fun healthMonitor(): HealthMonitor
}
