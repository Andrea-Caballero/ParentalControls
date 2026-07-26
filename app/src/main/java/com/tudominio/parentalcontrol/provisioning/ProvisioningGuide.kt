package com.tudominio.parentalcontrol.provisioning

/**
 * Guía de aprovisionamiento OOBE para Device Owner.
 * 
 * T31: Documentar el aprovisionamiento OOBE (QR/NFC/zero-touch).
 * 
 * ## Métodos de aprovisionamiento
 * 
 * ### 1. NFC (Bootstrapping)
 * ```
 * - Más común para MDM/EMM
 * - Requiere escribir NDEF en NFC tag
 * - Dispositivo debe tener NFC habilitado
 * ```
 * 
 * ### 2. QR Code (Android 9+)
 * ```
 * - Dispositivo en setup wizard
 * - Scan QR con cámara
 * - Wifi + provisioning info
 * ```
 * 
 * ### 3. Zero-Touch / DPC (Device Policy Controller)
 * ```
 * - Enrolled en EMM (Google, Samsung, etc.)
 * - Provisioning automático en factory reset
 * ```
 * 
 * ### 4. ADB (Desarrollo)
 * ```
 * adb dpm set-device-owner com.tudominio.parentalcontrol/.admin.ParentalDeviceAdminReceiver
 * ```
 */
object ProvisioningGuide {

    /**
     * Tipos de aprovisionamiento disponibles.
     */
    enum class Method {
        NFC,
        QR_CODE,
        ZERO_TOUCH,
        ADB
    }

    /**
     * Requisitos para cada método.
     */
    data class Requirements(
        val method: Method,
        val androidVersion: Int,
        val requirements: List<String>,
        val setupSteps: List<String>
    )

    /**
     * Obtiene los requisitos para aprovisionamiento via QR.
     */
    fun getQrProvisioningRequirements(): Requirements {
        return Requirements(
            method = Method.QR_CODE,
            androidVersion = 9, // API 28
            requirements = listOf(
                "Dispositivo en Factory Reset o setup wizard",
                "Conexión WiFi configurada",
                "Cámara disponible",
                "Código QR con configuración"
            ),
            setupSteps = listOf(
                "1. Iniciar setup wizard o hacer factory reset",
                "2. Conectar a WiFi",
                "3. Esperar pantalla de 'Set up a work profile' o similar",
                "4. Tocar 6 veces en el logo de la app de ajustes",
                "5. Escanear código QR",
                "6. Confirmar instalación"
            )
        )
    }

    /**
     * Obtiene los requisitos para aprovisionamiento via NFC.
     */
    fun getNfcProvisioningRequirements(): Requirements {
        return Requirements(
            method = Method.NFC,
            androidVersion = 5, // API 21
            requirements = listOf(
                "NFC tag writable (NDEF)",
                "App de configuración NFC",
                "Dispositivo con NFC"
            ),
            setupSteps = listOf(
                "1. Preparar tag NFC con datos de provisioning",
                "2. Iniciar dispositivo en setup wizard",
                "3. Tocar tag NFC con dispositivo",
                "4. Aceptar instalación"
            )
        )
    }

    /**
     * Estructura del JSON para provisioning.
     * 
     * Para QR o NFC, el payload debe contener las constantes de ProvisioningConstants.
     */
    data class ProvisioningPayload(
        val packageName: String,
        val downloadUri: String?,
        val checksum: String?,
        val wifiSsid: String?,
        val wifiPassword: String?,
        val encryptionToken: String?
    )

    /**
     * Genera el payload JSON para provisioning.
     */
    fun generateProvisioningPayload(
        packageName: String,
        downloadUri: String? = null,
        checksum: String? = null,
        wifiSsid: String? = null,
        wifiPassword: String? = null
    ): String {
        val payload = buildMap {
            put("android.app.devicepolicy.EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME", packageName)
            downloadUri?.let { put("android.app.devicepolicy.EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_URI", it) }
            checksum?.let { put("android.app.devicepolicy.EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM", it) }
            wifiSsid?.let { put("android.app.devicepolicy.EXTRA_PROVISIONING_WIFI_SSID", it) }
            wifiPassword?.let { put("android.app.devicepolicy.EXTRA_PROVISIONING_WIFI_PASSWORD", it) }
        }
        
        return payload.entries.joinToString("&") { "${it.key}=${it.value}" }
    }

    /**
     * Tips para un aprovisionamiento exitoso.
     */
    object Tips {
        val setupSuccess = listOf(
            "Hacer factory reset antes de aprovisionar",
            "Conectar a WiFi estable antes de escanear QR",
            "Verificar que la app esté firmada con release key",
            "El dispositivo no debe tener políticas existentes",
            "Tiempo promedio: 5-10 minutos"
        )
        
        val commonIssues = listOf(
            "Error 'Already has device owner' → hacer factory reset",
            "Error 'Invalid package' → verificar signature",
            "QR no escaneable → ajustar contraste y tamaño",
            "NFC no funciona → verificar que no esté en modo solo lectura"
        )
    }
}

/**
 * Constantes de provisioning para Android.
 */
object ProvisioningConstants {
    // Actions
    const val ACTION_PROVISION_MANAGED_DEVICE = "android.app.action.PROVISION_MANAGED_DEVICE"
    const val ACTION_PROVISION_MANAGED_PROFILE = "android.app.action.PROVISION_MANAGED_PROFILE"
    const val ACTION_ADMIN_ADD_DEVICE_OWNER = "android.app.action.ADMIN_ADD_DEVICE_OWNER"
    
    // Extras
    const val EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME = "android.app.devicepolicy.EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME"
    const val EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_URI = "android.app.devicepolicy.EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_URI"
    const val EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM = "android.app.devicepolicy.EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM"
    const val EXTRA_PROVISIONING_WIFI_SSID = "android.app.devicepolicy.EXTRA_PROVISIONING_WIFI_SSID"
    const val EXTRA_PROVISIONING_WIFI_PASSWORD = "android.app.devicepolicy.EXTRA_PROVISIONING_WIFI_PASSWORD"
    const val EXTRA_PROVISIONING_ENCRYPTION_TOKEN = "android.app.devicepolicy.EXTRA_PROVISIONING_ENCRYPTION_TOKEN"
    const val EXTRA_DEVICE_ADMIN = "android.app.devicepolicy.EXTRA_DEVICE_ADMIN"
}
