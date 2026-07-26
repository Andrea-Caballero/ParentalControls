package com.tudominio.parentalcontrol.security

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.data.model.OutboxEntity
import com.tudominio.parentalcontrol.time.TimeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

/**
 * Detector de intentos de manipulación y evasión.
 * Implementa T13 - Anti-tamper STANDARD.
 */
class TamperDetector(
    private val context: Context,
    private val database: ParentalDatabase,
    private val timeProvider: TimeProvider
) {
    companion object {
        // Nombres de eventos para la outbox
        const val EVENT_ACCESSIBILITY_OFF = "accessibility_off_detected"
        const val EVENT_UNINSTALL_ATTEMPT = "uninstall_attempt"
        const val EVENT_CLOCK_TAMPER = "clock_tamper_suspected"
        const val EVENT_TIMEZONE_CHANGE = "timezone_changed"
        
        private const val DEDUP_PREFIX = "tamper_"

        @Volatile
        private var instance: TamperDetector? = null

        fun getInstance(
            context: Context,
            database: ParentalDatabase,
            timeProvider: TimeProvider
        ): TamperDetector {
            return instance ?: synchronized(this) {
                instance ?: TamperDetector(context, database, timeProvider).also {
                    instance = it
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private val _tamperEvents = MutableStateFlow<List<TamperEvent>>(emptyList())
    val tamperEvents: StateFlow<List<TamperEvent>> = _tamperEvents.asStateFlow()

    // Baseline para detección de manipulación de reloj
    private var lastClockSkew: TimeProvider.ClockSkewSignal? = null
    private var lastZoneId: java.time.ZoneId? = null

    init {
        lastZoneId = timeProvider.currentZoneId()
    }

    /**
     * Registra un intento de desactivar accesibilidad.
     */
    fun onAccessibilityServiceAttemptedDisable() {
        val event = TamperEvent(
            type = TamperEvent.Type.ACCESSIBILITY_DISABLE_ATTEMPT,
            timestamp = Instant.now(),
            serverDate = timeProvider.currentDate().toString(),
            details = mapOf(
                "zone" to timeProvider.currentZoneId().id,
                "clock_skew" to (lastClockSkew?.type?.name ?: "none")
            )
        )
        
        scope.launch {
            enqueueEvent(event)
        }
    }

    /**
     * Registra un intento de desinstalar la app.
     */
    fun onUninstallAttempt(packageName: String) {
        val event = TamperEvent(
            type = TamperEvent.Type.UNINSTALL_ATTEMPT,
            timestamp = Instant.now(),
            serverDate = timeProvider.currentDate().toString(),
            details = mapOf(
                "suspect_package" to packageName
            )
        )
        
        scope.launch {
            enqueueEvent(event)
        }
    }

    /**
     * Detecta y registra manipulación de reloj.
     */
    fun onClockSkewDetected(signal: TimeProvider.ClockSkewSignal) {
        lastClockSkew = signal
        
        val event = TamperEvent(
            type = TamperEvent.Type.CLOCK_TAMPER,
            timestamp = Instant.now(),
            serverDate = timeProvider.currentDate().toString(),
            details = mapOf(
                "skew_type" to signal.type.name,
                "wall_before" to signal.wallTimeBefore.toString(),
                "wall_after" to signal.wallTimeAfter.toString(),
                "monotonic_delta" to signal.monotonicDelta.toString()
            )
        )
        
        scope.launch {
            enqueueEvent(event)
        }
    }

    /**
     * Detecta y registra cambio de zona horaria.
     */
    fun onTimezoneChanged(newZone: java.time.ZoneId, oldZone: java.time.ZoneId) {
        lastZoneId = newZone
        
        val event = TamperEvent(
            type = TamperEvent.Type.TIMEZONE_CHANGE,
            timestamp = Instant.now(),
            serverDate = timeProvider.currentDate().toString(),
            details = mapOf(
                "old_zone" to oldZone.id,
                "new_zone" to newZone.id
            )
        )
        
        scope.launch {
            enqueueEvent(event)
        }
    }

    /**
     * Verifica si el reloj fue manipulado.
     */
    fun checkForClockManipulation(): TimeProvider.ClockSkewSignal? {
        val signal = timeProvider.detectClockSkew()
        signal?.let { onClockSkewDetected(it) }
        return signal
    }

    /**
     * Verifica si la zona horaria cambió.
     */
    fun checkForTimezoneChange(): Boolean {
        val currentZone = timeProvider.currentZoneId()
        val changed = currentZone != lastZoneId
        
        if (changed) {
            onTimezoneChanged(currentZone, lastZoneId!!)
        }
        
        return changed
    }

    /**
     * Encola un evento a la outbox (T03).
     */
    private suspend fun enqueueEvent(event: TamperEvent) {
        // Agregar a estado local
        val currentEvents = _tamperEvents.value.toMutableList()
        currentEvents.add(event)
        _tamperEvents.value = currentEvents

        // Crear dedup key
        val dedupKey = "$DEDUP_PREFIX${event.type.name.lowercase()}_${event.timestamp.epochSecond}"

        // Verificar si ya existe (evitar duplicados)
        val existing = database.outboxDao().findByDedupKey(dedupKey)
        if (existing != null) {
            return
        }

        // Encolar a outbox
        val outboxRecord = OutboxEntity(
            tipo = event.type.eventName,
            payload_json = event.toJson(),
            dedup_key = dedupKey,
            created_at = event.timestamp.toString(),
            server_date = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
        )

        database.outboxDao().insertOutboxItem(outboxRecord)
    }

    /**
     * Obtiene el estado actual de sospechas.
     */
    fun getSuspicionLevel(): SuspicionLevel {
        val events = _tamperEvents.value
        val recentEvents = events.filter { 
            it.timestamp.isAfter(Instant.now().minusSeconds(3600)) // Última hora
        }

        return when {
            recentEvents.any { it.type == TamperEvent.Type.CLOCK_TAMPER } -> SuspicionLevel.HIGH
            recentEvents.any { it.type == TamperEvent.Type.TIMEZONE_CHANGE } -> SuspicionLevel.MEDIUM
            recentEvents.any { it.type == TamperEvent.Type.ACCESSIBILITY_DISABLE_ATTEMPT } -> SuspicionLevel.HIGH
            recentEvents.isNotEmpty() -> SuspicionLevel.LOW
            else -> SuspicionLevel.NONE
        }
    }

    /**
     * Limpia eventos antiguos.
     */
    fun clearOldEvents(olderThanHours: Int = 24) {
        val cutoff = Instant.now().minusSeconds(olderThanHours.toLong() * 3600)
        val filtered = _tamperEvents.value.filter { it.timestamp.isAfter(cutoff) }
        _tamperEvents.value = filtered
    }
}

/**
 * Evento de manipulación detectado.
 */
data class TamperEvent(
    val type: Type,
    val timestamp: Instant,
    val serverDate: String,
    val details: Map<String, String>
) {
    enum class Type(val eventName: String) {
        ACCESSIBILITY_DISABLE_ATTEMPT("accessibility_off_detected"),
        UNINSTALL_ATTEMPT("uninstall_attempt"),
        CLOCK_TAMPER("clock_tamper_suspected"),
        TIMEZONE_CHANGE("timezone_changed")
    }

    fun toJson(): String {
        return buildString {
            append("{")
            append("\"type\":\"${type.eventName}\",")
            append("\"timestamp\":\"${timestamp}\",")
            append("\"server_date\":\"$serverDate\",")
            append("\"details\":{")
            append(details.entries.mapIndexed { index, (key, value) ->
                "\"$key\":\"$value\""
            }.joinToString(","))
            append("}}")
        }
    }
}

/**
 * Nivel de sospecha.
 */
enum class SuspicionLevel {
    NONE,
    LOW,
    MEDIUM,
    HIGH
}
