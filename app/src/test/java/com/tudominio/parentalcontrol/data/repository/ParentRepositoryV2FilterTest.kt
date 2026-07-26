package com.tudominio.parentalcontrol.data.repository

import android.content.Context
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.network.SupabaseClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RED tests for the V2 server-side `Solicitudes` filter of
 * `sdd/fix-v2-server-side-solicitudes-filter`.
 *
 * Today's V1 implementation issues a single static Postgrest GET at
 * `ParentRepository.kt:297-298` with NO `device_id` filter — it returns
 * ALL pending requests belonging to the parent's devices (RLS scoped).
 * The client-side picker on `DashboardScreen` then discards rows whose
 * `deviceId` is not part of the currently-selected child.
 *
 * V2's contract: pushing `selectedChildId` to the server so only the
 * requested device ids (or all when `null`/Todos) come back. Specifically:
 *
 *  1. When `selectedChildId = "child-lucas-id"`, the request URL MUST
 *     carry `&device_id=in.(<devices of child lucas>)`. The repository is
 *     the one entity that knows which device ids belong to that child
 *     (it resolves them from the cached / just-fetched `getDevices()`
 *     list) — see [ParentRepository.getPendingRequests] future signature.
 *
 *  2. When `selectedChildId = null` (Todos), the request URL MUST NOT
 *     carry a `device_id` filter. RLS still scopes by `parent_id`. This
 *     keeps the no-arg overload `getPendingRequests()` callable from
 *     `SolicitudesPollingWorker.kt:70` (5-min auto-poll) without a
 *     behavioural change.
 *
 * Both assertions are RED today because (a) the no-arg method
 * `getPendingRequests()` does not take a `selectedChildId: String?`
 * parameter and (b) the URL builder hardcodes the static query without
 * any `device_id` clause — see the docstring at
 * `ParentRepository.kt:277-290`.
 *
 * These tests turn GREEN when `ParentRepository.getPendingRequests` is
 * overloaded to accept an optional `selectedChildId` parameter and the
 * URL builder appends `device_id=in.(...)` for non-null selections.
 */
class ParentRepositoryV2FilterTest {

    private lateinit var captured: MutableList<HttpRequestData>
    private lateinit var mockClient: HttpClient
    private lateinit var authManager: DeviceAuthManager
    private lateinit var clientProvider: SupabaseClientProvider
    private lateinit var context: Context
    private lateinit var repository: ParentRepository

