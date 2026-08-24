package com.tudominio.parentalcontrol.enforcement

import android.content.Context
import android.content.Intent
import com.tudominio.parentalcontrol.accessibility.AppMonitorService
import com.tudominio.parentalcontrol.admin.LockManager
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.data.local.LocalDataSource
import com.tudominio.parentalcontrol.data.model.PolicyEntity
import com.tudominio.parentalcontrol.data.model.PolicySnapshotState
import com.tudominio.parentalcontrol.deviceowner.DeviceCapability
import com.tudominio.parentalcontrol.deviceowner.DeviceOwnerManager
import com.tudominio.parentalcontrol.deviceowner.DeviceOwnerStatus
import com.tudominio.parentalcontrol.deviceowner.EnforcementLevel
import com.tudominio.parentalcontrol.domain.Decision
import com.tudominio.parentalcontrol.domain.DeviceState
import com.tudominio.parentalcontrol.domain.Grant
import com.tudominio.parentalcontrol.domain.Policy
import com.tudominio.parentalcontrol.domain.UsageContext
import com.tudominio.parentalcontrol.domain.evaluateWithoutTrustedTime
import com.tudominio.parentalcontrol.domain.evaluar
import com.tudominio.parentalcontrol.domain.isCriticalApp
import com.tudominio.parentalcontrol.overlay.BlockOverlayServiceGateway
import com.tudominio.parentalcontrol.overlay.OverlayGateway
import com.tudominio.parentalcontrol.overlay.OverlayHandle
import com.tudominio.parentalcontrol.overlay.OverlayHideResult
import com.tudominio.parentalcontrol.overlay.OverlayShowResult
import com.tudominio.parentalcontrol.overlay.OverlayOutcome
import com.tudominio.parentalcontrol.time.TimeProvider
import com.tudominio.parentalcontrol.time.TrustedTimeState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import java.time.Duration
import java.time.Instant

private const val MAX_DATE_ROLLOVER_DELAY_MS = 2 * 24 * 60 * 60 * 1_000L
private const val SHUTDOWN_CLEANUP_TIMEOUT_MS = 2_000L

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private fun automaticUsageDateFlow(timeProvider: TimeProvider): Flow<String> =
    combine(timeProvider.trustedTimeState, timeProvider.zoneChanges()) { state, zone -> state to zone }
        .flatMapLatest { (state, zone) ->
            if (state is TrustedTimeState.Unavailable) emptyFlow() else flow {
                while (currentCoroutineContext().isActive) {
                    val now = timeProvider.trustedNow() ?: break
                    emit(now.atZone(zone).toLocalDate().toString())
                    val next = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
                    delay(Duration.between(now, next).toMillis().coerceIn(1L, MAX_DATE_ROLLOVER_DELAY_MS))
                }
            }
        }.distinctUntilChanged()

