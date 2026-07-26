package com.tudominio.parentalcontrol.health

import android.content.Context
import android.util.Log
import com.tudominio.parentalcontrol.analytics.AnalyticsManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Singleton
class DegradationAlertManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val analyticsManager: AnalyticsManager
) {
    companion object {
        private const val TAG = "DegradationAlertMgr"

        private const val COOLDOWN_MS = 60 * 60 * 1000L
        private const val RECOVERY_COOLDOWN_MS = 30 * 60 * 1000L

        /**
         * Convenience accessor for non-Hilt call sites. Production code
         * inside `@AndroidEntryPoint` / `@HiltViewModel` should inject the
         * manager directly via `@Inject DegradationAlertManager`.
         */
        fun getInstance(context: Context): DegradationAlertManager {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                DegradationAlertManagerEntryPoint::class.java
            )
            return entryPoint.degradationAlertManager()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    data class ActiveAlert(
        val id: String,
        val causes: List<DegradationCause>,
        val shownAt: Long,
        val lastShownAt: Long = shownAt
    )

    data class DegradationCause(
        val issueType: String,
        val permission: Permission,
        val displayTitle: String,
        val displayDescription: String
    )

    private val _activeAlert = MutableStateFlow<ActiveAlert?>(null)
    val activeAlert: StateFlow<ActiveAlert?> = _activeAlert.asStateFlow()

    private val _isShowingRecovery = MutableStateFlow(false)
    val isShowingRecovery: StateFlow<Boolean> = _isShowingRecovery.asStateFlow()

    private var lastAlertTime = 0L
    private var lastRecoveryTime = 0L
    private var suppressedUntil = 0L

    fun shouldShowAlert(): Boolean {
        val now = System.currentTimeMillis()
        if (now < suppressedUntil) {
            Log.d(TAG, "Alert suppressed until $suppressedUntil")
            return false
        }
        if (now - lastAlertTime < COOLDOWN_MS) {
            Log.d(TAG, "Alert in cooldown period")
            return false
        }
        return true
    }

    fun showAlert(causes: List<DegradationCause>) {
        val now = System.currentTimeMillis()
        if (!shouldShowAlert() && causes.isNotEmpty()) {
            return
        }

        val alertId = "degraded_$now"
        val activeAlert = ActiveAlert(
            id = alertId,
            causes = causes,
            shownAt = now
        )
        _activeAlert.value = activeAlert
        lastAlertTime = now

        val reasonStrings = causes.map { it.issueType }
        analyticsManager.trackDegradedAlertShown(reasonStrings)

        Log.d(TAG, "Alert shown for causes: $reasonStrings")
    }

    fun onRepairTapped(issueType: String) {
        analyticsManager.trackRepairTapped(issueType)
        Log.d(TAG, "Repair tapped for: $issueType")
    }

    fun onProtectionRestored(issueType: String) {
        analyticsManager.trackProtectionRestored(issueType)
        Log.d(TAG, "Protection restored for: $issueType")

        _activeAlert.value = null
        _isShowingRecovery.value = true

        val now = System.currentTimeMillis()
        suppressedUntil = now + RECOVERY_COOLDOWN_MS
        lastRecoveryTime = now

        scope.launch {
            delay(3000)
            _isShowingRecovery.value = false
        }
    }

    fun checkAndSuppressRepeat(now: Long = System.currentTimeMillis()) {
        if (_activeAlert.value != null && now - lastAlertTime > COOLDOWN_MS) {
            Log.d(TAG, "Clearing stale alert after cooldown")
            _activeAlert.value = null
        }
    }

    fun dismissAlert() {
        _activeAlert.value = null
    }

    fun getCauseForPermission(permission: Permission): DegradationCause {
        return when (permission) {
            Permission.ACCESSIBILITY_SERVICE -> DegradationCause(
                issueType = "accessibility_off",
                permission = Permission.ACCESSIBILITY_SERVICE,
                displayTitle = "Servicio de Accesibilidad",
                displayDescription = "El servicio no está activo. Sin él, no se puede controlar el acceso a apps."
            )
            Permission.OVERLAY_PERMISSION -> DegradationCause(
                issueType = "overlay_revoked",
                permission = Permission.OVERLAY_PERMISSION,
                displayTitle = "Permiso de Overlay",
                displayDescription = "No se pueden mostrar mensajes de bloqueo sin este permiso."
            )
            Permission.USAGE_STATS -> DegradationCause(
                issueType = "usage_stats_off",
                permission = Permission.USAGE_STATS,
                displayTitle = "Acceso a Uso de Apps",
                displayDescription = "Necesario para saber cuánto tiempo usas cada app."
            )
            Permission.BATTERY_OPTIMIZATION -> DegradationCause(
                issueType = "battery_optimized",
                permission = Permission.BATTERY_OPTIMIZATION,
                displayTitle = "Optimización de Batería",
                displayDescription = "Batería optimizada. Puede causar que el control no funcione."
            )
            Permission.DEVICE_ADMIN -> DegradationCause(
                issueType = "device_admin_inactive",
                permission = Permission.DEVICE_ADMIN,
                displayTitle = "Administrador del Dispositivo",
                displayDescription = "No está activo. Sin esto, no se puede bloquear el dispositivo remotamente."
            )
        }
    }
}

/**
 * Hilt [EntryPoint] that exposes [DegradationAlertManager] from the
 * `SingletonComponent` to non-Hilt call sites.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DegradationAlertManagerEntryPoint {
    fun degradationAlertManager(): DegradationAlertManager
}
