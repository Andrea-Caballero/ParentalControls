package com.tudominio.parentalcontrol.onboarding

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import com.tudominio.parentalcontrol.consent.ConsentManager
import com.tudominio.parentalcontrol.health.HealthChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manager para el onboarding con progreso real.
 * 
 * §0.2: El progreso refleja el estado real de T12.
 * §0.9: Onboarding reanudable con estado persistido.
 */
class OnboardingStateManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "OnboardingState"
        
        // Pasos del onboarding (ordenados por valor)
        val STEPS = listOf(
            OnboardingStep.PAIRING,
            OnboardingStep.CONSENT,
            OnboardingStep.ACCESSIBILITY,
            OnboardingStep.FIRST_WIN,
            OnboardingStep.OVERLAY,
            OnboardingStep.BATTERY,
            OnboardingStep.NOTIFICATIONS,
            OnboardingStep.DEVICE_ADMIN
        )
        
        // Pasos que cuentan para la barra de progreso (T12)
        val COUNTED_STEPS = setOf(
            OnboardingStep.ACCESSIBILITY,
            OnboardingStep.OVERLAY,
            OnboardingStep.BATTERY,
            OnboardingStep.NOTIFICATIONS,
            OnboardingStep.DEVICE_ADMIN
        )

        @Volatile
        private var instance: OnboardingStateManager? = null

        fun getInstance(context: Context): OnboardingStateManager {
            return instance ?: synchronized(this) {
                instance ?: OnboardingStateManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val prefs = context.getSharedPreferences("onboarding_prefs", Context.MODE_PRIVATE)
    private val consentManager = ConsentManager.getInstance(context)
    private val healthChecker = HealthChecker(context)

    // Estado
    private val _currentStep = MutableStateFlow(loadCurrentStep())
    val currentStep: StateFlow<OnboardingStep> = _currentStep.asStateFlow()

    private val _progress = MutableStateFlow(calculateProgress())
    val progress: StateFlow<OnboardingProgress> = _progress.asStateFlow()

    // Pasos completados
    private val _completedSteps = MutableStateFlow(loadCompletedSteps())
    val completedSteps: StateFlow<Set<OnboardingStep>> = _completedSteps.asStateFlow()

    /**
     * Obtiene el paso actual.
     */
    fun getCurrentStep(): OnboardingStep = _currentStep.value

    /**
     * Avanza al siguiente paso.
     */
    fun advanceToNextStep() {
        val current = _currentStep.value
        val nextIndex = STEPS.indexOf(current) + 1
        
        if (nextIndex < STEPS.size) {
            val next = STEPS[nextIndex]
            _currentStep.value = next
            saveCurrentStep(next)
            _progress.value = calculateProgress()
            
            // Emitir evento de embudo
            AnalyticsEmitter.emit(OnboardingAnalyticsEvent.StepReached(next))
        }
    }

    /**
     * Marca un paso como completado.
     */
    fun completeStep(step: OnboardingStep) {
        _completedSteps.value = _completedSteps.value + step
        saveCompletedSteps(_completedSteps.value)
        _progress.value = calculateProgress()
        
        // Emitir evento
        AnalyticsEmitter.emit(OnboardingAnalyticsEvent.StepCompleted(step))
    }

    /**
     * Completa el paso actual.
     */
    fun completeCurrentStep() {
        completeStep(_currentStep.value)
    }

    /**
     * Va a un paso específico.
     */
    fun goToStep(step: OnboardingStep) {
        _currentStep.value = step
        saveCurrentStep(step)
    }

    /**
     * Verifica si un paso está completado.
     */
    fun isStepCompleted(step: OnboardingStep): Boolean {
        return _completedSteps.value.contains(step)
    }

    /**
     * Obtiene el porcentaje de progreso.
     */
    fun getProgressPercent(): Int {
        return _progress.value.percentage
    }

    /**
     * Obtiene el progreso basado en el estado real de T12.
     * 
     * §0.2: El progreso refleja el estado real.
     */
    fun calculateProgress(): OnboardingProgress {
        val healthResult = healthChecker.checkHealth()
        
        // Contar solo los pasos que están realmente completados
        var completedCount = 0
        var totalCount = COUNTED_STEPS.size
        
        // Accessibility
        if (healthResult.isAccessibilityServiceEnabled) {
            completedCount++
        }
        
        // Overlay
        if (healthResult.isOverlayPermissionGranted) {
            completedCount++
        }
        
        // Battery
        if (healthResult.isBatteryOptimizationIgnored) {
            completedCount++
        }
        
        // Notifications (verificado por HealthChecker)
        if (healthResult.isUsageStatsPermissionGranted) {
            // Las notificaciones se verifican de otra forma
            completedCount++
        }
        
        // Device Admin
        if (healthResult.isDeviceAdminActive) {
            completedCount++
        }
        
        return OnboardingProgress(
            completed = completedCount,
            total = totalCount,
            percentage = (completedCount * 100) / totalCount
        )
    }

    /**
     * Refresca el progreso desde T12.
     */
    fun refreshProgressFromHealth() {
        _progress.value = calculateProgress()
    }

    /**
     * Obtiene el Intent para abrir el ajuste correcto.
     */
    fun getSettingsIntent(step: OnboardingStep): Intent {
        val ctx = context // Capturar contexto
        return when (step) {
            OnboardingStep.ACCESSIBILITY -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                } else {
                    Intent(Settings.ACTION_SETTINGS)
                }
            }
            
            OnboardingStep.OVERLAY -> {
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${ctx.packageName}")
                )
            }
            
            OnboardingStep.BATTERY -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                } else {
                    Intent(Settings.ACTION_SETTINGS)
                }
            }
            
            OnboardingStep.NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    }
                } else {
                    Intent(Settings.ACTION_SETTINGS)
                }
            }
            
            OnboardingStep.DEVICE_ADMIN -> {
                Intent(Settings.ACTION_SECURITY_SETTINGS)
            }
            
            else -> Intent(Settings.ACTION_SETTINGS)
        }
    }

    /**
     * Verifica si el paso actual está listo para completarse.
     */
    fun isCurrentStepReady(): Boolean {
        val healthResult = healthChecker.checkHealth()
        return when (_currentStep.value) {
            OnboardingStep.ACCESSIBILITY -> healthResult.isAccessibilityServiceEnabled
            OnboardingStep.OVERLAY -> healthResult.isOverlayPermissionGranted
            OnboardingStep.BATTERY -> healthResult.isBatteryOptimizationIgnored
            OnboardingStep.NOTIFICATIONS -> healthResult.isUsageStatsPermissionGranted
            OnboardingStep.DEVICE_ADMIN -> healthResult.isDeviceAdminActive
            else -> true
        }
    }

    /**
     * Verifica el estado al volver de un permiso.
     */
    fun verifyAndAdvanceIfReady() {
        if (isCurrentStepReady()) {
            completeCurrentStep()
            advanceToNextStep()
        }
    }

    /**
     * Completa todo el onboarding.
     */
    fun completeOnboarding() {
        AnalyticsEmitter.emit(OnboardingAnalyticsEvent.OnboardingCompleted)
        prefs.edit().putBoolean("onboarding_complete", true).apply()
    }

    /**
     * Resetea el onboarding.
     */
    fun resetOnboarding() {
        _currentStep.value = OnboardingStep.PAIRING
        _completedSteps.value = emptySet()
        prefs.edit().clear().apply()
        AnalyticsEmitter.emit(OnboardingAnalyticsEvent.OnboardingAbandoned)
    }

    /**
     * Verifica si el onboarding está completo.
     */
    fun isOnboardingComplete(): Boolean {
        return prefs.getBoolean("onboarding_complete", false)
    }

    // ============ Persistencia ============

    private fun loadCurrentStep(): OnboardingStep {
        val saved = prefs.getString("current_step", null)
        return saved?.let {
            try { OnboardingStep.valueOf(it) } catch (e: Exception) { OnboardingStep.PAIRING }
        } ?: OnboardingStep.PAIRING
    }

    private fun saveCurrentStep(step: OnboardingStep) {
        prefs.edit().putString("current_step", step.name).apply()
    }

    private fun loadCompletedSteps(): Set<OnboardingStep> {
        val saved = prefs.getStringSet("completed_steps", emptySet()) ?: emptySet()
        return saved.mapNotNull {
            try { OnboardingStep.valueOf(it) } catch (e: Exception) { null }
        }.toSet()
    }

    private fun saveCompletedSteps(steps: Set<OnboardingStep>) {
        prefs.edit().putStringSet("completed_steps", steps.map { it.name }.toSet()).apply()
    }
}

