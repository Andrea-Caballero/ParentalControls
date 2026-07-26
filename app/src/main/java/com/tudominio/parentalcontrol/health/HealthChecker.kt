package com.tudominio.parentalcontrol.health

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.tudominio.parentalcontrol.admin.ParentalDeviceAdminReceiver

/**
 * Resultado de verificación de salud.
 */
data class HealthCheckResult(
    val isAccessibilityServiceEnabled: Boolean,
    val isOverlayPermissionGranted: Boolean,
    val isBatteryOptimizationIgnored: Boolean,
    val isUsageStatsPermissionGranted: Boolean,
    val isDeviceAdminActive: Boolean,
    val isDeviceOwner: Boolean,
    val enforcementLevel: EnforcementLevel,
    val missingPermissions: List<Permission>,
    val recommendations: List<Recommendation>
) {
    val isFullyOperational: Boolean
        get() = enforcementLevel == EnforcementLevel.STANDARD || enforcementLevel == EnforcementLevel.DEVICE_OWNER

    val isDegraded: Boolean
        get() = enforcementLevel == EnforcementLevel.DEGRADED
}

/**
 * Nivel de enforcement.
 */
enum class EnforcementLevel {
    /** Tiene Device Owner (máximo control) */
    DEVICE_OWNER,
    
    /** Tiene todos los permisos de consumo (STANDARD) */
    STANDARD,
    
    /** Faltan permisos críticos, protección parcial */
    DEGRADED
}

/**
 * Permisos críticos.
 */
enum class Permission {
    ACCESSIBILITY_SERVICE,
    OVERLAY_PERMISSION,
    BATTERY_OPTIMIZATION,
    USAGE_STATS,
    DEVICE_ADMIN
}

/**
 * Recomendaciones para el usuario.
 */
enum class Recommendation(val message: String) {
    ENABLE_ACCESSIBILITY("Activa el servicio de accesibilidad"),
    GRANT_OVERLAY("Permite que la app muestre sobre otras apps"),
    IGNORE_BATTERY_OPT("Desactiva la optimización de batería"),
    GRANT_USAGE_STATS("Permite acceso a estadísticas de uso"),
    ENABLE_DEVICE_ADMIN("Activa el admin de dispositivo")
}

/**
 * Verificador de salud del sistema.
 */
class HealthChecker(private val context: Context) {

    companion object {
        private const val ADMIN_PKG = "com.tudominio.parentalcontrol"
        private const val ADMIN_CLASS = "com.tudominio.parentalcontrol.admin.ParentalDeviceAdminReceiver"
    }

    private val devicePolicyManager: DevicePolicyManager by lazy {
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }

    private val accessibilityManager: AccessibilityManager by lazy {
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    }

