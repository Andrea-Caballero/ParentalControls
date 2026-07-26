package com.tudominio.parentalcontrol.ui.navigation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.tudominio.parentalcontrol.auth.Role
import com.tudominio.parentalcontrol.copy.CopyManager
import com.tudominio.parentalcontrol.data.repository.TimeExtraRepository
import com.tudominio.parentalcontrol.domain.model.ChildDevice
import com.tudominio.parentalcontrol.domain.model.TimeRequest
import com.tudominio.parentalcontrol.pairing.PairingViewModel
import com.tudominio.parentalcontrol.ui.child.status.ChildStatusViewModel
import com.tudominio.parentalcontrol.ui.screen.apps.AppsViewModel
import com.tudominio.parentalcontrol.ui.theme.ParentalControlTheme
import com.tudominio.parentalcontrol.viewmodel.DeviceListUiState
import com.tudominio.parentalcontrol.viewmodel.ParentViewModel
import com.tudominio.parentalcontrol.viewmodel.RenameChildState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the routing logic of [NavGraph].
 *
 * PR 4 of `align-with-guia-fedora44` extracted the top-level `when` block
 * from `MainActivity` into a dedicated `@Composable fun NavGraph(...)`.
 * Per the `app-entry-routing` spec (extended by T28 of `overlay-to-extratime`),
 * the routing depends on:
 *
 *   - `isPaired == false`                            → [OnboardingScreen]  (unpaired device)
 *   - `isPaired && isChildDevice`                    → [ChildStatusScreen] (paired child)
 *   - `isPaired && !isChildDevice`                   → [DashboardScreen]   (paired parent)
 *   - `pendingExtraTimePackage != null` (T28)        → [ExtraTimeScreen]   (overlay deeplink)
 *
 * Two layers of tests pin the contract:
 *
 *   1. **Pure-function tests** for [resolveInitialRoute]. The decision is
 *      a 4-way branch, so the pure function gives the clearest possible
 *      assertion and is the primary regression net.
 *
 *   2. **Compose / Robolectric tests** for [NavGraph] itself. These pass
 *      mock ViewModels + managers to the composable so the test does not
 *      depend on the Hilt graph. `MainActivity` resolves the real ones
 *      via `hiltViewModel()` / Hilt singletons and forwards them to
 *      [NavGraph] in production.
 *
 * Test runner: `./gradlew :app:testDebugUnitTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NavGraphTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val parentViewModel: ParentViewModel = mockk(relaxed = true)
    private val appsViewModel: AppsViewModel = mockk(relaxed = true)
    private val pairingViewModel: PairingViewModel = mockk(relaxed = true)
    private val childStatusViewModel: ChildStatusViewModel = mockk(relaxed = true)
    private val copyManager: CopyManager = mockk(relaxed = true)
    private val timeExtraRepository: TimeExtraRepository = mockk(relaxed = true)

    init {
        // The screens read several `StateFlow<T>` properties off their
        // ViewModels (`devices`, `uiState`, etc.) via `collectAsState()`,
        // which performs an unchecked cast to the generic `T`. A
        // relaxed-mockk returns `Any` for those calls, so the cast blows
        // up before the screen renders. Stub each flow with a real
        // `MutableStateFlow` carrying an empty / default value so the
        // cast resolves cleanly. This keeps the test focused on the
        // routing decision, not on the screen content itself.
        every { parentViewModel.devices } returns MutableStateFlow<List<ChildDevice>>(emptyList())
        every { parentViewModel.deviceListState } returns MutableStateFlow(DeviceListUiState.Loading)
        every { parentViewModel.pendingRequests } returns MutableStateFlow<List<TimeRequest>>(emptyList())
        every { parentViewModel.isLoading } returns MutableStateFlow(false)
        every { parentViewModel.error } returns MutableStateFlow<String?>(null)
        every { parentViewModel.filteredDevices } returns MutableStateFlow<List<ChildDevice>>(emptyList())
        every { parentViewModel.selectedChildId } returns MutableStateFlow<String?>(null)
        // DashboardScreen reads renameChildState via collectAsState() and
        // performs an unchecked cast to MutableStateFlow<RenameChildState>.
        // Without this stub the relaxed-mockk returns Any (null), and the
        // cast blows up with ClassCastException when the parent device
        // path composes the Dashboard (see navGraph_pairedParentDevice_composesDashboardScreen).
        every { parentViewModel.renameChildState } returns MutableStateFlow(RenameChildState.Hidden)

        every { childStatusViewModel.uiState } returns MutableStateFlow(
            com.tudominio.parentalcontrol.ui.child.status.ChildStatusUiState.Content(
                timeRemaining = 60L,
                timeUsedToday = 0L,
                dailyLimit = 120L,
                nextBlockTime = null,
                warningLevel = com.tudominio.parentalcontrol.ui.child.status.WarningLevel.NONE,
                hasPendingRequest = false,
                allowedAppsNow = emptyList()
            )
        )
        every { childStatusViewModel.warningLevel } returns MutableStateFlow(
            com.tudominio.parentalcontrol.ui.child.status.WarningLevel.NONE
        )
        every { childStatusViewModel.pendingTimeRequest } returns MutableStateFlow(null)
        every { childStatusViewModel.rewardBalance } returns MutableStateFlow(0L)
        every { childStatusViewModel.degradationCauses } returns MutableStateFlow(emptyList())
        every { childStatusViewModel.showRecoveryDialog } returns MutableStateFlow(false)
        every { childStatusViewModel.timeRemaining } returns MutableStateFlow(0L)
        // `events` is a SharedFlow that ChildStatusScreen collects inside a
        // LaunchedEffect; stub it with a real (empty) SharedFlow so the
        // collect{} does not blow up on a mockk Object.
        every { childStatusViewModel.events } returns kotlinx.coroutines.flow.MutableSharedFlow()
    }

    // -----------------------------------------------------------------
    // 1. Pure-function tests: resolveInitialRoute covers the 4 branches
    // -----------------------------------------------------------------

    @Test
    fun resolveInitialRoute_unpairedDevice_returnsOnboarding() {
        assertEquals(
            NavRoute.Onboarding,
            resolveInitialRoute(isPaired = false, isChildDevice = false)
        )
        assertEquals(
            NavRoute.Onboarding,
            resolveInitialRoute(isPaired = false, isChildDevice = true)
        )
    }

    @Test
    fun resolveInitialRoute_pairedChildDevice_returnsChildStatus() {
        assertEquals(
            NavRoute.ChildStatus,
            resolveInitialRoute(isPaired = true, isChildDevice = true)
        )
    }

    @Test
    fun resolveInitialRoute_pairedParentDevice_returnsDashboard() {
        assertEquals(
            NavRoute.Dashboard,
            resolveInitialRoute(isPaired = true, isChildDevice = false)
        )
    }

    // -----------------------------------------------------------------
    // 1a. Role-aware discriminator: resolveIsChildDevice (regression for
    //     the OPPO parent → CHILD UI misroute after relaunch).
    // -----------------------------------------------------------------

    @Test
    fun resolveIsChildDevice_unpaired_always_returnsFalse() {
        assertEquals(false, resolveIsChildDevice(isPaired = false, role = null))
        assertEquals(false, resolveIsChildDevice(isPaired = false, role = Role.CHILD))
        assertEquals(false, resolveIsChildDevice(isPaired = false, role = Role.PARENT))
    }

    @Test
    fun resolveIsChildDevice_pairedNullRole_returnsFalse() {
        // Defensive: a paired device without a role annotation is NOT
        // a child device by default. Stale pre-fix child installs are
        // migrated to Role.CHILD by loadPersistedState before this is
        // read, so this branch only fires for genuinely role-less
        // paired devices (a narrow edge case).
        assertEquals(false, resolveIsChildDevice(isPaired = true, role = null))
    }

    @Test
    fun resolveIsChildDevice_pairedParentRole_returnsFalse() {
        // THE BUG: previously this branch routed the OPPO parent to
        // the CHILD UI after devLogin because parent_id was present.
        assertEquals(false, resolveIsChildDevice(isPaired = true, role = Role.PARENT))
    }

    @Test
    fun resolveIsChildDevice_pairedChildRole_returnsTrue() {
        // Genuinely paired child devices must still route to ChildStatus.
        assertEquals(true, resolveIsChildDevice(isPaired = true, role = Role.CHILD))
    }

    // T28: pendingExtraTimePackage overrides the natural initial route
    // when the device is paired, regardless of role (the overlay only
    // appears on child devices, but the routing contract treats both
    // branches uniformly — a parent device would land on ExtraTime if
    // it ever received the deeplink, which is harmless).

    @Test
    fun resolveInitialRoute_pendingExtraTimeChildDevice_returnsExtraTime() {
        assertEquals(
            NavRoute.ExtraTime,
            resolveInitialRoute(
                isPaired = true,
                isChildDevice = true,
                pendingExtraTimePackage = "com.instagram.android"
            )
        )
    }

    @Test
    fun resolveInitialRoute_pendingExtraTimeParentDevice_returnsExtraTime() {
        assertEquals(
            NavRoute.ExtraTime,
            resolveInitialRoute(
                isPaired = true,
                isChildDevice = false,
                pendingExtraTimePackage = "com.instagram.android"
            )
        )
    }

    @Test
    fun resolveInitialRoute_pendingExtraTimeUnpairedDevice_returnsOnboarding() {
        // Edge case: if the device is NOT paired, the pending request
        // is dropped (no parent link exists to grant against). The
        // user lands on Onboarding as usual — the deeplink is a no-op.
        assertEquals(
            NavRoute.Onboarding,
            resolveInitialRoute(
                isPaired = false,
                isChildDevice = false,
                pendingExtraTimePackage = "com.instagram.android"
            )
        )
    }

    // -----------------------------------------------------------------
    // 2. Compose tests: NavGraph composes the correct screen
    // -----------------------------------------------------------------

    @Test
    fun navGraph_unpairedDevice_composesOnboardingScreen() {
        composeTestRule.setContent {
            ParentalControlTheme {
                NavGraph(
                    isPaired = false,
                    isChildDevice = false,
                    prefilledPairingCode = null,
                    pendingExtraTimePackage = null,
                    parentViewModel = parentViewModel,
                    appsViewModel = appsViewModel,
                    pairingViewModel = pairingViewModel,
                    childStatusViewModel = childStatusViewModel,
                    copyManager = copyManager,
                    timeExtraRepository = timeExtraRepository,
                    deviceId = "",
                    onPairingComplete = { },
                    onExtraTimeConsumed = { }
                )
            }
        }

        // OnboardingScreen renders the role-selection buttons.
        composeTestRule.onNodeWithText("Soy el padre").assertExists()
        composeTestRule.onNodeWithText("Soy el hijo").assertExists()
    }

    @Test
    fun navGraph_pairedChildDevice_composesChildStatusScreen() {
        composeTestRule.setContent {
            ParentalControlTheme {
                NavGraph(
                    isPaired = true,
                    isChildDevice = true,
                    prefilledPairingCode = null,
                    pendingExtraTimePackage = null,
                    parentViewModel = parentViewModel,
                    appsViewModel = appsViewModel,
                    pairingViewModel = pairingViewModel,
                    childStatusViewModel = childStatusViewModel,
                    copyManager = copyManager,
                    timeExtraRepository = timeExtraRepository,
                    deviceId = "test-device",
                    onPairingComplete = { },
                    onExtraTimeConsumed = { }
                )
            }
        }

        // ChildStatusScreen shows "minutos restantes" as a label.
        composeTestRule.onNodeWithText("minutos restantes").assertExists()
    }

    @Test
    fun navGraph_pairedParentDevice_composesDashboardScreen() {
        composeTestRule.setContent {
            ParentalControlTheme {
                NavGraph(
                    isPaired = true,
                    isChildDevice = false,
                    prefilledPairingCode = null,
                    pendingExtraTimePackage = null,
                    parentViewModel = parentViewModel,
                    appsViewModel = appsViewModel,
                    pairingViewModel = pairingViewModel,
                    childStatusViewModel = childStatusViewModel,
                    copyManager = copyManager,
                    timeExtraRepository = timeExtraRepository,
                    deviceId = "",
                    onPairingComplete = { },
                    onExtraTimeConsumed = { }
                )
            }
        }

        // DashboardScreen's top bar reads "Control Parental".
        composeTestRule.onNodeWithText("Control Parental").assertExists()
    }

    @Test
    fun navGraph_pendingExtraTime_composesExtraTimeScreen() {
        composeTestRule.setContent {
            ParentalControlTheme {
                NavGraph(
                    isPaired = true,
                    isChildDevice = true,
                    prefilledPairingCode = null,
                    pendingExtraTimePackage = "com.instagram.android",
                    parentViewModel = parentViewModel,
                    appsViewModel = appsViewModel,
                    pairingViewModel = pairingViewModel,
                    childStatusViewModel = childStatusViewModel,
                    copyManager = copyManager,
                    timeExtraRepository = timeExtraRepository,
                    deviceId = "test-device",
                    onPairingComplete = { },
                    onExtraTimeConsumed = { }
                )
            }
        }

        // T28: when arriving via the overlay deeplink, ExtraTimeScreen
        // shows the blocked-package context card. Asserting on the
        // package string pins the wiring end-to-end without spinning
        // up the full form / throttling / outbox stack.
        composeTestRule.onNodeWithText("com.instagram.android").assertExists()
    }
}
