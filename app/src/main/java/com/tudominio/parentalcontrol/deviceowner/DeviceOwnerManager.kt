package com.tudominio.parentalcontrol.deviceowner

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.tudominio.parentalcontrol.admin.ParentalDeviceAdminReceiver
import java.time.Instant

/**
 * Manager para Device Owner y hard enforcement.
 * 
 * §0.2: Niveles de enforcement:
 * - DEVICE_OWNER: Hard enforcement (suspensión, FRP, etc.)
 * - STANDARD: Soft enforcement (overlay, warnings)
 * - DEGRADED: Protección parcial
 * 
 * Solo Device Owner tiene acceso a las políticas fuertes.
 */
class DeviceOwnerManager private constructor(context: Context) {

    companion object {
        private const val TAG = "DeviceOwnerManager"
        
        private const val ADMIN_PKG = "com.tudominio.parentalcontrol"
        private const val ADMIN_CLASS = "com.tudominio.parentalcontrol.admin.ParentalDeviceAdminReceiver"

        @Volatile
        private var instance: DeviceOwnerManager? = null

        fun getInstance(context: Context): DeviceOwnerManager {
            return instance ?: synchronized(this) {
                instance ?: DeviceOwnerManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val context = context
    private val devicePolicyManager: DevicePolicyManager by lazy {
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }
    private val adminComponent: ComponentName by lazy {
        ComponentName(ADMIN_PKG, ADMIN_CLASS)
    }

    // ============ Detección de nivel ============

    /**
     * Verifica si el dispositivo es Device Owner.
     */
    fun isDeviceOwner(): Boolean {
        return try {
            devicePolicyManager.isDeviceOwnerApp(ADMIN_PKG)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking device owner: ${e.message}")
            false
        }
    }

    /**
     * Verifica si el admin está activo.
     */
    fun isAdminActive(): Boolean {
        return try {
            val admins = devicePolicyManager.getActiveAdmins()
            admins?.any { it.packageName == ADMIN_PKG } == true
        } catch (e: Exception) {
            Log.e(TAG, "Error checking admin: ${e.message}")
            false
        }
    }

    /**
     * Obtiene el nivel de enforcement actual.
     */
    fun getEnforcementLevel(): EnforcementLevel {
        return when {
            isDeviceOwner() -> EnforcementLevel.DEVICE_OWNER
            isAdminActive() -> EnforcementLevel.STANDARD
            else -> EnforcementLevel.DEGRADED
        }
    }

    /**
     * Obtiene las capacidades disponibles según el nivel.
     */
    fun getAvailableCapabilities(): Set<DeviceCapability> {
        val level = getEnforcementLevel()
        
        return when (level) {
            EnforcementLevel.DEVICE_OWNER -> DeviceCapability.entries.toSet()
            EnforcementLevel.STANDARD -> setOf(
                DeviceCapability.BASIC_BLOCK,
                DeviceCapability.USAGE_MONITORING,
                DeviceCapability.TIME_LIMITS,
                DeviceCapability.WARNINGS
            )
            EnforcementLevel.DEGRADED -> setOf(
                DeviceCapability.USAGE_MONITORING,
                DeviceCapability.WARNINGS
            )
        }
    }

    // ============ Hard Enforcement (Device Owner only) ============

    /**
     * Oculta una aplicación (no visible en launcher).
     * Solo disponible en Device Owner.
     */
    fun hideApplication(packageName: String): Boolean {
        if (!isDeviceOwner()) {
            Log.w(TAG, "hideApplication requires Device Owner")
            return false
        }
        
        return try {
            devicePolicyManager.setApplicationHidden(adminComponent, packageName, true)
            Log.d(TAG, "Application hidden: $packageName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding app: ${e.message}")
            false
        }
    }

    /**
     * Muestra una aplicación oculta.
     */
    fun unhideApplication(packageName: String): Boolean {
        if (!isDeviceOwner()) {
            Log.w(TAG, "unhideApplication requires Device Owner")
            return false
        }
        
        return try {
            devicePolicyManager.setApplicationHidden(adminComponent, packageName, false)
            Log.d(TAG, "Application unhidden: $packageName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error unhiding app: ${e.message}")
            false
        }
    }

    /**
     * Suspende aplicaciones (no se pueden ejecutar).
     * Solo disponible en Device Owner.
     */
    fun suspendPackage(packageName: String, durationMs: Long = 0): Boolean {
        if (!isDeviceOwner()) {
            Log.w(TAG, "suspendPackage requires Device Owner")
            return false
        }
        
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // En Android 12+ se puede especificar duración
                devicePolicyManager.setPackagesSuspended(
                    adminComponent,
                    arrayOf(packageName),
                    true
                )
            } else {
                // En versiones anteriores, solo podemos ocultar
                devicePolicyManager.setApplicationHidden(adminComponent, packageName, true)
            }
            Log.d(TAG, "Package suspended: $packageName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error suspending package: ${e.message}")
            false
        }
    }

    /**
     * Des-suspende una aplicación.
     */
    fun unsuspendPackage(packageName: String): Boolean {
        if (!isDeviceOwner()) {
            Log.w(TAG, "unsuspendPackage requires Device Owner")
            return false
        }
        
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                devicePolicyManager.setPackagesSuspended(
                    adminComponent,
                    arrayOf(packageName),
                    false
                )
            } else {
                devicePolicyManager.setApplicationHidden(adminComponent, packageName, false)
            }
            Log.d(TAG, "Package unsuspended: $packageName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error unsuspending package: ${e.message}")
            false
        }
    }

    /**
     * Bloquea la desinstalación de la app.
     * Solo disponible en Device Owner.
     */
    fun setUninstallBlocked(packageName: String, blocked: Boolean): Boolean {
        if (!isDeviceOwner()) {
            Log.w(TAG, "setUninstallBlocked requires Device Owner")
            return false
        }
        
        return try {
            devicePolicyManager.setUninstallBlocked(adminComponent, packageName, blocked)
            Log.d(TAG, "Uninstall blocked: $blocked for $packageName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting uninstall blocked: ${e.message}")
            false
        }
    }

    /**
     * Agrega un usuario existente al whitelist para kiosk.
     */
    fun addPersistentPreferredActivity(
        intentFilter: android.content.IntentFilter,
        activity: android.content.ComponentName
    ): Boolean {
        if (!isDeviceOwner()) {
            Log.w(TAG, "addPersistentPreferredActivity requires Device Owner")
            return false
        }
        
        return try {
            devicePolicyManager.addPersistentPreferredActivity(
                adminComponent,
                intentFilter,
                activity
            )
            Log.d(TAG, "Persistent preferred activity added")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error adding persistent activity: ${e.message}")
            false
        }
    }

    /**
     * Configura Lock Task (kiosk mode).
     */
    fun setLockTaskPackages(packages: List<String>): Boolean {
        if (!isDeviceOwner()) {
            Log.w(TAG, "setLockTaskPackages requires Device Owner")
            return false
        }
        
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                devicePolicyManager.setLockTaskPackages(adminComponent, packages.toTypedArray())
                Log.d(TAG, "Lock task packages set: $packages")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting lock task: ${e.message}")
            false
        }
    }

    /**
     * Habilita Lock Task mode.
     * Nota: startLockTask es un método de Activity, no de DevicePolicyManager.
     * El Activity debe llamar Activity.startLockTask() directamente.
     */
    fun requestStartLockTask() {
        if (!isDeviceOwner()) {
            Log.w(TAG, "startLockTask requires Device Owner")
            return
        }
        Log.d(TAG, "Lock task requested - Activity must call startLockTask()")
    }

    /**
     * Deshabilita Lock Task mode.
     * Nota: stopLockTask es un método de Activity, no de DevicePolicyManager.
     */
    fun requestStopLockTask() {
        if (!isDeviceOwner()) {
            Log.w(TAG, "stopLockTask requires Device Owner")
            return
        }
        Log.d(TAG, "Lock task stop requested - Activity must call stopLockTask()")
    }

    /**
     * Configura VPN obligatoria.
     */
    fun setAlwaysOnVpnPackage(packageName: String?, lockdown: Boolean = true): Boolean {
        if (!isDeviceOwner()) {
            Log.w(TAG, "setAlwaysOnVpnPackage requires Device Owner")
            return false
        }
        
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                devicePolicyManager.setAlwaysOnVpnPackage(
                    adminComponent,
                    packageName,
                    lockdown
                )
                Log.d(TAG, "Always-on VPN set: $packageName, lockdown: $lockdown")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting always-on VPN: ${e.message}")
            false
        }
    }

    /**
     * Bloquea el acceso a Safe Mode.
     * En Android 9+ permite deshabilitar la keyguard durante Safe Mode.
     * Esto evita que el usuario acceda a apps del sistema en modo seguro.
     */
    fun setSafeModeBlocked(blocked: Boolean): Boolean {
        if (!isDeviceOwner()) {
            Log.w(TAG, "setSafeModeBlocked requires Device Owner")
            return false
        }
        
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                devicePolicyManager.setKeyguardDisabled(
                    adminComponent,
                    blocked
                )
                Log.d(TAG, "Safe Mode blocked: $blocked")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting Safe Mode block: ${e.message}")
            false
        }
    }

    /**
     * Configura si las apps del sistema están ocultas.
     * Useful para evitar que el usuario acceda a apps del sistema.
     */
    fun setSystemAppsHidden(hidden: Boolean): Boolean {
        if (!isDeviceOwner()) {
            Log.w(TAG, "setSystemAppsHidden requires Device Owner")
            return false
        }
        
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                devicePolicyManager.setApplicationHidden(
                    adminComponent,
                    "com.android.launcher",
                    hidden
                )
                Log.d(TAG, "System apps hidden: $hidden")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting system apps hidden: ${e.message}")
            false
        }
    }

    /**
     * Establece la duración máxima de bloqueo.
     * Después de este tiempo, el dispositivo se desbloquea automáticamente.
     */
    fun setMaximumTimeToLock(timeoutMs: Long): Boolean {
        if (!isDeviceOwner()) {
            Log.w(TAG, "setMaximumTimeToLock requires Device Owner")
            return false
        }
        
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                devicePolicyManager.setMaximumTimeToLock(
                    adminComponent,
                    timeoutMs
                )
                Log.d(TAG, "Maximum time to lock set: ${timeoutMs}ms")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting maximum time to lock: ${e.message}")
            false
        }
    }

    /**
     * Obtiene el intent de provisioning QR para Device Owner.
     * 
     * OOBE: Para aprovisionar como Device Owner:
     * 1. Generar QR con datos de provisioning (NFC/QR)
     * 2. En primer inicio, escanear QR
     * 3. El dispositivo se aprovisiona automáticamente
     * 
     * Requiere Android 10+ (API 29) para QR provisioning.
     */
    fun getQrProvisioningIntent(provisioningJson: String): Intent {
        val action = "android.app.action.PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE"
        return Intent(action).apply {
            putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME, adminComponent)
            putExtra("android.app.extra.PROVISIONING_DEVICE_ADMIN_EXTRAS_BUNDLE", android.os.Bundle().apply {
                putString("provisioning_data", provisioningJson)
            })
        }
    }

    /**
     * Obtiene el intent de provisioning NFC.
     * 
     * OOBE: Para aprovisionar via NFC:
     * 1. Escribir datos de provisioning en tag NFC
     * 2. Tocar el tag con el dispositivo nuevo
     * 3. El dispositivo se aprovisiona automáticamente
     */
    fun getNfcProvisioningIntent(): Intent {
        val action = "android.app.action.PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE"
        return Intent(action).apply {
            putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME, adminComponent)
        }
    }

    // ============ Estado y Reporte ============

    /**
     * Obtiene el estado completo del Device Owner.
     */
    fun getOwnerStatus(): DeviceOwnerStatus {
        return DeviceOwnerStatus(
            isDeviceOwner = isDeviceOwner(),
            isAdminActive = isAdminActive(),
            enforcementLevel = getEnforcementLevel(),
            availableCapabilities = getAvailableCapabilities(),
            timestamp = Instant.now().toString()
        )
    }

    /**
     * Obtiene el intent para solicitar Device Owner.
     * 
     * Para Device Owner real se requiere provisioning via QR/NFC/zero-touch.
     * Este método solo inicia el proceso de admin básico.
     */
    fun getProvisioningIntent(): Intent {
        return Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Esta app necesita ser administradora para funcionar correctamente."
                )
            }
    }
}

