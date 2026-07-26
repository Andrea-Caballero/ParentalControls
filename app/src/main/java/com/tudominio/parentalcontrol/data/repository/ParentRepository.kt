package com.tudominio.parentalcontrol.data.repository

import android.content.Context
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.domain.model.ApprovalResult
import com.tudominio.parentalcontrol.domain.model.Child
import com.tudominio.parentalcontrol.domain.model.ChildDevice
import com.tudominio.parentalcontrol.domain.model.DeviceHealth
import com.tudominio.parentalcontrol.domain.model.DeviceState
import com.tudominio.parentalcontrol.domain.model.PolicyTemplate
import com.tudominio.parentalcontrol.domain.model.RequestStatus
import com.tudominio.parentalcontrol.domain.model.TimeRequest
import com.tudominio.parentalcontrol.data.local.PendingRequestsCache
import com.tudominio.parentalcontrol.network.SupabaseClientProvider
import com.tudominio.parentalcontrol.viewmodel.PairingCodeResult
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repositorio para operaciones del padre.
 * Conecta con las Edge Functions de Supabase (T15).
 *
 * Wired as a Hilt singleton so tests can swap a fake `KtorClient` via the
 * [SupabaseClientProvider] binding. PR 1 of
 * openspec/changes/wire-pairing-and-approval-end-to-end replaced the local
 * mock fallbacks with real HTTP calls to the Supabase edge functions.
 */
