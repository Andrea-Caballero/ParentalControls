package com.tudominio.parentalcontrol.ui.repair

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tudominio.parentalcontrol.health.HealthChecker
import com.tudominio.parentalcontrol.health.HealthCheckResult
import com.tudominio.parentalcontrol.health.Permission
import com.tudominio.parentalcontrol.health.Recommendation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel para la pantalla de reparar.
 * 
 * Detecta permisos degradados y guía al usuario para修复los.
 */
class RepairViewModel(
    private val context: Context
) : ViewModel() {

    companion object {
        private const val TAG = "RepairViewModel"
        
        // Refresh interval when observing (5 seconds)
        private const val REFRESH_INTERVAL_MS = 5000L

        fun factory(context: Context): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return RepairViewModel(context) as T
                }
            }
        }
    }

    private val healthChecker = HealthChecker(context)

    // Estado de la UI
    private val _uiState = MutableStateFlow<RepairUiState>(RepairUiState.Loading)
    val uiState: StateFlow<RepairUiState> = _uiState.asStateFlow()

    // Problemas detectados
    private val _issues = MutableStateFlow<List<RepairIssue>>(emptyList())
    val issues: StateFlow<List<RepairIssue>> = _issues.asStateFlow()

    // Progreso de reparación
    private val _progress = MutableStateFlow(RepairProgress())
    val progress: StateFlow<RepairProgress> = _progress.asStateFlow()

    // Si está completamente reparado
    private val _isFullyRepaired = MutableStateFlow(false)
    val isFullyRepaired: StateFlow<Boolean> = _isFullyRepaired.asStateFlow()

    // Refresh job
    private var refreshJob: kotlinx.coroutines.Job? = null

    init {
        startObserving()
    }

    /**
     * Inicia la observación del estado de salud.
     */
    private fun startObserving() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (true) {
                checkHealth()
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    /**
     * Detiene la observación.
     */
    fun stopObserving() {
        refreshJob?.cancel()
    }

    /**
     * Verifica el estado de salud y actualiza la UI.
     */
    fun checkHealth() {
        viewModelScope.launch {
            try {
                val result = healthChecker.checkHealth()
                val detectedIssues = detectIssues(result)
                
                _issues.value = detectedIssues
                _isFullyRepaired.value = detectedIssues.isEmpty()
                _progress.value = calculateProgress(result, detectedIssues)
                
                if (detectedIssues.isEmpty()) {
                    _uiState.value = RepairUiState.Repaired
                } else {
                    _uiState.value = RepairUiState.HasIssues(
                        criticalCount = detectedIssues.count { it.severity == IssueSeverity.CRITICAL },
                        totalCount = detectedIssues.size
                    )
                }
                
                Log.d(TAG, "Health check: ${detectedIssues.size} issues, fullyRepaired=${detectedIssues.isEmpty()}")
            } catch (e: Exception) {
                Log.e(TAG, "Error checking health: ${e.message}")
                _uiState.value = RepairUiState.Error(e.message ?: "Error checking health")
            }
        }
    }

    /**
     * Detecta problemas basado en el resultado de HealthChecker.
     */
    private fun detectIssues(result: HealthCheckResult): List<RepairIssue> {
        val issues = mutableListOf<RepairIssue>()
        
        // Accessibility
        if (!result.isAccessibilityServiceEnabled) {
            issues.add(
                RepairIssue(
                    type = IssueType.ACCESSIBILITY_SERVICE,
                    title = "Servicio de Accesibilidad",
                    description = "Activa el servicio para que el control parental funcione correctamente.",
                    severity = IssueSeverity.CRITICAL,
                    recommendation = Recommendation.ENABLE_ACCESSIBILITY,
                    deepLinkIntent = getDeepLinkIntent(IssueType.ACCESSIBILITY_SERVICE)
                )
            )
        }
        
        // Overlay
        if (!result.isOverlayPermissionGranted) {
            issues.add(
                RepairIssue(
                    type = IssueType.OVERLAY_PERMISSION,
                    title = "Permiso de Overlay",
                    description = "Permite mostrar mensajes cuando una app está bloqueada.",
                    severity = IssueSeverity.CRITICAL,
                    recommendation = Recommendation.GRANT_OVERLAY,
                    deepLinkIntent = getDeepLinkIntent(IssueType.OVERLAY_PERMISSION)
                )
            )
        }
        
        // Usage Stats
        if (!result.isUsageStatsPermissionGranted) {
            issues.add(
                RepairIssue(
                    type = IssueType.USAGE_STATS,
                    title = "Acceso a Uso de Apps",
                    description = "Permite monitorear cuánto tiempo usas cada app.",
                    severity = IssueSeverity.CRITICAL,
                    recommendation = Recommendation.GRANT_USAGE_STATS,
                    deepLinkIntent = getDeepLinkIntent(IssueType.USAGE_STATS)
                )
            )
        }
        
        // Battery Optimization
        if (!result.isBatteryOptimizationIgnored) {
            issues.add(
                RepairIssue(
                    type = IssueType.BATTERY_OPTIMIZATION,
                    title = "Optimización de Batería",
                    description = "Desactiva la optimización para que el control funcione sin interrupciones.",
                    severity = IssueSeverity.WARNING,
                    recommendation = Recommendation.IGNORE_BATTERY_OPT,
                    deepLinkIntent = getDeepLinkIntent(IssueType.BATTERY_OPTIMIZATION)
                )
            )
        }
        
        // Device Admin
        if (!result.isDeviceAdminActive) {
            issues.add(
                RepairIssue(
                    type = IssueType.DEVICE_ADMIN,
                    title = "Administrador del Dispositivo",
                    description = "Permite bloquear el dispositivo en momentos de descanso.",
                    severity = IssueSeverity.WARNING,
                    recommendation = Recommendation.ENABLE_DEVICE_ADMIN,
                    deepLinkIntent = getDeepLinkIntent(IssueType.DEVICE_ADMIN)
                )
            )
        }
        
        return issues
    }

    /**
     * Obtiene el Intent de deep link para abrir el ajuste correcto.
     */
    private fun getDeepLinkIntent(type: IssueType): Intent {
        return when (type) {
            IssueType.ACCESSIBILITY_SERVICE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                } else {
                    Intent(Settings.ACTION_SETTINGS)
                }
            }
            
            IssueType.OVERLAY_PERMISSION -> {
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}")
                )
            }
            
            IssueType.USAGE_STATS -> {
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            }
            
            IssueType.BATTERY_OPTIMIZATION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                } else {
                    Intent(Settings.ACTION_SETTINGS)
                }
            }
            
            IssueType.DEVICE_ADMIN -> {
                Intent(Settings.ACTION_SECURITY_SETTINGS)
            }
        }
    }

    /**
     * Calcula el progreso de reparación.
     */
    private fun calculateProgress(result: HealthCheckResult, issues: List<RepairIssue>): RepairProgress {
        val totalChecks = 5
        val passedChecks = listOf(
            result.isAccessibilityServiceEnabled,
            result.isOverlayPermissionGranted,
            result.isUsageStatsPermissionGranted,
            result.isBatteryOptimizationIgnored,
            result.isDeviceAdminActive
        ).count { it }
        
        return RepairProgress(
            passed = passedChecks,
            total = totalChecks,
            percentage = (passedChecks * 100) / totalChecks
        )
    }

    /**
     * Resuelve un issue (llamado después de que el usuario regresa de ajustes).
     */
    fun resolveIssue(issue: RepairIssue) {
        viewModelScope.launch {
            // Re-check health after user returns from settings
            checkHealth()
        }
    }

    /**
     * Resuelve todos los issues críticos.
     */
    fun resolveAllCritical() {
        viewModelScope.launch {
            // Navigate to first critical issue
            val critical = _issues.value.firstOrNull { it.severity == IssueSeverity.CRITICAL }
            critical?.let {
                // This would be handled by the UI
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopObserving()
    }
}

/**
 * Estados de la UI de reparación.
 */
sealed class RepairUiState {
    data object Loading : RepairUiState()
    
    data class HasIssues(
        val criticalCount: Int,
        val totalCount: Int
    ) : RepairUiState()
    
    data object Repaired : RepairUiState()
    
    data class Error(val message: String) : RepairUiState()
}

/**
 * Tipo de issue de reparación.
 */
enum class IssueType {
    ACCESSIBILITY_SERVICE,
    OVERLAY_PERMISSION,
    USAGE_STATS,
    BATTERY_OPTIMIZATION,
    DEVICE_ADMIN
}

/**
 * Severidad del issue.
 */
enum class IssueSeverity {
    CRITICAL,  // Bloquea funcionalidad
    WARNING     // Afecta rendimiento
}

/**
 * Issue de reparación.
 */
data class RepairIssue(
    val type: IssueType,
    val title: String,
    val description: String,
    val severity: IssueSeverity,
    val recommendation: Recommendation,
    val deepLinkIntent: Intent
)

/**
 * Progreso de reparación.
 */
data class RepairProgress(
    val passed: Int = 0,
    val total: Int = 5,
    val percentage: Int = 0
)
