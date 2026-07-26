package com.tudominio.parentalcontrol.copy

import android.content.Context
import android.content.res.Resources
import com.tudominio.parentalcontrol.consent.AgeBand
import com.tudominio.parentalcontrol.consent.ConsentManager
import android.util.Log

/**
 * Manager centralizado para textos de la app.
 * 
 * Proporciona acceso unificado a todos los textos,
 * con soporte para variantes por edad.
 * 
 * §0.6: Todos los textos de cara al menor están centralizados aquí.
 */
class CopyManager private constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "CopyManager"
        
        @Volatile
        private var instance: CopyManager? = null

        fun getInstance(context: Context): CopyManager {
            return instance ?: synchronized(this) {
                instance ?: CopyManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val resources: Resources = context.resources
    private val consentManager = ConsentManager.getInstance(context)

    /**
     * Obtiene una cadena de texto por su ID.
     * Aplica variante de edad si corresponde.
     */
    fun getString(stringId: String): String {
        return try {
            val resId = resources.getIdentifier(stringId, "string", context.packageName)
            if (resId != 0) {
                val text = resources.getString(resId)
                applyAgeVariant(text)
            } else {
                Log.w(TAG, "String no encontrado: $stringId")
                stringId // Fallback al ID
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo string $stringId: ${e.message}")
            stringId
        }
    }

    /**
     * Obtiene una cadena con argumentos.
     */
    fun getString(stringId: String, vararg formatArgs: Any): String {
        return try {
            val resId = resources.getIdentifier(stringId, "string", context.packageName)
            if (resId != 0) {
                val text = resources.getString(resId, *formatArgs)
                applyAgeVariant(text)
            } else {
                stringId
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo string $stringId: ${e.message}")
            stringId
        }
    }

    /**
     * Obtiene texto de divulgación.
     * §0.6: Divulgación prominente in-app.
     */
    fun getDisclosure(): DisclosureCopy {
        return DisclosureCopy(
            title = getString("disclosure_title"),
            subtitle = getString("disclosure_subtitle"),
            point1Title = getString("disclosure_point1_title"),
            point1Desc = getString("disclosure_point1_desc"),
            point2Title = getString("disclosure_point2_title"),
            point2Desc = getString("disclosure_point2_desc"),
            point3Title = getString("disclosure_point3_title"),
            point3Desc = getString("disclosure_point3_desc"),
            point4Title = getString("disclosure_point4_title"),
            point4Desc = getString("disclosure_point4_desc"),
            point5Title = getString("disclosure_point5_title"),
            point5Desc = getString("disclosure_point5_desc"),
            consentTitle = getString("disclosure_consent_title"),
            consentQuestion = getString("disclosure_consent_question"),
            consentYes = getString("disclosure_consent_yes"),
            consentNo = getString("disclosure_consent_no"),
            consentNote = getString("disclosure_consent_note")
        )
    }

    /**
     * Obtiene texto de transparencia.
     * §0.6: Siempre accesible para el menor.
     */
    fun getTransparency(): TransparencyCopy {
        return TransparencyCopy(
            title = getString("transparency_title"),
            subtitle = getString("transparency_subtitle"),
            monitoredTitle = getString("transparency_monitored_title"),
            appsUsed = getString("transparency_apps_used"),
            screenTime = getString("transparency_screen_time"),
            blockedAttempts = getString("transparency_blocked_attempts"),
            requests = getString("transparency_requests"),
            notMonitoredTitle = getString("transparency_not_monitored_title"),
            notMessages = getString("transparency_not_messages"),
            notCalls = getString("transparency_not_calls"),
            notBrowsing = getString("transparency_not_browsing"),
            notLocation = getString("transparency_not_location"),
            notCamera = getString("transparency_not_camera"),
            notMicrophone = getString("transparency_not_microphone"),
            privacyTitle = getString("transparency_privacy_title"),
            privacyDesc = getString("transparency_privacy_desc"),
            accessTitle = getString("transparency_access_title"),
            accessDesc = getString("transparency_access_desc")
        )
    }

    /**
     * Obtiene textos para la pantalla del niño.
     */
    fun getChildHome(): ChildHomeCopy {
        return ChildHomeCopy(
            welcome = getString("home_welcome"),
            timeRemaining = getString("home_time_remaining"),
            timeUsed = getString("home_time_used"),
            minutes = getString("home_minutes"),
            hour = getString("home_hour"),
            hours = getString("home_hours"),
            ofToday = getString("home_of_today")
        )
    }

    /**
     * Obtiene textos de bloqueo.
     */
    fun getBlock(): BlockCopy {
        return BlockCopy(
            title = getString("block_title"),
            titleAlt = getString("block_title_alt"),
            message = getString("block_message"),
            messageAlt = getString("block_message_alt"),
            timeRemaining = getString("block_time_remaining"),
            requestTime = getString("block_request_time"),
            parentContact = getString("block_parent_contact")
        )
    }

    /**
     * Obtiene textos de tiempo extra.
     */
    fun getExtraTime(): ExtraTimeCopy {
        return ExtraTimeCopy(
            title = getString("extra_time_title"),
            request = getString("extra_time_request"),
            granted = getString("extra_time_granted"),
            remaining = getString("extra_time_remaining"),
            used = getString("extra_time_used"),
            expired = getString("extra_time_expired"),
            pending = getString("extra_time_pending"),
            denied = getString("extra_time_denied")
        )
    }

    /**
     * Obtiene textos de solicitud de tiempo.
     */
    fun getRequestTime(): RequestTimeCopy {
        return RequestTimeCopy(
            title = getString("request_time_title"),
            howMuch = getString("request_time_how_much"),
            minutes = getString("request_time_minutes"),
            reason = getString("request_time_reason"),
            reasonPlaceholder = getString("request_time_reason_placeholder"),
            send = getString("request_time_send"),
            cancel = getString("request_time_cancel"),
            sent = getString("request_time_sent")
        )
    }

    /**
     * Obtiene textos de onboarding.
     */
    fun getOnboarding(): OnboardingCopy {
        return OnboardingCopy(
            welcome = getString("onboarding_welcome"),
            setup = getString("onboarding_setup"),
            next = getString("onboarding_next"),
            skip = getString("onboarding_skip"),
            done = getString("onboarding_done"),
            step1Title = getString("onboarding_step1_title"),
            step1Desc = getString("onboarding_step1_desc"),
            step2Title = getString("onboarding_step2_title"),
            step2Desc = getString("onboarding_step2_desc"),
            step3Title = getString("onboarding_step3_title"),
            step3Desc = getString("onboarding_step3_desc"),
            step4Title = getString("onboarding_step4_title"),
            step4Desc = getString("onboarding_step4_desc")
        )
    }

    /**
     * Obtiene textos de errores.
     */
    fun getErrors(): ErrorCopy {
        return ErrorCopy(
            generic = getString("error_generic"),
            network = getString("error_network"),
            timeout = getString("error_timeout"),
            server = getString("error_server")
        )
    }

    /**
     * Obtiene textos de permisos.
     */
    fun getPermissions(): PermissionCopy {
        return PermissionCopy(
            title = getString("permission_title"),
            accessibilityTitle = getString("permission_accessibility_title"),
            accessibilityDesc = getString("permission_accessibility_desc"),
            overlayTitle = getString("permission_overlay_title"),
            overlayDesc = getString("permission_overlay_desc"),
            usageTitle = getString("permission_usage_title"),
            usageDesc = getString("permission_usage_desc"),
            batteryTitle = getString("permission_battery_title"),
            batteryDesc = getString("permission_battery_desc"),
            grant = getString("permission_grant"),
            skip = getString("permission_skip")
        )
    }

    /**
     * Obtiene textos de la pantalla de estado del menor.
     * §0.8: Sin exact alarms.
     * §0.9: No configurable por el menor.
     */
    fun getStatus(): StatusCopy {
        return StatusCopy(
            title = getString("status_title"),
            timeRemainingLabel = getString("status_time_remaining_label"),
            timeRemainingUnit = getString("status_time_remaining_unit"),
            timeUsedToday = getString("status_time_used_today"),
            nextBlockLabel = getString("status_next_block_label"),
            nextBlockUntil = getString("status_next_block_until"),
            nextBlockTomorrow = getString("status_next_block_tomorrow"),
            warning10Title = getString("status_warning_10_title"),
            warning10Desc = getString("status_warning_10_desc"),
            warning5Title = getString("status_warning_5_title"),
            warning5Desc = getString("status_warning_5_desc"),
            warningBlockedTitle = getString("status_warning_blocked_title"),
            warningBlockedDesc = getString("status_warning_blocked_desc"),
            allowedAppsTitle = getString("status_allowed_apps_title"),
            allowedAppsMore = getString("status_allowed_apps_more"),
            extraTimeButton = getString("status_extra_time_button"),
            extraTimePending = getString("status_extra_time_pending")
        )
    }

    /**
     * Obtiene textos para alertas de degradación y reparación.
     * T25: Copy con encuadre honesto, sin urgencia falsa.
     */
    fun getRepair(): RepairCopy {
        return RepairCopy(
            alertTitle = getString("repair_alert_title"),
            alertSubtitle = getString("repair_alert_subtitle"),
            causeTitle = getString("repair_cause_title"),
            causeDescription = getString("repair_cause_description"),
            repairButton = getString("repair_button"),
            dismissButton = getString("repair_dismiss_button"),
            recoveryTitle = getString("repair_recovery_title"),
            recoveryMessage = getString("repair_recovery_message"),
            accessibilityCause = getString("repair_accessibility_cause"),
            accessibilityDesc = getString("repair_accessibility_desc"),
            overlayCause = getString("repair_overlay_cause"),
            overlayDesc = getString("repair_overlay_desc"),
            batteryCause = getString("repair_battery_cause"),
            batteryDesc = getString("repair_battery_desc"),
            usageCause = getString("repair_usage_cause"),
            usageDesc = getString("repair_usage_desc"),
            adminCause = getString("repair_admin_cause"),
            adminDesc = getString("repair_admin_desc")
        )
    }

    /**
     * Aplica variante de edad al texto.
     */
    private fun applyAgeVariant(text: String): String {
        val ageBand = consentManager.getAgeBand()
        
        return when (ageBand) {
            AgeBand.AGE_7_12 -> {
                // Agregar prefijo para niños más pequeños
                if (text.contains("tiempo", ignoreCase = true) ||
                    text.contains("usar", ignoreCase = true)) {
                    // Textos motivacionales para edad 7-12
                    text.replace("tiempo", "tiempo divertido")
                } else {
                    text
                }
            }
            AgeBand.AGE_13_17 -> {
                // Textos más maduros para adolescentes
                text
            }
            AgeBand.UNSPECIFIED -> {
                text
            }
        }
    }

    /**
     * Obtiene el nombre de la app.
     */
    fun getAppName(): String = getString("app_name")

    /**
     * Obtiene el propósito de la app.
     */
    fun getAppPurpose(): String = getString("app_purpose")
}

// ================================================================
// DATA CLASSES PARA TEXTOS CENTRALIZADOS
// ================================================================

data class DisclosureCopy(
    val title: String,
    val subtitle: String,
    val point1Title: String,
    val point1Desc: String,
    val point2Title: String,
    val point2Desc: String,
    val point3Title: String,
    val point3Desc: String,
    val point4Title: String,
    val point4Desc: String,
    val point5Title: String,
    val point5Desc: String,
    val consentTitle: String,
    val consentQuestion: String,
    val consentYes: String,
    val consentNo: String,
    val consentNote: String
)

data class TransparencyCopy(
    val title: String,
    val subtitle: String,
    val monitoredTitle: String,
    val appsUsed: String,
    val screenTime: String,
    val blockedAttempts: String,
    val requests: String,
    val notMonitoredTitle: String,
    val notMessages: String,
    val notCalls: String,
    val notBrowsing: String,
    val notLocation: String,
    val notCamera: String,
    val notMicrophone: String,
    val privacyTitle: String,
    val privacyDesc: String,
    val accessTitle: String,
    val accessDesc: String
)

data class ChildHomeCopy(
    val welcome: String,
    val timeRemaining: String,
    val timeUsed: String,
    val minutes: String,
    val hour: String,
    val hours: String,
    val ofToday: String
)

data class BlockCopy(
    val title: String,
    val titleAlt: String,
    val message: String,
    val messageAlt: String,
    val timeRemaining: String,
    val requestTime: String,
    val parentContact: String
)

data class ExtraTimeCopy(
    val title: String,
    val request: String,
    val granted: String,
    val remaining: String,
    val used: String,
    val expired: String,
    val pending: String,
    val denied: String
)

data class RequestTimeCopy(
    val title: String,
    val howMuch: String,
    val minutes: String,
    val reason: String,
    val reasonPlaceholder: String,
    val send: String,
    val cancel: String,
    val sent: String
)

data class OnboardingCopy(
    val welcome: String,
    val setup: String,
    val next: String,
    val skip: String,
    val done: String,
    val step1Title: String,
    val step1Desc: String,
    val step2Title: String,
    val step2Desc: String,
    val step3Title: String,
    val step3Desc: String,
    val step4Title: String,
    val step4Desc: String
)

data class ErrorCopy(
    val generic: String,
    val network: String,
    val timeout: String,
    val server: String
)

data class PermissionCopy(
    val title: String,
    val accessibilityTitle: String,
    val accessibilityDesc: String,
    val overlayTitle: String,
    val overlayDesc: String,
    val usageTitle: String,
    val usageDesc: String,
    val batteryTitle: String,
    val batteryDesc: String,
    val grant: String,
    val skip: String
)

data class StatusCopy(
    val title: String,
    val timeRemainingLabel: String,
    val timeRemainingUnit: String,
    val timeUsedToday: String,
    val nextBlockLabel: String,
    val nextBlockUntil: String,
    val nextBlockTomorrow: String,
    val warning10Title: String,
    val warning10Desc: String,
    val warning5Title: String,
    val warning5Desc: String,
    val warningBlockedTitle: String,
    val warningBlockedDesc: String,
    val allowedAppsTitle: String,
    val allowedAppsMore: String,
    val extraTimeButton: String,
    val extraTimePending: String
)

data class RepairCopy(
    val alertTitle: String,
    val alertSubtitle: String,
    val causeTitle: String,
    val causeDescription: String,
    val repairButton: String,
    val dismissButton: String,
    val recoveryTitle: String,
    val recoveryMessage: String,
    val accessibilityCause: String,
    val accessibilityDesc: String,
    val overlayCause: String,
    val overlayDesc: String,
    val batteryCause: String,
    val batteryDesc: String,
    val usageCause: String,
    val usageDesc: String,
    val adminCause: String,
    val adminDesc: String
)