@Singleton
@Suppress(
    "TooManyFunctions",
    // The class already exceeded detekt's `LargeClass` threshold when
    // `fix-v2-server-side-solicitudes-filter` added the V2 overload +
    // device-cache mirror (302 LOC vs the 300-line cap). The functions
    // are intentionally co-located because each one is a single REST
    // call with its own URL builder and DTO decoder; splitting them
    // across files would scatter the Supabase wire surface. The 300-line
    // cap is a soft guideline for this kind of remote-API layer.
    "LargeClass"
)
open class ParentRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authManager: DeviceAuthManager,
    private val clientProvider: SupabaseClientProvider
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Process-level cache of the most recent pending-requests fetch
     * (design D2 of `fix-parent-solicitudes-auto-poll`).
     *
     * Initialized to `emptyList()` so the first UI frame never sees a
     * different value than the VM's `_pendingRequests` initial value.
     * The worker (`SolicitudesPollingWorker`) writes here on every
     * successful 5-min tick; the VM mirrors the value into its own
     * `_pendingRequests` flow via a collector in `init`.
     *
     * Kept as a [StateFlow] (not [kotlinx.coroutines.flow.SharedFlow])
     * because `StateFlow.update {}`-style replacement matches the spec
     * wording at `specs/time-request-approval/spec.md:51` ("post via
     * `StateFlow.update {}`") and gives the VM a single hot replay
     * channel for collectors.
     */
    private val _pendingRequestsFlow = MutableStateFlow<List<TimeRequest>>(emptyList())
    open val pendingRequestsFlow: StateFlow<List<TimeRequest>> = _pendingRequestsFlow.asStateFlow()

    /**
     * Mirror of the most recent `getDevices()` fetch, used by
     * [getPendingRequests] when `selectedChildId != null` to translate a
     * child id into the set of device ids we should ask Postgrest for.
     *
     * Initialized to `emptyList()` so the first frame matches the V1
     * behaviour ("ask Postgrest without a `device_id` filter when we
     * have no devices yet") — for the TODO path the URL builder treats
     * this as "no filter" and lets RLS scope the rows.
     *
     * Updated via [primeDevicesCache]. Hydration from a real
     * `getDevices()` call (Task 3.3 of `fix-parent-solicitudes-auto-poll`
     * style) is out of scope for V2 — the VM-side mirror at
     * `ParentViewModel._devices` is the source of truth and the V2 test
     * path seeds the cache directly. See the proposal at
     * `openspec/changes/2026-07-07-fix-v2-server-side-solicitudes-filter/proposal.md`
     * for the deferred "lazy hydration" follow-up.
     */
    private val _devicesCache = MutableStateFlow<List<ChildDevice>>(emptyList())

    /**
     * Serializes concurrent cold-start hydration calls so a burst of V2
     * `getPendingRequests(selectedChildId)` calls (e.g., VM re-init + a
     * worker tick + 3 recompositions) collapses to a single
     * `get-devices-for-parent` HTTP round-trip. The re-check after
     * `withLock` (`if (_devicesCache.value.isNotEmpty()) return …`) is
     * what actually dedupes — the Mutex itself only serializes. The pair
     * (Mutex + re-check) is the cheapest way to dedupe without a
     * `CompletableDeferred` per proposal §Open questions #4.
     *
     * Acquired by [hydrateDevicesCache] (the V2 path's private helper)
     * and by the public [getDevicesForParent] wrapper (used by
     * `SolicitudesPollingWorker`). Never acquired by the existing
     * `ParentViewModel.loadDevices()` (which calls the private
     * [getDevices]) — a known double-call window, deferred per proposal
     * §Out of scope #3.
     */
    private val devicesCacheMutex = Mutex()

    /**
     * Shared long-lived scope for fire-and-forget cache writes and the
     * cold-start hydration collector. `SupervisorJob` so a single failed
     * task (e.g., a corrupt cache) does not cancel the scope.
     */
    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * DataStore-backed cache for the cold-start hydration path. Owned by
     * this repository (not injected via Hilt) to avoid cascading changes
     * to the existing constructor signature used by
     * `ParentRepositoryTest` / `ParentRepositoryColdStartTest` /
     * `ParentRepositoryMergeTest`.
     *
     * Two `ParentRepository` instances sharing the same application
     * context reuse the same [PendingRequestsCache] (the `get(context)`
     * factory memoizes per application context), so warm + cold in
     * `ParentRepositoryColdStartTest` share one DataStore — required by
     * the `androidx.datastore` "one DataStore per file per process"
     * invariant.
     *
     * Nullable for test tolerance: `ParentRepositoryTest` mocks the
     * [Context] (mockk relaxed), so `applicationContext` returns `null`
     * and DataStore cannot construct. In that case the cache is `null`
     * and both cold-start hydration and the write-through in
     * [publishPendingRequests] silently no-op. Production paths always
     * receive a real [Context] (via Hilt's `@ApplicationContext`), so
     * the production `pendingRequestsCache` is never `null`.
     */
    private val pendingRequestsCache: PendingRequestsCache? = runCatching {
        PendingRequestsCache.get(context)
    }.getOrNull()

    /**
     * Hydrate the in-memory flow from the persisted cache exactly once,
     * asynchronously. The flow's initial value stays `emptyList()` so the
     * first UI frame matches the VM's `_pendingRequests` initial value
     * (task 3.2 first-frame invariant). Subsequent collectors see the
     * cached list once the IO read completes — typically <10 ms after
     * construction on a warm process, longer on the first install
     * (fresh DataStore file = `emptyList()`, no observable change).
     *
     * **Race-safety:** the hydration observer reads `cache.observe().first()`
     * once and only updates the flow when the cache actually carries data.
     * If the cache is empty (fresh install) the hydration is a no-op and
     * the in-memory `emptyList()` invariant holds. If the cache is non-empty
     * (warm session before process death) the hydration overrides whatever
     * the in-memory flow currently holds with the persisted snapshot. The
     * no-op-when-empty path prevents a race where hydration's first read
     * completes BEFORE a subsequent `publishPendingRequests`'s cache write
     * lands: hydration would otherwise overwrite the just-published value
     * back to `emptyList()`.
     *
     * If the cache is `null` (e.g., a mocked-context unit test), hydration
     * is skipped entirely — the in-memory flow keeps its `emptyList()`.
     */
    init {
        cacheScope.launch {
            val cache = pendingRequestsCache ?: return@launch
            val cached = cache.observe().first()
            if (cached.isNotEmpty()) {
                _pendingRequestsFlow.value = cached
            }
        }
    }

    /**
     * Public write hook for both the VM's [getPendingRequests] success
     * path and the worker's `SolicitudesPollingWorker.doWork()` success
     * path.
     *
     * **Merge semantics** (the `(m) merge` decision from engram #245 —
     * `sdd/fix-parent-log-events-cleared-on-reopen/decisions`): the
     * incoming list is unioned with the current in-memory value, keyed
     * by [TimeRequest.id]. When the same id appears in both lists, the
     * row with a strictly-newer `status` + `respondedAt` wins. This
     * preserves optimistic local updates that have not been synced
     * upstream yet — e.g., a parent who just tapped "Approve" on a row
     * whose APPROVED state has not reached the `approve-request` edge
     * function, but a stale PENDING server fetch returned the same row.
     *
     * The merged result is also written through to the DataStore cache.
     * This is a `suspend` function (NOT fire-and-forget) so the call
     * returns only when the cache write has committed — guaranteeing a
     * subsequent cold-start hydration see the latest state when the
     * call site is itself a suspending context (Worker's `doWork()`,
     * VM's `viewModelScope.launch { ... }`, tests' `runTest { ... }`).
     *
     * The first-frame invariant (`emptyList()` until async hydration
     * completes) is preserved: the in-memory flow is updated synchronously
     * on the calling thread BEFORE the suspending cache write, so any
     * `collectAsState()` consumer (today: `ParentViewModel`'s mirror at
     * `viewmodel/ParentViewModel.kt:119-122`) sees the new list on the
     * very next frame regardless of cache state.
     *
     * If the cache is `null` (mocked-context test path) the cache write
     * is skipped; the in-memory flow is still updated.
     */
    suspend fun publishPendingRequests(newList: List<TimeRequest>) {
        val merged = mergeTimeRequests(_pendingRequestsFlow.value, newList)
        _pendingRequestsFlow.value = merged
        val cache = pendingRequestsCache ?: return
        cache.write(merged)
    }

    /**
     * Returns the union of [local] and [incoming], keyed by
     * [TimeRequest.id]. For an id present in both sides, the row whose
     * `respondedAt` (then `createdAt`) is lexicographically later wins
     * — i.e., the locally-known newer status beats the stale server
     * fetch that briefly surfaces an already-decided row as PENDING.
     */
    private fun mergeTimeRequests(
        local: List<TimeRequest>,
        incoming: List<TimeRequest>
    ): List<TimeRequest> {
        val byId = local.associateBy { it.id }.toMutableMap()
        for (req in incoming) {
            val existing = byId[req.id]
            byId[req.id] = if (existing == null) {
                req
            } else if (isNewer(existing, req)) {
                existing
            } else {
                req
            }
        }
        return byId.values.toList()
    }

    /**
     * Tie-break helper for [mergeTimeRequests]. A row with a non-null
     * `respondedAt` beats one without (the local parent decision is the
     * newer state). When both sides have `respondedAt`, the
     * lexicographically later timestamp wins.
     */
    private fun isNewer(a: TimeRequest, b: TimeRequest): Boolean {
        return when {
            a.respondedAt != null && b.respondedAt == null -> true
            a.respondedAt == null && b.respondedAt != null -> false
            else -> (a.respondedAt ?: a.createdAt) > (b.respondedAt ?: b.createdAt)
        }
    }

    /**
     * Obtiene los dispositivos hijos del padre.
     *
     * PR 2 of `openspec/changes/wire-pairing-and-approval-end-to-end`
     * replaces the local mock with a real HTTP call to the
     * `get-devices-for-parent` edge function. The edge function uses the
     * ANON key with the caller's JWT — the RLS policy `devices_parent_select`
     * (`parent_id = auth.uid()`) scopes the rows. We do not need to pass
     * `parent_id` from the client because RLS is the security boundary.
     *
     * Returns [Result.success] with the parsed list on a 2xx response,
     * [Result.failure] with a typed [DeviceListError] otherwise:
     *  - [DeviceListError.AuthMissing] when the parent has no session
     *    (the synthetic hotfix path from `hotfix-parent-auth-session`).
     *  - [DeviceListError.Transient] for any other failure (network,
     *    HTTP non-2xx, parse error).
     */
    open suspend fun getDevices(): Result<List<ChildDevice>> = withContext(Dispatchers.IO) {
        try {
            val token = authManager.getAccessToken()
                ?: return@withContext Result.failure(DeviceListError.AuthMissing)

            val response = clientProvider.httpClient.post(
                "${SupabaseClientProvider.SUPABASE_URL}/functions/v1/get-devices-for-parent"
            ) {
                header("Authorization", "Bearer $token")
                header("apikey", SupabaseClientProvider.SUPABASE_ANON_KEY)
                contentType(ContentType.Application.Json)
                setBody("{}")
            }

            if (!response.status.isSuccess()) {
                return@withContext Result.failure(
                    DeviceListError.Transient("HTTP ${response.status}")
                )
            }

            val body = json.decodeFromString<List<DeviceDto>>(response.bodyAsText())
            Result.success(body.map { it.toChildDevice() })
        } catch (e: IllegalStateException) {
            // Preserve the typed AuthMissing contract for any IllegalStateException
            // that bubbles up with the "not authenticated" message (e.g., from
            // the Ktor engine's internal checks).
            if (e.message?.contains("not authenticated") == true) {
                Result.failure(DeviceListError.AuthMissing)
            } else {
                Result.failure(DeviceListError.Transient(e.message ?: "Unknown error"))
            }
        } catch (e: Exception) {
            Result.failure(DeviceListError.Transient(e.message ?: "Unknown error"))
        }
    }

    /**
     * Public, Mutex-guarded wrapper around [getDevices]. Used by
     * `SolicitudesPollingWorker` to pre-warm [_devicesCache] on every
     * 5-min tick (per Q3=y from
     * `sdd/fix-v2-filter-production-lazy-hydration/decisions`).
     *
     * Acquires the same [devicesCacheMutex] the V2 lazy-hydration gate
     * uses, so worker-tick hydration and V2 cold-start hydration are
     * serialized — at most one in-flight `getDevices()` HTTP call
     * across the app at any moment.
     *
     * On success, the cache is seeded with the fetched devices. On
     * failure, the cache is left untouched and the original typed error
     * is returned to the caller. The worker swallows failures (logs +
     * continues) per proposal §What changes #2.
     */
    suspend fun getDevicesForParent(): Result<List<ChildDevice>> = devicesCacheMutex.withLock {
        val result = getDevices()
        if (result.isSuccess) {
            _devicesCache.value = result.getOrThrow()
        }
        result
    }

    /**
     * Obtiene solicitudes de tiempo pendientes para los dispositivos del padre.
     *
     * PR 4 of `openspec/changes/wire-pairing-and-approval-end-to-end`
     * replaces the local mock with a real REST query against
     * `${SUPABASE_URL}/rest/v1/time_requests`. The query is scoped by RLS:
     * the `time_requests_parent_select` policy
     * (`parent_id = auth.uid()`) returns only the parent's own rows. We
     * additionally filter by `status = "PENDING"` as a defense-in-depth so
     * the repository never accidentally surfaces already-decided rows.
     *
     * Returns [Result.success] with the parsed list on a 2xx response,
     * [Result.failure] on a non-2xx response or on any network exception
     * (including the "not authenticated" case).
     */
    open suspend fun getPendingRequests(): Result<List<TimeRequest>> =
        getPendingRequests(selectedChildId = null)

    /**
     * V2 server-side filter variant of [getPendingRequests].
     *
     * `selectedChildId = null` preserves the V1 (Todos) behaviour — RLS
     * alone scopes the rows and no `device_id` filter is appended. Any
     * non-null id resolves to the child's devices via [_devicesCache]
     * and appends `&device_id=in.(<comma-separated ids>)` to the static
     * Postgrest query, so the server only ships the relevant rows over
     * the wire (transport optimization, RLS remains the security
     * boundary).
     *
     * RLS contract is unchanged: the `time_requests_parent_select`
     * policy (`supabase/migrations/002_rls_policies.sql:153-161`) still
     * scopes by `parent_id = auth.uid()`. The `device_id` clause is
     * purely additive — narrower, never wider than the V1 path.
     *
     * Empty-device edge case (Q4=e from
     * `sdd/fix-v2-server-side-solicitudes-filter/decisions`): when
     * `selectedChildId` resolves to zero device ids (e.g., the child was
     * deleted between Devices and Solicitudes fetches) we return
     * `Result.success(emptyList())` instead of a malformed
     * `device_id=in.()` query or a surfacing error. `DashboardScreen`
     * already renders "Sin solicitudes" for an empty list.
     *
     * Added by `fix-v2-server-side-solicitudes-filter`. The no-arg
     * overload above delegates here so `SolicitudesPollingWorker` keeps
     * its Todos-only polling semantics with zero behavioural change.
     */
    open suspend fun getPendingRequests(selectedChildId: String?): Result<List<TimeRequest>> =
        withContext(Dispatchers.IO) {
            // Cold-start lazy-hydration gate (per Q1=f / Q2=m from
            // `sdd/fix-v2-filter-production-lazy-hydration/decisions`):
            // when a specific child is selected AND the device cache is
            // empty, fire a hydration `getDevices()` call BEFORE the
            // child→device-id resolution. Without this gate, the
            // empty-cache branch at the Q4=e guard below would return
            // `success(emptyList())` and the parent would see
            // "Sin solicitudes" even when there ARE pending requests.
            //
            // The gate is skipped when:
            //  - `selectedChildId == null` (Todos path) — RLS scopes the
            //    rows, no device-id filter is appended, the cache is
            //    irrelevant.
            //  - `_devicesCache.value.isNotEmpty()` (warm cache) — the
            //    hot path is uncontended; the Mutex is only acquired on
            //    cold start. Warm-cache `getPendingRequests(...)` calls
            //    run in <1ms as before.
            if (selectedChildId != null && _devicesCache.value.isEmpty()) {
                if (hydrateDevicesCache().isFailure) {
                    val msg = hydrateDevicesCache().exceptionOrNull()?.message ?: "unknown"
                    return@withContext Result.failure(
                        DeviceListError.Transient("device cache hydration failed: $msg")
                    )
                }
                // _devicesCache now populated; proceed with the V2 query below
            }

            try {
                val token = authManager.getAccessToken()
                    ?: return@withContext Result.failure(DeviceListError.AuthMissing)

                val base = "${SupabaseClientProvider.SUPABASE_URL}/rest/v1/time_requests?" +
                    "select=*,devices(device_name)&status=eq.PENDING&order=created_at.desc"
                val url = if (selectedChildId == null) {
                    base
                } else {
                    val deviceIds = deviceIdsForChild(selectedChildId)
                    if (deviceIds.isEmpty()) {
                        // Q4=e — don't issue a malformed `device_id=in.()`
                        // query; the UI renders the empty state the same
                        // way it does for a child with zero pending requests.
                        return@withContext Result.success(emptyList())
                    }
                    base + "&device_id=in.(${deviceIds.joinToString(",")})"
                }

                val response = clientProvider.httpClient.get(url) {
                    header("Authorization", "Bearer $token")
                    header("apikey", SupabaseClientProvider.SUPABASE_ANON_KEY)
                }

                if (!response.status.isSuccess()) {
                    return@withContext Result.failure(
                        DeviceListError.Transient("HTTP ${response.status}")
                    )
                }

                val body = json.decodeFromString<List<TimeRequestDto>>(response.bodyAsText())
                Result.success(body.map { it.toTimeRequest() })
            } catch (e: Exception) {
                Result.failure(DeviceListError.Transient(e.message ?: "Unknown error"))
            }
        }

    /**
     * Mutex-guarded hydration of [_devicesCache] from a real
     * `getDevices()` call. Acquires [devicesCacheMutex] so concurrent
     * cold-start callers (VM re-init + worker tick + recompositions)
     * collapse to a single HTTP round-trip. After acquiring the lock,
     * re-checks the cache: if a previous holder already populated it,
     * this call is a no-op (`Result.success(Unit)`).
     *
     * On hydration failure, the original typed error from [getDevices]
     * is preserved (typically `DeviceListError.Transient`). The V2
     * gate wraps it as `Transient("device cache hydration failed: …")`
     * for the caller per Q1=f.
     */
    private suspend fun hydrateDevicesCache(): Result<Unit> = devicesCacheMutex.withLock {
        if (_devicesCache.value.isNotEmpty()) {
            return@withLock Result.success(Unit)
        }
        val r = getDevices()
        if (r.isSuccess) {
            _devicesCache.value = r.getOrThrow()
            Result.success(Unit)
        } else {
            Result.failure(r.exceptionOrNull() ?: UnknownError())
        }
    }

    /**
     * Translates a child id into the list of device ids that belong to
     * that child, using the [_devicesCache] mirror. Returns an empty
     * list when no device is currently associated with the id (the V2
     * overload surfaces that as `Result.success(emptyList())` per
     * Q4=e).
     *
     * Devices paired BEFORE the `children` table migration keep
     * `child == null` and are NEVER returned here — only devices with
     * a hydrated `Child` association count. This matches the dashboard
     * picker semantics: "Sin asignar" devices do not surface under any
     * specific child.
     */
    private fun deviceIdsForChild(childId: String): List<String> =
        _devicesCache.value
            .filter { it.child?.id == childId }
            .map { it.id }

    /**
     * Obtiene plantillas de política disponibles.
     */
    suspend fun getTemplates(): List<PolicyTemplate> = withContext(Dispatchers.IO) {
        // TODO: En implementación real, llamar a supabase
        listOf(
            PolicyTemplate(
                id = "template-1",
                name = "Preescolar (0-6 años)",
                description = "Restricciones estrictas para niños pequeños",
                ageBand = "0-6",
                isDefault = false
            ),
            PolicyTemplate(
                id = "template-2",
                name = "Escuela Primaria (7-12 años)",
                description = "Balance entre estudio y entretenimiento",
                ageBand = "7-12",
                isDefault = true
            ),
            PolicyTemplate(
                id = "template-3",
                name = "Adolescencia (13-17 años)",
                description = "Más libertad con supervisión",
                ageBand = "13-17",
                isDefault = false
            )
        )
    }

    /**
     * Aprueba una solicitud de tiempo llamando a la edge function
     * `approve-request` (`${SUPABASE_URL}/functions/v1/approve-request`).
     *
     * The wire format sent to the edge function is
     * `{ request_id, minutes, response_text }` — exactly the shape the
     * existing server-side handler at
     * `supabase/functions/approve-request/index.ts:38` already reads. The
     * server inserts a `grants` row with `source = "EXTRA_TIME"` (pre-approved
     * per design §7 Q4 in `openspec/changes/wire-pairing-and-approval-end-to-end/design.md`)
     * and returns `{ success, grant_id, minutes, expires_at }`.
     *
     * PR 4 of `openspec/changes/wire-pairing-and-approval-end-to-end` replaces
     * the local mock with this real HTTP call. Returns [Result.success] with
     * the parsed [ApprovalResult] on a 2xx response, [Result.failure]
     * otherwise.
     */
    suspend fun approveRequest(
        requestId: String,
        minutes: Int,
        response: String?
    ): Result<ApprovalResult> = withContext(Dispatchers.IO) {
        try {
            val token = authManager.getAccessToken()
                ?: return@withContext Result.failure(DeviceListError.AuthMissing)

            val responseText = jsonStringOrNull(response)
            val body = "{\"request_id\":\"$requestId\",\"minutes\":$minutes,\"response_text\":$responseText}"

            val httpResponse = clientProvider.httpClient.post(
                "${SupabaseClientProvider.SUPABASE_URL}/functions/v1/approve-request"
            ) {
                header("Authorization", "Bearer $token")
                header("apikey", SupabaseClientProvider.SUPABASE_ANON_KEY)
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            if (!httpResponse.status.isSuccess()) {
                return@withContext Result.failure(
                    DeviceListError.Transient("HTTP ${httpResponse.status}")
                )
            }

            val responseBody = json.decodeFromString<ApproveResponseDto>(httpResponse.bodyAsText())
            Result.success(
                ApprovalResult(
                    success = responseBody.success,
                    grantId = responseBody.grant_id,
                    minutes = responseBody.minutes ?: minutes,
                    expiresAt = responseBody.expires_at
                )
            )
        } catch (e: Exception) {
            Result.failure(DeviceListError.Transient(e.message ?: "Unknown error"))
        }
    }

    /**
     * Deniega una solicitud de tiempo.
     *
     * Calls the same `approve-request` edge function with `action = "DENY"`
     * and `minutes = 0`. The current server-side handler does not yet branch
     * on `action` (see `supabase/functions/approve-request/index.ts:38`); the
     * wire format is locked in here and the server-side branch is a tracked
     * follow-up — flagged in PR 4's PR description per design §7 Q5.
     *
     * Returns [Result.success] with `true` on a 2xx response,
     * [Result.failure] otherwise.
     */
    suspend fun denyRequest(requestId: String, reason: String?): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val token = authManager.getAccessToken()
                    ?: return@withContext Result.failure(DeviceListError.AuthMissing)

                val responseText = jsonStringOrNull(reason)
                val body = "{\"request_id\":\"$requestId\",\"minutes\":0,\"action\":\"DENY\"," +
                    "\"response_text\":$responseText}"

                val httpResponse = clientProvider.httpClient.post(
                    "${SupabaseClientProvider.SUPABASE_URL}/functions/v1/approve-request"
                ) {
                    header("Authorization", "Bearer $token")
                    header("apikey", SupabaseClientProvider.SUPABASE_ANON_KEY)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }

                if (!httpResponse.status.isSuccess()) {
                    return@withContext Result.failure(
                        DeviceListError.Transient("HTTP ${httpResponse.status}")
                    )
                }

                Result.success(true)
            } catch (e: Exception) {
                Result.failure(DeviceListError.Transient(e.message ?: "Unknown error"))
            }
        }

    /**
     * Crea código de emparejamiento.
     *
     * Posts to `${SUPABASE_URL}/functions/v1/create-pairing-code` with the
     * parent's bearer token + apikey header. The server generates the 8-char
     * code and returns `{ code, expires_at, deeplink }` per the spec at
     * `openspec/changes/wire-pairing-and-approval-end-to-end/specs/pairing-flow/spec.md`.
     */
    suspend fun createPairingCode(
        deviceName: String,
        ageBand: String,
        ttlMinutes: Int
    ): Result<PairingCodeResult> = withContext(Dispatchers.IO) {
        try {
            val token = authManager.getAccessToken()
                ?: return@withContext Result.failure(DeviceListError.AuthMissing)

            val response = clientProvider.httpClient.post(
                "${SupabaseClientProvider.SUPABASE_URL}/functions/v1/create-pairing-code"
            ) {
                header("Authorization", "Bearer $token")
                header("apikey", SupabaseClientProvider.SUPABASE_ANON_KEY)
                contentType(ContentType.Application.Json)
                setBody(
                    "{\"device_name\":\"$deviceName\",\"age_band\":\"$ageBand\",\"ttl_minutes\":$ttlMinutes}"
                )
            }

            if (!response.status.isSuccess()) {
                return@withContext Result.failure(
                    DeviceListError.Transient("HTTP ${response.status}")
                )
            }

            val body = json.decodeFromString<PairingCodeDto>(response.bodyAsText())
            Result.success(
                PairingCodeResult(
                    code = body.code,
                    expiresAt = body.expires_at,
                    deeplink = body.deeplink
                )
            )
        } catch (e: Exception) {
            Result.failure(DeviceListError.Transient(e.message ?: "Unknown error"))
        }
    }

    /**
     * Concede recompensa de tiempo.
     */
    suspend fun grantReward(deviceId: String, minutes: Int, reason: String?): Boolean =
        withContext(Dispatchers.IO) {
            // TODO: En implementación real, llamar a Edge Function
            true
        }

    /**
     * Aplica plantilla de política a dispositivo.
     */
    suspend fun applyTemplate(deviceId: String, templateId: String): Boolean =
        withContext(Dispatchers.IO) {
            // TODO: En implementación real, llamar a Edge Function
            true
        }

    /**
     * Serializes a possibly-null string into a JSON string-or-null literal.
     * Used by [approveRequest] and [denyRequest] to embed an optional
     * `response_text` field without writing the JSON by hand. Pulled out to
     * keep the call sites short enough to fit inside ktlint's 120-char
     * max-line-length rule.
     */
    private fun jsonStringOrNull(value: String?): String =
        if (value == null) "null" else "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    /**
     * Renames a child by PATCHing the `children` table at
     * `${SUPABASE_URL}/rest/v1/children?id=eq.{childId}` with body
     * `{"first_name":"<newName>"}`. RLS-guarded by `children_parent_update`
     * (`parent_id = auth.uid()`), so an unknown id surfaces as a non-2xx
     * response (404 from PostgREST) and the parent UI shows the inline
     * error.
     *
     * Mirrors the existing `approveRequest` / `denyRequest` HTTP pattern:
     * `withContext(Dispatchers.IO)`, parent bearer token + apikey
     * header, `setBody(...)`. Returns [Result.success] on 2xx,
     * [Result.failure] carrying a typed [DeviceListError] otherwise.
     *
     * Added by `fix-rename-child-dialog` (greenfield Material 3 modal
     * per design §B.6 of the Q2 chain archive). The dialog passes the
     * already-validated + trimmed name into this method; we send it
     * verbatim. Per Q4=p (pessimistic rename) no optimistic update
     * happens before the await.
     */
    open suspend fun renameChild(childId: String, newName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val token = authManager.getAccessToken()
                    ?: return@withContext Result.failure(DeviceListError.AuthMissing)

                val httpResponse = clientProvider.httpClient.patch(
                    "${SupabaseClientProvider.SUPABASE_URL}/rest/v1/children?id=eq.$childId"
                ) {
                    header("Authorization", "Bearer $token")
                    header("apikey", SupabaseClientProvider.SUPABASE_ANON_KEY)
                    contentType(ContentType.Application.Json)
                    setBody("""{"first_name":"${newName.replace("\\", "\\\\").replace("\"", "\\\"")}"}""")
                }

                if (!httpResponse.status.isSuccess()) {
                    return@withContext Result.failure(
                        DeviceListError.Transient("HTTP ${httpResponse.status}")
                    )
                }

                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(DeviceListError.Transient(e.message ?: "Unknown error"))
            }
        }

    /**
     * Lock device immediately by setting `device_state = 'LOCKED'` via
     * the auth-gated `set-device-state` edge function.
     *
     * The function runs with `service_role` privileges inside the edge
     * function (mirroring `create-pairing-code`) — Supabase RLS does
     * not expose a `devices_parent_update` policy in
     * `supabase/migrations/002_rls_policies.sql`, so direct PostgREST
     * PATCH from a parent's JWT would be rejected. The edge function
     * is the only safe surface to mutate `devices.device_state` from
     * a parent's session.
     *
     * Returns `true` on a 2xx response and `false` on a missing
     * token, non-2xx response, or any network exception. Callers
     * (notably [com.tudominio.parentalcontrol.viewmodel.ParentViewModel.lockDevice])
     * surface the failure as a snackbar / error state so the parent
     * sees the click failed rather than a silent reload.
     */
    suspend fun lockDevice(deviceId: String): Boolean = setDeviceState(
        deviceId = deviceId,
        state = "LOCKED"
    )

    /**
     * Unlock device by setting `device_state = 'ACTIVE'` via the
     * auth-gated `set-device-state` edge function. Mirrors
     * [lockDevice] — see that contract for the wire format and RLS
     * rationale.
     */
    suspend fun unlockDevice(deviceId: String): Boolean = setDeviceState(
        deviceId = deviceId,
        state = "ACTIVE"
    )

    /**
     * WU-2 — internal helper that issues the `set-device-state` POST
     * with the parent's bearer token + apikey + the typed-state body.
     * Shared by [lockDevice] (state=LOCKED) and
     * [unlockDevice] (state=ACTIVE).
     *
     * Returns `false` when:
     *  - the access token is missing (auth missing → caller shows
     *    sign-in CTA, not a snackbar)
     *  - the HTTP response is non-2xx (server-side validation: bad
     *    state, unknown device_id, ownership mismatch)
     *  - the request throws (network error, JSON parse failure,
     *    Keystore unavailable during token refresh)
     */
    private suspend fun setDeviceState(
        deviceId: String,
        state: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = authManager.getAccessToken()
                ?: return@withContext false

            val body = """{"device_id":"${deviceId.replace("\\", "\\\\").replace("\"", "\\\"")}",""" +
                """"state":"$state"}"""

            val response = clientProvider.httpClient.post(
                "${SupabaseClientProvider.SUPABASE_URL}/functions/v1/set-device-state"
            ) {
                header("Authorization", "Bearer $token")
                header("apikey", SupabaseClientProvider.SUPABASE_ANON_KEY)
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            response.status.isSuccess()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Obtiene estadísticas de uso de un dispositivo.
     */
    suspend fun getUsageStats(
        deviceId: String,
        date: String
    ): List<com.tudominio.parentalcontrol.domain.model.UsageStats> =
        withContext(Dispatchers.IO) {
            listOf(
                com.tudominio.parentalcontrol.domain.model.UsageStats(
                    deviceId = deviceId,
                    packageName = "com.instagram.android",
                    appName = "Instagram",
                    date = date,
                    minutesUsed = 45,
                    limitMinutes = 60,
                    remainingMinutes = 15
                ),
                com.tudominio.parentalcontrol.domain.model.UsageStats(
                    deviceId = deviceId,
                    packageName = "com.whatsapp",
                    appName = "WhatsApp",
                    date = date,
                    minutesUsed = 30,
                    limitMinutes = null,
                    remainingMinutes = null
                )
            )
        }

    /**
     * Obtiene salud del dispositivo.
     */
    suspend fun getDeviceHealth(deviceId: String): DeviceHealth =
        withContext(Dispatchers.IO) {
            DeviceHealth(
                enforcementLevel = "STANDARD",
                suspicionLevel = "NONE",
                lastHeartbeat = "2026-06-04T12:00:00Z",
                batteryLevel = 85,
                isCharging = false,
                alerts = emptyList()
            )
        }

    @Serializable
    private data class PairingCodeDto(
        val code: String,
        val expires_at: String,
        val deeplink: String
    )

    /** Nested `devices` from `select=*,devices(device_name)` (OBJECT or null). */
    @Serializable
    private data class TimeRequestDeviceRelationDto(
        val device_name: String
    )

    /**
     * Wire shape returned by `${SUPABASE_URL}/rest/v1/time_requests` when
     * called with `select=*,devices(device_name)`. The `device_name`
     * field is joined from the `devices` table by Supabase's resource
     * embedding. Status values are the [RequestStatus] enum names
     * (`PENDING`, `APPROVED`, `DENIED`).
     */
    @Serializable
    private data class TimeRequestDto(
        val id: String,
        val device_id: String,
        val package_name: String? = null,
        val app_name: String? = null,
        val minutes_requested: Int,
        val reason: String? = null,
        val status: String,
        val created_at: String,
        val responded_at: String? = null,
        val parent_response: String? = null,
        val devices: TimeRequestDeviceRelationDto? = null
    ) {
        fun toTimeRequest(): TimeRequest = TimeRequest(
            id = id,
            deviceId = device_id,
            deviceName = devices?.device_name,
            packageName = package_name,
            appName = app_name,
            minutesRequested = minutes_requested,
            reason = reason,
            status = when (status.uppercase()) {
                "APPROVED" -> RequestStatus.APPROVED
                "DENIED" -> RequestStatus.DENIED
                else -> RequestStatus.PENDING
            },
            createdAt = created_at,
            respondedAt = responded_at,
            parentResponse = parent_response
        )
    }

    /**
     * Wire shape returned by the `approve-request` edge function on a
     * successful approval. Mirrors the JSON keys in
     * `supabase/functions/approve-request/index.ts:147-156`.
     */
    @Serializable
    private data class ApproveResponseDto(
        val success: Boolean,
        val grant_id: String? = null,
        val minutes: Int? = null,
        val expires_at: String? = null
    )

    /** Nested `child` from `child:children(id, first_name)` (OBJECT or null). */
    @Serializable
    private data class DeviceChildRelationDto(
        val id: String,
        val first_name: String
    )

    /**
     * Wire shape returned by the `get-devices-for-parent` edge function
     * (Supabase REST row). Mirrors the columns selected in
     * `supabase/functions/get-devices-for-parent/index.ts:73-87`. The
     * `device_state` column is stored as the Postgres enum (string) and
     * maps to [DeviceState] via [toChildDevice].
     *
     * Change A of `feat-multi-child-picker` (design §A.6 / §A.8) adds
     * `child_id` + `child_first_name` so the parser can hydrate
     * `ChildDevice.child = Child(...)` when the wire carries a child.
     * Both fields default-null to keep back-compat with devices paired
     * BEFORE the `children` table migration — those rows keep a `null`
     * `child` and the dashboard surfaces "Sin asignar".
     */
    @Serializable
    private data class DeviceDto(
        val id: String,
        val device_name: String,
        val device_model: String? = null,
        val os_version: String? = null,
        val app_version: String = "1.0.0",
        val device_state: String = "ACTIVE",
        val policy_version: Int = 1,
        val last_seen_at: String,
        val child_id: String? = null,
        val child: DeviceChildRelationDto? = null
    ) {
        fun toChildDevice(): ChildDevice = ChildDevice(
            id = id,
            name = device_name,
            model = device_model,
            appVersion = app_version,
            policyVersion = policy_version,
            state = when (device_state.uppercase()) {
                "LOCKED" -> DeviceState.LOCKED
                "DOWNTIME" -> DeviceState.DOWNTIME
                else -> DeviceState.ACTIVE
            },
            lastSeenAt = last_seen_at,
            // Devices are considered "online" if their last heartbeat is
            // within the past 5 minutes. The edge function returns the ISO
            // timestamp from `devices.last_seen_at` (default NOW()). Use a
            // forgiving ISO parser so a malformed value degrades to offline
            // instead of crashing the dashboard.
            isOnline = runCatching {
                val instant = java.time.Instant.parse(last_seen_at)
                val ageMs = System.currentTimeMillis() - instant.toEpochMilli()
                ageMs in 0..(5 * 60 * 1000L)
            }.getOrDefault(false),
            // Hydrate the child only when BOTH wire fields are present;
            // any other shape (both-null, only-id, only-name) leaves
            // `child = null` so the dashboard falls through to the
            // "Sin asignar" surface per design A.6. The `parentId` /
            // `createdAt` / `updatedAt` are empty on the device payload —
            // a future `getChildren()` call hydrates them (design A.6).
            child = child?.let {
                Child(
                    id = it.id,
                    parentId = "",
                    firstName = it.first_name,
                    createdAt = "",
                    updatedAt = ""
                )
            }
        )
    }
}