/** Serial owner of all enforcement state and effects. */
class EnforcementController(
    private val context: Context,
    private val database: ParentalDatabase,
    private val timeProvider: TimeProvider,
    private val authManager: DeviceAuthManager = DeviceAuthManager.getInstance(context),
    private val lockManager: LockManager = LockManager(context),
    private val ownerManager: DeviceOwnerManager = DeviceOwnerManager.getInstance(context),
    private val usageContextFlowProvider: (String, String) -> Flow<UsageContext> =
        { deviceId, dateKey -> LocalDataSource(database).getUsageContextFlow(deviceId, dateKey) },
    private val usageDateFlowProvider: () -> Flow<String> = { automaticUsageDateFlow(timeProvider) },
    private val overlayGateway: OverlayGateway = BlockOverlayServiceGateway(context),
    private val controllerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    companion object {
        private const val THRESHOLD_RECHECK_INTERVAL_MINUTES = 5
        private const val MAX_LOCK_RETRIES = 2
        private const val LOCK_RETRY_DELAY_MS = 1_000L
        private const val MAX_CLEANUP_RETRIES = 3
        private const val CLEANUP_RETRY_DELAY_MS = 1_000L
        private const val MAX_OWNERSHIP_DEBT_PACKAGES = 32
        @Volatile private var instance: EnforcementController? = null
        fun getInstance(context: Context, database: ParentalDatabase): EnforcementController =
            instance ?: synchronized(this) {
                instance ?: EnforcementController(
                    context, database,
                    EntryPointAccessors.fromApplication(
                        context.applicationContext, EnforcementControllerEntryPoint::class.java
                    ).timeProvider()
                ).also { instance = it }
            }
    }

    private sealed interface Event {
        data class Foreground(val packageName: String) : Event
        data class Policy(val state: PolicySnapshotState) : Event
        data class Trusted(val state: TrustedTimeState) : Event
        data class Usage(val usage: UsageContext) : Event
        data class DateChanged(val date: String) : Event
        data class Expired(val policyGeneration: Long, val timeGeneration: Long, val target: Instant) : Event
        data class LockRetry(val policyGeneration: Long, val timeGeneration: Long, val attempt: Int) : Event
        data class CleanupRetry(val attempt: Int) : Event
        data object Force : Event
        data class Threshold(val minutes: Int) : Event
    }

    private enum class Operation { OVERLAY, SUSPEND, HIDE, LOCK, HOME }
    private data class EffectKey(val packageName: String, val operation: Operation)
    private val events = Channel<Event>(Channel.UNLIMITED)

    // These are immutable snapshots for the existing synchronous API/reflection tests.
    @Volatile private var currentPolicy: Policy? = null
    @Volatile private var currentPolicyState: PolicySnapshotState = PolicySnapshotState.Empty
    @Volatile private var currentGrants: List<Grant> = emptyList()
    @Volatile private var currentUsage: UsageContext = UsageContext.empty()
    @Volatile private var invalidPolicyReason: String? = null
    @Volatile private var lastEvaluatedPackage: String? = null
    @Volatile private var isBlocked = false
    @Volatile private var statusSnapshot = EnforcementStatus(false, null, false, false)

    private val _decisionFlow = MutableSharedFlow<EnforcementDecision>(replay = 1)
    val decisionFlow: SharedFlow<EnforcementDecision> = _decisionFlow.asSharedFlow()

    init {
        observeForeground()
        observePolicy()
        observeUsage()
        observeTrustedTime()
        observeGrantQueries()
        controllerScope.launch { runActor() }
    }

    private fun enqueue(event: Event) {
        events.trySend(event)
    }

    private fun observeForeground() = controllerScope.launch {
        AppMonitorService.appInForeground.collect { it?.let { enqueue(Event.Foreground(it)) } }
    }

    private fun observePolicy() = controllerScope.launch {
        authManager.deviceId.flatMapLatest { id ->
            if (id.isNullOrBlank()) flowOf(PolicySnapshotState.Empty)
            else LocalDataSource(database).getPolicySnapshotStateFlow(id)
        }.distinctUntilChanged().collect { enqueue(Event.Policy(it)) }
    }

    private fun observeUsage() = controllerScope.launch {
        combine(authManager.deviceId, usageDateFlowProvider()) { id, date -> id to date }
            .distinctUntilChanged().flatMapLatest { (id, date) ->
                if (id.isNullOrBlank()) flowOf(UsageContext.empty()) else usageContextFlowProvider(id, date)
            }.distinctUntilChanged().collect { enqueue(Event.Usage(it)) }
    }

    private fun observeTrustedTime() = controllerScope.launch {
        timeProvider.trustedTimeState.drop(1).collect { enqueue(Event.Trusted(it)) }
    }

    private fun observeGrantQueries() = controllerScope.launch {
        combine(authManager.deviceId, timeProvider.trustedTimeState) { id, state -> id to state }
            .flatMapLatest { (id, state) ->
                val now = timeProvider.trustedNow()
                if (id.isNullOrBlank() || state !is TrustedTimeState.Available || now == null) {
                    emptyFlow()
                } else {
                    database.grantDao().getActiveGrantsFlow(id, now.toString())
                }
            }.collect { /* Policy snapshots remain the canonical grant source. */ }
    }

    private suspend fun runActor() {
        var foregroundGeneration = 0L
        var policyGeneration = 0L
        var timeGeneration = 0L
        var foreground: String? = null
        var policyState: PolicySnapshotState = PolicySnapshotState.Empty
        var policy: Policy? = null
        var persistedPolicy: Policy? = null
        var usage = UsageContext.empty()
        var trusted = timeProvider.trustedTimeState.value
        var expiryJob: Job? = null
        var lockRetryJob: Job? = null
        var cleanupRetryJob: Job? = null
        val ledger = mutableSetOf<EffectKey>()
        val pendingCleanup = mutableSetOf<EffectKey>()
        val overlayHandles = mutableMapOf<EffectKey, OverlayHandle>()
        var invalidReason: String? = null
        var enforcementDegradedReason: String? = null

        suspend fun clearPackage(packageName: String): Boolean {
            var success = clearOperation(packageName, Operation.OVERLAY, ledger, pendingCleanup, overlayHandles)
            success = clearOperation(packageName, Operation.SUSPEND, ledger, pendingCleanup) && success
            success = clearOperation(packageName, Operation.HIDE, ledger, pendingCleanup) && success
            return success
        }

        suspend fun clearAll(): Boolean {
            var success = true
            ledger.map { it.packageName }.toSet().forEach {
                success = clearPackage(it) && success
            }
            return success && ledger.isEmpty()
        }

        fun scheduleCleanupRetry(attempt: Int) {
            if (attempt >= MAX_CLEANUP_RETRIES || cleanupRetryJob?.isActive == true) return
            cleanupRetryJob = controllerScope.launch {
                delay(CLEANUP_RETRY_DELAY_MS)
                enqueue(Event.CleanupRetry(attempt + 1))
            }
        }

        suspend fun publish(packageName: String, decision: Decision, generation: Long, retry: Boolean) {
            if (generation != foregroundGeneration || packageName != foreground) return
            val allowed = decision is Decision.Permitir
            var effective = allowed || (isBlocked && lastEvaluatedPackage == packageName); var cleanupProven = true
            if (allowed) {
                cleanupProven = clearAll() && ledger.isEmpty()
                if (cleanupProven) {
                    cleanupRetryJob?.cancel(); cleanupRetryJob = null
                    enforcementDegradedReason = null
                } else {
                    enforcementDegradedReason = "Cleanup of a previous blocking effect failed"
                    scheduleCleanupRetry(0)
                }
            } else if (!isBlocked || lastEvaluatedPackage != packageName || retry) {
                effective = applyBlocked(packageName, decision, policy, ledger, pendingCleanup, overlayHandles)
                enforcementDegradedReason = when {
                    !effective -> "No effective blocking barrier established"
                    pendingCleanup.isNotEmpty() -> "Cleanup of a previous blocking effect failed"
                    else -> null
                }
                if (!effective && ledger.isNotEmpty()) scheduleCleanupRetry(0)
            }
            val wasBlocked = isBlocked && lastEvaluatedPackage == packageName
            if (!allowed && !wasBlocked && effective) applyHome()
            isBlocked = if (allowed && !cleanupProven) true else !allowed && effective
            lastEvaluatedPackage = packageName
            updateSnapshot(policyState, policy, invalidReason, foreground, isBlocked, enforcementDegradedReason)
            _decisionFlow.emit(if (allowed && cleanupProven) EnforcementDecision.Allowed(packageName)
            else EnforcementDecision.Blocked(packageName, if (allowed) "Cleanup of a previous blocking effect failed" else (decision as Decision.Bloquear).motivo))
        }

        suspend fun evaluate(retry: Boolean = false) {
            val packageName = foreground ?: return
            val generation = foregroundGeneration
            val state = policyState
            if (state is PolicySnapshotState.Empty) return
            if (state is PolicySnapshotState.Invalid) {
                publish(packageName, if (isCriticalApp(packageName)) Decision.Permitir
                else Decision.Bloquear("Policy invalid: ${state.reason}"), generation, retry)
                return
            }
            val activePolicy = policy ?: return
            val now = timeProvider.trustedNow()
            val decision = if (now == null) {
                evaluateWithoutTrustedTime(activePolicy, packageName)
                    ?: Decision.Bloquear("Trusted time unavailable")
            } else {
                evaluar(activePolicy, packageName, usage, now.atZone(timeProvider.currentZoneId()).toLocalDateTime(), timeProvider.currentZoneId())
            }
            publish(packageName, decision, generation, retry)
        }

        try {
            for (event in events) when (event) {
                is Event.Foreground -> {
                    if (event.packageName != foreground) {
                        foregroundGeneration++
                        val cleaned = foreground?.let { clearPackage(it) } ?: true
                        if (!cleaned) {
                            enforcementDegradedReason = "Cleanup of a previous blocking effect failed"
                            scheduleCleanupRetry(0)
                        }
                        foreground = event.packageName
                    }
                    evaluate()
                }
                is Event.Policy -> {
                    if (event.state == policyState) continue
                    policyGeneration++
                    policyState = event.state
                    currentPolicyState = event.state
                    when (event.state) {
                        PolicySnapshotState.Empty -> {
                            expiryJob?.cancel(); expiryJob = null
                            lockRetryJob?.cancel(); lockRetryJob = null
                            val cleaned = clearAll()
                            if (cleaned) {
                                enforcementDegradedReason = null
                            } else {
                                enforcementDegradedReason = "Cleanup of a previous blocking effect failed"
                                scheduleCleanupRetry(0)
                            }
                            policy = null; persistedPolicy = null; currentPolicy = null; currentGrants = emptyList()
                            invalidReason = null; isBlocked = false
                        }
                        is PolicySnapshotState.Invalid -> {
                            expiryJob?.cancel(); expiryJob = null
                            lockRetryJob?.cancel(); lockRetryJob = null
                            policy = null; persistedPolicy = null; currentPolicy = null; currentGrants = emptyList()
                            invalidReason = event.state.reason
                            enforcementDegradedReason = if (ledger.isEmpty()) null
                            else "Cleanup of a previous blocking effect failed"
                            if (ledger.isNotEmpty()) scheduleCleanupRetry(0)
                            evaluate(retry = true)
                        }
                        is PolicySnapshotState.Valid -> {
                            lockRetryJob?.cancel(); lockRetryJob = null
                            persistedPolicy = event.state.policy
                            policy = event.state.policy.copy(grants = activeGrants(event.state.policy.grants))
                            currentGrants = policy?.grants.orEmpty()
                            currentPolicy = event.state.policy
                            invalidReason = null
                            enforcementDegradedReason = if (ledger.isEmpty()) null
                            else "Cleanup of a previous blocking effect failed"
                            if (ledger.isNotEmpty()) scheduleCleanupRetry(0)
                            if (policy?.device_state == DeviceState.LOCKED && foreground == null) {
                                lockRetryJob = enforceLockedWithoutForeground(policyGeneration, timeGeneration, 0) { p, t, attempt ->
                                    enqueue(Event.LockRetry(p, t, attempt))
                                }
                            }
                            expiryJob?.cancel()
                            expiryJob = scheduleExpiry(event.state.policy.grants, policyGeneration, timeGeneration) { p, t, target ->
                                enqueue(Event.Expired(p, t, target))
                            }
                            evaluate(retry = true)
                        }
                    }
                    if (policy?.device_state != DeviceState.LOCKED || foreground != null) {
                        updateSnapshot(policyState, policy, invalidReason, foreground, isBlocked, enforcementDegradedReason)
                    }
                }
                is Event.Trusted -> {
                    trusted = event.state
                    timeGeneration++
                    if (trusted is TrustedTimeState.Unavailable) {
                        expiryJob?.cancel(); expiryJob = null
                        policy = policy?.copy(grants = emptyList()); currentPolicy = policy; currentGrants = emptyList()
                    } else {
                        policy = persistedPolicy?.copy(grants = activeGrants(persistedPolicy?.grants.orEmpty()))
                        currentPolicy = policy; currentGrants = policy?.grants.orEmpty()
                            expiryJob?.cancel()
                            persistedPolicy?.let { expiryJob = scheduleExpiry(it.grants, policyGeneration, timeGeneration) { p, t, target ->
                                enqueue(Event.Expired(p, t, target))
                            } }
                    }
                    evaluate(retry = true)
                }
                is Event.Usage -> { usage = event.usage; currentUsage = usage; evaluate() }
                is Event.DateChanged -> { evaluate() }
                is Event.Expired -> if (event.policyGeneration == policyGeneration && event.timeGeneration == timeGeneration &&
                    event.target <= (timeProvider.trustedNow() ?: Instant.MIN)) {
                    expiryJob = null
                    policy = persistedPolicy?.copy(grants = activeGrants(persistedPolicy?.grants.orEmpty()))
                    currentPolicy = policy; currentGrants = policy?.grants.orEmpty()
                    expiryJob = scheduleExpiry(persistedPolicy?.grants.orEmpty(), policyGeneration, timeGeneration) { p, t, target ->
                        enqueue(Event.Expired(p, t, target))
                    }
                    evaluate()
                }
                is Event.LockRetry -> if (event.policyGeneration == policyGeneration && event.timeGeneration == timeGeneration) {
                    lockRetryJob = enforceLockedWithoutForeground(event.policyGeneration, event.timeGeneration, event.attempt) { p, t, attempt ->
                        enqueue(Event.LockRetry(p, t, attempt))
                    }
                }
                is Event.CleanupRetry -> {
                    cleanupRetryJob = null
                    if (clearAll()) {
                        enforcementDegradedReason = null
                        if (foreground != null && policyState is PolicySnapshotState.Valid) evaluate(retry = true)
                        else updateSnapshot(policyState, policy, invalidReason, foreground, isBlocked, enforcementDegradedReason)
                    } else {
                        enforcementDegradedReason = "Cleanup of a previous blocking effect failed"
                        scheduleCleanupRetry(event.attempt)
                        updateSnapshot(policyState, policy, invalidReason, foreground, isBlocked, enforcementDegradedReason)
                    }
                }
                Event.Force -> evaluate(retry = true)
                is Event.Threshold -> if (
                    event.minutes > 0 && event.minutes % THRESHOLD_RECHECK_INTERVAL_MINUTES == 0
                ) evaluate(retry = true)
            }
        } finally {
            expiryJob?.cancel(); lockRetryJob?.cancel(); cleanupRetryJob?.cancel()
            withContext(NonCancellable) {
                withTimeoutOrNull(SHUTDOWN_CLEANUP_TIMEOUT_MS) { clearAll() }
            }
        }
    }

    private fun enforceLockedWithoutForeground(
        policyGeneration: Long,
        timeGeneration: Long,
        attempt: Int,
        enqueue: (Long, Long, Int) -> Unit,
    ): Job? {
        val hasCapability = runCatching { lockManager.isAdminActive() }.getOrDefault(false)
        if (!hasCapability) {
            updateSnapshot(currentPolicyState, currentPolicy, null, null, false, "LOCKED protection unavailable: device admin is inactive")
            return null
        }
        if (runCatching { lockManager.lockNow() }.getOrDefault(false)) {
            updateSnapshot(currentPolicyState, currentPolicy, null, null, true, null)
            return null
        }
        if (attempt >= MAX_LOCK_RETRIES) {
            updateSnapshot(currentPolicyState, currentPolicy, null, null, false, "LOCKED protection failed after retries")
            return null
        }
        return controllerScope.launch {
            delay(LOCK_RETRY_DELAY_MS)
            enqueue(policyGeneration, timeGeneration, attempt + 1)
        }
    }

    private suspend fun scheduleExpiry(
        grants: List<Grant>, policyGeneration: Long, timeGeneration: Long,
        enqueue: (Long, Long, Instant) -> Unit,
    ): Job? {
        val now = timeProvider.trustedNow() ?: return null
        val next = grants.mapNotNull { runCatching { Instant.parse(it.canonicalExpiresAt()) }.getOrNull() }
            .filter { it.isAfter(now) }.minOrNull() ?: return null
        return controllerScope.launch {
            delay(Duration.between(now, next).toMillis().coerceAtLeast(1L))
            enqueue(policyGeneration, timeGeneration, next)
        }
    }

    private fun activeGrants(grants: List<Grant>): List<Grant> {
        val now = timeProvider.trustedNow() ?: return emptyList()
        return grants.filter { runCatching { Instant.parse(it.canonicalExpiresAt()).isAfter(now) }.getOrDefault(false) }
    }

    private suspend fun applyBlocked(
        packageName: String,
        decision: Decision,
        policy: Policy?,
        ledger: MutableSet<EffectKey>,
        pendingCleanup: MutableSet<EffectKey>,
        overlayHandles: MutableMap<EffectKey, OverlayHandle>,
    ): Boolean {
        val owner = ownerManager
        if (owner.getEnforcementLevel() == EnforcementLevel.DEVICE_OWNER) {
            clearOperation(packageName, Operation.OVERLAY, ledger, pendingCleanup, overlayHandles)
            clearOperation(packageName, Operation.LOCK, ledger, pendingCleanup)
            val capabilities = owner.getAvailableCapabilities()
            val suspended = DeviceCapability.PACKAGE_SUSPENSION in capabilities &&
                applyOperation(packageName, Operation.SUSPEND, ledger, pendingCleanup, overlayHandles) {
                    owner.suspendPackage(packageName)
                }
            if (suspended) {
                clearOperation(packageName, Operation.HIDE, ledger, pendingCleanup)
                return true
            }
            val hidden = DeviceCapability.APP_HIDING in capabilities &&
                applyOperation(packageName, Operation.HIDE, ledger, pendingCleanup, overlayHandles) {
                    owner.hideApplication(packageName)
                }
            if (hidden) {
                return true
            }
            clearOperation(packageName, Operation.SUSPEND, ledger, pendingCleanup)
            clearOperation(packageName, Operation.HIDE, ledger, pendingCleanup)
            val overlay = showOverlay(
                packageName, (decision as Decision.Bloquear).motivo, ledger, pendingCleanup, overlayHandles
            )
            return overlay
        } else if (policy?.device_state == DeviceState.LOCKED) {
            clearOperation(packageName, Operation.OVERLAY, ledger, pendingCleanup, overlayHandles)
            clearOperation(packageName, Operation.SUSPEND, ledger, pendingCleanup)
            clearOperation(packageName, Operation.HIDE, ledger, pendingCleanup)
            val lockAvailable = runCatching { lockManager.isAdminActive() }.getOrDefault(false)
            return lockAvailable && runCatching { lockManager.lockNow() }.getOrDefault(false)
        } else {
            clearOperation(packageName, Operation.SUSPEND, ledger, pendingCleanup)
            clearOperation(packageName, Operation.HIDE, ledger, pendingCleanup)
            clearOperation(packageName, Operation.LOCK, ledger, pendingCleanup)
            return showOverlay(
                packageName, (decision as Decision.Bloquear).motivo, ledger, pendingCleanup, overlayHandles
            )
        }
        return false
    }

    private suspend fun clearOperation(
        packageName: String,
        operation: Operation,
        ledger: MutableSet<EffectKey>,
        pendingCleanup: MutableSet<EffectKey>,
        overlayHandles: MutableMap<EffectKey, OverlayHandle> = mutableMapOf(),
    ): Boolean {
        val key = EffectKey(packageName, operation)
        if (key !in ledger) return true
        val success = when (operation) {
            Operation.OVERLAY -> runCatchingOperational {
                when (val handle = overlayHandles[key]) {
                    null -> false
                    else -> when (overlayGateway.hide(handle)) {
                        is OverlayOutcome.Hidden, is OverlayOutcome.AlreadyAbsent -> true
                        is OverlayOutcome.Requested,
                        is OverlayOutcome.Shown,
                        is OverlayOutcome.Lost,
                        is OverlayOutcome.Failed -> false
                    }
                }
            }.getOrDefault(false)
            Operation.SUSPEND -> runCatchingOperational { ownerManager.unsuspendPackage(packageName) }.getOrDefault(false)
            Operation.HIDE -> runCatchingOperational { ownerManager.unhideApplication(packageName) }.getOrDefault(false)
            Operation.LOCK, Operation.HOME -> true
        }
        if (success) {
            ledger.remove(key); pendingCleanup.remove(key); overlayHandles.remove(key)
        } else {
            pendingCleanup += key
        }
        return success
    }

    private suspend fun showOverlay(
        packageName: String,
        reason: String,
        ledger: MutableSet<EffectKey>,
        pendingCleanup: MutableSet<EffectKey>,
        overlayHandles: MutableMap<EffectKey, OverlayHandle>,
    ): Boolean {
        val key = EffectKey(packageName, Operation.OVERLAY)
        if (key in ledger) {
            // An existing ledger entry is already the exact pending handoff. Slice A must not
            // replace its handle or claim physical ownership through a re-show/reconcile call.
            pendingCleanup += key
            return false
        }
        if (!ensureOwnershipCapacity(packageName, ledger, pendingCleanup, overlayHandles)) return false
        return when (val result = runCatchingOperational { overlayGateway.show(packageName, reason) }.getOrNull()) {
            is OverlayOutcome.Shown -> { ledger += key; overlayHandles[key] = result.handle; true }
            is OverlayOutcome.Requested -> {
                ledger += key; overlayHandles[key] = result.handle; pendingCleanup += key; false
            }
            else -> false
        }
    }

    private suspend fun ensureOwnershipCapacity(
        packageName: String,
        ledger: MutableSet<EffectKey>,
        pendingCleanup: MutableSet<EffectKey>,
        overlayHandles: MutableMap<EffectKey, OverlayHandle>,
    ): Boolean {
        if (hasOwnershipCapacity(packageName, ledger)) return true
        for (historicalPackage in ledger.map { it.packageName }.distinct().filter { it != packageName }) {
            clearPackageOwnership(historicalPackage, ledger, pendingCleanup, overlayHandles)
            if (hasOwnershipCapacity(packageName, ledger)) return true
        }
        return false
    }

    private fun hasOwnershipCapacity(packageName: String, ledger: Set<EffectKey>): Boolean =
        packageName in ledger.map { it.packageName }.toSet() ||
            ledger.asSequence().map { it.packageName }.distinct().count() < MAX_OWNERSHIP_DEBT_PACKAGES

    private suspend fun clearPackageOwnership(
        packageName: String,
        ledger: MutableSet<EffectKey>,
        pendingCleanup: MutableSet<EffectKey>,
        overlayHandles: MutableMap<EffectKey, OverlayHandle>,
    ) {
        clearOperation(packageName, Operation.OVERLAY, ledger, pendingCleanup, overlayHandles)
        clearOperation(packageName, Operation.SUSPEND, ledger, pendingCleanup)
        clearOperation(packageName, Operation.HIDE, ledger, pendingCleanup)
        clearOperation(packageName, Operation.LOCK, ledger, pendingCleanup)
    }

    private suspend fun applyOperation(
        packageName: String,
        operation: Operation,
        ledger: MutableSet<EffectKey>,
        pendingCleanup: MutableSet<EffectKey>,
        overlayHandles: MutableMap<EffectKey, OverlayHandle>,
        action: suspend () -> Boolean,
    ): Boolean {
        val key = EffectKey(packageName, operation)
        if (key in ledger) return key !in pendingCleanup
        if (!ensureOwnershipCapacity(packageName, ledger, pendingCleanup, overlayHandles)) return false
        if (runCatchingOperational { action() }.getOrDefault(false)) {
            ledger += key
            return true
        }
        return false
    }

    private fun applyHome() {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME); flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }
    }

    private suspend fun <T> runCatchingOperational(action: suspend () -> T): Result<T> = try {
        Result.success(action())
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        Result.failure(exception)
    }

    private fun updateSnapshot(
        state: PolicySnapshotState,
        policy: Policy?,
        reason: String?,
        packageName: String?,
        blocked: Boolean,
        enforcementReason: String? = null,
    ) {
        currentPolicyState = state; currentPolicy = policy; invalidPolicyReason = reason
        statusSnapshot = EnforcementStatus(blocked, packageName, policy != null, lockManager.isAdminActive(),
            when (state) {
                PolicySnapshotState.Empty -> PolicyHealth.EMPTY
                is PolicySnapshotState.Invalid -> PolicyHealth.DEGRADED_POLICY_INVALID
                is PolicySnapshotState.Valid -> if (enforcementReason != null) PolicyHealth.DEGRADED_ENFORCEMENT else PolicyHealth.HEALTHY
            }, enforcementReason ?: reason)
    }

    fun evaluateAndEnforce(packageName: String) = enqueue(Event.Foreground(packageName))
    fun forceReevaluation() = enqueue(Event.Force)
    fun reevaluateOnThreshold(passedMinutes: Int) = enqueue(Event.Threshold(passedMinutes))

    fun setPolicy(policy: Policy) {
        enqueue(Event.Policy(PolicySnapshotState.Valid(policy)))
        controllerScope.launch { database.policyDao().upsertPolicyIfNewer(policy.toEntity()) }
    }

    fun getEnforcementLevel(): EnforcementLevel = ownerManager.getEnforcementLevel()
    fun getAvailableCapabilities(): Set<DeviceCapability> = ownerManager.getAvailableCapabilities()
    fun getOwnerStatus(): DeviceOwnerStatus = ownerManager.getOwnerStatus()
    fun getStatus(): EnforcementStatus = statusSnapshot
    fun close() { events.close(); controllerScope.cancel() }
}

sealed class EnforcementDecision {
    abstract val packageName: String
    data class Allowed(override val packageName: String) : EnforcementDecision()
    data class Blocked(override val packageName: String, val reason: String) : EnforcementDecision()
}

data class EnforcementStatus(
    val isBlocked: Boolean, val currentPackage: String?, val hasPolicy: Boolean, val isAdminActive: Boolean,
    val policyHealth: PolicyHealth = PolicyHealth.EMPTY, val policyInvalidReason: String? = null,
)
enum class PolicyHealth { HEALTHY, EMPTY, DEGRADED_POLICY_INVALID, DEGRADED_ENFORCEMENT }

private fun Policy.toEntity() = PolicyEntity(device_id, version.toLong(), category_assignments, device_state.name,
    daily_screen_time_minutes, schedules, category_limits)

@EntryPoint
@InstallIn(SingletonComponent::class)
interface EnforcementControllerEntryPoint { fun timeProvider(): TimeProvider }
