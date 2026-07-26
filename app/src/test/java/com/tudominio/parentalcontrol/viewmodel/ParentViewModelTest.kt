package com.tudominio.parentalcontrol.viewmodel

import app.cash.turbine.test
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.auth.Role
import com.tudominio.parentalcontrol.data.repository.DeviceListError
import com.tudominio.parentalcontrol.data.repository.ParentRepository
import com.tudominio.parentalcontrol.domain.model.Child
import com.tudominio.parentalcontrol.domain.model.ChildDevice
import com.tudominio.parentalcontrol.domain.model.DeviceState
import com.tudominio.parentalcontrol.domain.model.RequestStatus
import com.tudominio.parentalcontrol.domain.model.TimeRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [ParentViewModel.authenticateAsParent] and the typed
 * [DeviceListUiState.Error] branch (T6 of `hotfix-parent-auth-session`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ParentViewModelTest {

    private lateinit var repository: ParentRepository
    private lateinit var authManager: DeviceAuthManager

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = mockk(relaxed = true)
        // `fix-parent-solicitudes-auto-poll` — the VM's `init` block now
        // collects `repository.pendingRequestsFlow`. Stub a default empty
        // flow so the collector doesn't NPE on the relaxed mock; the
        // dedicated test below overrides this stub with a custom value.
        every { repository.pendingRequestsFlow } returns MutableStateFlow(emptyList())
        authManager = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `authenticateAsParent delegates to auth manager with PARENT role`() = runTest {
        coEvery { authManager.authenticateOrCreate(Role.PARENT) } returns Result.success(Unit)

        val viewModel = ParentViewModel(repository, authManager)
        val result = viewModel.authenticateAsParent()

        assertTrue("authenticateAsParent must return success, got $result", result.isSuccess)
        coVerify { authManager.authenticateOrCreate(Role.PARENT) }
    }

    @Test
    fun `authenticateAsParent propagates auth manager failure`() = runTest {
        val failure = RuntimeException("auth failed")
        coEvery { authManager.authenticateOrCreate(Role.PARENT) } returns Result.failure(failure)

        val viewModel = ParentViewModel(repository, authManager)
        val result = viewModel.authenticateAsParent()

        assertTrue("authenticateAsParent must return failure, got $result", result.isFailure)
        assertEquals(failure, result.exceptionOrNull())
    }

    @Test
    fun `loadDevices with not-authenticated failure maps to Error AuthMissing`() = runTest {
        coEvery { repository.getDevices() } returns Result.failure(
            IllegalStateException("not authenticated")
        )

        val viewModel = ParentViewModel(repository, authManager)
        viewModel.loadDevices()

        val state = viewModel.deviceListState.value
        assertTrue(
            "Expected Error state with AuthMissing, got $state",
            state is DeviceListUiState.Error
        )
        val error = (state as DeviceListUiState.Error).reason
        assertEquals(
            "Error reason must be typed AuthMissing, not a raw string",
            DeviceListError.AuthMissing,
            error
        )
    }

    @Test
    fun `loadDevices with transient failure maps to Error Transient with reason`() = runTest {
        coEvery { repository.getDevices() } returns Result.failure(
            RuntimeException("HTTP 500 internal error")
        )

        val viewModel = ParentViewModel(repository, authManager)
        viewModel.loadDevices()

        val state = viewModel.deviceListState.value
        assertTrue(
            "Expected Error state with Transient, got $state",
            state is DeviceListUiState.Error
        )
        val error = (state as DeviceListUiState.Error).reason
        assertTrue(
            "Error reason must be Transient, got $error",
            error is DeviceListError.Transient
        )
        assertTrue(
            "Transient reason must carry the underlying message, got $error",
            (error as DeviceListError.Transient).reason.contains("HTTP 500")
        )
    }

    @Test
    fun `loadDevices with successful fixture maps to Success with parsed devices`() = runTest {
        val fixtureDevices = listOf(
            ChildDevice(
                id = "dev-001",
                name = "Galaxy Tab S6 Lite",
                model = "SM-P610",
                appVersion = "1.0.0",
                policyVersion = 3,
                state = DeviceState.ACTIVE,
                lastSeenAt = "2026-06-19T20:55:00Z",
                isOnline = true
            ),
            ChildDevice(
                id = "dev-002",
                name = "Moto G32",
                model = "moto g32",
                appVersion = "1.0.0",
                policyVersion = 1,
                state = DeviceState.DOWNTIME,
                lastSeenAt = "2026-06-19T20:58:00Z",
                isOnline = true
            )
        )
        coEvery { repository.getDevices() } returns Result.success(fixtureDevices)

        val viewModel = ParentViewModel(repository, authManager)
        viewModel.loadDevices()

        val state = viewModel.deviceListState.value
        assertTrue(
            "Expected Success state, got $state",
            state is DeviceListUiState.Success
        )
        val success = state as DeviceListUiState.Success
        assertEquals(2, success.items.size)
        assertEquals("dev-001", success.items.first().id)
        assertEquals("Galaxy Tab S6 Lite", success.items.first().name)
    }

    @Test
    fun `loadDevices with empty list maps to Empty state`() = runTest {
        coEvery { repository.getDevices() } returns Result.success(emptyList())

        val viewModel = ParentViewModel(repository, authManager)
        viewModel.loadDevices()

        val state = viewModel.deviceListState.value
        assertEquals(DeviceListUiState.Empty, state)
    }

    // T1.1 / T1.2 of `hotfix-parent-auth-cta-reload` — `authenticateAsParent()`
    // must chain `.onSuccess { loadDevices() }` so the dashboard reloads
    // devices after the user taps "Iniciar sesión como padre". The failure
    // path must NOT trigger loadDevices (per design Decision 3).
    @Test
    fun `authenticateAsParent_success_invokesLoadDevices`() = runTest {
        var getDevicesCalls = 0
        coEvery { repository.getDevices() } coAnswers {
            getDevicesCalls++
            Result.success(emptyList())
        }
        coEvery { authManager.authenticateOrCreate(Role.PARENT) } returns Result.success(Unit)

        val viewModel = ParentViewModel(repository, authManager)

        // init { loadDevices() } fired once. After auth success the VM must
        // re-invoke loadDevices — exact count becomes 2.
        assertEquals(
            "init block must have triggered exactly one loadDevices call",
            1, getDevicesCalls
        )

        viewModel.authenticateAsParent()

        assertEquals(
            "authenticateAsParent on success must trigger loadDevices (init + post-auth)",
            2, getDevicesCalls
        )
    }

    @Test
    fun `authenticateAsParent_failure_doesNotInvokeLoadDevices`() = runTest {
        var getDevicesCalls = 0
        coEvery { repository.getDevices() } coAnswers {
            getDevicesCalls++
            Result.failure(IllegalStateException("not authenticated"))
        }
        coEvery { authManager.authenticateOrCreate(Role.PARENT) } returns
            Result.failure(RuntimeException("auth failed"))

        val viewModel = ParentViewModel(repository, authManager)

        // init fired exactly one loadDevices → state = Error(AuthMissing).
        assertEquals(1, getDevicesCalls)
        assertTrue(
            "after init, state must be Error(AuthMissing)",
            viewModel.deviceListState.value is DeviceListUiState.Error &&
                (viewModel.deviceListState.value as DeviceListUiState.Error).reason ==
                DeviceListError.AuthMissing
        )

        viewModel.authenticateAsParent()

        // Failure path must NOT trigger a second loadDevices call.
        assertEquals(
            "authenticateAsParent on failure must NOT trigger loadDevices",
            1, getDevicesCalls
        )
        // And the error banner must remain visible.
        assertTrue(
            "after auth failure, state must remain Error(AuthMissing)",
            viewModel.deviceListState.value is DeviceListUiState.Error &&
                (viewModel.deviceListState.value as DeviceListUiState.Error).reason ==
                DeviceListError.AuthMissing
        )
    }

    // -------------------------------------------------------------------------
    // RED coverage for `fix-parent-solicitudes-auto-poll` (Tasks 1.1).
    // -------------------------------------------------------------------------

    /**
     * T1.1 — In-flight `loadPendingRequests()` must dedupe. The design's
     * decision D5 gates repeat calls on the existing `_isLoading` flag:
     * when a fetch is already in flight, a second invocation returns
     * immediately without issuing another `getPendingRequests()` call.
     * This test pre-REDs the scenario: the only `getPendingRequests()` call
     * is the one fired by `init`, suspended on a `CompletableDeferred`; two
     * subsequent `loadPendingRequests()` invocations must NOT issue more
     * fetches.
     */
    @Test
    fun `loadPendingRequests_second_call_while_running_is_noop`() = runTest {
        var getPendingCalls = 0
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.getPendingRequests() } coAnswers {
            getPendingCalls++
            gate.await()
            Result.success(emptyList())
        }
        // V2 thread-through (`fix-v2-server-side-solicitudes-filter`):
        // VM's `loadPendingRequests()` now calls the `selectedChildId`
        // overload; mirror the same stub shape so the counter stays in
        // sync regardless of the selected chip.
        coEvery { repository.getPendingRequests(selectedChildId = null) } coAnswers {
            getPendingCalls++
            gate.await()
            Result.success(emptyList())
        }
        // Devices returns immediately so init's `loadDevices()` does not
        // hold `_isLoading=true` past init — that way the only thing
        // holding the flag is `loadPendingRequests()`'s suspended body.
        coEvery { repository.getDevices() } returns Result.success(emptyList())

        val viewModel = ParentViewModel(repository, authManager)
        // init's loadPendingRequests is launched and suspended on `gate`.
        assertEquals(
            "init must have launched exactly one loadPendingRequests",
            1, getPendingCalls
        )
        assertTrue(
            "isLoading must be true while the in-flight fetch is suspended",
            viewModel.isLoading.value
        )

        // Second and third explicit calls must dedupe while isLoading=true.
        viewModel.loadPendingRequests()
        viewModel.loadPendingRequests()

        assertEquals(
            "loadPendingRequests must dedupe while a previous call is in flight",
            1, getPendingCalls
        )

        // Release the gate and confirm the original fetch completes once.
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(
            "still exactly one fetch after the in-flight call completes",
            1, getPendingCalls
        )
        assertFalse(
            "isLoading must reset to false after the in-flight call completes",
            viewModel.isLoading.value
        )
    }

    /**
     * T1.1 — The VM must collect `ParentRepository.pendingRequestsFlow`
     * (a `StateFlow<List<TimeRequest>>` added by design D2) in its `init`
     * block so that any writer — the `SolicitudesPollingWorker` or
     * `loadPendingRequests()` itself via `publishPendingRequests()` —
     * surfaces new rows in `_pendingRequests` without a manual reload.
     */
    @Test
    fun `init_collects_pendingRequestsFlow_into_state`() = runTest {
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
        val flow = MutableStateFlow(fixture)
        every { repository.pendingRequestsFlow } returns flow
        // init's loadPendingRequests returns the same fixture so the direct
        // write into `_pendingRequests` and the publish into the flow are
        // both consistent.
        coEvery { repository.getPendingRequests() } returns Result.success(fixture)
        // V2 mirror — VM's `loadPendingRequests()` uses the
        // `selectedChildId` overload (V2 thread-through).
        coEvery { repository.getPendingRequests(selectedChildId = null) } returns
            Result.success(fixture)
        coEvery { repository.getDevices() } returns Result.success(emptyList())

        val viewModel = ParentViewModel(repository, authManager)

        // The collector must mirror the StateFlow value into `_pendingRequests`.
        assertEquals(
            "init's collector must mirror pendingRequestsFlow into pendingRequests",
            fixture, viewModel.pendingRequests.value
        )

        // Turbine: emit a fresh list, confirm the collector picks it up.
        // (This path simulates the worker pushing data via publishPendingRequests.)
        val second = listOf(
            TimeRequest(
                id = "tr-2",
                deviceId = "dev-2",
                deviceName = "Moto G32",
                minutesRequested = 15,
                status = RequestStatus.PENDING,
                createdAt = "2026-06-30T00:01:00Z"
            )
        )
        viewModel.pendingRequests.test {
            // Skip the current replay (StateFlow replays its value on subscribe).
            assertEquals(fixture, awaitItem())
            flow.value = second
            assertEquals(second, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // -------------------------------------------------------------------------
    // RED coverage for Change B of `feat-multi-child-picker` (B.1.1).
    //
    // After Change A lands the `Child` entity, Change B adds:
    //  - `selectedChildId: StateFlow<String?>` (null = "Todos")
    //  - `setSelectedChild(id: String?)`
    //  - `filteredDevices: StateFlow<List<ChildDevice>>` derived via combine
    //  - stale-selection reset in `loadDevices()` (parent-device-list spec
    //    scenario "Stale selection is reset after a fetch")
    //
    // These tests exercise the contract. RED today: `selectedChildId` and
    // `filteredDevices` don't exist on `ParentViewModel.kt` →
    // `Unresolved reference` build error.
    // -------------------------------------------------------------------------

    /**
     * Helper that builds a 3-device fixture (Lucas x2 + Sofía x1) mirroring
     * `app/src/main/assets/mock-supabase/devices.json`. B.1.1 tests need
     * devices with populated `child` fields to exercise the filter.
     */
    private fun lucasAndSofiaFixtures(): List<ChildDevice> = listOf(
        ChildDevice(
            id = "dev-001",
            name = "Galaxy Tab S6 Lite",
            model = "SM-P610",
            appVersion = "1.0.0",
            policyVersion = 3,
            state = DeviceState.ACTIVE,
            lastSeenAt = "2026-06-19T20:55:00Z",
            isOnline = true,
            child = Child(
                id = "child-lucas",
                parentId = "parent-demo",
                firstName = "Lucas",
                createdAt = "2026-06-01T00:00:00Z",
                updatedAt = "2026-06-01T00:00:00Z"
            )
        ),
        ChildDevice(
            id = "dev-002",
            name = "Moto G32",
            model = "moto g32",
            appVersion = "1.0.0",
            policyVersion = 1,
            state = DeviceState.DOWNTIME,
            lastSeenAt = "2026-06-19T20:58:00Z",
            isOnline = true,
            child = Child(
                id = "child-lucas",
                parentId = "parent-demo",
                firstName = "Lucas",
                createdAt = "2026-06-01T00:00:00Z",
                updatedAt = "2026-06-01T00:00:00Z"
            )
        ),
        ChildDevice(
            id = "dev-003",
            name = "Pixel 7a",
            model = "GWKK3",
            appVersion = "1.0.0",
            policyVersion = 7,
            state = DeviceState.LOCKED,
            lastSeenAt = "2026-06-19T20:59:30Z",
            isOnline = true,
            child = Child(
                id = "child-sofia",
                parentId = "parent-demo",
                firstName = "Sofía",
                createdAt = "2026-06-01T00:00:00Z",
                updatedAt = "2026-06-01T00:00:00Z"
            )
        )
    )

    @Test
    fun `cold_start_defaults_selectedChildId_to_null`() = runTest {
        coEvery { repository.getDevices() } returns Result.success(emptyList())

        val viewModel = ParentViewModel(repository, authManager)

        // R2-V1: no DataStore persistence. Cold start MUST be null.
        assertEquals(
            "selectedChildId MUST default to null (= Todos) on cold start",
            null, viewModel.selectedChildId.value
        )
    }

    @Test
    fun `setSelectedChild_updates_StateFlow`() = runTest {
        coEvery { repository.getDevices() } returns Result.success(lucasAndSofiaFixtures())

        val viewModel = ParentViewModel(repository, authManager)

        viewModel.setSelectedChild("child-lucas")
        assertEquals(
            "setSelectedChild(child-lucas) MUST emit child-lucas",
            "child-lucas", viewModel.selectedChildId.value
        )

        viewModel.setSelectedChild(null)
        assertEquals(
            "setSelectedChild(null) MUST emit null (= Todos)",
            null, viewModel.selectedChildId.value
        )
    }

    @Test
    fun `filteredDevices_filters_by_selectedChildId`() = runTest {
        coEvery { repository.getDevices() } returns Result.success(lucasAndSofiaFixtures())

        val viewModel = ParentViewModel(repository, authManager)

        // `filteredDevices` is `combine(...).stateIn(WhileSubscribed)` so
        // its StateFlow value stays at the initialValue (`emptyList()`)
        // until a subscriber exists. Turbine subscribes once `.test {}`
        // enters and replays the upstream emissions.
        viewModel.filteredDevices.test {
            // Cold start: selectedChildId=null → every device.
            assertEquals(
                "Todos (selectedChildId=null) MUST return all 3 devices",
                3, awaitItem().size
            )

            // Tap Lucas chip → only Lucas's 2 devices remain.
            viewModel.setSelectedChild("child-lucas")
            val lucasOnly = awaitItem()
            assertEquals(
                "Lucas chip MUST narrow filteredDevices to 2 devices",
                2, lucasOnly.size
            )
            assertTrue(
                "filteredDevices MUST only contain Lucas's devices",
                lucasOnly.all { it.child?.id == "child-lucas" }
            )

            // Tap Sofía chip → only Sofía's 1 device remains.
            viewModel.setSelectedChild("child-sofia")
            val sofiaOnly = awaitItem()
            assertEquals(
                "Sofía chip MUST narrow filteredDevices to 1 device",
                1, sofiaOnly.size
            )
            assertEquals(
                "filteredDevices MUST contain Sofía's device id",
                "dev-003", sofiaOnly.first().id
            )

            // Tap Todos chip → restored to all 3.
            viewModel.setSelectedChild(null)
            assertEquals(
                "Todos chip MUST restore filteredDevices to all 3",
                3, awaitItem().size
            )

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `loadDevices_resets_stale_selection_to_null`() = runTest {
        // First fetch: Lucas + Sofía populated.
        coEvery { repository.getDevices() } returnsMany listOf(
            Result.success(lucasAndSofiaFixtures()),
            // Second fetch (after pair): only Sofía — Lucas is gone.
            Result.success(
                listOf(
                    lucasAndSofiaFixtures()[2]
                )
            )
        )

        val viewModel = ParentViewModel(repository, authManager)

        // User had selected Lucas on the first fetch.
        viewModel.setSelectedChild("child-lucas")
        assertEquals(
            "Sanity: selection is Lucas before the second fetch",
            "child-lucas", viewModel.selectedChildId.value
        )

        // Trigger a second fetch — Lucas disappears.
        viewModel.loadDevices()
        advanceUntilIdle()

        // Selection MUST auto-reset to null because Lucas is gone from
        // the device list (parent-device-list spec scenario "Stale
        // selection is reset after a fetch").
        assertEquals(
            "Stale selection MUST reset to null when selected child is gone",
            null, viewModel.selectedChildId.value
        )
    }
}
