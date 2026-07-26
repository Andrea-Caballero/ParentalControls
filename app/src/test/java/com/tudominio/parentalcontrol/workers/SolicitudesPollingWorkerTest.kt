package com.tudominio.parentalcontrol.workers

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.tudominio.parentalcontrol.BuildConfig
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.data.repository.DeviceListError
import com.tudominio.parentalcontrol.data.repository.ParentRepository
import com.tudominio.parentalcontrol.domain.model.RequestStatus
import com.tudominio.parentalcontrol.domain.model.TimeRequest
import com.tudominio.parentalcontrol.network.ConnectionState
import com.tudominio.parentalcontrol.network.SupabaseClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests for [SolicitudesPollingWorker].
 *
 * The worker mirrors `HeartbeatWorker`'s lifecycle (design D3) but is
 * read-only — it fetches pending time requests and pushes the result
 * through `ParentRepository.pendingRequestsFlow` so the
 * `ParentViewModel` collector updates the UI without a manual reload.
 *
 * Scenarios covered:
 *  - **Offline** (design D4): `connectionState != CONNECTED` → `Result.success()`
 *    no-op, no fetch attempted, no `publishPendingRequests` call.
 *  - **No signed-in parent**: `getPendingRequests()` returns
 *    `DeviceListError.AuthMissing` → `Result.success()` no-op (spec scenario
 *    "Worker with no signed-in parent is a no-op").
 *  - **Success**: `getPendingRequests()` returns a list → `publishPendingRequests`
 *    called and `Result.success()`.
 *
 * Pattern follows `OutboxDrainerTest`: Robolectric + `WorkManagerTestInitHelper`
 * + MockK for the repository / provider.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SolicitudesPollingWorkerTest {

    private lateinit var context: Context
    private lateinit var parentRepository: ParentRepository
    private lateinit var clientProvider: SupabaseClientProvider
    private lateinit var authManager: DeviceAuthManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        parentRepository = mockk(relaxed = true)
        clientProvider = mockk(relaxed = true)
        authManager = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        // No persistent state; mocks are GC'd per test.
    }

    private fun newWorker(
        repository: ParentRepository = parentRepository,
        provider: SupabaseClientProvider = clientProvider,
        auth: DeviceAuthManager = authManager
    ): SolicitudesPollingWorker {
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker? {
                return SolicitudesPollingWorker(
                    appContext,
                    workerParameters,
                    repository,
                    provider,
                    auth
                )
            }
        }
        return TestListenableWorkerBuilder
            .from(context, SolicitudesPollingWorker::class.java)
            .setWorkerFactory(factory)
            .build() as SolicitudesPollingWorker
    }

    /**
     * Scenario "Worker skips when offline" — design D4:
     *   `connectionState.value != ConnectionState.CONNECTED` → return
     *   `Result.success()` without fetching.
     */
    @Test
    fun doWork_whenOffline_returnsSuccess_withoutFetching() = runBlocking {
        every { clientProvider.connectionState } returns
            kotlinx.coroutines.flow.MutableStateFlow(ConnectionState.DISCONNECTED)

        val worker = newWorker()
        val result = worker.doWork()

        assertEquals(
            "offline worker must return success without throwing",
            ListenableWorker.Result.success(), result
        )
        coVerify(exactly = 0) { parentRepository.getPendingRequests() }
        coVerify(exactly = 0) { parentRepository.publishPendingRequests(any()) }
    }

    /**
     * Scenario "Worker with no signed-in parent is a no-op" — when the
     * repository call returns `AuthMissing`, the worker returns
     * `Result.success()` (no retry loop) and does NOT mutate
     * `pendingRequestsFlow`.
     */
    @Test
    fun doWork_whenNoSession_returnsSuccess_withoutMutatingState() = runBlocking {
        every { clientProvider.connectionState } returns
            kotlinx.coroutines.flow.MutableStateFlow(ConnectionState.CONNECTED)
        coEvery { parentRepository.getPendingRequests() } returns
            Result.failure(DeviceListError.AuthMissing)

        val worker = newWorker()
        val result = worker.doWork()

        assertEquals(
            "AuthMissing must surface as success (no retry, no loop)",
            ListenableWorker.Result.success(), result
        )
        coVerify(exactly = 1) { parentRepository.getPendingRequests() }
        coVerify(exactly = 0) { parentRepository.publishPendingRequests(any()) }
    }

    /**
     * Scenario "Worker retries on transient failure" — when
     * `getPendingRequests()` returns a non-AuthMissing failure
     * (network drop, HTTP 5xx, parse error), the worker returns
     * `Result.retry()` so WorkManager applies exponential backoff.
     */
    @Test
    fun doWork_returns_retry_on_transient_failure() = runBlocking {
        every { clientProvider.connectionState } returns
            kotlinx.coroutines.flow.MutableStateFlow(ConnectionState.CONNECTED)
        coEvery { parentRepository.getPendingRequests() } returns
            Result.failure(DeviceListError.Transient("server 503"))

        val worker = newWorker()
        val result = worker.doWork()

        assertEquals(
            "transient failure must surface as retry (WorkManager backoff)",
            ListenableWorker.Result.retry(), result
        )
        coVerify(exactly = 1) { parentRepository.getPendingRequests() }
        coVerify(exactly = 0) { parentRepository.publishPendingRequests(any()) }
    }

    /**
     * Happy path — successful fetch pushes the parsed list into
     * `pendingRequestsFlow` (design D2) and returns `Result.success()`.
     * Spec scenario "Worker success pushes fresh data to the StateFlow".
     */
    @Test
    fun doWork_onSuccess_publishesToPendingRequestsFlow() = runBlocking {
        val fixture = listOf(
            TimeRequest(
                id = "tr-1",
                deviceId = "dev-1",
                deviceName = "Poco X6 Pro",
                minutesRequested = 30,
                reason = "homework",
                status = RequestStatus.PENDING,
                createdAt = "2026-06-30T00:00:00Z"
            )
        )
        every { clientProvider.connectionState } returns
            kotlinx.coroutines.flow.MutableStateFlow(ConnectionState.CONNECTED)
        coEvery { parentRepository.getPendingRequests() } returns Result.success(fixture)

        val worker = newWorker()
        val result = worker.doWork()

        assertEquals(
            "successful fetch must return success",
            ListenableWorker.Result.success(), result
        )
        coVerify(exactly = 1) { parentRepository.getPendingRequests() }
        coVerify(exactly = 1) { parentRepository.publishPendingRequests(fixture) }
    }

    /**
     * Worker pre-warm contract (per Q3=y from
     * `sdd/fix-v2-filter-production-lazy-hydration/decisions`): on every
     * 5-min tick, the worker MUST call
     * `parentRepository.getDevicesForParent()` BEFORE the no-arg
     * `getPendingRequests()` so the device cache stays fresh for the
     * V2 cold-start path. The pre-warm is best-effort — a failure is
     * swallowed (logged + continue) and MUST NOT short-circuit the
     * existing polling path.
     *
     * **RED before the production fix** because the worker on master
     * only calls `getPendingRequests()`. No
     * `getDevicesForParent()` call is issued, so the
     * `coVerify { parentRepository.getDevicesForParent() }` assertion
     * fails.
     *
     * This case uses a real `ParentRepository` (not a mock) wired to a
     * route-by-path `MockEngine` that responds to BOTH the hydration
     * POST and the time-requests GET — so the test exercises the real
     * `runCatching { parentRepository.getDevicesForParent() }`
     * call site end-to-end.
     */
    @Test
    fun doWork_preWarmsDevicesCacheBeforeGetPendingRequests() = runBlocking {
        val prewarmCaptured = mutableListOf<HttpRequestData>()
        val prewarmEngine = MockEngine { request ->
            prewarmCaptured.add(request)
            when {
                request.url.encodedPath.endsWith("/get-devices-for-parent") -> respond(
                    content = ByteReadChannel(
                        """[{"id":"dev-1","device_name":"Tablet","last_seen_at":"2026-07-07T12:00:00Z"}]"""
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
                request.url.encodedPath.endsWith("/time_requests") -> respond(
                    content = ByteReadChannel(
                        """[{"id":"tr-1","device_id":"dev-1","minutes_requested":15,"""" +
                            """status":"PENDING","created_at":"2026-07-07T12:00:00Z"}]"""
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
                else -> error("Unexpected request: ${request.method.value} ${request.url}")
            }
        }
        val prewarmClient = HttpClient(prewarmEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val prewarmClientProvider: SupabaseClientProvider = mockk()
        every { prewarmClientProvider.httpClient } returns prewarmClient
        every { prewarmClientProvider.connectionState } returns
            kotlinx.coroutines.flow.MutableStateFlow(ConnectionState.CONNECTED)
        val prewarmAuth: DeviceAuthManager = mockk(relaxed = true)
        every { prewarmAuth.getAccessToken() } returns "test-jwt-token"

        val realRepository = ParentRepository(
            context = context,
            authManager = prewarmAuth,
            clientProvider = prewarmClientProvider
        )
        val worker = newWorker(
            repository = realRepository,
            provider = prewarmClientProvider,
            auth = prewarmAuth
        )

        val result = worker.doWork()

        assertEquals(
            "Worker pre-warm must NOT short-circuit the success path",
            ListenableWorker.Result.success(), result
        )
        val hydrationIdx = prewarmCaptured.indexOfFirst {
            it.method == HttpMethod.Post &&
                it.url.encodedPath.endsWith("/get-devices-for-parent")
        }
        val timeGetIdx = prewarmCaptured.indexOfFirst {
            it.method == HttpMethod.Get && it.url.encodedPath.endsWith("/time_requests")
        }
        assertTrue(
            "Worker MUST pre-warm devices via POST to /get-devices-for-parent. " +
                "Captured: ${prewarmCaptured.map { "${it.method.value} ${it.url}" }}",
            hydrationIdx >= 0
        )
        assertTrue(
            "Worker MUST issue the time-requests GET. " +
                "Captured: ${prewarmCaptured.map { "${it.method.value} ${it.url}" }}",
            timeGetIdx >= 0
        )
        assertTrue(
            "Worker pre-warm (POST get-devices-for-parent) MUST precede the " +
                "time-requests GET. hydrationIdx=$hydrationIdx timeGetIdx=$timeGetIdx",
            hydrationIdx < timeGetIdx
        )
    }

    // -------------------------------------------------------------------
    // Shared-mock dev fast-poll re-arm contract.
    //
    // In the shared-mock dev environment the realtime push is in-process
    // only (PR #39), so the periodic 5-minute polling cadence is too slow
    // — a parent would not see a "Pedir tiempo" tap for up to 5 minutes.
    // The fix re-arms a separate `OneTimeWorkRequest` (REPLACE) at a
    // ~10-second cadence. The gate lives in two places, both gated only on
    // `BuildConfig.USE_SHARED_MOCK`:
    //
    //  1. `WorkScheduler.scheduleSolicitudesPolling` enqueues the one-shot
    //     in addition to the existing periodic.
    //  2. `SolicitudesPollingWorker.doWork` re-arms the next one-shot
    //     on success by calling back into
    //     `WorkScheduler.scheduleSolicitudesPolling(applicationContext)`.
    //
    // The default test build has `USE_SHARED_MOCK=false`, so the runtime
    // assertions assert the *production* path (no re-arm). The
    // source-text assertions lock the *contract* — under
    // `-PenableSharedMock=true` builds the branch will fire because the
    // gate is present in the source. This mirrors the
    // `NetworkModuleTest` source-text pattern: BuildConfig debug/release
    // variants are compile-time, so the only honest way to assert
    // "the shared-mock branch exists in source" is to read the file.
    // -------------------------------------------------------------------

    @Test
    fun doWork_onSuccess_inSharedMock_reEnqueuesNextFastPoll() = runBlocking {
        val sharedMockActive =
            BuildConfig.USE_SHARED_MOCK

        // Source-text contract — worker must gate a re-arm call on the
        // shared-mock flag.
        val workerSrc = File(
            "src/main/java/com/tudominio/parentalcontrol/workers/SolicitudesPollingWorker.kt"
        ).readText()
        assertTrue(
            "SolicitudesPollingWorker.doWork must gate the fast-poll re-arm " +
                "on BuildConfig.USE_SHARED_MOCK and call " +
                "WorkScheduler.scheduleSolicitudesPolling. Source:\n$workerSrc",
            workerSrc.contains("if (BuildConfig.USE_SHARED_MOCK)") &&
                !workerSrc.contains("USE_SHARED_MOCK &&") &&
                workerSrc.contains("WorkScheduler.scheduleSolicitudesPolling")
        )

        // Source-text contract — scheduler must enqueue a one-shot
        // fast-poll (REPLACE) gated on the same flag. PeriodicWork has
        // a 15-minute floor that we must NOT try to bypass.
        val schedulerSrc = File(
            "src/main/java/com/tudominio/parentalcontrol/workers/WorkScheduler.kt"
        ).readText()
        assertTrue(
            "WorkScheduler.scheduleSolicitudesPolling must gate a " +
                "OneTimeWorkRequestBuilder<SolicitudesPollingWorker> " +
                "fast-poll on BuildConfig.USE_SHARED_MOCK with " +
                "ExistingWorkPolicy.REPLACE. Source:\n$schedulerSrc",
            schedulerSrc.contains("if (BuildConfig.USE_SHARED_MOCK)") &&
                !schedulerSrc.contains("USE_SHARED_MOCK &&") &&
                schedulerSrc.contains("OneTimeWorkRequestBuilder<SolicitudesPollingWorker>") &&
                schedulerSrc.contains("ExistingWorkPolicy.REPLACE")
        )

        // Runtime: under the current BuildConfig, the worker must
        // re-arm iff shared-mock dev is active.
        mockkObject(WorkScheduler)
        try {
            every { clientProvider.connectionState } returns
                MutableStateFlow(ConnectionState.CONNECTED)
            coEvery { parentRepository.getPendingRequests() } returns
                Result.success(emptyList())

            val worker = newWorker()
            val result = worker.doWork()

            assertEquals(
                "successful fetch must still return success (re-arm does " +
                    "not change the worker result)",
                ListenableWorker.Result.success(), result
            )
            verify(exactly = if (sharedMockActive) 1 else 0) {
                WorkScheduler.scheduleSolicitudesPolling(any())
            }
        } finally {
            unmockkObject(WorkScheduler)
        }
    }

    @Test
    fun scheduleSolicitudesPolling_inSharedMock_enqueuesOneShotFastPoll() {
        val sharedMockActive =
            BuildConfig.USE_SHARED_MOCK

        // Initialise the WorkManager test helper per the pattern in
        // `WorkersTest.WorkSchedulerSchedulingTest` — without this,
        // `enqueueUniqueWork` throws because there is no WorkManager
        // bound to the application context under Robolectric.
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.DEBUG)
                .build()
        )

        WorkScheduler.scheduleSolicitudesPolling(context)
        shadowOf(Looper.getMainLooper()).idle()

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(SolicitudesPollingWorker.WORK_NAME)
            .get()

        // Periodic and one-time work share the unique-work namespace.
        // In shared-mock builds REPLACE leaves the delayed one-shot as the
        // active unique work; otherwise the original periodic work remains.
        assertEquals(1, infos.size)
        val info = infos.single()
        if (sharedMockActive) {
            assertEquals(
                "shared-mock fast-poll must wait 10 seconds",
                10_000L,
                info.initialDelayMillis
            )
            assertEquals(
                "shared-mock active work must be the one-shot fast-poll",
                null,
                info.periodicityInfo
            )
        } else {
            assertEquals(
                "production periodic work must not add an initial delay",
                0L,
                info.initialDelayMillis
            )
            assertTrue(
                "production active work must remain periodic",
                info.periodicityInfo != null
            )
        }
    }
}