    @Before
    fun setUp() {
        captured = mutableListOf()

        // Route-by-path MockEngine: POST to `/get-devices-for-parent`
        // answers with the standard `GET_DEVICES_RESPONSE_BODY` so the
        // V2 cold-start lazy-hydration gate at
        // `ParentRepository.kt:354-391` can fetch Lucas's device
        // naturally; GET to `/time_requests` answers with
        // `PENDING_REQUESTS_RESPONSE_BODY`. This replaces the
        // `primeDevicesCache` test seam removed in
        // `fix-v2-filter-production-lazy-hydration` (per Q4=r).
        val mockEngine = MockEngine { request ->
            captured.add(request)
            when {
                request.url.encodedPath.endsWith("/get-devices-for-parent") -> respond(
                    content = ByteReadChannel(GET_DEVICES_RESPONSE_BODY),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
                request.url.encodedPath.endsWith("/time_requests") -> respond(
                    content = ByteReadChannel(PENDING_REQUESTS_RESPONSE_BODY),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
                else -> error("Unexpected request: ${request.method.value} ${request.url}")
            }
        }

        mockClient = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        authManager = mockk()
        every { authManager.getAccessToken() } returns "test-jwt-token"

        clientProvider = mockk()
        every { clientProvider.httpClient } returns mockClient

        context = mockk(relaxed = true)

        repository = ParentRepository(
            context = context,
            authManager = authManager,
            clientProvider = clientProvider
        )
    }

    @After
    fun tearDown() {
        mockClient.close()
    }

    /**
     * Captures the request issued by a no-arg `getPendingRequests()` and
     * resolves its full URL (path + raw query). The mock captures the
     * raw query via [HttpRequestData.url].
     */
    private fun lastRequestUrl(): String {
        val request = captured.lastOrNull()
            ?: error("No HTTP request was captured — the repository never issued a fetch")
        return request.url.toString()
    }

    /**
     * V2 contract, positive: when the parent UI has selected a child, the
     * repository MUST append `device_id=in.(<ids>)` to the Postgrest
     * query. Today (V1) the request URL is hardcoded at
     * `ParentRepository.kt:297-298` and has no `device_id` clause — this
     * test is RED.
     */
    @Test
    fun `getPendingRequests with selectedChildId sends device_id in filter`() = runTest {
        val result = repository.getPendingRequests(selectedChildId = CHILD_LUCAS_ID)

        // 1. The fetch must succeed with a non-failure Result — this guards
        //    against a future regression where the V2 path accidentally
        //    short-circuits before the HTTP layer is reached.
        assertTrue(
            "Expected getPendingRequests(selectedChildId=…) to succeed, got $result",
            result.isSuccess
        )

        // 2. The captured URL MUST carry a `device_id=in.(...)` clause.
        //    In V2 the repository translates the child id to its
        //    associated device id(s) and emits a Postgrest `in` filter.
        //    In V1 the URL is the static string at ParentRepository.kt:298,
        //    which has no `device_id` segment — this assertion is RED.
        val url = lastRequestUrl()
        assertTrue(
            "Expected request URL to contain 'device_id=in.(...)' when a child is " +
                "selected, but got: $url",
            url.contains("device_id=in.")
        )

        // 3. The Postgrest `in.(...)` list MUST include Lucas's device
        //    (dev-001). UUIDs are URL-safe so no encoding is required;
        //    a single-id filter is the minimum viable shape when only
        //    one device is linked to the child record.
        assertTrue(
            "Expected device_id=in filter to include 'dev-001' (Lucás's device), " +
                "but got: $url",
            url.contains("dev-001")
        )

        // 4. The static ordering and status clauses MUST remain intact —
        //    the V2 change is purely additive.
        assertTrue(
            "Expected the existing status filter to be preserved, but got: $url",
            url.contains("status=eq.PENDING")
        )
    }

    /**
     * V2 contract, neutral: when `selectedChildId = null` (Todos) the URL
     * MUST NOT carry a `device_id=` clause. RLS
     * (`time_requests_parent_select`, see
     * `supabase/migrations/002_rls_policies.sql:153-161`) already
     * scopes rows by `parent_id = auth.uid()`. Adding a `device_id=in.()`
     * filter on the Todos path would force the repository to resolve the
     * parent's full device list on every fetch — wasted bytes.
     *
     * Today (V1) the no-arg call passes the static URL with no
     * `device_id` clause — this assertion happens to be GREEN. We keep
     * it as a guardrail so the GREEN phase can't accidentally regress
     * the Todos path.
     */
    @Test
    fun `getPendingRequests with null selectedChildId omits device_id filter`() = runTest {
        val result = repository.getPendingRequests(selectedChildId = null)

        assertTrue(
            "Expected getPendingRequests(selectedChildId=null) to succeed, got $result",
            result.isSuccess
        )

        val url = lastRequestUrl()
        assertFalse(
            "Expected no 'device_id=' clause when selectedChildId is null " +
                "(Todos returns all parent's pending requests via RLS), " +
                "but got: $url",
            url.contains("device_id=")
        )
        // Sanity: the static shape must still be there.
        assertTrue(
            "Expected status=eq.PENDING to be preserved, but got: $url",
            url.contains("status=eq.PENDING")
        )
        assertTrue(
            "Expected order=created_at.desc to be preserved, but got: $url",
            url.contains("order=created_at.desc")
        )
    }

    /**
     * V2 contract, cold-start: when the device cache is empty (cold
     * start, no `loadDevices()` has run yet, no test-only
     * `primeDevicesCache(...)` seam) the V2 path MUST lazily hydrate
     * the device cache from a real `get-devices-for-parent` call
     * BEFORE resolving child→device ids, so the user does not see
     * "Sin solicitudes" when there ARE pending requests.
     *
     * This is the production follow-up to
     * `fix-v2-server-side-solicitudes-filter` (PR #21). The deferred
     * note at `ParentRepository.kt:101-107` calls this out by name:
     *
     *   "Hydration from a real `getDevices()` call ... is out of
     *    scope for V2 — the VM-side mirror at
     *    `ParentViewModel._devices` is the source of truth and the V2
     *    test path seeds the cache directly. ... a future
     *    lazy-hydration follow-up can call it once per `loadDevices()`
     *    success."
     *
     * Today's bug: the V2 path's cache can be empty when a parent
     * first selects a child (cold start, app re-launch, or a
     * `loadDevices()` transient failure that left `_devices` empty).
     * `deviceIdsForChild()` returns `[]` → V2 short-circuits to
     * `Result.success(emptyList())` (Q4=e from
     * `sdd/fix-v2-server-side-solicitudes-filter/decisions`) → the
     * parent sees "Sin solicitudes" even when there ARE pending
     * requests for the selected child's devices.
     *
     * **RED today** because the V2 path does NOT trigger hydration.
     * With an empty cache, the V2 call short-circuits at
     * `ParentRepository.kt:366-370` and issues NO HTTP requests —
     * `captured` stays empty and `lastOrNull()` throws. Every
     * assertion below fails on master.
     *
     * **GREEN when** `ParentRepository.getPendingRequests(selectedChildId)`
     * detects the empty-cache + child-selected precondition and
     * fires a hydration `getDevices()` call before resolving
     * child→device ids. The time-requests GET then carries
     * `device_id=in.(dev-001)` and the cache stays warm for the
     * second call (which MUST NOT re-hydrate).
     */
    @Test
    fun `getPendingRequests with selectedChildId lazily hydrates device cache when empty`() = runTest {
        // Cold-start fixture: a fresh MockEngine that routes the
        // hydration POST to `get-devices-for-parent` (returns Lucas's
        // device) and the V2 GET to `time_requests` (returns the
        // standard pending list). The route-by-path pattern mirrors
        // the integration-test seam at
        // `ParentRepositoryTest.kt:236-244` (fail-by-status override)
        // and `ParentRepositoryTest.kt:373-379` (GET URL assertion).
        val lazyCaptured = mutableListOf<HttpRequestData>()
        val lazyEngine = MockEngine { request ->
            lazyCaptured.add(request)
            when {
                request.url.encodedPath.endsWith("/get-devices-for-parent") -> respond(
                    content = ByteReadChannel(GET_DEVICES_RESPONSE_BODY),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
                request.url.encodedPath.endsWith("/time_requests") -> respond(
                    content = ByteReadChannel(PENDING_REQUESTS_RESPONSE_BODY),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
                else -> error("Unexpected request: ${request.method.value} ${request.url}")
            }
        }
        val lazyClient = HttpClient(lazyEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val lazyClientProvider: SupabaseClientProvider = mockk()
        every { lazyClientProvider.httpClient } returns lazyClient

        // NOTE: NO `primeDevicesCache(...)` — the device cache starts
        // empty, simulating cold start. The fix must hydrate from a
        // real `getDevices()` call, not rely on the test-only seam at
        // `ParentRepository.kt:425-427`.
        val coldRepository = ParentRepository(
            context = context,
            authManager = authManager,
            clientProvider = lazyClientProvider
        )

        // ----- First call: cold start -----
        val firstResult = coldRepository.getPendingRequests(selectedChildId = CHILD_LUCAS_ID)

        // 1. The call must succeed (the V2 path returns success even
        //    for the empty-cache case today — this is a sanity pin,
        //    not the bug we're fixing).
        assertTrue(
            "Expected getPendingRequests to succeed on cold start, got $firstResult",
            firstResult.isSuccess
        )

        // 2. The result MUST NOT be empty — that is exactly the bug.
        //    Today the V2 path returns `success(emptyList())` because
        //    `deviceIdsForChild` resolves to `[]` against an empty
        //    cache. The fix must hydrate first, then return the
        //    real rows. PENDING_REQUESTS_RESPONSE_BODY carries
        //    dev-001 + dev-002 rows; we expect at least one.
        assertNotEquals(
            "Lazy hydration: V2 must not return emptyList() when the " +
                "selected child has pending requests and the device " +
                "cache is empty. Got: ${firstResult.getOrNull()}",
            emptyList<com.tudominio.parentalcontrol.domain.model.TimeRequest>(),
            firstResult.getOrNull()
        )

        // 3. The hydration POST to `get-devices-for-parent` MUST have
        //    been issued. This is the core contract: V2 must
        //    self-hydrate when the cache is empty and a child is
        //    selected.
        val hydrationPost = lazyCaptured.firstOrNull {
            it.method == HttpMethod.Post &&
                it.url.encodedPath.endsWith("/get-devices-for-parent")
        }
        assertNotNull(
            "Lazy hydration: expected a POST to " +
                "/functions/v1/get-devices-for-parent when the device " +
                "cache is empty and a child is selected, but no such " +
                "request was issued. Captured URLs: " +
                lazyCaptured.map { "${it.method.value} ${it.url}" },
            hydrationPost
        )

        // 4. The time-requests GET MUST be the last issued request
        //    and MUST carry the resolved `device_id=in.(dev-001)`
        //    clause (proving the cache was populated BEFORE the
        //    device-id resolution ran).
        val firstGet = lazyCaptured.last()
        assertEquals(
            "The final issued request must be the time-requests GET, " +
                "not the hydration POST. Captured: " +
                lazyCaptured.map { "${it.method.value} ${it.url}" },
            HttpMethod.Get, firstGet.method
        )
        val firstGetUrl = firstGet.url.toString()
        assertTrue(
            "Lazy hydration: expected device_id=in.(dev-001) in the " +
                "time-requests GET URL after the cold-start hydration, " +
                "but got: $firstGetUrl",
            firstGetUrl.contains("device_id=in.(dev-001)")
        )

        // ----- Second call: cache is warm now -----
        val capturedAfterFirst = lazyCaptured.size
        val secondResult = coldRepository.getPendingRequests(selectedChildId = CHILD_LUCAS_ID)

        // 5. The second call MUST still succeed and return the
        //    resolved rows.
        assertTrue(
            "Expected the warm-cache call to succeed, got $secondResult",
            secondResult.isSuccess
        )

        // 6. The cache is warm now: no NEW hydration POST should be
        //    issued. Only the time-requests GET is allowed.
        val newHydrationPosts = lazyCaptured.drop(capturedAfterFirst).filter {
            it.method == HttpMethod.Post &&
                it.url.encodedPath.endsWith("/get-devices-for-parent")
        }
        assertEquals(
            "Warm-cache: V2 MUST NOT re-hydrate the device cache on a " +
                "second call. New hydration POSTs: $newHydrationPosts",
            0, newHydrationPosts.size
        )

        // 7. The second GET URL must still carry the device-id filter.
        val secondGet = lazyCaptured.last()
        val secondGetUrl = secondGet.url.toString()
        assertTrue(
            "Warm-cache: expected device_id=in.(dev-001) on the second " +
                "time-requests GET, but got: $secondGetUrl",
            secondGetUrl.contains("device_id=in.(dev-001)")
        )

        lazyClient.close()
    }

    /**
     * V2 contract, cold-start hydration failure: when the device cache
     * is empty AND the lazy-hydration `get-devices-for-parent` call
     * fails (e.g., HTTP 500 from the edge function), the V2 path MUST
     * propagate the failure as `DeviceListError.Transient` — NOT
     * silently return `success(emptyList())` (that IS the original
     * bug).
     *
     * Per Q1=f from `sdd/fix-v2-filter-production-lazy-hydration/decisions`:
     * the V2 call surfaces the hydration error to the UI as a transient
     * banner so the parent knows the empty state is a failure, not a
     * real "no requests yet" answer.
     *
     * **RED before the production fix** because the V2 path on master
     * short-circuits at `ParentRepository.kt:366-370` when the cache
     * is empty and returns `success(emptyList())` without ever calling
     * the hydration endpoint — so the assertion
     * `result.isFailure` fails and the typed-error assertion never
     * runs.
     */
    @Test
    fun `getPendingRequests with empty cache propagates hydration failure as Transient`() = runTest {
        val failCaptured = mutableListOf<HttpRequestData>()
        val failEngine = MockEngine { request ->
            failCaptured.add(request)
            when {
                request.url.encodedPath.endsWith("/get-devices-for-parent") -> respond(
                    content = ByteReadChannel("""{"error":"internal"}"""),
                    status = HttpStatusCode.InternalServerError,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
                else -> error("Unexpected request after hydration failure: ${request.method.value} ${request.url}")
            }
        }
        val failClient = HttpClient(failEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val failClientProvider: SupabaseClientProvider = mockk()
        every { failClientProvider.httpClient } returns failClient

        val failRepository = ParentRepository(
            context = context,
            authManager = authManager,
            clientProvider = failClientProvider
        )

        val result = failRepository.getPendingRequests(selectedChildId = CHILD_LUCAS_ID)

        assertTrue(
            "Hydration failure MUST surface as Result.failure, not silently " +
                "return success(emptyList()). Got: $result",
            result.isFailure
        )
        val error = result.exceptionOrNull()
        assertTrue(
            "Hydration failure MUST be typed as DeviceListError.Transient per Q1=f. " +
                "Got: $error (${error?.javaClass?.name})",
            error is DeviceListError.Transient
        )

        failClient.close()
    }

    /**
     * V2 contract, cold-start concurrent dedup: when 5 concurrent V2
     * calls fire on a cold start (the parent re-opens the Solicitudes
     * tab in 5 places — e.g., VM re-init + worker tick + 3 recompositions),
     * the lazy-hydration `get-devices-for-parent` call MUST dedupe
     * to a SINGLE HTTP round-trip via the Mutex + re-check pattern.
     *
     * Per Q2=m from `sdd/fix-v2-filter-production-lazy-hydration/decisions`:
     * a `kotlinx.coroutines.sync.Mutex` serializes the hydration; each
     * waiting caller re-checks `_devicesCache.value.isNotEmpty()` after
     * acquiring the lock and short-circuits when warm. 5 concurrent
     * calls → 1 POST.
     *
     * **RED before the production fix** because the V2 path on master
     * does not call any hydration at all. With no Mutex and no
     * hydration, all 5 calls return `success(emptyList())` — and even
     * if hydration were added without the Mutex, 5 calls would issue
     * 5 POSTs.
     */
    @Test
    fun `concurrent cold-start V2 calls dedupe hydration to a single getDevices HTTP call`() = runTest {
        val dedupCaptured = mutableListOf<HttpRequestData>()
        val dedupEngine = MockEngine { request ->
            dedupCaptured.add(request)
            when {
                request.url.encodedPath.endsWith("/get-devices-for-parent") -> respond(
                    content = ByteReadChannel(GET_DEVICES_RESPONSE_BODY),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
                request.url.encodedPath.endsWith("/time_requests") -> respond(
                    content = ByteReadChannel(PENDING_REQUESTS_RESPONSE_BODY),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
                else -> error("Unexpected request: ${request.method.value} ${request.url}")
            }
        }
        val dedupClient = HttpClient(dedupEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val dedupClientProvider: SupabaseClientProvider = mockk()
        every { dedupClientProvider.httpClient } returns dedupClient

        val dedupRepository = ParentRepository(
            context = context,
            authManager = authManager,
            clientProvider = dedupClientProvider
        )

        // 5 concurrent cold-start V2 calls — same child id, same empty
        // cache. The Mutex + re-check pattern must collapse them to a
        // single `get-devices-for-parent` POST.
        val results = (1..5).map {
            async { dedupRepository.getPendingRequests(selectedChildId = CHILD_LUCAS_ID) }
        }.awaitAll()

        results.forEachIndexed { i, r ->
            assertTrue(
                "All 5 concurrent V2 calls must succeed (index=$i), got: $r",
                r.isSuccess
            )
        }

        val hydrationPosts = dedupCaptured.filter {
            it.method == HttpMethod.Post &&
                it.url.encodedPath.endsWith("/get-devices-for-parent")
        }
        assertEquals(
            "Concurrent cold-start V2 calls must dedupe to a single " +
                "get-devices-for-parent POST via Mutex + re-check. " +
                "Captured: ${dedupCaptured.map { "${it.method.value} ${it.url}" }}",
            1, hydrationPosts.size
        )

        val timeGets = dedupCaptured.filter {
            it.method == HttpMethod.Get && it.url.encodedPath.endsWith("/time_requests")
        }
        assertEquals(
            "Each of the 5 concurrent V2 calls must issue its own time-requests GET.",
            5, timeGets.size
        )

        dedupClient.close()
    }

    companion object {
        private const val CHILD_LUCAS_ID = "child-lucas-id"

        // Two pending requests across two devices — one owned by Lucas
        // (dev-001) and one owned by another future child (dev-002). The
        // server is mocked to return both regardless of the filter; the
        // V2 contract asserts on the REQUEST URL shape, not the
        // response. Children table is in `supabase/migrations/005_children_table.sql`.
        private const val PENDING_REQUESTS_RESPONSE_BODY = """
            [
              {
                "id":"req-001",
                "device_id":"dev-001",
                "package_name":"com.instagram.android",
                "app_name":"Instagram",
                "minutes_requested":15,
                "reason":null,
                "status":"PENDING",
                "created_at":"2026-07-07T12:00:00Z",
                "responded_at":null,
                "parent_response":null,
                "devices": {"device_name":"Lucas Tablet"}
              },
              {
                "id":"req-002",
                "device_id":"dev-002",
                "package_name":"com.whatsapp",
                "app_name":"WhatsApp",
                "minutes_requested":10,
                "reason":null,
                "status":"PENDING",
                "created_at":"2026-07-07T12:01:00Z",
                "responded_at":null,
                "parent_response":null,
                "devices": {"device_name":"Other Tablet"}
              }
            ]
        """

        /**
         * Cold-start hydration fixture — the wire shape returned by
         * `${SUPABASE_URL}/functions/v1/get-devices-for-parent` when
         * the V2 path's lazy hydration fires. Mirrors the columns
         * the `DeviceDto` parser at
         * `ParentRepository.kt:771-823` expects. The nested
         * `child.id` is what allows the V2 path to resolve
         * `CHILD_LUCAS_ID` → "dev-001" once the hydration completes.
         */
        private const val GET_DEVICES_RESPONSE_BODY = """
            [
              {
                "id":"dev-001",
                "device_name":"Lucas Tablet",
                "device_model":"SM-P610",
                "os_version":"34",
                "app_version":"1.0.0",
                "device_state":"ACTIVE",
                "policy_version":3,
                "last_seen_at":"2026-07-07T12:00:00Z",
                "child_id":"child-lucas-id",
                "child": {"id":"child-lucas-id","first_name":"Lucas"}
              }
            ]
        """
    }
}
