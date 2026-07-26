package com.tudominio.parentalcontrol.sync

import android.content.Context
import android.util.Log
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.data.model.AppPolicyEntity
import com.tudominio.parentalcontrol.data.model.OutboxEntity
import com.tudominio.parentalcontrol.data.model.PolicyEntity
import com.tudominio.parentalcontrol.data.model.WindowEntity
import com.tudominio.parentalcontrol.data.repository.GrantResult
import com.tudominio.parentalcontrol.data.repository.TimeExtraRepository
import com.tudominio.parentalcontrol.di.SupabaseClient
import com.tudominio.parentalcontrol.network.ConnectionState
import com.tudominio.parentalcontrol.network.SupabaseClientProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import java.time.ZoneOffset
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

enum class OutboxEventType {
    USAGE_LOG,
    DEVICE_ALERT,
    BEHAVIORAL_EVENT,
    TIME_REQUEST,
    HEARTBEAT
}

sealed class SyncResult {
    data object Success : SyncResult()
    data class PartialSuccess(val failedItems: Int) : SyncResult()
    data class Error(val message: String) : SyncResult()
    data object Offline : SyncResult()
}

/**
 * Result of a single outbox-item send attempt.
 *
 * - [Success]: the receiver accepted the row.
 * - [RetryableFailure]: a transient error (5xx, 408, 429, IOException). The
 *   row is still in the outbox and will be retried on the next drain cycle.
 * - [PermanentFailure]: a non-recoverable error (4xx other than 408/429).
 *   The caller decides whether to mark the row processed (so the worker does
 *   not loop forever) or to keep it pending for a manual fix-up.
 */
sealed class OutboxSendResult {
    data object Success : OutboxSendResult()
    data class RetryableFailure(val cause: Throwable) : OutboxSendResult()
    data class PermanentFailure(val statusCode: Int) : OutboxSendResult()
}

enum class SyncStatus {
    IDLE,
    SYNCING,
    OFFLINE,
    PENDING_ITEMS,
    ERROR
}

@Serializable
data class PolicyPullResponse(
    val policy_id: String? = null,
    val version: Long,
    val category_assignments: Map<String, String>? = null,
    val app_policies: List<AppPolicyResponse>? = null,
    val server_time: Long? = null,
    // R4 — child reads the parent's `device_state` (LOCKED / ACTIVE)
    // here so the local enforcement flow can pick up a parent's lock
    // or unlock on the next pullPolicy cycle. The shared Python mock
    // and the production `get-policy` edge function both include
    // this field in their response; without it, a parent-side
    // `lockDevice` would have no propagation path beyond the
    // device's current heartbeat.
    val device_state: String? = null
)

@Serializable
data class AppPolicyResponse(
    val package_name: String,
    val state: String,
    val daily_limit_minutes: Int? = null,
    val allowed_windows: List<String>? = null
)

/**
 * Wire shape of a `time_requests` row that the child pulls to learn about
 * approvals. The mock server (and production Supabase) returns
 * `minutes_approved` (defaults to `minutes_requested` if the parent omits
 * it) and `resolved_at` (epoch-iso). `id` is the request id the child
 * submitted, which `TimeExtraRepository.processApproval` uses to look up the
 * matching local row and write a `GrantEntity`.
 */
@Serializable
private data class ApprovedRequestDto(
    val id: String,
    val minutes_approved: Int = 0,
    val resolved_at: String? = null
)

