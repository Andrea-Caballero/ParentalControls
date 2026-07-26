package com.tudominio.parentalcontrol.enforcement

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import com.tudominio.parentalcontrol.accessibility.AppMonitorService
import com.tudominio.parentalcontrol.admin.LockManager
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.data.model.AppPolicyEntity
import com.tudominio.parentalcontrol.data.model.GrantEntity
import com.tudominio.parentalcontrol.data.model.PolicyEntity
import com.tudominio.parentalcontrol.deviceowner.DeviceCapability
import com.tudominio.parentalcontrol.deviceowner.DeviceOwnerManager
import com.tudominio.parentalcontrol.deviceowner.EnforcementLevel
import com.tudominio.parentalcontrol.domain.*
import com.tudominio.parentalcontrol.overlay.BlockOverlayService
import com.tudominio.parentalcontrol.reconciliation.UsageStatsReconciler
import com.tudominio.parentalcontrol.service.MonitorForegroundService
import com.tudominio.parentalcontrol.time.DefaultTimeProvider
import com.tudominio.parentalcontrol.time.TimeProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Controlador central de enforcement.
 * Orchestra T02 (motor), T03 (Room), T05 (T05), T06 (contador), T08 (overlay), T09 (lock).
 *
 * Ciclo funcional:
 * 1. Evento de accesibilidad (T05) → motor (T02) evalúa sobre Room (T03) → decisión
 * 2. BLOQUEAR → overlay (T08) + HOME; si locked → lockNow() (T09)
 * 3. PERMITIR → contador (T06) marca foreground
 * 4. Reevaluar cuando usage_today cruza un umbral
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EnforcementController(
    private val context: Context,
    private val database: ParentalDatabase,
    private val timeProvider: TimeProvider,
    private val authManager: DeviceAuthManager = DeviceAuthManager.getInstance(context),
    private val lockManager: LockManager = LockManager(context),
) {
    companion object {
        private const val THRESHOLD_RECHECK_MINUTES = 5 // Reevaluar cada 5 minutos

        @Volatile
        private var instance: EnforcementController? = null

        fun getInstance(context: Context, database: ParentalDatabase): EnforcementController {
            return instance ?: synchronized(this) {
                instance ?: EnforcementController(context, database, DefaultTimeProvider(context)).also {
                    instance = it
                }
            }
        }
    }

    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val reconciler = UsageStatsReconciler(context, database)
    private val deviceOwnerManager = DeviceOwnerManager.getInstance(context)

    /**
     * F2b — the child device id under which the local Room cache
     * is observed. Pre-fix was the hardcoded literal `"default"`,
     * which never matched the real id written by
     * [com.tudominio.parentalcontrol.sync.SyncManager.applyPolicy].
     * Falls back to `"default"` when the device is not yet paired
     * (defensive — the controller is only active when paired, so
     * this is unused in practice).
     */
    private fun effectiveDeviceId(): String =
        authManager.deviceId.value ?: "default"

    // Estado actual
    private var currentPolicy: Policy? = null
    private var currentGrants: List<Grant> = emptyList()
    private var currentAppPolicies: List<AppPolicy> = emptyList()
    private var lastEvaluatedPackage: String? = null
    private var lastEvaluationTime: Long = 0L
    private var isBlocked = false

    // Flow para decisiones
    private val _decisionFlow = MutableSharedFlow<EnforcementDecision>(replay = 1)
    val decisionFlow: SharedFlow<EnforcementDecision> = _decisionFlow.asSharedFlow()

    init {
        observeForegroundChanges()
        loadCurrentPolicy()
        loadGrants()
        loadAppPolicies()
    }

    /**
     * Carga la política desde Room (T03). F2b — observes the row
     * for the REAL device id (or `"default"` fallback). Re-subscribes
     * when the device id changes (e.g. after a fresh pairing
     * flips `authManager.deviceId` from null to a real id).
     */
    private fun loadCurrentPolicy() {
        controllerScope.launch {
            authManager.deviceId
                .flatMapLatest { id ->
                    database.policyDao().getPolicyFlow(id ?: "default")
                }
                .collect { entity ->
                    entity?.let {
                        currentPolicy = it.toPolicy(currentGrants, currentAppPolicies)
                        onPolicyDeviceStateChanged(it.device_state)
                    }
                }
        }
    }

    /**
     * F2a — react to the parent's `device_state` (LOCKED / ACTIVE)
     * observed in the policy table. On LOCKED we call
     * [LockManager.lockNow] so the child locks its screen without
     * waiting for an FCM push. On ACTIVE we no-op (the parent has
     * unlocked; the next accessibility event re-evaluates). The
     * `lastAppliedDeviceState` guard makes the call idempotent so
     * a re-emit of the same row doesn't re-fire the lock.
     */
    private var lastAppliedDeviceState: String? = null
    private fun onPolicyDeviceStateChanged(deviceState: String) {
        if (deviceState == lastAppliedDeviceState) return
        lastAppliedDeviceState = deviceState
        if (deviceState == DeviceState.LOCKED.name) {
            // The parent locked the device. Apply the lock locally
            // so the child screen locks immediately on the next sync
            // (NextSync propagation; FCM is OUT OF SCOPE per the
            // residual limitations).
            runCatching { lockManager.lockNow() }
        }
    }

    /**
     * Carga las app_policies desde Room para el dispositivo actual.
     * F2b — observes the real device id.
     */
    private fun loadAppPolicies() {
        controllerScope.launch {
            authManager.deviceId
                .flatMapLatest { id ->
                    database.appPolicyDao()
                        .getAppPoliciesForDeviceFlow(id ?: "default")
                }
                .collect { entities ->
                    currentAppPolicies = entities.mapNotNull { it.toAppPolicyOrNull() }
                    // Re-evalúa con la política actual ya cargada para que tome los nuevos app policies
                    currentPolicy?.let { currentPolicy = it.copy(app_policies = currentAppPolicies) }
                }
        }
    }

    /**
     * Carga los grants activos desde Room. F2b — observes the real
     * device id.
     */
    private fun loadGrants() {
        controllerScope.launch {
            authManager.deviceId
                .flatMapLatest { id ->
                    val now = java.time.Instant.now().toString()
                    database.grantDao()
                        .getActiveGrantsFlow(id ?: "default", now)
                }
                .collect { entities ->
                    currentGrants = entities.map { it.toGrant() }
                    currentPolicy = currentPolicy?.copy(grants = currentGrants)
                }
        }
    }

    /**
     * Observa cambios de foreground desde T05.
     */
    private fun observeForegroundChanges() {
        controllerScope.launch {
            AppMonitorService.appInForeground.collect { packageName ->
                packageName?.let { evaluateAndEnforce(it) }
            }
        }
    }

    /**
     * Evalúa una app y ejecuta la decisión.
     */
    fun evaluateAndEnforce(packageName: String) {
        val policy = currentPolicy ?: return

        // Evitar reevaluaciones frecuentes
        if (packageName == lastEvaluatedPackage &&
            SystemClock.elapsedRealtime() - lastEvaluationTime < 1000
        ) {
            return
        }

        lastEvaluatedPackage = packageName
        lastEvaluationTime = SystemClock.elapsedRealtime()

        // Evaluar con el motor (T02)
        val now = LocalDateTime.now(timeProvider.currentZoneId())
        val decision = evaluar(policy, packageName, UsageContext.empty(), now, timeProvider.currentZoneId())

        // Ejecutar decisión
        controllerScope.launch {
            executeDecision(packageName, decision, policy)
        }
    }

    /**
     * Ejecuta la decisión del motor.
     */
    private suspend fun executeDecision(packageName: String, decision: Decision, policy: Policy) {
        val enforcementDecision = when (decision) {
            is Decision.Permitir -> {
                isBlocked = false
                EnforcementDecision.Allowed(packageName)
            }
            is Decision.Bloquear -> {
                isBlocked = true
                handleBlocked(packageName, decision.motivo, policy)
                EnforcementDecision.Blocked(packageName, decision.motivo)
            }
        }

        _decisionFlow.emit(enforcementDecision)
    }

    /**
     * Maneja el caso de app bloqueada.
     * El mismo motor (T02) sirve a ambos niveles, degradando acciones en STANDARD.
     */
    private fun handleBlocked(packageName: String, motivo: String, policy: Policy) {
        val level = deviceOwnerManager.getEnforcementLevel()
        val capabilities = deviceOwnerManager.getAvailableCapabilities()

        // DEVICE_OWNER: Hard enforcement
        if (level == EnforcementLevel.DEVICE_OWNER) {
            // Usar suspensión o ocultamiento (hard block)
            if (capabilities.contains(DeviceCapability.PACKAGE_SUSPENSION)) {
                deviceOwnerManager.suspendPackage(packageName)
            } else if (capabilities.contains(DeviceCapability.APP_HIDING)) {
                deviceOwnerManager.hideApplication(packageName)
            }

            // Ir a home
            goToHome()
            return
        }

        // STANDARD: Soft enforcement (overlay + warnings)
        // El mismo motor, acción degradada

        // Si device_state es LOCKED, usar lockNow (T09)
        if (policy.device_state == DeviceState.LOCKED) {
            lockManager.lockNow()
            return
        }

        // Si no es locked, mostrar overlay (T08) e ir a home
        BlockOverlayService.show(context, motivo) {
            // T28: el botón "Pedir permiso" del overlay ahora navega al
            // flujo de tiempo extra vía deeplink. BlockOverlayService es
            // un Service (no puede mutar el grafo Compose directamente),
            // así que dispara `parentalcontrol://request-extra-time?pkg=…`
            // y MainActivity lo captura. Se oculta el overlay ANTES de
            // lanzar la activity para que el chico no vea el overlay
            // encima del formulario. `FLAG_ACTIVITY_NEW_TASK` es
            // obligatorio porque `context` aquí es application context
            // (no un Activity); `FLAG_ACTIVITY_CLEAR_TOP` + `SINGLE_TOP`
            // evitan apilar instancias de MainActivity si el chico ya
            // la tenía abierta.
            BlockOverlayService.hide(context)
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("parentalcontrol://request-extra-time?pkg=$packageName")
            ).apply {
                setPackage(context.packageName)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                component = android.content.ComponentName(
                    context,
                    com.tudominio.parentalcontrol.MainActivity::class.java
                )
            }
            context.startActivity(intent)
        }

        // Ir a home
        goToHome()
    }

    /**
     * Obtiene el nivel de enforcement actual.
     */
    fun getEnforcementLevel(): EnforcementLevel {
        return deviceOwnerManager.getEnforcementLevel()
    }

    /**
     * Obtiene las capacidades disponibles.
     */
    fun getAvailableCapabilities(): Set<DeviceCapability> {
        return deviceOwnerManager.getAvailableCapabilities()
    }

    /**
     * Obtiene el estado del Device Owner.
     */
    fun getOwnerStatus(): com.tudominio.parentalcontrol.deviceowner.DeviceOwnerStatus {
        return deviceOwnerManager.getOwnerStatus()
    }

    /**
     * Navega al home del sistema.
     */
    private fun goToHome() {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Si falla, ignorar
        }
    }

    /**
     * Callback cuando el usuario pide permiso (T28).
     *
     * Wired inline in [handleBlocked] — the deeplink
     * `parentalcontrol://request-extra-time?pkg=…` is fired from there
     * because it needs the `packageName` and the `Context` already in
     * scope. Kept as a private hook in case a future caller needs the
     * intent built standalone (e.g. a deep-link shortcut from a
     * notification action).
     */
    @Suppress("unused")
    private fun buildRequestExtraTimeIntent(packageName: String): Intent =
        Intent(
            Intent.ACTION_VIEW,
            Uri.parse("parentalcontrol://request-extra-time?pkg=$packageName")
        ).apply {
            setPackage(context.packageName)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            component = android.content.ComponentName(
                context,
                com.tudominio.parentalcontrol.MainActivity::class.java
            )
        }

    /**
     * Reevalúa cuando el uso cruza un umbral.
     */
    fun reevaluateOnThreshold(passedMinutes: Int) {
        val policy = currentPolicy ?: return

        // Verificar si cruzamos un umbral
        if (passedMinutes > 0 && passedMinutes % THRESHOLD_RECHECK_MINUTES == 0) {
            lastEvaluatedPackage?.let { evaluateAndEnforce(it) }
        }
    }

    /**
     * Fuerza una reevaluación.
     */
    fun forceReevaluation() {
        lastEvaluatedPackage?.let { evaluateAndEnforce(it) }
    }

    /**
     * Actualiza la política mock.
     */
    fun setPolicy(policy: Policy) {
        currentPolicy = policy
        // Guardar en Room
        controllerScope.launch {
            database.policyDao().upsertPolicyIfNewer(policy.toEntity())
        }
        // Reevaluar con la nueva política
        forceReevaluation()
    }

    /**
     * Obtiene el estado actual de enforcement.
     */
    fun getStatus(): EnforcementStatus {
        return EnforcementStatus(
            isBlocked = isBlocked,
            currentPackage = lastEvaluatedPackage,
            hasPolicy = currentPolicy != null,
            isAdminActive = lockManager.isAdminActive()
        )
    }
}

