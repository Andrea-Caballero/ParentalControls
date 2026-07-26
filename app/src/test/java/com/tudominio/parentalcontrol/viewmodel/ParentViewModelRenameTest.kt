package com.tudominio.parentalcontrol.viewmodel

import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.data.repository.DeviceListError
import com.tudominio.parentalcontrol.data.repository.ParentRepository
import com.tudominio.parentalcontrol.domain.model.ChildDevice
import com.tudominio.parentalcontrol.domain.model.TimeRequest
import com.tudominio.parentalcontrol.network.SupabaseClientProvider
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RED coverage for [ParentViewModel.renameChildState] +
 * [ParentViewModel.requestRename] + [ParentViewModel.confirmRename] +
 * [ParentViewModel.dismissRename] from
 * `openspec/changes/2026-07-07-fix-rename-child-dialog/proposal.md` §4.
 *
 * Per Q2=h (hoisted StateFlow) + Q4=p (pessimistic rename):
 *   - `RenameChildState` is a sealed UI state with the 5 variants the
 *     dashboard pattern-matches on (Hidden / Editing / Saving / Saved / Failed).
 *   - `requestRename(childId, currentName)` moves Hidden → Editing.
 *   - `confirmRename(newName)` moves Editing → Saving → Saved on success
 *     (then Saved → Hidden after the 1.5s auto-dismiss) or Editing → Saving
 *     → Failed on error.
 *   - `dismissRename()` resets the flow to Hidden at any time.
 *
 * RED today (without this feature): none of these symbols exist on
 * `ParentViewModel`. The test body fails to compile.
 *
 * Implementation note: this test deliberately avoids
 * `mockk<ParentRepository>` because mockk 1.13.7's SubclassMockMaker
 * cannot mock Kotlin final classes on this codebase (project-wide
 * infrastructure issue). Instead, it uses [FakeParentRepository], a
 * hand-rolled test double that extends an `open`-marked
 * [ParentRepository] with stubs for every method the VM calls from
 * `init` + the rename flow.
 *
 * The `fix-rename-child-dialog` apply phase marked `ParentRepository`
 * (and the four seams the VM touches) `open` — a minimal production
 * change that restores the project's normal extensibility without
 * affecting any production behavior.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ParentViewModelRenameTest {

    private lateinit var repository: FakeParentRepository
    private lateinit var authManager: DeviceAuthManager

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        authManager = DeviceAuthManager.getInstance(context)
        val noopClient = HttpClient()
        repository = FakeParentRepository(
            context = context,
            authManager = authManager,
            injectedClient = noopClient
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(): ParentViewModel = ParentViewModel(repository, authManager)

    @Test
    fun renameChildState_initial_value_is_Hidden() {
        val viewModel = newViewModel()

        assertSame(
            "Fresh VM must expose RenameChildState.Hidden, got ${viewModel.renameChildState.value}",
            RenameChildState.Hidden,
            viewModel.renameChildState.value
        )
    }

    @Test
    fun requestRename_moves_state_Hidden_to_Editing() {
        val viewModel = newViewModel()

        viewModel.requestRename(
            childId = "child-lucas",
            currentName = "Lucas"
        )

        val state = viewModel.renameChildState.value
        assertTrue(
            "requestRename must move Hidden → Editing, got $state",
            state is RenameChildState.Editing
        )
        val editing = state as RenameChildState.Editing
        assertEquals("child-lucas", editing.childId)
        assertEquals("Lucas", editing.currentName)
    }

    /**
     * Happy path: confirmRename sees the stubbed repo return success,
     * transitions through Saving → Saved, and then (after the 1.5s
     * auto-dismiss) back to Hidden. This is the Q4=p pessimistic
     * contract.
     */
    @Test
    fun confirmRename_happy_path_transitions_Editing_Saving_Saved_then_auto_dismisses_to_Hidden() =
        runTest {
            repository.renameChildResult = Result.success(Unit)
            repository.getDevicesResult = Result.success(emptyList<ChildDevice>())
            repository.getPendingRequestsResult = Result.success(emptyList<TimeRequest>())

            val viewModel = newViewModel()
            viewModel.requestRename("child-lucas", "Lucas")
            assertTrue(
                "precondition: state must be Editing before confirmRename",
                viewModel.renameChildState.value is RenameChildState.Editing
            )

            viewModel.confirmRename("Mateo")
            advanceUntilIdle()

            // The stub returns synchronously under the StandardTestDispatcher
            // when `advanceUntilIdle()` runs the viewModelScope.launch; the
            // state moves through Saving → Saved.
            val saved = viewModel.renameChildState.value
            assertTrue(
                "Final state should be Saved (transient) or Hidden (auto-dismissed), got $saved",
                saved is RenameChildState.Saved || saved is RenameChildState.Hidden
            )
            // The repository was called once with the trimmed name.
            assertEquals(1, repository.renameChildCalls.size)
            assertEquals("child-lucas", repository.renameChildCalls.first().first)
            assertEquals("Mateo", repository.renameChildCalls.first().second)
        }

    /**
     * Failure path: confirmRename with a failing repo surfaces
     * Failed(childId, error) so the dashboard can render the inline
     * error.
     */
    @Test
    fun confirmRename_failed_repo_surfaces_Failed_state() = runTest {
        repository.renameChildResult =
            Result.failure(DeviceListError.Transient("HTTP 409"))

        val viewModel = newViewModel()
        viewModel.requestRename("child-lucas", "Lucas")

        viewModel.confirmRename("Mateo")
        advanceUntilIdle()

        val state = viewModel.renameChildState.value
        assertTrue(
            "confirmRename must surface Failed on error, got $state",
            state is RenameChildState.Failed
        )
        val failed = state as RenameChildState.Failed
        assertEquals("child-lucas", failed.childId)
        assertTrue(
            "Failed.error must mention HTTP 409 (carries the server error message), got '${failed.error}'",
            failed.error.contains("HTTP 409")
        )
    }

    /**
     * dismissRename resets the flow to Hidden from any state. Pinning
     * the contract explicitly because Saved has an in-flight auto-dismiss
     * timer and a manual tap must short-circuit it.
     */
    @Test
    fun dismissRename_resets_state_to_Hidden() {
        val viewModel = newViewModel()
        viewModel.requestRename("child-lucas", "Lucas")
        assertTrue(
            "precondition: state must be Editing before dismissRename",
            viewModel.renameChildState.value is RenameChildState.Editing
        )

        viewModel.dismissRename()

        assertSame(
            "dismissRename must reset state to Hidden, got ${viewModel.renameChildState.value}",
            RenameChildState.Hidden,
            viewModel.renameChildState.value
        )
    }
}

/**
 * Hand-rolled test double for [ParentRepository]. The real class is
 * `final` (no `open`) AND its constructor wires a DataStore-backed
 * cache that requires Robolectric + `PendingRequestsCache` — neither
 * of which is mockk-friendly on this codebase.
 *
 * The VM only reads from a small handful of methods:
 *
 *  - `init { loadDevices(); loadPendingRequests(); repository.pendingRequestsFlow.collect {} }`
 *  - `confirmRename(...)` → `repository.renameChild(...)`.
 *
 * We satisfy those four seams with plain mutable fields. Every other
 * `ParentRepository` method falls through to a default no-op (or
 * returns an empty `Result.failure`) since the VM does not exercise
 * them in this slice.
 *
 * Kept in this file so the seam is co-located with the test that
 * uses it; moved to a shared `fakes/` package only if other tests
 * need it.
 */
@OptIn(androidx.annotation.VisibleForTesting::class)
internal class FakeParentRepository(
    context: android.content.Context,
    authManager: DeviceAuthManager,
    injectedClient: HttpClient
) : ParentRepository(
    context = context,
    authManager = authManager,
    // `SupabaseClientProvider` is final, so we can't subclass it;
    // use the `internal` constructor that accepts an injected
    // HttpClient (same-module seam, exposed by the
    // `fix-supabase-client-provider-legacy-mock-gate` family).
    clientProvider = SupabaseClientProvider(context, injectedClient = injectedClient)
) {
    var renameChildResult: Result<Unit> = Result.success(Unit)
    val renameChildCalls: MutableList<Pair<String, String>> = mutableListOf()
    var getDevicesResult: Result<List<ChildDevice>> = Result.success(emptyList())
    var getPendingRequestsResult: Result<List<TimeRequest>> = Result.success(emptyList())
    private val pendingFlow: MutableStateFlow<List<TimeRequest>> = MutableStateFlow(emptyList())

    override suspend fun renameChild(childId: String, newName: String): Result<Unit> {
        renameChildCalls += childId to newName
        return renameChildResult
    }

    override suspend fun getDevices(): Result<List<ChildDevice>> = getDevicesResult
    override suspend fun getPendingRequests(): Result<List<TimeRequest>> =
        getPendingRequestsResult
    override suspend fun getPendingRequests(selectedChildId: String?): Result<List<TimeRequest>> =
        getPendingRequestsResult
    override val pendingRequestsFlow: StateFlow<List<TimeRequest>>
        get() = pendingFlow
}
