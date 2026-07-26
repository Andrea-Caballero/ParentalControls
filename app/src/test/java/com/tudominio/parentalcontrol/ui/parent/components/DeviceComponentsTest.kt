package com.tudominio.parentalcontrol.ui.parent.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.data.repository.ParentRepository
import com.tudominio.parentalcontrol.ui.theme.ParentalControlTheme
import com.tudominio.parentalcontrol.viewmodel.PairingCodeResult
import com.tudominio.parentalcontrol.viewmodel.ParentViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [PairingBottomSheet] QR rendering (PR 1 task #7 of
 * `openspec/changes/wire-pairing-and-approval-end-to-end`).
 *
 * Verifies that the QR is rendered (with the `pairing_qr` test tag) when
 * the parent advances to step 2 and `pairingCode` is set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceComponentsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var viewModel: ParentViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val mockRepository = mockk<ParentRepository>(relaxed = true)
        coEvery {
            mockRepository.createPairingCode(any(), any(), any())
        } returns Result.success(
            PairingCodeResult(
                code = "ABCDEFGH",
                expiresAt = "2026-06-04T12:10:00Z",
                deeplink = "parentalcontrol://pair?code=ABCDEFGH"
            )
        )
        // `fix-parent-solicitudes-auto-poll` — VM's `init` block now
        // collects `repository.pendingRequestsFlow`. Stub a default empty
        // flow so the collector doesn't NPE on the relaxed mock.
        every { mockRepository.pendingRequestsFlow } returns
            kotlinx.coroutines.flow.MutableStateFlow(emptyList())
        val mockAuthManager = mockk<DeviceAuthManager>(relaxed = true)
        viewModel = ParentViewModel(mockRepository, mockAuthManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun qr_renders_for_pairing_step_2() {
        // Pre-populate the viewmodel's pairingCode state via reflection on the
        // private MutableStateFlow so the test doesn't depend on the
        // viewModelScope coroutine timing.
        val codeField = ParentViewModel::class.java.getDeclaredField("_pairingCode")
        codeField.isAccessible = true
        val codeState = codeField.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<PairingCodeResult?>
        codeState.value = PairingCodeResult(
            code = "ABCDEFGH",
            expiresAt = "2026-06-04T12:10:00Z",
            deeplink = "parentalcontrol://pair?code=ABCDEFGH"
        )

        composeTestRule.setContent {
            ParentalControlTheme {
                PairingBottomSheet(
                    viewModel = viewModel,
                    onDismiss = {},
                    initialStep = 2
                )
            }
        }
        composeTestRule.waitForIdle()

        // The QR must be rendered with the test tag
        composeTestRule.onNodeWithTag("pairing_qr").assertExists()
    }

    @Test
    fun eight_char_code_still_shown_below_qr() {
        val codeField = ParentViewModel::class.java.getDeclaredField("_pairingCode")
        codeField.isAccessible = true
        val codeState = codeField.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<PairingCodeResult?>
        codeState.value = PairingCodeResult(
            code = "XYZ12345",
            expiresAt = "2026-06-04T12:10:00Z",
            deeplink = "parentalcontrol://pair?code=XYZ12345"
        )

        composeTestRule.setContent {
            ParentalControlTheme {
                PairingBottomSheet(
                    viewModel = viewModel,
                    onDismiss = {},
                    initialStep = 2
                )
            }
        }
        composeTestRule.waitForIdle()

        // The 8-char code text is still shown for manual entry
        composeTestRule.onNodeWithText("XYZ12345").assertExists()
        // And the "Válido por 10 minutos" caption
        composeTestRule.onNodeWithText("Válido por 10 minutos").assertExists()
    }

    /**
     * RED coverage for the UX race in [PairingBottomSheet] step 1.
     *
     * The bug: the "Generar código" button sets `step = 2` synchronously
     * on click, advancing the sheet BEFORE the pairing-code request
     * completes. On any failure path (5xx, timeout) the sheet renders an
     * empty step-2 card with `pairingCode == null`, and the existing
     * error snackbar gets cleared by `LaunchedEffect(error) { ... }` —
     * the parent has no idea anything went wrong.
     *
     * The fix: replace the eager `step = 2` with a reactive transition —
     * `LaunchedEffect(pairingCode) { if (pairingCode != null) step = 2 }`.
     * The step only advances when an actual `PairingCodeResult` arrives
     * from the viewModel. On failure, `pairingCode` stays null and the
     * sheet stays on step 1 where the snackbar surfaces the error.
     *
     * This test exercises the reactive transition directly: it starts the
     * sheet at `initialStep = 1`, then pre-seeds `pairingCode` via
     * reflection on the viewModel's private StateFlow (the same pattern
     * already used by [qr_renders_for_pairing_step_2] and
     * [eight_char_code_still_shown_below_qr] to dodge the
     * viewModelScope coroutine timing). After `waitForIdle`, the
     * step-2 heading MUST be on screen — proving the LaunchedEffect
     * fired.
     *
     * Test interaction note: we do NOT click the "Generar código" button
     * here. The button is wrapped in a Material 3 ModalBottomSheet whose
     * scrim layer swallows test clicks (verified via a separate
     * diagnostic — `performClick` on the button's testTag inside the
     * sheet is a no-op even on the unfixed build; the same Button
     * outside the sheet dispatches fine). The reactive transition is
     * identical regardless of how `pairingCode` becomes non-null — we
     * simulate the "arrival" by seeding the StateFlow directly.
     *
     * RED: today's code does not run a LaunchedEffect on `pairingCode`,
     * so the step stays at 1 and the assertion fails.
     * GREEN (after the fix): the LaunchedEffect advances step to 2.
     */
    @Test
    fun step_advances_when_pairing_code_arrives_reactively() {
        // Pre-populate the viewmodel's pairingCode state via reflection on the
        // private MutableStateFlow — same pattern used by the QR tests
        // above so the test doesn't depend on viewModelScope coroutine
        // timing.
        val codeField = ParentViewModel::class.java.getDeclaredField("_pairingCode")
        codeField.isAccessible = true
        val codeState = codeField.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<PairingCodeResult?>
        codeState.value = PairingCodeResult(
            code = "ABCDEFGH",
            expiresAt = "2026-06-04T12:10:00Z",
            deeplink = "parentalcontrol://pair?code=ABCDEFGH"
        )

        composeTestRule.setContent {
            ParentalControlTheme {
                PairingBottomSheet(
                    viewModel = viewModel,
                    onDismiss = {},
                    initialStep = 1
                )
            }
        }
        composeTestRule.waitForIdle()

        // The step-2 heading MUST be on screen — proving the
        // LaunchedEffect fired in response to `pairingCode` becoming
        // non-null. Before the fix, `step` stays at 1 and this
        // assertion fails.
        composeTestRule.onNodeWithText("Código de emparejamiento").assertExists()
    }
}
