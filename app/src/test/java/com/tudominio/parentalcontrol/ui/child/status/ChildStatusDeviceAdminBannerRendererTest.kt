package com.tudominio.parentalcontrol.ui.child.status

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tudominio.parentalcontrol.copy.CopyManager
import com.tudominio.parentalcontrol.ui.theme.ParentalControlTheme
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RED→GREEN — focused Compose coverage for the
 * [DeviceAdminActivationBanner] seam added in `WU-D follow-up`.
 *
 * The screen-level `ChildStatusScreen` wires a
 * `rememberLauncherForActivityResult(StartActivityForResult())` around
 * the banner; mocking that path requires the full screen mount plus a
 * LockManager stub. To avoid dragging `Robolectric` device-policy
 * plumbing into the test, the banner is exposed as `internal` and
 * tested directly here. The screen-level callback contract is pinned
 * by the launcher's result-routing code and by the underlying
 * [ChildStatusViewModel.onDeviceAdminResult] unit tests in
 * [ChildStatusDeviceAdminBannerTest].
 *
 * Test tags are part of the public contract for the renderer (they
 * surface in NavGraphTest's relaxed-mockk stubs). Do not rename
 * without updating the upstream consumers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChildStatusDeviceAdminBannerRendererTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun banner_is_visible_with_correct_copy_and_activate_button_when_enabled() {
        var activations = 0
        composeTestRule.setContent {
            ParentalControlTheme {
                DeviceAdminActivationBanner(
                    onActivate = { activations += 1 }
                )
            }
        }
        composeTestRule.waitForIdle()

        // Test tag covers the root Card so NavGraphTest and any
        // future instrumentation can locate the banner reliably.
        composeTestRule.onNodeWithTag("child_status_admin_banner")
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("child_status_admin_activate_button")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Activa el control parental").assertIsDisplayed()
        composeTestRule.onNodeWithText("Activar").assertIsDisplayed()

        // Tapping the button must invoke the activate callback
        // exactly once — the screen wires this to the launcher.
        composeTestRule.onNodeWithTag("child_status_admin_activate_button")
            .performClick()
        composeTestRule.runOnIdle {
            assertEquals(
                "Tapping the activate button must invoke the callback once " +
                    "so the launcher can dispatch LockManager.getEnableAdminIntent().",
                1,
                activations
            )
        }
    }

    /**
     * StatusContent gates the banner via `bannerVisible=false`. Pin
     * that the gate works end-to-end: when the coordinator is in
     * Idle (bannerVisible=false), the banner test tag must NOT be
     * on the tree at all — preventing duplicate activation prompts
     * after admin is confirmed.
     */
    @Test
    fun banner_is_not_rendered_when_bannerVisible_is_false() {
        val copyManager: CopyManager = mockk(relaxed = true)
        composeTestRule.setContent {
            ParentalControlTheme {
                StatusContent(
                    state = ChildStatusUiState.Content(
                        timeRemaining = 60L,
                        timeUsedToday = 0L,
                        dailyLimit = 120L,
                        nextBlockTime = null,
                        warningLevel = WarningLevel.NONE,
                        hasPendingRequest = false,
                        allowedAppsNow = emptyList()
                    ),
                    warningLevel = WarningLevel.NONE,
                    hasPendingRequest = false,
                    rewardBalance = 0L,
                    copyManager = copyManager,
                    onRequestExtraTime = {},
                    bannerVisible = false,
                    onActivateAdmin = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("child_status_admin_banner").assertDoesNotExist()
    }
}