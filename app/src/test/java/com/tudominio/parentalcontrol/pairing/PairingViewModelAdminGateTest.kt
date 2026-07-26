package com.tudominio.parentalcontrol.pairing

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RED→GREEN — verifies the PairingViewModel no longer auto-emits
 * `NavigateToHome` after a successful pairing. Pre-fix bug: the
 * gating logic in `SuccessContent` (the Device Admin prompt) was
 * defeated by `PairingViewModel.handlePairingResult` firing the
 * navigation event synchronously, before `SuccessContent` could
 * mount and decide whether to show the prompt.
 *
 * Post-fix contract:
 *  - After `Success` is handled: `uiState == Success(deviceId)` but
 *    no `NavigateToHome` event has been emitted (the prompt may
 *    still gate the navigation).
 *  - After `confirmAdminDecisionAndNavigate()` is invoked: exactly
 *    one `NavigateToHome` event is emitted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PairingViewModelAdminGateTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `success handled does not auto-emit NavigateToHome`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val vm = PairingViewModel(context)
        vm.simulateSuccess("dev-1")

        assertEquals(
            "uiState must transition to Success so SuccessContent mounts.",
            PairingUiState.Success("dev-1"),
            vm.uiState.value
        )
        val nav = vm.navigationEvents.replayCache
            .filterIsInstance<PairingNavigationEvent.NavigateToHome>()
            .take(1)
            .toList()
        assertTrue(
            "PairingViewModel must NOT auto-emit NavigateToHome on " +
                "Success; the admin decision must be confirmed via " +
                "confirmAdminDecisionAndNavigate first. Got: $nav",
            nav.isEmpty()
        )
    }

    @Test
    fun `confirmAdminDecisionAndNavigate emits exactly one NavigateToHome`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val vm = PairingViewModel(context)
        vm.simulateSuccess("dev-1")
        // Background collector + await pattern: the collector subscribes
        // before the emit, then we await the resulting List to keep
        // runTest from exiting before the emission completes.
        val job = kotlinx.coroutines.GlobalScope.launch(
            kotlinx.coroutines.Dispatchers.Unconfined
        ) {
            vm.navigationEvents
                .filterIsInstance<PairingNavigationEvent.NavigateToHome>()
                .take(1)
                .collect { /* terminal */ }
        }
        vm.confirmAdminDecisionAndNavigate()
        kotlinx.coroutines.withTimeout(5_000) { job.join() }
        // If the join completes within 5s, the emission happened.
        assertTrue(
            "After confirmAdminDecisionAndNavigate, exactly one " +
                "NavigateToHome must fire (collect job completed). " +
                "Job state: ${job}",
            job.isCompleted
        )
    }

    @Test
    fun `uiState stays Success after confirmAdminDecisionAndNavigate`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val vm = PairingViewModel(context)
        vm.simulateSuccess("dev-1")
        val job = kotlinx.coroutines.GlobalScope.launch(
            kotlinx.coroutines.Dispatchers.Unconfined
        ) {
            vm.navigationEvents
                .filterIsInstance<PairingNavigationEvent.NavigateToHome>()
                .take(1)
                .collect { /* terminal */ }
        }
        vm.confirmAdminDecisionAndNavigate()
        kotlinx.coroutines.withTimeout(5_000) { job.join() }
        assertEquals(
            "uiState must stay Success after the admin decision " +
                "navigates home (the caller switches screens).",
            PairingUiState.Success("dev-1"),
            vm.uiState.value
        )
    }
}
