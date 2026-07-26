package com.tudominio.parentalcontrol.security

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import com.tudominio.parentalcontrol.accessibility.AppMonitorService
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.time.DefaultTimeProvider
import com.tudominio.parentalcontrol.time.TimeProvider
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Servicio anti-evasión que monitorea intentos de manipulación.
 * Se integra con el servicio de accesibilidad para detectar eventos.
 */
@AndroidEntryPoint
class AntiEvasionService : AccessibilityService() {

    companion object {
        private var instance: AntiEvasionService? = null

        fun isActive(): Boolean = instance != null

        fun getInstance(): AntiEvasionService? = instance
    }

    private lateinit var tamperDetector: TamperDetector
    private lateinit var timeProvider: TimeProvider
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Injected by Hilt before onCreate() (see @AndroidEntryPoint).
    @Inject lateinit var database: ParentalDatabase

    // Package del sistema que no deben generar alertas
    private val safePackages = setOf(
        "com.android.launcher",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.google.android.inputmethod.latin",
        packageName // Nuestra propia app
    )

    // Rutas de intent que indican intentos de manipulate
    private val settingsPackage = "com.android.settings"
    private val suspiciousIntents = listOf(
        "android.settings.ACCESSIBILITY_SETTINGS",
        "android.settings.APPLICATION_SETTINGS",
        "android.settings.MANAGE_APPLICATIONS_SETTINGS",
        "android.app.admin.DEVICE_ADMIN_SETTINGS"
    )

    override fun onCreate() {
        super.onCreate()
        instance = this

        timeProvider = DefaultTimeProvider(this)
        tamperDetector = TamperDetector.getInstance(this, database, timeProvider)

        // Iniciar monitoreo periódico
        startPeriodicMonitoring()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT
            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChanged(event)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                handleContentChanged(event)
            }
        }
    }

    override fun onInterrupt() {
        // No hacer nada en interrupt
    }

    /**
     * Maneja cambios de estado de ventana.
     */
    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        // Ignorar paquetes seguros
        if (packageName in safePackages) return

        // Detectar apertura de Ajustes del sistema
        if (packageName == settingsPackage) {
            checkForSuspiciousSettings()
        }

        // Detectar intento de desinstalar nuestra app
        if (packageName == "com.android.packageinstaller" ||
            packageName == "com.google.android.packageinstaller"
        ) {
            checkForUninstallAttempt(event)
        }
    }

    /**
     * Maneja cambios de contenido.
     */
    private fun handleContentChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        // Detectar texto relacionado con desinstalar
        val content = event.text?.joinToString(" ") ?: return

        if (content.contains("Desinstalar", ignoreCase = true) ||
            content.contains("Uninstall", ignoreCase = true)
        ) {
            tamperDetector.onUninstallAttempt(packageName)
        }
    }

    /**
     * Verifica si se están abriendo ajustes sospechosos.
     */
    private fun checkForSuspiciousSettings() {
        // Obtener la actividad actual
        val currentPackage = AppMonitorService.appInForeground.value

        // Si viene de nuestra app, podría ser un intento de evadir
        // (aunque en realidad el usuario tiene derecho a abrir ajustes)
        // El tamper se detecta cuando intenta cambiar la accesibilidad
    }

    /**
     * Detecta intentos de desinstalación.
     */
    private fun checkForUninstallAttempt(event: AccessibilityEvent) {
        // Verificar si nuestra app está siendo desinstalada
        val content = event.text?.joinToString(" ") ?: ""

        if (content.contains("Control Parental", ignoreCase = true) ||
            content.contains(packageName, ignoreCase = false)
        ) {
            tamperDetector.onUninstallAttempt(packageName)
        }
    }

    /**
     * Inicia monitoreo periódico de manipulación.
     */
    private fun startPeriodicMonitoring() {
        serviceScope.launch {
            while (isActive) {
                delay(30_000) // Cada 30 segundos

                // Verificar manipulación de reloj
                tamperDetector.checkForClockManipulation()

                // Verificar cambio de zona horaria
                tamperDetector.checkForTimezoneChange()
            }
        }
    }

    /**
     * Reporta que se intentó desactivar la accesibilidad.
     */
    fun reportAccessibilityDisableAttempt() {
        tamperDetector.onAccessibilityServiceAttemptedDisable()
    }

    /**
     * Obtiene el nivel de sospecha actual.
     */
    fun getSuspicionLevel(): SuspicionLevel {
        return tamperDetector.getSuspicionLevel()
    }
}
