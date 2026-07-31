package com.tudominio.parentalcontrol.ui.child.status

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import com.tudominio.parentalcontrol.copy.CopyManager
import com.tudominio.parentalcontrol.ui.theme.ParentalControlTheme
import com.tudominio.parentalcontrol.workers.PostPairingSchedulingOutcome
import com.tudominio.parentalcontrol.workers.WorkerInitializer
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Process-death fix — screen-level proof that ChildStatusScreen's
// startup reconciliation fires once on first composition. The
// coordinator restarts Idle after process death; without this hook
// the banner would stay hidden even when Device Admin is inactive.
// Boundary: Robolectric's DPM shadow returns false for isAdminActive,
// so we pin the negative branch. The true-branch needs instrumentation.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChildStatusStartupReconciliationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setUp() {
        mockkObject(WorkerInitializer)
        every { WorkerInitializer.recoverAfterPairing(any()) } returns
            PostPairingSchedulingOutcome.SCHEDULED
    }

    @After
    fun tearDown() {
        unmockkObject(WorkerInitializer)
    }

    private fun stubViewModel(): ChildStatusViewModel {
        val vm = mockk<ChildStatusViewModel>(relaxed = true)
        every { vm.uiState } returns MutableStateFlow(
            ChildStatusUiState.Content(
                timeRemaining = 60L, timeUsedToday = 0L, dailyLimit = 120L,
                nextBlockTime = null, warningLevel = WarningLevel.NONE,
                hasPendingRequest = false, allowedAppsNow = emptyList()
            )
        )
        every { vm.warningLevel } returns MutableStateFlow(WarningLevel.NONE)
        every { vm.pendingTimeRequest } returns MutableStateFlow(null)
        every { vm.rewardBalance } returns MutableStateFlow(0L)
        every { vm.degradationCauses } returns MutableStateFlow(emptyList())
        every { vm.showRecoveryDialog } returns MutableStateFlow(false)
        every { vm.timeRemaining } returns MutableStateFlow(0L)
        every { vm.deviceAdminBanner } returns MutableStateFlow(false)
        every { vm.events } returns MutableSharedFlow()
        return vm
    }

    @Test
    fun startup_reconciliation_invokes_sync_with_lockManager_inactive_state() {
        val vm = stubViewModel()
        val copyManager = mockk<CopyManager>(relaxed = true)
        val recomposition = mutableIntStateOf(0)
        composeTestRule.setContent {
            ParentalControlTheme {
                ChildStatusScreen(
                    viewModel = vm,
                    copyManager = copyManager,
                    onRequestExtraTime = {},
                    modifier = Modifier.testTag("child-status-${recomposition.intValue}")
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle { recomposition.intValue++ }
        composeTestRule.waitForIdle()

        verify(exactly = 1) { vm.syncDeviceAdminState(false) }
        verify(exactly = 1) { WorkerInitializer.recoverAfterPairing(any()) }
    }
}
