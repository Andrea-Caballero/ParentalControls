package com.tudominio.parentalcontrol.analytics

import android.content.Context
import android.util.Log
import com.tudominio.parentalcontrol.auth.DeviceAuthService
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.data.model.BehavioralEventEntity
import com.tudominio.parentalcontrol.time.DefaultTimeProvider
import com.tudominio.parentalcontrol.time.TimeProvider
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
import kotlinx.coroutines.launch

/**
 * Manager de analytics para tracking de eventos conductuales.
 *
 * T32: Instrumentación de eventos conductuales
 *
 * Características:
 * - Encola eventos (no bloquea enforcement)
 * - Minimización de datos (§0.6)
 * - Esquema versionado
 * - Resiliente a offline
 *
 * §0.5: Tabla behavioral_events
 * §0.6: Sin contenido del menor
 *
 * `database` is Hilt-injected (PR 4 of `align-with-guia-fedora44`).
 * Use the [AnalyticsManagerEntryPoint] bridge only for non-Hilt call sites.
 */
@Singleton
class AnalyticsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: ParentalDatabase
) {

    companion object {
        private const val TAG = "AnalyticsManager"

        // Versión del esquema de eventos
        const val EVENT_VERSION = 1

        // Catalog de eventos
        object Events {
            // Onboarding (T26)
            const val ONBOARDING_STEP_REACHED = "onboarding_step_reached"
            const val ONBOARDING_FIRST_WIN = "onboarding_first_win"
            const val ONBOARDING_COMPLETED = "onboarding_completed"
            const val ONBOARDING_ABANDONED = "onboarding_abandoned"

            // Protección (T12/T30)
            const val PROTECTION_PROGRESS = "protection_progress"
            const val PERMISSION_GRANTED = "permission_granted"
            const val DEVICE_OWNER_OFFERED = "device_owner_offered"
            const val DEVICE_OWNER_ADOPTED = "device_owner_adopted"
            const val DEVICE_OWNER_DECLINED = "device_owner_declined"
            const val DEGRADED_ALERT_SHOWN = "degraded_alert_shown"
            const val REPAIR_TAPPED = "repair_tapped"
            const val PROTECTION_RESTORED = "protection_restored"

            // Tiempo (T27/T28)
            const val TIME_WARNING_SHOWN = "time_warning_shown"
            const val LIMIT_REACHED = "limit_reached"
            const val BLOCK_OVERLAY_SHOWN = "block_overlay_shown"
            const val ASK_PERMISSION_TAPPED = "ask_permission_tapped"
            const val EXTRA_TIME_REQUESTED = "extra_time_requested"
            const val EXTRA_TIME_RESOLVED = "extra_time_resolved"

            // Recompensas (T29)
            const val REWARD_GRANTED = "reward_granted"
            const val REWARD_SEEN = "reward_seen"

            // Anti-tampering (T13)
            const val ACCESSIBILITY_OFF_DETECTED = "accessibility_off_detected"
            const val UNINSTALL_ATTEMPT = "uninstall_attempt"
            const val CLOCK_TAMPER_SUSPECTED = "clock_tamper_suspected"
            const val TIMEZONE_CHANGED = "timezone_changed"

            // Parent outcome (T33)
            const val PARENT_OUTCOME_CHECKIN = "parent_outcome_checkin"
        }

        // Steps de onboarding
        object OnboardingSteps {
            const val PAIRING = "pairing"
            const val CONSENT = "consent"
            const val ACCESSIBILITY = "accessibility"
            const val FIRST_WIN = "first_win"
            const val OVERLAY = "overlay"
            const val BATTERY = "battery"
            const val NOTIFICATIONS = "notifications"
            const val DEVICE_ADMIN = "device_admin"
        }

        /**
         * Convenience accessor for non-Hilt call sites. Production code
         * inside `@AndroidEntryPoint` / `@HiltViewModel` should inject the
         * manager directly via `@Inject AnalyticsManager`.
         */
        fun getInstance(context: Context): AnalyticsManager {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                AnalyticsManagerEntryPoint::class.java
            )
            return entryPoint.analyticsManager()
        }
    }

    private val eventDao = database.behavioralEventDao()
    private val timeProvider: TimeProvider = DefaultTimeProvider(context)
    private val analyticsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Device ID cache
    private var cachedDeviceId: String? = null

    /**
     * Obtiene el device ID actual.
     */
    private fun getDeviceId(): String {
        cachedDeviceId?.let { return it }

        return try {
            val deviceId = DeviceAuthService.getInstance(context).getDeviceId()
            deviceId?.also { cachedDeviceId = it } ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * Emite un evento de analytics.
     *
     * Siempre encola (no bloquea el enforcement).
     * No incluye contenido del menor (§0.6).
     */
    fun track(eventType: String, props: Map<String, String> = emptyMap()) {
        analyticsScope.launch {
            try {
                val now = timeProvider.wallInstant().toString()

                val event = BehavioralEventEntity(
                    event_type = eventType,
                    event_version = EVENT_VERSION,
                    device_id = getDeviceId(),
                    client_ts = now,
                    props = encodeProps(props),
                    synced = false,
                    created_at = now
                )

                eventDao.insert(event)

                Log.d(TAG, "Event tracked: $eventType")
            } catch (e: Exception) {
                // Nunca fallar el tracking - solo log
                Log.e(TAG, "Error tracking event: ${e.message}")
            }
        }
    }

    /**
     * Track con propiedades strongly-typed para evitar errores.
     */
    fun track(eventType: String, vararg props: Pair<String, String>) {
        track(eventType, mapOf(*props))
    }

    /**
     * Track de onboarding.
     */
    fun trackOnboardingStep(step: String) {
        track(Events.ONBOARDING_STEP_REACHED, "step" to step)
    }

    fun trackOnboardingFirstWin() {
        track(Events.ONBOARDING_FIRST_WIN)
    }

    fun trackOnboardingCompleted() {
        track(Events.ONBOARDING_COMPLETED)
    }

    fun trackOnboardingAbandoned(reason: String? = null) {
        val props = reason?.let { mapOf("reason" to it) } ?: emptyMap()
        track(Events.ONBOARDING_ABANDONED, props)
    }

    /**
     * Track de protección.
     */
    fun trackProtectionProgress(percent: Int, issuesCount: Int) {
        track(
            Events.PROTECTION_PROGRESS,
            "percent" to percent.toString(),
            "issues_count" to issuesCount.toString()
        )
    }

    fun trackPermissionGranted(permission: String) {
        track(Events.PERMISSION_GRANTED, "permission" to permission)
    }

    fun trackDeviceOwnerOffered() {
        track(Events.DEVICE_OWNER_OFFERED)
    }

    fun trackDeviceOwnerAdopted() {
        track(Events.DEVICE_OWNER_ADOPTED)
    }

    fun trackDeviceOwnerDeclined() {
        track(Events.DEVICE_OWNER_DECLINED)
    }

    fun trackDegradedAlertShown(reasons: List<String>) {
        track(Events.DEGRADED_ALERT_SHOWN, "reasons" to reasons.joinToString(","))
    }

    fun trackRepairTapped(issueType: String) {
        track(Events.REPAIR_TAPPED, "issue_type" to issueType)
    }

    fun trackProtectionRestored(issueType: String) {
        track(Events.PROTECTION_RESTORED, "issue_type" to issueType)
    }

    /**
     * Track de tiempo.
     */
    fun trackTimeWarningShown(minutesRemaining: Int) {
        track(Events.TIME_WARNING_SHOWN, "minutes_remaining" to minutesRemaining.toString())
    }

    fun trackLimitReached(appPackage: String) {
        track(Events.LIMIT_REACHED, "app_package" to appPackage)
    }

    fun trackBlockOverlayShown(reason: String) {
        track(Events.BLOCK_OVERLAY_SHOWN, "reason" to reason)
    }

    fun trackAskPermissionTapped(appPackage: String) {
        track(Events.ASK_PERMISSION_TAPPED, "app_package" to appPackage)
    }

    fun trackExtraTimeRequested(minutes: Int) {
        track(Events.EXTRA_TIME_REQUESTED, "minutes" to minutes.toString())
    }

    fun trackExtraTimeResolved(granted: Boolean, minutes: Int? = null) {
        val props = mutableMapOf("granted" to granted.toString())
        minutes?.let { props["minutes"] = it.toString() }
        track(Events.EXTRA_TIME_RESOLVED, props)
    }

    /**
     * Track de recompensas.
     */
    fun trackRewardGranted(minutes: Int) {
        track(Events.REWARD_GRANTED, "minutes" to minutes.toString())
    }

    fun trackRewardSeen() {
        track(Events.REWARD_SEEN)
    }

    /**
     * Track de anti-tampering.
     */
    fun trackAccessibilityOffDetected() {
        track(Events.ACCESSIBILITY_OFF_DETECTED)
    }

    fun trackUninstallAttempt() {
        track(Events.UNINSTALL_ATTEMPT)
    }

    fun trackClockTamperSuspected() {
        track(Events.CLOCK_TAMPER_SUSPECTED)
    }

    fun trackTimezoneChanged(oldTz: String, newTz: String) {
        track(
            Events.TIMEZONE_CHANGED,
            "old_tz" to oldTz,
            "new_tz" to newTz
        )
    }

    /**
     * Track de outcome del padre (T33).
     * Cadencia: quincenal (cada 15 días)
     * Rating: POSITIVE, NEUTRAL, NEGATIVE
     */
    fun trackParentOutcomeCheckin(
        rating: OutcomeRating,
        comment: String? = null,
        periodStart: String? = null,
        periodEnd: String? = null
    ) {
        val props = mutableMapOf<String, String>(
            "rating" to rating.name
        )
        comment?.let { props["has_comment"] = "true" }
        periodStart?.let { props["period_start"] = it }
        periodEnd?.let { props["period_end"] = it }

        track(Events.PARENT_OUTCOME_CHECKIN, props)
    }

    /**
     * Rating del check-in de outcome.
     */
    enum class OutcomeRating {
        POSITIVE, // 😊
        NEUTRAL, // 😐
        NEGATIVE // ☹️
    }

    /**
     * Obtiene eventos no sincronizados para subir al backend.
     */
    suspend fun getUnsyncedEvents(limit: Int = 100): List<BehavioralEventEntity> {
        return try {
            eventDao.getUnsyncedEvents(limit)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting unsynced events: ${e.message}")
            emptyList()
        }
    }

    /**
     * Marca eventos como sincronizados.
     */
    suspend fun markSynced(eventIds: List<Long>) {
        try {
            eventDao.markSynced(eventIds)
            Log.d(TAG, "Marked ${eventIds.size} events as synced")
        } catch (e: Exception) {
            Log.e(TAG, "Error marking events as synced: ${e.message}")
        }
    }

    /**
     * Limpia eventos antiguos sincronizados.
     */
    suspend fun cleanupOldEvents(olderThanDays: Int = 7) {
        try {
            eventDao.deleteOldSyncedEvents(olderThanDays)
            Log.d(TAG, "Cleaned up old synced events")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up old events: ${e.message}")
        }
    }

    /**
     * Codifica props a JSON simple.
     */
    private fun encodeProps(props: Map<String, String>): String {
        if (props.isEmpty()) return "{}"
        return props.entries.joinToString(",", "{", "}") { "\"${it.key}\":\"${it.value}\"" }
    }
}

/**
 * Hilt [EntryPoint] that exposes [AnalyticsManager] from the
 * `SingletonComponent` to non-Hilt call sites.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AnalyticsManagerEntryPoint {
    fun analyticsManager(): AnalyticsManager
}