    private val powerManager: PowerManager by lazy {
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    private val adminComponent: ComponentName by lazy {
        ComponentName(ADMIN_PKG, ADMIN_CLASS)
    }

    /**
     * Realiza una verificación completa de salud.
     */
    fun checkHealth(): HealthCheckResult {
        val accessibilityEnabled = isAccessibilityServiceEnabled()
        val overlayGranted = isOverlayPermissionGranted()
        val batteryIgnored = isBatteryOptimizationIgnored()
        val usageStatsGranted = isUsageStatsPermissionGranted()
        val adminActive = isDeviceAdminActive()
        val deviceOwner = isDeviceOwner()

        val missingPermissions = mutableListOf<Permission>()
        val recommendations = mutableListOf<Recommendation>()

        // Verificar permisos faltantes
        if (!accessibilityEnabled) {
            missingPermissions.add(Permission.ACCESSIBILITY_SERVICE)
            recommendations.add(Recommendation.ENABLE_ACCESSIBILITY)
        }

        if (!overlayGranted) {
            missingPermissions.add(Permission.OVERLAY_PERMISSION)
            recommendations.add(Recommendation.GRANT_OVERLAY)
        }

        if (!batteryIgnored) {
            missingPermissions.add(Permission.BATTERY_OPTIMIZATION)
            recommendations.add(Recommendation.IGNORE_BATTERY_OPT)
        }

        if (!usageStatsGranted) {
            missingPermissions.add(Permission.USAGE_STATS)
            recommendations.add(Recommendation.GRANT_USAGE_STATS)
        }

        if (!adminActive) {
            missingPermissions.add(Permission.DEVICE_ADMIN)
            recommendations.add(Recommendation.ENABLE_DEVICE_ADMIN)
        }

        // Calcular nivel de enforcement
        val enforcementLevel = calculateEnforcementLevel(
            accessibilityEnabled = accessibilityEnabled,
            overlayGranted = overlayGranted,
            batteryIgnored = batteryIgnored,
            usageStatsGranted = usageStatsGranted,
            adminActive = adminActive,
            deviceOwner = deviceOwner
        )

        return HealthCheckResult(
            isAccessibilityServiceEnabled = accessibilityEnabled,
            isOverlayPermissionGranted = overlayGranted,
            isBatteryOptimizationIgnored = batteryIgnored,
            isUsageStatsPermissionGranted = usageStatsGranted,
            isDeviceAdminActive = adminActive,
            isDeviceOwner = deviceOwner,
            enforcementLevel = enforcementLevel,
            missingPermissions = missingPermissions,
            recommendations = recommendations
        )
    }

    /**
     * Calcula el nivel de enforcement basado en permisos.
     */
    private fun calculateEnforcementLevel(
        accessibilityEnabled: Boolean,
        overlayGranted: Boolean,
        batteryIgnored: Boolean,
        usageStatsGranted: Boolean,
        adminActive: Boolean,
        deviceOwner: Boolean
    ): EnforcementLevel {
        // Device Owner tiene máximo control
        if (deviceOwner) {
            return EnforcementLevel.DEVICE_OWNER
        }

        // Verificar permisos críticos para STANDARD
        val criticalPermissions = listOf(
            accessibilityEnabled,
            overlayGranted,
            batteryIgnored
        )

        // Si falta algún permiso crítico, estamos degradados
        val missingCritical = criticalPermissions.count { !it }
        
        if (missingCritical > 0) {
            return EnforcementLevel.DEGRADED
        }

        // Tenemos todos los permisos de consumo
        return EnforcementLevel.STANDARD
    }

    /**
     * Verifica si el servicio de accesibilidad está activo.
     */
    fun isAccessibilityServiceEnabled(): Boolean {
        val services = accessibilityManager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_GENERIC
        )
        return services.any { 
            it.resolveInfo.serviceInfo.packageName == context.packageName
        }
    }

    /**
     * Verifica si el permiso de overlay está concedido.
     */
    fun isOverlayPermissionGranted(): Boolean {
        return Settings.canDrawOverlays(context)
    }

    /**
     * Verifica si la optimización de batería está ignorada.
     */
    fun isBatteryOptimizationIgnored(): Boolean {
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Verifica si el permiso de uso está concedido.
     */
    fun isUsageStatsPermissionGranted(): Boolean {
        val packageManager = context.packageManager
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        return intent.resolveActivity(packageManager) != null
    }

    /**
     * Verifica si el Device Admin está activo.
     */
    fun isDeviceAdminActive(): Boolean {
        return devicePolicyManager.isAdminActive(adminComponent)
    }

    /**
     * Verifica si es Device Owner.
     */
    fun isDeviceOwner(): Boolean {
        return try {
            devicePolicyManager.isDeviceOwnerApp(ADMIN_PKG)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Obtiene el intent para activar el servicio de accesibilidad.
     */
    fun getAccessibilitySettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }

    /**
     * Obtiene el intent para activar el overlay.
     */
    fun getOverlaySettingsIntent(): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
    }

    /**
     * Obtiene el intent para ignorar optimización de batería.
     */
    fun getBatteryOptimizationIntent(): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    /**
     * Obtiene el intent para el permiso de uso.
     */
    fun getUsageStatsIntent(): Intent {
        return Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
    }

    /**
     * Obtiene el intent para activar Device Admin.
     */
    fun getDeviceAdminIntent(): Intent {
        return Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Activa el control parental para permitir bloquear el dispositivo remotamente."
            )
        }
    }
}