@Singleton
class SyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @SupabaseClient private val httpClient: HttpClient,
    private var database: ParentalDatabase
) {
    companion object {
        private const val TAG = "SyncManager"
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 5000L
        private const val SYNC_INTERVAL_MS = 60_000L

        /**
         * Convenience accessor for non-Hilt call sites (legacy Workers
         * constructed by `WorkManager` outside the `@HiltWorker` graph).
         * Production code MUST inject the manager directly.
         */
        fun getInstance(context: Context): SyncManager {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                SyncManagerEntryPoint::class.java
            )
            return entryPoint.syncManager()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val authManager = DeviceAuthManager.getInstance(context)
    private val clientProvider = SupabaseClientProvider.getInstance(context)

    private val _syncStatus = MutableStateFlow(SyncStatus.IDLE)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _lastSyncTime = MutableStateFlow<Long?>(null)
    val lastSyncTime: StateFlow<Long?> = _lastSyncTime.asStateFlow()

    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    private val _serverTimeOffset = MutableStateFlow(0L)
    val serverTimeOffset: StateFlow<Long> = _serverTimeOffset.asStateFlow()

    // R4 — observed `device_state` from the most recent pullPolicy
    // response. Exposed as a `StateFlow<String?>` so the
    // `EnforcementController` (and any future local lock indicator)
    // can switch on the parent's LOCKED/ACTIVE signal without
    // polling. `null` until the first successful `get-policy`.
    private val _deviceStateFlow = MutableStateFlow<String?>(null)
    val deviceStateFlow: StateFlow<String?> = _deviceStateFlow.asStateFlow()

    private var syncJob: Job? = null
    private var periodicSyncJob: Job? = null

    init {
        scope.launch { updatePendingCount() }
    }

    fun setDatabase(db: ParentalDatabase) {
        database = db
    }

    fun startPeriodicSync() {
        if (periodicSyncJob?.isActive == true) return

        periodicSyncJob = scope.launch {
            while (isActive) {
                delay(SYNC_INTERVAL_MS)
                if (isActive) {
                    sync()
                }
            }
        }
    }

    fun stopPeriodicSync() {
        periodicSyncJob?.cancel()
        periodicSyncJob = null
    }

    suspend fun sync(): SyncResult = withContext(Dispatchers.IO) {
        _syncStatus.value = SyncStatus.SYNCING

        // PR verification 2026-07-01: gate on `authManager.isPaired()` instead
        // of `connectionState`. After PairingManager.pairWithCode() succeeds,
        // the auth state is paired but the SupabaseClientProvider
        // `connectionState` is never updated to CONNECTED (PairingManager
        // doesn't go through that path), so the sync returned Offline and
        // pullApprovedRequests never ran. `isPaired()` is the source of
        // truth here.
        if (!authManager.isPaired()) {
            _syncStatus.value = SyncStatus.OFFLINE
            return@withContext SyncResult.Offline
        }

        var pullSuccess = false
        var pushFailedCount = 0

        val pullResult = pullPolicy()
        pullSuccess = pullResult is SyncResult.Success

        // Best-effort: surface APPROVED grants to the child so its local
        // quota / bloqueo reflects the parent's decision. Failures here
        // don't fail the overall sync — the next pull will retry.
        try {
            pullApprovedRequests()
        } catch (e: Exception) {
            // already logged inside pullApprovedRequests()
        }

        val pushResult = drainOutbox()
        pushFailedCount = when (pushResult) {
            is SyncResult.Success -> 0
            is SyncResult.PartialSuccess -> pushResult.failedItems
            is SyncResult.Error -> -1
            is SyncResult.Offline -> -1
        }

        refreshFcmTokenIfNeeded()

        _lastSyncTime.value = System.currentTimeMillis()
        updatePendingCount()

        _syncStatus.value = if (pushFailedCount == 0) SyncStatus.IDLE else SyncStatus.PENDING_ITEMS

        return@withContext when {
            pushFailedCount == 0 -> SyncResult.Success
            pushFailedCount > 0 -> SyncResult.PartialSuccess(pushFailedCount)
            else -> SyncResult.Error("Sincronización parcial")
        }
    }

    suspend fun pullPolicy(): SyncResult = withContext(Dispatchers.IO) {
        val accessToken = authManager.getAccessToken()
            ?: return@withContext SyncResult.Offline

        val deviceId = authManager.deviceId.value ?: "default"

        return@withContext try {
            val localVersion = database.policyDao().getLocalVersion(deviceId) ?: 0L

            val response = httpClient.get("${SupabaseClientProvider.SUPABASE_URL}/functions/v1/get-policy") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", SupabaseClientProvider.SUPABASE_ANON_KEY)
            }

            when {
                response.status == HttpStatusCode.NotModified -> {
                    SyncResult.Success
                }
                response.status.isSuccess() -> {
                    val body = response.bodyAsText()
                    val policyResponse = Json.decodeFromString<PolicyPullResponse>(body)

                    if (policyResponse.version > localVersion) {
                        applyPolicy(policyResponse, deviceId)
                    }

                    policyResponse.server_time?.let { serverTime ->
                        val localTime = System.currentTimeMillis() / 1000
                        _serverTimeOffset.value = serverTime - localTime
                    }

                    SyncResult.Success
                }
                response.status == HttpStatusCode.Unauthorized -> {
                    SyncResult.Offline
                }
                else -> {
                    SyncResult.Error("Error ${response.status}")
                }
            }
        } catch (e: Exception) {
            SyncResult.Offline
        }
    }

    /**
     * Pulls the child's APPROVED `time_requests` from the server and
     * forwards each one to [TimeExtraRepository.processApproval], which
     * writes the matching local `GrantEntity` (idempotent by request id) so
     * the home screen's "minutos restantes" / bloqueo state reflects the
     * parent's decision on the next sync.
     *
     * Idempotent: re-running the same pull just re-applies the same
     * approvals (REPLACE in `GrantDao` keeps the row stable).
     *
     * Best-effort: a failure here does not fail the overall sync — the
     * next pull will retry. The OPPO's approve row disappears from the
     * parent's Solicitudes list once [pullPolicy] sees the status change;
     * here we only need the *opposite* leg (server → child).
     */
    suspend fun pullApprovedRequests() = withContext(Dispatchers.IO) {
        val accessToken = authManager.getAccessToken() ?: return@withContext
        val deviceId = authManager.deviceId.value ?: return@withContext

        val response = httpClient.get(
            "${SupabaseClientProvider.SUPABASE_URL}/rest/v1/time_requests"
        ) {
            header("Authorization", "Bearer $accessToken")
            header("apikey", SupabaseClientProvider.SUPABASE_ANON_KEY)
            parameter("device_id", "eq.$deviceId")
            parameter("status", "eq.APPROVED")
            parameter("select", "id,minutes_approved,resolved_at")
            // Most recent first so a 4xx mid-list doesn't strand old rows.
            parameter("order", "resolved_at.desc")
        }

        if (!response.status.isSuccess()) {
            android.util.Log.w(
                "SyncManager",
                "pullApprovedRequests: server returned ${response.status}"
            )
            return@withContext
        }

        // Use a local Json config with `ignoreUnknownKeys = true` — the mock
        // server (and PostgREST in general) returns the full row regardless of
        // the `select=…` filter, so extra fields like `device_id`,
        // `minutes_requested`, `reason`, `status`, `created_at` would
        // otherwise trigger a SerializationException that the outer
        // try/catch in `sync()` would silently swallow.
        val pullJson = Json { ignoreUnknownKeys = true }
        val rows = pullJson.decodeFromString<List<ApprovedRequestDto>>(response.bodyAsText())
        if (rows.isEmpty()) return@withContext

        val timeExtraRepo = TimeExtraRepository.getInstance(context)
        var applied = 0
        for (row in rows) {
            val minutes = if (row.minutes_approved > 0) row.minutes_approved else 0
            if (minutes <= 0) continue
            // The server returns `resolved_at` (when the parent approved),
            // not the grant's expiry. The grant should last `minutes` minutes
            // from the approval time, so we offset by minutes*60_000ms.
            val expiresAtMs = row.resolved_at?.let {
                parseIsoToEpochMs(it)?.plus(minutes * 60_000L)
            }
            val result = timeExtraRepo.processApproval(
                requestId = row.id,
                approvedMinutes = minutes,
                expiresAt = expiresAtMs
            )
            if (result is GrantResult.Success) applied++
        }
        android.util.Log.d(
            "SyncManager",
            "pullApprovedRequests: applied=$applied of ${rows.size} approved rows for device=$deviceId"
        )
    }

    private fun parseIsoToEpochMs(iso: String): Long? = try {
        java.time.Instant.parse(iso).toEpochMilli()
    } catch (e: Exception) {
        null
    }

    suspend fun drainOutbox(): SyncResult = withContext(Dispatchers.IO) {
        val accessToken = authManager.getAccessToken()
            ?: return@withContext SyncResult.Offline

        var failedCount = 0

        while (true) {
            val pendingItems = database.outboxDao().getPendingItems(MAX_RETRY_ATTEMPTS, 50)
            if (pendingItems.isEmpty()) break

            for (item in pendingItems) {
                when (sendOutboxItem(item, accessToken)) {
                    is OutboxSendResult.Success ->
                        database.outboxDao().deleteItem(item.id)
                    is OutboxSendResult.RetryableFailure -> {
                        database.outboxDao().incrementRetries(item.id)
                        failedCount++
                    }
                    is OutboxSendResult.PermanentFailure -> {
                        // Legacy drain path: leave the row in place and count
                        // it as a failure. The new [OutboxDrainer] worker is
                        // the canonical path that marks permanent failures as
                        // processed (PR 3).
                        failedCount++
                    }
                }
            }
        }

        return@withContext if (failedCount == 0) {
            SyncResult.Success
        } else {
            SyncResult.PartialSuccess(failedCount)
        }
    }

    suspend fun enqueue(
        eventType: OutboxEventType,
        payload: Map<String, Any>,
        dedupKey: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val key = dedupKey ?: UUID.randomUUID().toString()

        val existing = database.outboxDao().findByDedupKey(key)
        if (existing != null) {
            return@withContext false
        }

        val outboxEntity = OutboxEntity(
            tipo = eventType.name,
            payload_json = Json.encodeToString(payload),
            dedup_key = key,
            created_at = java.time.Instant.now().toString(),
            server_date = java.time.LocalDate.now(ZoneOffset.UTC).toString()
        )

        database.outboxDao().insertOutboxItem(outboxEntity)
        updatePendingCount()

        scope.launch {
            if (clientProvider.connectionState.value == ConnectionState.CONNECTED) {
                drainOutbox()
            }
        }

        return@withContext true
    }

    suspend fun sendHeartbeat(enforcementLevel: String? = null): Boolean = withContext(Dispatchers.IO) {
        val accessToken = authManager.getAccessToken()
            ?: return@withContext false

        return@withContext try {
            val body = buildJsonObject {
                enforcementLevel?.let { put("enforcement_level", it) }
                put("client_ts", java.time.Instant.now().toString())
            }

            val response = httpClient.post("${SupabaseClientProvider.SUPABASE_URL}/functions/v1/heartbeat") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", SupabaseClientProvider.SUPABASE_ANON_KEY)
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }

            response?.status?.isSuccess() == true
        } catch (e: Exception) {
            false
        }
    }

    fun getServerTime(): Long {
        return System.currentTimeMillis() / 1000 + _serverTimeOffset.value
    }

    private suspend fun applyPolicy(response: PolicyPullResponse, deviceId: String) {
        // F2a — persist the parent's `device_state` (LOCKED / ACTIVE)
        // into the local `policy` table. The default `"ACTIVE"` keeps
        // pre-v8 installs working; the column is read back through
        // `PolicyEntity.toPolicy` in `EnforcementController`, which
        // observes the policy and triggers the lock callback on
        // `LOCKED`. We deliberately do NOT block the pullPolicy cycle
        // — the next observation of the table picks up the change.
        // FCM push is OUT OF SCOPE per the residual limitations.
        val policyEntity = PolicyEntity(
            device_id = deviceId,
            version = response.version,
            category_assignments = response.category_assignments ?: emptyMap(),
            device_state = response.device_state ?: "ACTIVE"
        )
        database.policyDao().upsertPolicyIfNewer(policyEntity)

        // F2a — the in-memory `_deviceStateFlow` is now redundant
        // (the Room policy is the single source of truth). Kept for
        // backward compatibility with any external consumer; setting it
        // from the persisted value keeps the StateFlow coherent with
        // the table.
        response.device_state?.let { state ->
            _deviceStateFlow.value = state
        }

        response.app_policies?.forEach { appPolicy ->
            val windows = appPolicy.allowed_windows?.map { windowStr ->
                WindowEntity(days = emptyList(), from = windowStr, to = windowStr)
            } ?: emptyList()
            val entity = AppPolicyEntity(
                package_name = appPolicy.package_name,
                device_id = deviceId,
                state = appPolicy.state,
                daily_limit_minutes = appPolicy.daily_limit_minutes,
                allowed_windows = windows,
                category = null
            )
            database.appPolicyDao().upsertAppPolicy(entity)
        }
    }

    /**
     * Sends a single outbox row to Supabase. Returns a structured result so
     * callers (notably [com.tudominio.parentalcontrol.workers.OutboxDrainer])
     * can branch on retryable vs permanent failure.
     */
    suspend fun sendOutboxItem(item: OutboxEntity, accessToken: String? = null): OutboxSendResult {
        val client = httpClient
        val endpoint = when (item.tipo) {
            "USAGE_LOG" -> "/rest/v1/usage_logs"
            "DEVICE_ALERT" -> "/rest/v1/device_alerts"
            "BEHAVIORAL_EVENT" -> "/rest/v1/behavioral_events"
            "TIME_REQUEST" -> "/rest/v1/time_requests"
            "HEARTBEAT" -> "/functions/v1/heartbeat"
            else -> return OutboxSendResult.PermanentFailure(statusCode = 0)
        }

        val token = accessToken ?: authManager.getAccessToken()
            ?: return OutboxSendResult.RetryableFailure(
                IllegalStateException("no access token")
            )

        return try {
            val isFunction = endpoint.startsWith("/functions")
            val isTimeRequest = item.tipo == "TIME_REQUEST"

            val response = if (isFunction) {
                client.post("${SupabaseClientProvider.SUPABASE_URL}$endpoint") {
                    header("Authorization", "Bearer $token")
                    header("Content-Type", "application/json")
                    setBody(item.payload_json)
                }
            } else {
                client.post("${SupabaseClientProvider.SUPABASE_URL}$endpoint") {
                    header("Authorization", "Bearer $token")
                    header("apikey", SupabaseClientProvider.SUPABASE_ANON_KEY)
                    header("Content-Type", "application/json")
                    // TIME_REQUEST needs the inserted row back so we can
                    // reconcile the local request_id with the server's id.
                    // Other endpoints can keep return=minimal (cheaper).
                    header("Prefer", if (isTimeRequest) "return=representation" else "return=minimal")
                    setBody(item.payload_json)
                }
            }

            val result = classifyResponse(response.status)

            // On a successful TIME_REQUEST, swap the local request_id for
            // the server's id so the post-boot pullApprovedRequests can find
            // the row when the parent approves. Best-effort: a parse error
            // here just leaves the local id in place, which falls back to
            // the pre-fix behavior (pull can see the row but cannot map it).
            if (result is OutboxSendResult.Success && isTimeRequest) {
                try {
                    val body = response.bodyAsText()
                    val serverId = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
                        .find(body)?.groupValues?.get(1)
                    val localId = Regex("\"request_id\"\\s*:\\s*\"([^\"]+)\"")
                        .find(item.payload_json)?.groupValues?.get(1)
                    if (serverId != null && localId != null && localId != serverId) {
                        database.timeRequestDao().updateRequestId(localId, serverId)
                        Log.d(TAG, "Reconciled local request_id: $localId → $serverId")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not reconcile server id for time request: ${e.message}")
                }
            }

            result
        } catch (e: java.io.IOException) {
            OutboxSendResult.RetryableFailure(e)
        } catch (e: Exception) {
            OutboxSendResult.RetryableFailure(e)
        }
    }

    private fun classifyResponse(status: HttpStatusCode): OutboxSendResult = when {
        status.isSuccess() || status == HttpStatusCode.Conflict -> OutboxSendResult.Success
        status == HttpStatusCode.RequestTimeout || status == HttpStatusCode.TooManyRequests ->
            OutboxSendResult.RetryableFailure(
                IllegalStateException("HTTP $status")
            )
        status.value in 400..499 ->
            OutboxSendResult.PermanentFailure(statusCode = status.value)
        else ->
            // 5xx and anything else not in the 2xx/4xx buckets above.
            OutboxSendResult.RetryableFailure(
                IllegalStateException("HTTP $status")
            )
    }

    private suspend fun refreshFcmTokenIfNeeded() {
    }

    private suspend fun updatePendingCount() {
        _pendingCount.value = database.outboxDao().getPendingCountFlow().first()
    }
}

/**
 * Hilt [EntryPoint] that exposes [SyncManager] from the
 * `SingletonComponent` to non-Hilt call sites.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SyncManagerEntryPoint {
    fun syncManager(): SyncManager
}
