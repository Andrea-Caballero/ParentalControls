package com.tudominio.parentalcontrol.ui.parent.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.auth.Role
import com.tudominio.parentalcontrol.data.repository.DeviceListError
import com.tudominio.parentalcontrol.data.repository.ParentRepository
import com.tudominio.parentalcontrol.domain.model.Child
import com.tudominio.parentalcontrol.domain.model.ChildDevice
import com.tudominio.parentalcontrol.domain.model.DeviceState
import com.tudominio.parentalcontrol.ui.screen.apps.AppsViewModel
import com.tudominio.parentalcontrol.ui.theme.ParentalControlTheme
import com.tudominio.parentalcontrol.viewmodel.DeviceListUiState
import com.tudominio.parentalcontrol.viewmodel.ParentViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose tests for the [DashboardScreen] error banner CTA swap (T8 of
 * `hotfix-parent-auth-session`).
 *
 * Per the spec delta at
 * `openspec/changes/hotfix-parent-auth-session/specs/parent-device-list/spec.md`:
 *  - When the error is [DeviceListError.AuthMissing], the banner shows a
 *    single "Iniciar sesión como padre" CTA (no retry/back).
 *  - When the error is [DeviceListError.Transient], the banner shows
 *    "Reintentar" + "Cerrar" CTAs as before.
 *
 * The previous behavior unconditionally rendered retry/back, which made
 * the auth-missing case unfixable from the UI. T8 fixes the contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var viewModel: ParentViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val mockRepository = mockk<ParentRepository>(relaxed = true)
        // `fix-parent-solicitudes-auto-poll` — VM's `init` block now
        // collects `repository.pendingRequestsFlow`. Stub a default empty
        // flow so the collector doesn't NPE on the relaxed mock.
        every { mockRepository.pendingRequestsFlow } returns MutableStateFlow(emptyList())
        val mockAuthManager = mockk<DeviceAuthManager>(relaxed = true)
        viewModel = ParentViewModel(mockRepository, mockAuthManager)
        // Seed successful child-device load so the default state is not a
        // spinner — we'll override _deviceListState per test as needed.
        coEvery { mockRepository.getDevices() } returns Result.success(
            listOf(
                ChildDevice(
                    id = "dev-1",
                    name = "Test Device",
                    model = "Pixel 7",
                    appVersion = "1.0.0",
                    policyVersion = 1,
                    state = DeviceState.ACTIVE,
                    lastSeenAt = "2026-06-19T20:00:00Z",
                    isOnline = true
                )
            )
        )
        coEvery { mockAuthManager.authenticateOrCreate(Role.PARENT) } returns Result.success(Unit)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun seedDeviceListState(error: DeviceListError) {
        // Use reflection to set the private StateFlow value — avoids
        // running the full loadDevices() coroutine for a focused test.
        val stateField = ParentViewModel::class.java.getDeclaredField("_deviceListState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<DeviceListUiState>
        stateFlow.value = DeviceListUiState.Error(error)
    }

    /**
     * Mirror of [seedDeviceListState] for the Success branch — used by the
     * 3 RED tests in `feature-pluralize-empty-state-and-add-n-device-tests`
     * to pin N-device rendering in `DashboardScreen`. Reads
     * `app/src/main/assets/mock-supabase/devices.json` fixtures (ACTIVE /
     * DOWNTIME / LOCKED) directly so the test stays in sync with the
     * shipped fixture data.
     *
     * Change B also seeds `_devices` (the raw StateFlow) because the
     * picker row derives `distinctChildren` from `_devices`, not from
     * `_deviceListState`. The Devices tab in Change B renders
     * `filteredDevices` (derived from `_devices + _selectedChildId`),
     * so `_devices` must be populated for the picker row to render
     * and for the LazyColumn to show the seeded items.
     */
    private fun seedSuccessState(items: List<ChildDevice>) {
        val stateField = ParentViewModel::class.java.getDeclaredField("_deviceListState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<DeviceListUiState>
        stateFlow.value = DeviceListUiState.Success(items = items)

        val devicesField = ParentViewModel::class.java.getDeclaredField("_devices")
        devicesField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val devicesFlow = devicesField.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<List<ChildDevice>>
        devicesFlow.value = items
    }

    /**
     * Three-device fixture mirroring
     * `app/src/main/assets/mock-supabase/devices.json`. Pinned here (not
     * imported from JSON) so the assertion stays self-contained and the
     * test does not depend on the assets dir being on the unit-test
     * classpath.
     *
     * Change B: each device now carries a `child` reference mirroring the
     * post-Change-A mock JSON (dev-001 + dev-002 → Lucas; dev-003 → Sofía;
     * dev-002 deliberately left with `child = null` would break the
     * "distinctBy child.id ≥ 2" contract, so all three reference Lucas
     * or Sofía here). The q2_gap_* tests use this fixture to drive the
     * Change B acceptance contract — picker visible (N=2 children) and
     * child_name testTag emitted on each card.
     */
    private val threeDeviceFixtures: List<ChildDevice> = listOf(
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

    /**
     * Mock the repository's `getPendingRequests()` so each call returns
     * success and is counted. Used by the tab-tap test below. MUST be
     * called BEFORE constructing the VM so init's launch sees the
     * counter stub.
     */
    private fun setupPendingRequestsCounter(
        repository: ParentRepository
    ): () -> Int {
        var count = 0
        coEvery { repository.getPendingRequests() } answers {
            count++
            Result.success(emptyList())
        }
        // V2 mirror — VM's `loadPendingRequests()` uses the
        // `selectedChildId` overload (V2 thread-through,
        // `fix-v2-server-side-solicitudes-filter`).
        coEvery { repository.getPendingRequests(selectedChildId = null) } answers {
            count++
            Result.success(emptyList())
        }
        return { count }
    }

    @Test
    fun error_banner_authMissing_shows_sign_in_cta_only() {
        seedDeviceListState(DeviceListError.AuthMissing)

        val appsVm = mockk<AppsViewModel>(relaxed = true)
        composeTestRule.setContent {
            ParentalControlTheme {
                DashboardScreen(viewModel = viewModel, appsViewModel = appsVm)
            }
        }
        composeTestRule.waitForIdle()

        // The sign-in CTA MUST be visible.
        composeTestRule.onNodeWithText("Iniciar sesión como padre").assertIsDisplayed()
        // The retry/back CTAs MUST NOT be visible for an auth error.
        val retry = runCatching {
            composeTestRule.onNodeWithText("Reintentar").assertExists()
            true
        }.getOrDefault(false)
        val back = runCatching {
            composeTestRule.onNodeWithText("Cerrar").assertExists()
            true
        }.getOrDefault(false)
        org.junit.Assert.assertFalse(
            "Retry CTA must NOT appear for AuthMissing, found=$retry",
            retry
        )
        org.junit.Assert.assertFalse(
            "Back CTA must NOT appear for AuthMissing, found=$back",
            back
        )
    }

    @Test
    fun error_banner_transient_shows_retry_and_back() {
        seedDeviceListState(DeviceListError.Transient("HTTP 500"))

        val appsVm = mockk<AppsViewModel>(relaxed = true)
        composeTestRule.setContent {
            ParentalControlTheme {
                DashboardScreen(viewModel = viewModel, appsViewModel = appsVm)
            }
        }
        composeTestRule.waitForIdle()

        // The retry + back CTAs MUST be visible.
        composeTestRule.onNodeWithText("Reintentar").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cerrar").assertIsDisplayed()
        // The sign-in CTA MUST NOT be visible for a transient error.
        val signIn = runCatching {
            composeTestRule.onNodeWithText("Iniciar sesión como padre").assertExists()
            true
        }.getOrDefault(false)
        org.junit.Assert.assertFalse(
            "Sign-in CTA must NOT appear for Transient, found=$signIn",
            signIn
        )
    }

    // -------------------------------------------------------------------------
    // RED coverage for `fix-parent-solicitudes-auto-poll` (Task 1.4).
    //
    // The dashboard's `DashboardScaffold` carries a `LaunchedEffect(selectedTab)`
    // that fires `viewModel.loadPendingRequests()` whenever the parent
    // switches to the Solicitudes tab (index 1). Tabs other than 1 must NOT
    // trigger a Solicitudes fetch. This test asserts both halves.
    // -------------------------------------------------------------------------

    @Test
    fun tab_tap_to_solicitudes_invokes_loadPendingRequests_other_tabs_do_not() {
        // Fresh mocks so this test owns its VM and its counters.
        val mockRepository = mockk<ParentRepository>(relaxed = true)
        val mockAuthManager = mockk<DeviceAuthManager>(relaxed = true)
        // VM's init block now collects pendingRequestsFlow — stub a default
        // empty flow so the collector doesn't NPE on the relaxed mock.
        every { mockRepository.pendingRequestsFlow } returns MutableStateFlow(emptyList())
        coEvery { mockRepository.getDevices() } returns Result.success(emptyList())
        coEvery { mockAuthManager.authenticateOrCreate(Role.PARENT) } returns Result.success(Unit)

        // Set up the counter BEFORE constructing the VM so init's
        // launch sees the counter stub.
        val pendingCalls = setupPendingRequestsCounter(mockRepository)

        val tabViewModel = ParentViewModel(mockRepository, mockAuthManager)
        // init's loadPendingRequests fired exactly once.
        assertEquals(
            "init must have launched exactly one loadPendingRequests",
            1, pendingCalls()
        )

        val appsVm = mockk<AppsViewModel>(relaxed = true)
        composeTestRule.setContent {
            ParentalControlTheme {
                DashboardScreen(viewModel = tabViewModel, appsViewModel = appsVm)
            }
        }
        composeTestRule.waitForIdle()

        // Initial selectedTab is 0 (Devices). The LaunchedEffect body is
        // gated to `selectedTab == 1`, so no extra Solicitudes fetch.
        assertEquals(
            "Default Devices tab must NOT trigger a Solicitudes fetch",
            1, pendingCalls()
        )

        // Tap Solicitudes → selectedTab becomes 1 → LaunchedEffect fires.
        composeTestRule.onNodeWithText("Solicitudes").performClick()
        composeTestRule.waitForIdle()
        assertEquals(
            "Solicitudes tab tap must trigger a Solicitudes fetch",
            2, pendingCalls()
        )

        // Tap Devices → selectedTab becomes 0 → LaunchedEffect re-keys but
        // body is gated; no fetch.
        composeTestRule.onNodeWithText("Dispositivos").performClick()
        composeTestRule.waitForIdle()
        assertEquals(
            "Devices tab tap must NOT trigger a Solicitudes fetch",
            2, pendingCalls()
        )

        // Re-tap Solicitudes → another fresh fetch.
        composeTestRule.onNodeWithText("Solicitudes").performClick()
        composeTestRule.waitForIdle()
        assertEquals(
            "Re-tap on Solicitudes must trigger a fresh fetch",
            3, pendingCalls()
        )
    }

    // -------------------------------------------------------------------------
    // RED coverage for `feature-pluralize-empty-state-and-add-n-device-tests`.
    //
    // The data layer is already 1:N (ParentRepository.getDevices() returns
    // List<ChildDevice>) but the dashboard's DeviceList only had zero test
    // coverage for the N-device rendering path. These 3 tests pin that:
    //   1.2.1 — the LazyColumn renders exactly N DeviceCards for an N-item
    //           Success state (counted via the `device_card` testTag added
    //           to DeviceCard at DeviceComponents.kt:36-39).
    //   1.2.2 — each card surfaces its `device.name` and `device.model`.
    //   1.2.3 — each card surfaces its state-badge label (Activo /
    //           Hora de dormir / Bloqueado).
    // -------------------------------------------------------------------------

    @Test
    fun success_state_with_3_devices_renders_3_device_card_testTags() {
        seedSuccessState(threeDeviceFixtures)

        val appsVm = mockk<AppsViewModel>(relaxed = true)
        composeTestRule.setContent {
            ParentalControlTheme {
                DashboardScreen(viewModel = viewModel, appsViewModel = appsVm)
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithTag("device_card").assertCountEquals(3)
    }

    @Test
    fun success_state_with_3_devices_renders_per_card_name_and_model() {
        seedSuccessState(threeDeviceFixtures)

        val appsVm = mockk<AppsViewModel>(relaxed = true)
        composeTestRule.setContent {
            ParentalControlTheme {
                DashboardScreen(viewModel = viewModel, appsViewModel = appsVm)
            }
        }
        composeTestRule.waitForIdle()

        // `assertExists` (not `assertIsDisplayed`) because the third card
        // sits below the Robolectric default viewport; the semantic intent
        // is "the card renders the device's name and model into the tree"
        // which is met by existence. Visibility is exercised separately by
        // the count assertion in 1.2.1 and by instrumented tests in CI.

        // dev-001 (ACTIVE)
        composeTestRule.onNodeWithText("Galaxy Tab S6 Lite").assertExists()
        composeTestRule.onNodeWithText("SM-P610").assertExists()
        // dev-002 (DOWNTIME)
        composeTestRule.onNodeWithText("Moto G32").assertExists()
        composeTestRule.onNodeWithText("moto g32").assertExists()
        // dev-003 (LOCKED)
        composeTestRule.onNodeWithText("Pixel 7a").assertExists()
        composeTestRule.onNodeWithText("GWKK3").assertExists()
    }

    @Test
    fun success_state_with_3_devices_renders_per_card_state_badge() {
        seedSuccessState(threeDeviceFixtures)

        val appsVm = mockk<AppsViewModel>(relaxed = true)
        composeTestRule.setContent {
            ParentalControlTheme {
                DashboardScreen(viewModel = viewModel, appsViewModel = appsVm)
            }
        }
        composeTestRule.waitForIdle()

        // `assertExists` for the same reason as above: per-card badge
        // labels are rendered into the tree regardless of viewport.

        // Each fixture's badge label, per DeviceComponents.kt:183-188.
        composeTestRule.onNodeWithText("Activo").assertExists()
        composeTestRule.onNodeWithText("Hora de dormir").assertExists()
        composeTestRule.onNodeWithText("Bloqueado").assertExists()
    }

    // -------------------------------------------------------------------------
    // RED spike for `feature-multi-child-q2-child-picker` (Q2).
    //
    // The data layer today has no `Child` entity: `ChildDevice` carries
    // only device-level fields (id, name, model, state, ...), the
    // pairing flow provisions an anonymous per-device user, and the
    // dashboard renders one card per device without any child-identity
    // marker. Q2 will introduce a `Child` model + a child picker on
    // DashboardScreen so the parent can scope the device list to one
    // kid.
    //
    // These tests PIN the current gap (expect RED today) so that the
    // Q2 schema + domain + pairing + UI migration has a concrete
    // acceptance target. They become GREEN when Q2 lands.
    // -------------------------------------------------------------------------

    /**
     * Smoke (expect GREEN): proves the Robolectric + Compose infra
     * compiles and runs after PR #17's setup. With a 3-device seed,
     * the dashboard must render exactly three `device_card` testTags.
     * This mirrors the contract from
     * `feature-pluralize-empty-state-and-add-n-device-tests` (1.2.1).
     */
    @Test
    fun q2_smoke_3_device_seed_renders_three_device_card_testTags() {
        seedSuccessState(threeDeviceFixtures)

        val appsVm = mockk<AppsViewModel>(relaxed = true)
        composeTestRule.setContent {
            ParentalControlTheme {
                DashboardScreen(viewModel = viewModel, appsViewModel = appsVm)
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithTag("device_card").assertCountEquals(3)
    }

    /**
     * Gap A (expect RED today): DashboardScreen must render at least
     * one child-identity marker — either a `child_name` or
     * `child_first_name` testTag — for the paired devices. Today no
     * such field exists on `ChildDevice` (Models.kt:9-18) and no
     * DashboardScreen rendering emits a child-identity testTag, so
     * `assertExists` fails for both candidates. After Q2's schema +
     * domain + UI migration lands and the card surfaces the child's
     * name, this assertion goes GREEN.
     */
    @Test
    fun q2_gap_dashboard_renders_child_identity_testTag_for_paired_devices() {
        // Fresh VM so init's `loadDevices()` seeds `_devices` directly
        // with the 3-device fixture (avoids the
        // `seedSuccessState` reflection path that's order-sensitive
        // with `WhileSubscribed(5_000)`).
        val mockRepository = mockk<ParentRepository>(relaxed = true)
        every { mockRepository.pendingRequestsFlow } returns MutableStateFlow(emptyList())
        coEvery { mockRepository.getDevices() } returns Result.success(threeDeviceFixtures)
        val mockAuthManager = mockk<DeviceAuthManager>(relaxed = true)
        coEvery { mockAuthManager.authenticateOrCreate(Role.PARENT) } returns Result.success(Unit)
        val gapViewModel = ParentViewModel(mockRepository, mockAuthManager)

        val appsVm = mockk<AppsViewModel>(relaxed = true)
        composeTestRule.setContent {
            ParentalControlTheme {
                DashboardScreen(viewModel = gapViewModel, appsViewModel = appsVm)
            }
        }
        composeTestRule.waitForIdle()

        // `onAllNodesWithTag(...).fetchSemanticsNodes()` instead of
        // `onNodeWithTag(...)` because the dashboard renders a
        // `child_name` marker per distinct child (Lucas + Sofía here),
        // and `onNodeWithTag` would throw `AmbiguousNodeMatcherException`
        // when 2+ nodes carry the same testTag. The original loose
        // contract only requires the testTag to "exist" somewhere in
        // the tree, so a count >= 1 assertion is equivalent.
        val hasChildName = composeTestRule.onAllNodesWithTag("child_name")
            .fetchSemanticsNodes().isNotEmpty()
        val hasChildFirstName = composeTestRule.onAllNodesWithTag("child_first_name")
            .fetchSemanticsNodes().isNotEmpty()
        assertTrue(
            "Q2 gap: DashboardScreen must render a child-identity testTag " +
                "('child_name' or 'child_first_name') for the 3-device seed; " +
                "found child_name=$hasChildName child_first_name=$hasChildFirstName. " +
                "Today ChildDevice has no child-name field — Q2 must add it " +
                "and surface it on the dashboard.",
            hasChildName || hasChildFirstName
        )
    }

    /**
     * Gap B (expect RED today): DashboardScreen must expose a child
     * picker / filter control so the parent can scope the device list
     * to one kid. The control's exact composition is an open design
     * decision, so we accept ANY of the candidate testTags:
     *   - `child_picker` — a single selector (e.g., dropdown)
     *   - `child_chip`   — chip-per-child toggle row
     *   - `child_filter_row` — broader filter chrome (e.g., row above the list)
     *
     * Today none of these testTags are emitted by DashboardScreen or
     * its children, so the assertion fails with a message listing the
     * candidates. After Q2 ships the picker, this goes GREEN.
     */
    @Test
    fun q2_gap_dashboard_renders_child_picker_or_filter_control() {
        seedSuccessState(threeDeviceFixtures)

        val appsVm = mockk<AppsViewModel>(relaxed = true)
        composeTestRule.setContent {
            ParentalControlTheme {
                DashboardScreen(viewModel = viewModel, appsViewModel = appsVm)
            }
        }
        composeTestRule.waitForIdle()

        val candidateTags = listOf("child_picker", "child_chip", "child_filter_row")
        val foundTags = candidateTags.filter { tag ->
            runCatching {
                composeTestRule.onNodeWithTag(tag).assertExists()
                true
            }.getOrDefault(false)
        }
        assertTrue(
            "Q2 gap: DashboardScreen must render a child picker / filter " +
                "control tagged with one of $candidateTags so the parent " +
                "can scope the device list by child; found=$foundTags. " +
                "Today no such control exists — Q2 must add it.",
            foundTags.isNotEmpty()
        )
    }

    // -------------------------------------------------------------------------
    // GREEN coverage for Change B of `feat-multi-child-picker` (B.1.2).
    //
    // Tests the picker visibility contract + filter behavior on both tabs.
    // All tests seed via `seedSuccessState` (devices flow) and then drive
    // chip taps through `performClick`.
    // -------------------------------------------------------------------------

    /**
     * Helper: build a 3-device fixture with Lucas + Sofía children
     * populated. Mirrors `app/src/main/assets/mock-supabase/devices.json`
     * after Change A.
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

    /**
     * Single-child fixture (Lucas x3) — picker MUST be hidden.
     */
    private fun lucasOnlyFixtures(): List<ChildDevice> = listOf(
        threeDeviceFixtures[0].copy(
            child = Child(
                id = "child-lucas",
                parentId = "parent-demo",
                firstName = "Lucas",
                createdAt = "2026-06-01T00:00:00Z",
                updatedAt = "2026-06-01T00:00:00Z"
            )
        ),
        threeDeviceFixtures[1].copy(
            child = Child(
                id = "child-lucas",
                parentId = "parent-demo",
                firstName = "Lucas",
                createdAt = "2026-06-01T00:00:00Z",
                updatedAt = "2026-06-01T00:00:00Z"
            )
        ),
        threeDeviceFixtures[2].copy(
            child = Child(
                id = "child-lucas",
                parentId = "parent-demo",
                firstName = "Lucas",
                createdAt = "2026-06-01T00:00:00Z",
                updatedAt = "2026-06-01T00:00:00Z"
            )
        )
    )

    @Test
    fun picker_hidden_when_one_child() {
        seedSuccessState(lucasOnlyFixtures())

        val appsVm = mockk<AppsViewModel>(relaxed = true)
        composeTestRule.setContent {
            ParentalControlTheme {
                DashboardScreen(viewModel = viewModel, appsViewModel = appsVm)
            }
        }
        composeTestRule.waitForIdle()

        // N=1 → no chip row visible.
        composeTestRule.onAllNodesWithTag("child_picker").assertCountEquals(0)
    }

    @Test
    fun picker_visible_with_chip_all_when_two_children() {
        seedSuccessState(lucasAndSofiaFixtures())

        val appsVm = mockk<AppsViewModel>(relaxed = true)
        composeTestRule.setContent {
            ParentalControlTheme {
                DashboardScreen(viewModel = viewModel, appsViewModel = appsVm)
            }
        }
        composeTestRule.waitForIdle()

        // N=2 → chip row visible with Todos + per-child chips.
        composeTestRule.onAllNodesWithTag("child_picker").assertCountEquals(1)
        composeTestRule.onNodeWithTag("child_picker_chip_all").assertIsDisplayed()
        composeTestRule.onNodeWithTag("child_picker_chip_child-lucas").assertIsDisplayed()
        composeTestRule.onNodeWithTag("child_picker_chip_child-sofia").assertIsDisplayed()
    }

    @Test
    fun chip_tap_filters_devices_tab_to_one_child() {
        seedSuccessState(lucasAndSofiaFixtures())

        val appsVm = mockk<AppsViewModel>(relaxed = true)
        composeTestRule.setContent {
            ParentalControlTheme {
                DashboardScreen(viewModel = viewModel, appsViewModel = appsVm)
            }
        }
        composeTestRule.waitForIdle()

        // All 3 devices initially (Todos).
        composeTestRule.onAllNodesWithTag("device_card").assertCountEquals(3)

        // Tap Sofía chip → only dev-003.
        composeTestRule.onNodeWithTag("child_picker_chip_child-sofia").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithTag("device_card").assertCountEquals(1)
    }

    @Test
    fun todos_chip_restores_unfiltered_list() {
        seedSuccessState(lucasAndSofiaFixtures())

        val appsVm = mockk<AppsViewModel>(relaxed = true)
        composeTestRule.setContent {
            ParentalControlTheme {
                DashboardScreen(viewModel = viewModel, appsViewModel = appsVm)
            }
        }
        composeTestRule.waitForIdle()

        // Tap Lucas → 2 cards.
        composeTestRule.onNodeWithTag("child_picker_chip_child-lucas").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithTag("device_card").assertCountEquals(2)

        // Tap Todos → restored to 3.
        composeTestRule.onNodeWithTag("child_picker_chip_all").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithTag("device_card").assertCountEquals(3)
    }
}
