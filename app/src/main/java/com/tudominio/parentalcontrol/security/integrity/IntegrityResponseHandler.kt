package com.tudominio.parentalcontrol.security.integrity

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Manejador de respuestas de integridad.
 * 
 * Implementa la reacción a veredictos negativos sin falsos positivos catastróficos.
 * 
 * §0.9: La degradación es gradual, no catastrófica.
 */
class IntegrityResponseHandler private constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "IntegrityResponseHandler"
        
        // Número de fallos consecutivos antes de escalar
        private const val THRESHOLD_CONSECUTIVE_FAILURES = 3
        
        // Tiempo máximo de degradación
        private const val MAX_DEGRADATION_HOURS = 24

        @Volatile
        private var instance: IntegrityResponseHandler? = null

        fun getInstance(context: Context): IntegrityResponseHandler {
            return instance ?: synchronized(this) {
                instance ?: IntegrityResponseHandler(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private var consecutiveFailures = 0
    private var currentDegradationLevel = DegradationLevel.NONE
    private var degradationStartTime: Long = 0

    /**
     * Procesa el resultado de verificación de integridad.
     * 
     * @param result Resultado de la verificación
     * @return Nivel de acción recomendado
     */
    fun processResult(result: IntegrityVerificationResult): IntegrityAction {
        Log.d(TAG, "Procesando resultado de integridad: ${result::class.simpleName}")
        
        return when (result) {
            is IntegrityVerificationResult.Passed -> {
                handlePassed()
            }
            is IntegrityVerificationResult.Failed -> {
                handleFailed(result.reason)
            }
            is IntegrityVerificationResult.Uncertain -> {
                handleUncertain()
            }
            is IntegrityVerificationResult.Error -> {
                handleError(result.message)
            }
        }
    }

    /**
     * Maneja veredicto passed.
     * Resetear contadores y restaurar si es necesario.
     */
    private fun handlePassed(): IntegrityAction {
        Log.d(TAG, "Integridad verificada OK")
        
        // Resetear contador de fallos
        consecutiveFailures = 0
        
        // Restaurar si estaba degradado
        if (currentDegradationLevel != DegradationLevel.NONE) {
            Log.d(TAG, "Restaurando nivel de servicio")
            restore()
        }
        
        // Registrar verificación exitosa
        recordVerification(success = true)
        
        return IntegrityAction.LOG
    }

    /**
     * Maneja veredicto failed.
     * Implementar degradación gradual.
     */
    private fun handleFailed(reason: String): IntegrityAction {
        Log.w(TAG, "Integridad falló: $reason")
        
        consecutiveFailures++
        
        // Registrar fallo
        recordVerification(success = false, reason = reason)
        
        // Determinar acción según razón
        val action = when {
            reason.contains("app_integrity", ignoreCase = true) -> {
                // App alterada - acción más severa
                escalateDegradation(DegradationLevel.REDUCED_ENFORCEMENT)
                IntegrityAction.ALERT_AND_DEGRADE
            }
            reason.contains("device", ignoreCase = true) -> {
                // Problema de dispositivo - degradación moderada
                escalateDegradation(DegradationLevel.SOFT_LIMITS)
                IntegrityAction.ALERT_PARENT
            }
            reason.contains("play", ignoreCase = true) -> {
                // Problema con Play Services
                // No degradar, podría ser falso positivo
                IntegrityAction.LOG_ONLY
            }
            else -> {
                // Desconocido - tratar como severo pero con precaución
                escalateDegradation(DegradationLevel.WARNING_ONLY)
                IntegrityAction.ALERT_PARENT
            }
        }
        
        return action
    }

    /**
     * Maneja resultado incierto.
     * No degradar pero registrar.
     */
    private fun handleUncertain(): IntegrityAction {
        Log.w(TAG, "Resultado de integridad incierto")
        
        // Incrementar contador pero no degradar inmediatamente
        consecutiveFailures++
        
        // Registrar
        recordVerification(success = null)
        
        // Solo log o alerta menor
        return IntegrityAction.LOG_ONLY
    }

    /**
     * Maneja error de comunicación.
     * No asumir lo peor.
     */
    private fun handleError(message: String): IntegrityAction {
        Log.e(TAG, "Error de verificación: $message")
        
        // Registrar pero no degradar por error de red
        recordVerification(success = null, reason = message)
        
        return IntegrityAction.LOG_ONLY
    }

    /**
     * Escala el nivel de degradación.
     */
    private fun escalateDegradation(targetLevel: DegradationLevel) {
        // Solo escalar si es más severo
        if (targetLevel.ordinal > currentDegradationLevel.ordinal) {
            val previousLevel = currentDegradationLevel
            currentDegradationLevel = targetLevel
            degradationStartTime = System.currentTimeMillis()
            
            Log.w(TAG, "Degradando: $previousLevel -> $currentDegradationLevel")
            
            // Guardar en preferencias compartidas para persistencia
            saveDegradationState()
        }
    }

    /**
     * Restaura el nivel de servicio normal.
     */
    private fun restore() {
        currentDegradationLevel = DegradationLevel.NONE
        consecutiveFailures = 0
        degradationStartTime = 0
        clearDegradationState()
        
        Log.d(TAG, "Servicio restaurado")
    }

    /**
     * Verifica si debe auto-restaurarse tras timeout.
     */
    fun checkAutoRestore() {
        if (currentDegradationLevel == DegradationLevel.NONE) return
        
        val elapsedHours = (System.currentTimeMillis() - degradationStartTime) / (1000 * 60 * 60)
        
        if (elapsedHours >= MAX_DEGRADATION_HOURS) {
            Log.w(TAG, "Timeout de degradación, restaurando...")
            restore()
        }
    }

    /**
     * Obtiene el nivel de degradación actual.
     */
    fun getCurrentDegradationLevel(): DegradationLevel = currentDegradationLevel

    /**
     * Registra la verificación en la base de datos.
     */
    private fun recordVerification(success: Boolean?, reason: String? = null) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Guardar en SharedPreferences para simplicity
                // En producción, esto debería ir a la base de datos
                val prefs = context.getSharedPreferences("integrity_log", Context.MODE_PRIVATE)
                val logEntry = """
                    {
                        "success": $success,
                        "reason": ${reason?.let { "\"$it\"" } ?: "null"},
                        "degradation_level": "${currentDegradationLevel.name}",
                        "timestamp": ${System.currentTimeMillis()}
                    }
                """.trimIndent()
                
                // Append al log
                val existingLog = prefs.getString("verification_log", "[]") ?: "[]"
                prefs.edit().putString("verification_log", existingLog + "," + logEntry).apply()
                
                Log.d(TAG, "Verificación registrada: $logEntry")
            } catch (e: Exception) {
                Log.e(TAG, "Error registrando verificación: ${e.message}")
            }
        }
    }

    /**
     * Guarda el estado de degradación.
     */
    private fun saveDegradationState() {
        try {
            val prefs = context.getSharedPreferences("integrity_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("degradation_level", currentDegradationLevel.name)
                .putLong("degradation_start", degradationStartTime)
                .putInt("consecutive_failures", consecutiveFailures)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error guardando estado: ${e.message}")
        }
    }

    /**
     * Limpia el estado de degradación.
     */
    private fun clearDegradationState() {
        try {
            val prefs = context.getSharedPreferences("integrity_prefs", Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error limpiando estado: ${e.message}")
        }
    }

    /**
     * Restaura el estado desde preferencias.
     */
    fun restoreState() {
        try {
            val prefs = context.getSharedPreferences("integrity_prefs", Context.MODE_PRIVATE)
            val level = prefs.getString("degradation_level", null)
            if (level != null) {
                currentDegradationLevel = DegradationLevel.valueOf(level)
                degradationStartTime = prefs.getLong("degradation_start", 0)
                consecutiveFailures = prefs.getInt("consecutive_failures", 0)
                Log.d(TAG, "Estado restaurado: $currentDegradationLevel")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error restaurando estado: ${e.message}")
        }
    }
}

/**
 * Niveles de degradación de servicio.
 */
enum class DegradationLevel {
    /** Servicio normal, sin degradación */
    NONE,
    
    /** Solo registro/log, sin degradación visible */
    WARNING_ONLY,
    
    /** Límites blandos: advertencias en UI, sin bloqueo */
    SOFT_LIMITS,
    
    /** Enforcement reducido: menos restricciones */
    REDUCED_ENFORCEMENT,
    
    /** Bloqueo total del agente */
    FULL_LOCK
}

/**
 * Acciones de integridad.
 */
enum class IntegrityAction {
    /** Solo log, sin acción adicional */
    LOG,
    
    /** Log únicamente */
    LOG_ONLY,
    
    /** Alertar al padre */
    ALERT_PARENT,
    
    /** Alertar y degradar servicio */
    ALERT_AND_DEGRADE
}
