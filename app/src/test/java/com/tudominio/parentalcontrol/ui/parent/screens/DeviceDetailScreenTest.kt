package com.tudominio.parentalcontrol.ui.parent.screens

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.tudominio.parentalcontrol.data.repository.ParentRepository
import com.tudominio.parentalcontrol.ui.theme.ParentalControlTheme
import com.tudominio.parentalcontrol.viewmodel.ParentViewModel
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI tests for [DeviceDetailScreen] Policy tab.
 *
 * PR 5 of `openspec/changes/wire-pairing-and-approval-end-to-end` (task #32).
 *
 * Verifies the "Add to block list" affordance added to the Policy tab:
 *   - The button is visible.
 *   - Tapping it invokes `onNavigateToApps` with the device id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DeviceDetailScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var viewModel: ParentViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val mockRepo = mockk<ParentRepository>(relaxed = true)
        // `fix-parent-solicitudes-auto-poll` — VM's `init` block now
        // collects `repository.pendingRequestsFlow`. Stub a default empty
        // flow so the collector doesn't NPE on the relaxed mock.
        io.mockk.every { mockRepo.pendingRequestsFlow } returns
            kotlinx.coroutines.flow.MutableStateFlow(emptyList())
        // WU-4 — DeviceDetailScreen now resolves the selected device
        // from `viewModel.devices` rather than a hardcoded fake. Seed
        // a real test fixture via the mocked `getDevices` so the
        // existing PR-5 "Add to block list" navigation contract keeps
        // working without fake data.
        io.mockk.coEvery { mockRepo.getDevices() } returns Result.success(
            listOf(
                com.tudominio.parentalcontrol.domain.model.ChildDevice(
                    id = "dev-1",
                    name = "Moto G8 Plus",
                    model = "moto g(8) plus",
                    appVersion = "1.4.2",
                    policyVersion = 7,
                    state = com.tudominio.parentalcontrol.domain.model.DeviceState.ACTIVE,
                    lastSeenAt = "2026-06-19T22:55:00Z",
                    isOnline = true
                )
            )
        )
        io.mockk.coEvery { mockRepo.publishPendingRequests(any()) } returns Unit
        viewModel = ParentViewModel(
            mockRepo,
            mockk<com.tudominio.parentalcontrol.auth.DeviceAuthManager>(relaxed = true)
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun policy_tab_shows_add_to_block_list_button() {
        var capturedDeviceId: String? = null

        composeTestRule.setContent {
            ParentalControlTheme {
                DeviceDetailScreen(
                    deviceId = "dev-1",
                    viewModel = viewModel,
                    onNavigateBack = {},
                    onNavigateToApps = { id -> capturedDeviceId = id }
                )
            }
        }
        composeTestRule.waitForIdle()

        // Switch to the Política tab by clicking the third Tab node
        // (Uso=0, Salud=1, Política=2). We find the Tabs by Role.Tab.
        val tabs = composeTestRule
            .onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        tabs.assertCountEquals(3)
        tabs[2].performClick()
        composeTestRule.waitForIdle()

        // The Política tab now shows its content. The "Add to block list"
        // OutlinedButton is the fourth card; on a phone-sized screen it
        // sits below the visible viewport. We scroll to it before
        // clicking.
        composeTestRule
            .onNodeWithText("Add to block list")
            .assertExists()
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        assertEquals(
            "dev-1",
            capturedDeviceId
        )
    }
}