/**
 * Pasos del onboarding (ordenados por valor).
 */
enum class OnboardingStep {
    PAIRING,        // 1. Emparejar con padre
    CONSENT,        // 2. Consentimiento
    ACCESSIBILITY,   // 3. Permiso de accesibilidad (CARO)
    FIRST_WIN,      // 4. Primera victoria (overlay demo)
    OVERLAY,        // 5. Permiso overlay
    BATTERY,        // 6. Batería
    NOTIFICATIONS,  // 7. Notificaciones
    DEVICE_ADMIN    // 8. Device Admin
}

/**
 * Progreso del onboarding.
 */
data class OnboardingProgress(
    val completed: Int,
    val total: Int,
    val percentage: Int
) {
    val isComplete: Boolean get() = completed >= total
    val remaining: Int get() = total - completed
}

/**
 * Eventos de analytics para el onboarding.
 */
sealed class OnboardingAnalyticsEvent {
    data class StepReached(val step: OnboardingStep) : OnboardingAnalyticsEvent()
    data class StepCompleted(val step: OnboardingStep) : OnboardingAnalyticsEvent()
    data object FirstWin : OnboardingAnalyticsEvent()
    data object OnboardingCompleted : OnboardingAnalyticsEvent()
    data object OnboardingAbandoned : OnboardingAnalyticsEvent()
}

/**
 * Emisor de eventos de analytics.
 */
object AnalyticsEmitter {
    private val listeners = mutableListOf<(OnboardingAnalyticsEvent) -> Unit>()
    
    fun emit(event: OnboardingAnalyticsEvent) {
        Log.d("Analytics", "Event: $event")
        listeners.forEach { it(event) }
    }
    
    fun addListener(listener: (OnboardingAnalyticsEvent) -> Unit) {
        listeners.add(listener)
    }
    
    fun removeListener(listener: (OnboardingAnalyticsEvent) -> Unit) {
        listeners.remove(listener)
    }
}
