package com.tudominio.parentalcontrol.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.data.local.PendingRequestsCache
import com.tudominio.parentalcontrol.domain.model.RequestStatus
import com.tudominio.parentalcontrol.domain.model.TimeRequest
import com.tudominio.parentalcontrol.network.SupabaseClientProvider
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RED coverage for `fix-parent-log-events-cleared-on-reopen`.
 *
 * Bug: "Log de eventos del padre se borra al reabrir la app".
 *
 * Surface: `ParentRepository.pendingRequestsFlow` (read by `ParentViewModel.init {}`
 * at line 119 of `app/src/main/java/com/tudominio/parentalcontrol/viewmodel/ParentViewModel.kt`
 * and mirrored into `_pendingRequests`, which backs the Solicitudes tab in
 * `DashboardScreen.kt:95`).
 *
 * Today (pre-fix):
 *  - `_pendingRequestsFlow` is initialized to `emptyList()` at construction
 *    (`ParentRepository.kt:71`).
 *  - `publishPendingRequests` writes to the in-memory flow only
 *    (`ParentRepository.kt:81-83`); it does NOT persist to SharedPreferences
 *    or any DataStore.
 *  - On process death + relaunch, a fresh `ParentRepository` instance starts
 *    from `emptyList()` again. The SolicitudesPollingWorker does not fire for
 *    up to 5 minutes, so the parent sees an empty "Solicitudes" tab for that
 *    entire window even though rows still exist on the Supabase
 *    `time_requests` table.
 *
 * The RED tests below assert the cold-start invariant. They will go GREEN
 * once `ParentRepository` hydrates `pendingRequestsFlow` from a local
 * SharedPreferences-backed cache on construction and writes through on every
 * `publishPendingRequests` call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ParentRepositoryColdStartTest {

    private lateinit var context: Context
    private lateinit var authManager: DeviceAuthManager
    private lateinit var clientProvider: SupabaseClientProvider

    private val fixture = listOf(
        TimeRequest(
            id = "tr-001",
            deviceId = "dev-001",
            deviceName = "Galaxy Tab S6 Lite",
            minutesRequested = 30,
            reason = "homework",
            status = RequestStatus.PENDING,
            createdAt = "2026-07-03T11:00:00Z"
        ),
        TimeRequest(
            id = "tr-002",
            deviceId = "dev-002",
            deviceName = "Moto G32",
            minutesRequested = 15,
            status = RequestStatus.PENDING,
            createdAt = "2026-07-03T11:05:00Z"
        )
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Wipe the eventual cache prefs namespace so each test starts cold.
        // The actual prefs file name is fixed by the fix; today there is no
        // writer, so this clear() is a no-op but harmless.
        context.getSharedPreferences(PendingRequestsPrefs.NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        // Wipe the DataStore namespace too — `PendingRequestsCache` lives
        // there after this fix lands, and Robolectric does not reset
        // app-private storage between tests in the same JVM.
        PendingRequestsCache.clearForTest(context)

        authManager = mockk(relaxed = true)
        clientProvider = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(PendingRequestsPrefs.NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        PendingRequestsCache.clearForTest(context)
    }

    /**
     * T1.1 — RED today.
     *
     * Scenario: warm session publishes a non-empty list of pending requests
     * (the polling worker or a `loadPendingRequests` success path). Then
     * process death: drop the `ParentRepository` and construct a fresh one
     * (this mirrors what Hilt does on real cold start — the @Singleton is
     * re-created). The fresh instance's `pendingRequestsFlow` MUST already
     * carry the fixture so the VM's first `collectAsState()` reads it
     * synchronously, before any network round-trip.
     *
     * Today: fails. `ParentRepository.pendingRequestsFlow.value` is
     * `emptyList()` on a fresh instance because there is no disk-hydration
     * path.
     */
    @Test
    fun `pendingRequestsFlow hydrates from disk on cold start`() = runTest {
        // Warm session: populate the singleton flow with the fixture.
        val warmRepository = newRepository()
        warmRepository.publishPendingRequests(fixture)
        // The `publishPendingRequests` write-through to DataStore runs on
        // `Dispatchers.IO` via the repository's private `cacheScope`.
        // `runTest` does not await real-IO threads; wait for the cache
        // write to settle before simulating process death so the cold
        // instance's hydration sees the warm-written payload.
        awaitUntil { warmRepository.pendingRequestsFlow.value == fixture }
        assertEquals(
            "sanity: warm repository must hold the fixture before simulated process death",
            fixture, warmRepository.pendingRequestsFlow.value
        )

        // Process death: drop the warm repository entirely and build a
        // fresh one (Hilt does this on every real cold start — @Singleton
        // is process-scoped, the OS reaps the process, on relaunch Hilt
        // constructs a brand-new instance).
        val coldRepository = newRepository()
        // Cold-start hydration runs in `init {}` on `Dispatchers.IO`
        // (same real-IO scope as the warm write). Wait for it.
        awaitUntil { coldRepository.pendingRequestsFlow.value == fixture }

        // The cold-start instance MUST surface the fixture synchronously
        // — this is the parent-side "event log" the user expects to see.
        assertEquals(
            "fresh ParentRepository on cold start MUST hydrate pendingRequestsFlow " +
                "from local cache (SharedPreferences/DataStore), not emptyList()",
            fixture, coldRepository.pendingRequestsFlow.value
        )
    }

    /**
     * T1.2 — RED today (sharpened assertion).
     *
     * Stronger form of T1.1: assert the cold-start value is NOT the empty
     * list. This guards against a regression where the cache is written but
     * never read on init (today's actual state — write path doesn't exist,
     * read path doesn't exist, and `emptyList()` is the only option).
     */
    @Test
    fun `pendingRequestsFlow on cold start is not empty after warm session`() = runTest {
        val warmRepository = newRepository()
        warmRepository.publishPendingRequests(fixture)
        awaitUntil { warmRepository.pendingRequestsFlow.value == fixture }

        val coldRepository = newRepository()
        awaitUntil { coldRepository.pendingRequestsFlow.value == fixture }

        assertNotEquals(
            "cold-start pendingRequestsFlow MUST NOT be emptyList() when a warm " +
                "session populated it before process death",
            emptyList<TimeRequest>(),
            coldRepository.pendingRequestsFlow.value
        )
    }

    /**
     * Polls [predicate] on real wall-clock time (via [Thread.sleep]) until it
     * returns `true` or [timeoutMs] elapses. Required because
     * `ParentRepository` writes to the DataStore cache on `Dispatchers.IO`
     * (real thread pool), and `runTest`'s virtual-time dispatcher does not
     * advance those real-IO coroutines — same pattern as `BootReceiverTest`'s
     * `Thread.sleep(1000L)` waits.
     */
    private fun awaitUntil(
        timeoutMs: Long = 2000L,
        intervalMs: Long = 20L,
        predicate: () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(intervalMs)
        }
    }

    /**
     * T1.3 — GREEN today (sanity pin).
     *
     * No warm session → fresh install / first launch → cold-start value is
     * the empty list. This must NOT regress when the cache layer lands: a
     * missing/empty cache file should hydrate to `emptyList()`, not throw.
     */
    @Test
    fun `pendingRequestsFlow on cold start without prior session stays empty`() = runTest {
        val coldRepository = newRepository()

        assertEquals(
            "fresh install MUST start at emptyList() — no fake rows from a missing cache",
            emptyList<TimeRequest>(),
            coldRepository.pendingRequestsFlow.value
        )
    }

    private fun newRepository(): ParentRepository =
        ParentRepository(context, authManager, clientProvider)
}

/**
 * Constants for the eventual SharedPreferences-backed cache. Defined here
 * (test-side) so the RED tests reference the same namespace the apply phase
 * will introduce in `data/local/PendingRequestsPrefs.kt`. Centralized so a
 * future refactor only changes one place.
 */
internal object PendingRequestsPrefs {
    const val NAME = "parent_pending_requests_cache"
    const val KEY_REQUESTS = "requests_json_v1"
}
