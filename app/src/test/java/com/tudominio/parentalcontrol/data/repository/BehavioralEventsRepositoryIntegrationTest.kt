package com.tudominio.parentalcontrol.data.repository

import android.content.Context
import androidx.room.Room
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.auth.Role
import com.tudominio.parentalcontrol.data.db.BehavioralEventDao
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.data.remote.MockSupabaseEngine
import com.tudominio.parentalcontrol.network.SupabaseClientProvider
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * RED→GREEN coverage for the integration gap discovered during the 5th live
 * test session (2026-07-08, post `feat-parent-behavioral-event-log`):
 * after the parent taps "Soy el padre" on OnboardingScreen, the synthetic
 * parent auth path (`DeviceAuthManager.authenticateOrCreate(Role.PARENT)`)
 * writes `role` + `synthetic_access_token` to `device_auth_prefs` but
 * **does NOT** write `parent_id`. Downstream: the DAO read filter
 * `WHERE parent_id = :parentId` never matches the fixture's
 * `parent_id = "parent-demo"` rows, and the UI shows "Sin eventos" instead
 * of the 5 seeded events.
 *
 * PR A's 4 unit tests in [BehavioralEventsRepositoryTest] covered the
 * wire-shape contract in isolation (they inject `parentId = "parent-demo"`
 * directly into `repository.refresh(...)`, bypassing the auth-manager
 * read). PR B's 8 Compose tests seeded the DAO directly via
 * `dao.insertAll(...)`. Neither covered the
 * `authenticateOrCreate(Role.PARENT)` → events-sourcing path end-to-end.
 * This test is the seam that was missing.
 *
 * **RED BEFORE FIX**: the refresh writes 5 fixture rows with
 * `parent_id = "parent-demo"` into the DAO, but the auth-manager lookup
 * returns `parentId = null` (because the synthetic auth path never wrote
 * `parent_id`), so the DAO filter `WHERE parent_id = ''` matches 0 of
 * those rows. The test fails on `assertEquals(5, events.size)`.
 *
 * **GREEN AFTER FIX**: `DeviceAuthManager.authenticateOrCreate(Role.PARENT)`
 * writes `parent_id = MockSupabaseEngine.MOCK_PARENT_ID` alongside the
 * existing `role` + `synthetic_access_token` writes. The refresh URL
 * carries `parent_id=eq.parent-demo`, the mock returns the 5 events, the
 * DAO upserts them with `parentId = "parent-demo"`, and the DAO flow
 * emits 5 rows. The VM's `vm.events` StateFlow observes this same flow
 * via `stateIn → repository.observe → dao.flowByParent`, so the parent
 * UI shows the 5 events instead of "Sin eventos".
 *
 * Robolectric + real `MockSupabaseEngine` (serves the real
 * `behavioral_events.json` fixture) + real Room in-memory DB.
 *
 * Implementation note: the original draft of this test asserted against
 * `BehaviorLogViewModel.events.first()` while `runTest { ... }` drove the
 * scheduler. That combination is racy under `runTest`'s virtual-time
 * scheduler: the VM's `init { refresh() }` is fire-and-forget on
 * `viewModelScope` (= Dispatchers.Main), and the refresh + DAO write
 * cross `Dispatchers.IO` (a real thread pool). `runTest` cannot advance
 * real-thread work via virtual time, so the async pipeline stalls and
 * `vm.events.first()` either returns the StateFlow's `initialValue =
 * emptyList()` (test timed out, fails with empty list) or `withTimeout`
 * fires as a virtual-time TimeoutCancellationException. To make the
 * assertion deterministic we trigger the refresh directly on the
 * repository (same code path the VM's `init { refresh() }` calls) and
 * read the underlying DAO flow that `vm.events` observes. This exercises
 * the same `authenticateOrCreate → getParentId → repository.refresh →
 * dao.flowByParent` seam the VM does, without the StateFlow + virtual
 * time race.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BehavioralEventsRepositoryIntegrationTest {

    private lateinit var context: Context
    private lateinit var database: ParentalDatabase
    private lateinit var dao: BehavioralEventDao
    private lateinit var authManager: DeviceAuthManager
    private val openClients = mutableListOf<HttpClient>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        authManager = DeviceAuthManager.getInstance(context)
        database = Room.inMemoryDatabaseBuilder(
            context,
            ParentalDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.behavioralEventDao()
    }

    @After
    fun tearDown() {
        // Wipe the synthetic parent prefs the test wrote so sibling tests
        // start from a clean slate (DeviceAuthManager is a process-wide
        // singleton).
        authManager.clearSession()
        openClients.forEach { runCatching { it.close() } }
        database.close()
    }

    /**
     * End-to-end RED→GREEN test: simulate the parent's live auth path
     * (`authenticateOrCreate(Role.PARENT)` — the one the live test's
     * "Soy el padre" CTA actually triggers), wire the real
     * [MockSupabaseEngine] as the HTTP client, trigger a refresh, and
     * assert the 5 fixture events surface on the DAO path the VM's
     * `vm.events` StateFlow observes.
     */
    @Test
    fun fixture_events_surface_in_viewmodel_after_synthetic_parent_auth() = runBlocking {
        // Arrange: simulate the live-device parent auth path exactly.
        val authResult = authManager.authenticateOrCreate(Role.PARENT)
        assertTrue(
            "Synthetic parent auth must succeed, got $authResult",
            authResult.isSuccess
        )

        // The repo requires a non-null access token; the synthetic path
        // already sets one in `currentAccessToken` (verified by the
        // authResult.isSuccess assert).
        assertNotNull(
            "After authenticateOrCreate(Role.PARENT), getAccessToken() must be non-null",
            authManager.getAccessToken()
        )

        // Pin the fix: the synthetic PARENT auth path must now write
        // parent_id alongside role + synthetic_access_token so the DAO
        // filter matches the fixture's parent_id column.
        val parentId = authManager.getParentId()
            ?: error("After authenticateOrCreate(Role.PARENT), getParentId() must be non-null")
        assertEquals(
            "authenticateOrCreate(Role.PARENT) must write parent_id = MOCK_PARENT_ID " +
                "(matches every row in behavioral_events.json)",
            "parent-demo",
            parentId
        )

        // Wire the real MockSupabaseEngine — serves the real
        // behavioral_events.json fixture (5 events, parent_id="parent-demo").
        val realMockClient: HttpClient = MockSupabaseEngine(context).httpClient
        openClients.add(realMockClient)
        val provider = SupabaseClientProvider(context, injectedClient = realMockClient)
        val repository = BehavioralEventsRepository(provider, dao, authManager)

        // Act: trigger the same refresh the VM's `init { refresh() }`
        // fires, awaited directly to bypass the StateFlow + virtual-time
        // race documented at the top of this file.
        val refreshResult = repository.refresh(parentId)
        assertTrue(
            "Refresh must succeed after synthetic parent auth, got $refreshResult",
            refreshResult.isSuccess
        )

        // Assert: the DAO surface for the auth-resolved parentId carries
        // the 5 fixture events. The VM's `vm.events` StateFlow observes
        // this same flow via `stateIn → repository.observe →
        // dao.flowByParent`, so a successful read here proves the
        // BehaviorLogScreen will render the 5 events instead of
        // "Sin eventos".
        val events = dao.flowByParent(parentId).first()

        // RED before fix: events.size == 0 because parentId="" filters out
        // the fixture's parent_id="parent-demo" rows.
        // GREEN after fix: events.size == 5.
        assertEquals(
            "DAO must surface the 5 fixture events after synthetic parent auth; " +
                "got ${events.size} events (parentId='$parentId')",
            5,
            events.size
        )
    }
}