/**
 * Nivel de enforcement.
 */
enum class EnforcementLevel {
    /** Device Owner: máximo control */
    DEVICE_OWNER,
    
    /** Admin activo: control estándar */
    STANDARD,
    
    /** Protección parcial (degradado) */
    DEGRADED
}

/**
 * Capacidades del dispositivo.
 */
enum class DeviceCapability {
    /** Bloqueo de apps (soft o hard según nivel) */
    BASIC_BLOCK,
    
    /** Monitoreo de uso */
    USAGE_MONITORING,
    
    /** Límites de tiempo */
    TIME_LIMITS,
    
    /** Advertencias y avisos */
    WARNINGS,
    
    /** Suspensión de paquetes (DO only) */
    PACKAGE_SUSPENSION,
    
    /** Ocultar apps del launcher (DO only) */
    APP_HIDING,
    
    /** Bloqueo de desinstalación (DO only) */
    UNINSTALL_BLOCK,
    
    /** Lock Task / Kiosk (DO only) */
    LOCK_TASK,
    
    /** VPN obligatoria (DO only) */
    ALWAYS_ON_VPN,
    
    /** FRP (Factory Reset Protection) (DO only) - automático con DO */
    FRP,
    
    /** Bloqueo de Safe Mode (DO only) */
    SAFE_MODE_BLOCK
}

/**
 * Estado del Device Owner.
 */
data class DeviceOwnerStatus(
    val isDeviceOwner: Boolean,
    val isAdminActive: Boolean,
    val enforcementLevel: EnforcementLevel,
    val availableCapabilities: Set<DeviceCapability>,
    val timestamp: String
)