/**
 * Decisiones de enforcement.
 */
sealed class EnforcementDecision {
    abstract val packageName: String

    data class Allowed(override val packageName: String) : EnforcementDecision()
    data class Blocked(override val packageName: String, val reason: String) : EnforcementDecision()
}

/**
 * Estado del enforcement.
 */
data class EnforcementStatus(
    val isBlocked: Boolean,
    val currentPackage: String?,
    val hasPolicy: Boolean,
    val isAdminActive: Boolean
)

/**
 * Extensiones para conversión.
 */
private fun PolicyEntity.toPolicy(
    grants: List<Grant> = emptyList(),
    appPolicies: List<AppPolicy> = emptyList()
): Policy {
    // F2a — parse the persisted `device_state` column (default
    // ACTIVE) so a parent lock surfaces in
    // [EnforcementController.observePolicy] and triggers the lock
    // callback. Unknown values fall back to ACTIVE so a corrupted
    // column doesn't block local enforcement.
    val parsedDeviceState = runCatching {
        DeviceState.valueOf(device_state)
    }.getOrDefault(DeviceState.ACTIVE)
    return Policy(
        device_id = device_id,
        version = version.toInt(),
        device_state = parsedDeviceState,
        daily_screen_time_minutes = 120,
        schedules = emptyList(),
        category_limits = emptyList(),
        app_policies = appPolicies,
        category_assignments = category_assignments,
        grants = grants
    )
}

private fun AppPolicyEntity.toAppPolicyOrNull(): AppPolicy? {
    val mappedState = runCatching { AppPolicyState.valueOf(state) }.getOrNull() ?: return null
    val mappedWindows = allowed_windows.mapNotNull { windowEntity ->
        runCatching {
            Window(
                days = windowEntity.days.map { DayOfWeek.valueOf(it) },
                from = windowEntity.from,
                to = windowEntity.to
            )
        }.getOrNull()
    }
    return AppPolicy(
        package_name = package_name,
        state = mappedState,
        daily_limit_minutes = daily_limit_minutes,
        allowed_windows = mappedWindows,
        category = category
    )
}

private fun GrantEntity.toGrant(): Grant {
    return Grant(
        id = id,
        request_id = request_id,
        scope = scope,
        minutes = minutes,
        source = GrantSource.valueOf(source.uppercase()),
        granted_at = granted_at,
        expires_at = expires_at
    )
}

private fun Policy.toEntity(): PolicyEntity {
    return PolicyEntity(
        device_id = device_id,
        version = version.toLong(),
        category_assignments = category_assignments
    )
}
