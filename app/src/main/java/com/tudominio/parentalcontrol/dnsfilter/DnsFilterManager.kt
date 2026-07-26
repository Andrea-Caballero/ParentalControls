package com.tudominio.parentalcontrol.dnsfilter

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import com.tudominio.parentalcontrol.deviceowner.DeviceOwnerManager

/**
 * Manager para el servicio DNS Filter.
 * 
 * T35: Controla el servicio VPN de filtrado DNS.
 * 
 * Responsabilidades:
 * - Iniciar/detener el servicio VPN
 * - Gestionar permisos VPN
 * - Advertir sobre límite de VPN única
 * - Integrar con Device Owner (setAlwaysOnVpnPackage)
 */
class DnsFilterManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "DnsFilterManager"
        
        // Umbral para "sitio popular" (ejemplo)
        private const val POPULAR_THRESHOLD = 1000000

        @Volatile
        private var instance: DnsFilterManager? = null

        fun getInstance(context: Context): DnsFilterManager {
            return instance ?: synchronized(this) {
                instance ?: DnsFilterManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val deviceOwnerManager = DeviceOwnerManager.getInstance(context)
    
    // Estado actual
    private var currentState: DnsFilterState = DnsFilterState.STOPPED
    private var blockedDomains: Set<String> = emptySet()
    
    // Callback para cambios de estado
    private var stateChangeListener: ((DnsFilterState) -> Unit)? = null
    
    // Listener para resultados de filtrado
    private var filterResultListener: ((DnsFilterResult) -> Unit)? = null

    /**
     * Verifica si hay una VPN activa (además de la nuestra).
     * 
     * Nota: No hay API pública para listar VPNs activas.
     * Esta función siempre retorna false y se basa en el intento
     * de iniciar el VPN para detectar conflictos.
     */
    fun hasActiveVpn(): Boolean {
        // No hay forma de detectar otras VPNs activamente
        // El conflicto se detecta cuando VpnService.prepare() retorna non-null
        return false
    }

    /**
     * Verifica si podemos iniciar el VPN.
     */
    fun canStartVpn(): Pair<Boolean, String> {
        return when {
            currentState == DnsFilterState.RUNNING -> {
                false to "VPN ya está activo"
            }
            hasActiveVpn() -> {
                false to "Otra VPN está activa. Solo puede haber una VPN a la vez."
            }
            else -> {
                true to "OK"
            }
        }
    }

    /**
     * Prepara el intent para solicitar permiso VPN.
     * Devuelve null si no necesita permiso.
     */
    fun prepareVpnPermission(): Intent? {
        return VpnService.prepare(context)
    }

    /**
     * Inicia el servicio DNS filter.
     */
    fun startDnsFilter(
        domains: Set<String>,
        permissionLauncher: ActivityResultLauncher<Intent>?
    ) {
        // Verificar si hay otra VPN activa
        val (canStart, message) = canStartVpn()
        
        if (!canStart) {
            Log.w(TAG, "Cannot start DNS filter: $message")
            currentState = DnsFilterState.ERROR
            stateChangeListener?.invoke(currentState)
            return
        }
        
        // Verificar permiso
        val prepareIntent = VpnService.prepare(context)
        
        if (prepareIntent != null) {
            // Necesita permiso del usuario
            if (permissionLauncher != null) {
                permissionLauncher.launch(prepareIntent)
            } else {
                // No hay forma de pedir permiso, fallar
                currentState = DnsFilterState.ERROR
                stateChangeListener?.invoke(currentState)
            }
            return
        }
        
        // Iniciar servicio
        doStartDnsFilter(domains)
    }

    /**
     * Callback después de que el usuario otorga permiso VPN.
     */
    fun onVpnPermissionGranted(domains: Set<String>) {
        doStartDnsFilter(domains)
    }

    /**
     * Inicia realmente el servicio DNS filter.
     */
    private fun doStartDnsFilter(domains: Set<String>) {
        currentState = DnsFilterState.STARTING
        stateChangeListener?.invoke(currentState)
        
        blockedDomains = domains
        
        val intent = Intent(context, DnsFilterService::class.java).apply {
            action = DnsFilterService.ACTION_START
            putStringArrayListExtra("blocked_domains", ArrayList(domains.toList()))
        }
        
        try {
            context.startService(intent)
            currentState = DnsFilterState.RUNNING
            stateChangeListener?.invoke(currentState)
            Log.d(TAG, "DNS filter started with ${domains.size} domains")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start DNS filter: ${e.message}")
            currentState = DnsFilterState.ERROR
            stateChangeListener?.invoke(currentState)
        }
    }

    /**
     * Detiene el servicio DNS filter.
     */
    fun stopDnsFilter() {
        currentState = DnsFilterState.STOPPING
        stateChangeListener?.invoke(currentState)
        
        val intent = Intent(context, DnsFilterService::class.java).apply {
            action = DnsFilterService.ACTION_STOP
        }
        
        try {
            context.startService(intent)
            currentState = DnsFilterState.STOPPED
            stateChangeListener?.invoke(currentState)
            Log.d(TAG, "DNS filter stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop DNS filter: ${e.message}")
            currentState = DnsFilterState.ERROR
            stateChangeListener?.invoke(currentState)
        }
    }

    /**
     * Actualiza la lista de dominios bloqueados.
     */
    fun updateBlockedDomains(domains: Set<String>) {
        blockedDomains = domains
        
        if (currentState == DnsFilterState.RUNNING) {
            val intent = Intent(context, DnsFilterService::class.java).apply {
                action = "UPDATE_DOMAINS"
                putStringArrayListExtra("blocked_domains", ArrayList(domains.toList()))
            }
            context.startService(intent)
        }
    }

    /**
     * Obtiene el estado actual.
     */
    fun getState(): DnsFilterState = currentState

    /**
     * Establece listener para cambios de estado.
     */
    fun setStateChangeListener(listener: (DnsFilterState) -> Unit) {
        stateChangeListener = listener
    }

    /**
     * Establece listener para resultados de filtrado.
     */
    fun setFilterResultListener(listener: (DnsFilterResult) -> Unit) {
        filterResultListener = listener
    }

    /**
     * Configura Always-On VPN (Device Owner only).
     * 
     * §0.6: Advertir que esto puede afectar otras VPN del usuario.
     */
    fun setAlwaysOnVpn(enabled: Boolean): Boolean {
        if (deviceOwnerManager.getEnforcementLevel() !=
            com.tudominio.parentalcontrol.deviceowner.EnforcementLevel.DEVICE_OWNER
        ) {
            Log.w(TAG, "setAlwaysOnVpn requires Device Owner")
            return false
        }

        val packageName = if (enabled) context.packageName else null
        return deviceOwnerManager.setAlwaysOnVpnPackage(packageName, lockdown = true)
    }

    /**
     * Obtiene la advertencia sobre VPN única.
     */
    fun getVpnWarningMessage(): String {
        return buildString {
            appendLine("⚠️ Limitación de VPN")
            appendLine()
            appendLine("Esta app usa una conexión VPN para filtrar contenido.")
            appendLine("Solo puede haber una VPN activa a la vez.")
            appendLine()
            appendLine("Si usas otra VPN (ej. NordVPN, ExpressVPN),")
            appendLine("deberás desactivarla para usar el control parental.")
            appendLine()
            appendLine("Alternativas:")
            appendLine("• Usar el modo reforzado (Device Owner)")
            appendLine("• Activar/desactivar la VPN del control parental")
        }
    }

    /**
     * Verifica si Always-On VPN está disponible.
     */
    fun isAlwaysOnVpnAvailable(): Boolean {
        return deviceOwnerManager.getEnforcementLevel() ==
            com.tudominio.parentalcontrol.deviceowner.EnforcementLevel.DEVICE_OWNER
    }

    /**
     * Obtiene información del estado para UI.
     */
    fun getStatusInfo(): DnsFilterStatusInfo {
        return DnsFilterStatusInfo(
            state = currentState,
            blockedDomainsCount = blockedDomains.size,
            hasActiveVpnConflict = hasActiveVpn(),
            isAlwaysOnVpnAvailable = isAlwaysOnVpnAvailable(),
            canStart = canStartVpn().first,
            warningMessage = if (hasActiveVpn()) getVpnWarningMessage() else null
        )
    }
}

/**
 * Información de estado del DNS filter para UI.
 */
data class DnsFilterStatusInfo(
    val state: DnsFilterState,
    val blockedDomainsCount: Int,
    val hasActiveVpnConflict: Boolean,
    val isAlwaysOnVpnAvailable: Boolean,
    val canStart: Boolean,
    val warningMessage: String?
)
