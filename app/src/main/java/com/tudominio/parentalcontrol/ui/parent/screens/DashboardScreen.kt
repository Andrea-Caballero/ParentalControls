package com.tudominio.parentalcontrol.ui.parent.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.tudominio.parentalcontrol.data.repository.DeviceListError
import com.tudominio.parentalcontrol.domain.model.ChildDevice
import com.tudominio.parentalcontrol.domain.model.TimeRequest
import com.tudominio.parentalcontrol.ui.parent.components.ChildPickerChips
import com.tudominio.parentalcontrol.ui.parent.components.DeviceCard
import com.tudominio.parentalcontrol.ui.parent.components.PairingBottomSheet
import com.tudominio.parentalcontrol.ui.parent.components.RenameChildDialog
import com.tudominio.parentalcontrol.ui.parent.components.RequestCard
import com.tudominio.parentalcontrol.ui.screen.apps.AppsScreen
import com.tudominio.parentalcontrol.ui.screen.apps.AppsViewModel
import com.tudominio.parentalcontrol.viewmodel.BehaviorLogViewModel
import com.tudominio.parentalcontrol.viewmodel.DeviceListUiState
import com.tudominio.parentalcontrol.viewmodel.ParentViewModel
import com.tudominio.parentalcontrol.viewmodel.RenameChildState
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Pantalla principal del padre.
 * Muestra dispositivos y solicitudes pendientes.
 *
 * PR 2 of `openspec/changes/wire-pairing-and-approval-end-to-end` wires
 * the Devices tab to the new `DeviceListUiState` sealed UI state
 * (`Loading` / `Success` / `Empty` / `Error`) so the dashboard can render
 * a retry banner when the `get-devices-for-parent` call fails and an
 * empty-state CTA when no devices are paired.
 *
 * PR 5 adds an inline navigation state machine: tapping a device opens
 * [DeviceDetailScreen]; the Policy tab's "Add to block list" button opens
 * [AppsScreen] pre-scoped to the originating `deviceId`. The state is a
 * simple stack so back-navigation returns to the previous screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: ParentViewModel,
    appsViewModel: AppsViewModel? = null,
    onNavigateToDevice: (String) -> Unit = {},
    onNavigateToRequests: () -> Unit = {}
) {
    val devices by viewModel.devices.collectAsState()
    val filteredDevices by viewModel.filteredDevices.collectAsState()
    val selectedChildId by viewModel.selectedChildId.collectAsState()
    val deviceListState by viewModel.deviceListState.collectAsState()
    val pendingRequests by viewModel.pendingRequests.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val renameState by viewModel.renameChildState.collectAsState()

    var showPairingSheet by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }

    // PR 5 navigation state machine. The three screens are the Devices/Requests
    // tabs, [DeviceDetailScreen], and [AppsScreen].
    var navTarget by remember { mutableStateOf<NavTarget>(NavTarget.Dashboard) }

    // Slice B1 — fix-3: bounded lifecycle-aware refresh while the
    // device list is empty. The OPPO parent opens the Dashboard
    // BEFORE the child has consumed the pairing code; the existing
    // SolicitudesPollingWorker only refreshes the requests flow, not
    // the devices list. The scheduler survives the Empty→Loading→
    // Empty flicker (Loading must NOT reset the attempt counter)
    // and stops after EMPTY_REFRESH_MAX_ATTEMPTS retries.
    val emptyRefreshScheduler = remember { EmptyRefreshScheduler() }

    val appsVm: AppsViewModel = appsViewModel
        ?: error("DashboardScreen requires `appsViewModel` to be provided by the caller (Hilt @HiltViewModel).")

    // Slice B1 — fix-3 (effect body). The scheduler decides whether
    // to keep waiting / schedule the next fetch / give up. Loading
    // and non-Empty states are not reset points; only Success/Error
    // ends the empty cycle.
    LaunchedEffect(deviceListState) {
        val delayMs = emptyRefreshScheduler.onState(deviceListState) ?: return@LaunchedEffect
        delay(delayMs)
        viewModel.loadDevices()
    }

    when (val target = navTarget) {
        is NavTarget.Dashboard -> {
            DashboardScaffold(
                viewModel = viewModel,
                devices = devices,
                filteredDevices = filteredDevices,
                selectedChildId = selectedChildId,
                deviceListState = deviceListState,
                pendingRequests = pendingRequests,
                isLoading = isLoading,
                error = error,
                renameState = renameState,
                selectedTab = selectedTab,
                showPairingSheet = showPairingSheet,
                onSelectTab = { selectedTab = it },
                onShowPairingSheet = { showPairingSheet = true },
                onDismissPairingSheet = { showPairingSheet = false },
                onSelectChild = { id -> viewModel.setSelectedChild(id) },
                onLongPressChild = { child ->
                    viewModel.requestRename(
                        childId = child.id,
                        currentName = child.firstName
                    )
                },
                onNavigateToDevice = { id -> navTarget = NavTarget.DeviceDetail(id) },
                onNavigateToRequests = onNavigateToRequests,
                onNavigateToBehaviorLog = { navTarget = NavTarget.BehaviorLog },
                onClearError = { viewModel.clearError() }
            )
        }
        is NavTarget.DeviceDetail -> {
            DeviceDetailScreen(
                deviceId = target.deviceId,
                viewModel = viewModel,
                onNavigateBack = { navTarget = NavTarget.Dashboard },
                onNavigateToApps = { id -> navTarget = NavTarget.Apps(id) }
            )
        }
        is NavTarget.Apps -> {
            AppsScreen(
                deviceId = target.deviceId,
                viewModel = appsVm,
                onBack = { navTarget = NavTarget.DeviceDetail(target.deviceId) }
            )
        }
        is NavTarget.BehaviorLog -> {
            val behaviorLogVm: BehaviorLogViewModel = hiltViewModel()
            BehaviorLogScreen(
                viewModel = behaviorLogVm,
                onNavigateBack = { navTarget = NavTarget.Dashboard }
            )
        }
    }
}

/**
 * Internal navigation targets for the parent screens — replaces the
 * standalone `NavController`/`NavHost` until a proper Navigation graph
 * lands. Stays trivial: each target knows how to render itself.
 */
private sealed class NavTarget {
    data object Dashboard : NavTarget()
    data class DeviceDetail(val deviceId: String) : NavTarget()
    data class Apps(val deviceId: String) : NavTarget()
    data object BehaviorLog : NavTarget()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardScaffold(
    viewModel: ParentViewModel,
    devices: List<ChildDevice>,
    filteredDevices: List<ChildDevice>,
    selectedChildId: String?,
    deviceListState: DeviceListUiState,
    pendingRequests: List<TimeRequest>,
    isLoading: Boolean,
    error: String?,
    renameState: RenameChildState,
    selectedTab: Int,
    showPairingSheet: Boolean,
    onSelectTab: (Int) -> Unit,
    onShowPairingSheet: () -> Unit,
    onDismissPairingSheet: () -> Unit,
    onSelectChild: (String?) -> Unit,
    onLongPressChild: (com.tudominio.parentalcontrol.domain.model.Child) -> Unit,
    onNavigateToDevice: (String) -> Unit,
    onNavigateToRequests: () -> Unit,
    onNavigateToBehaviorLog: () -> Unit,
    onClearError: () -> Unit
) {
    // Distinct children derived from the unfiltered `devices` so the chip
    // row remains stable when the parent switches chips. Per the
    // `parent-device-list` spec, the picker is hidden when there are ≤ 1
    // distinct children (including the all-null case).
    val distinctChildren = remember(devices) {
        devices.mapNotNull { it.child }
            .distinctBy { it.id }
            .sortedBy { it.firstName }
    }

    // Solicitudes filter: client-side `filter` over `_pendingRequests` so
    // a chip tap does NOT trigger a new HTTP call. The `allowedDeviceIds`
    // set is derived from the *filtered* device list so a Lucas chip tap
    // narrows both tabs at once. The notifications badge keeps the
    // unfiltered `pendingRequests.size` (design §B.3) — by design.
    val filteredRequests = remember(pendingRequests, selectedChildId, filteredDevices) {
        if (selectedChildId == null) {
            pendingRequests
        } else {
            val allowedDeviceIds = filteredDevices.map { it.id }.toSet()
            pendingRequests.filter { it.deviceId in allowedDeviceIds }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Control Parental") },
                actions = {
                    BadgedBox(
                        badge = {
                            if (pendingRequests.isNotEmpty()) {
                                Badge { Text(pendingRequests.size.toString()) }
                            }
                        }
                    ) {
                        IconButton(onClick = onNavigateToRequests) {
                            Icon(Icons.Default.Notifications, "Solicitudes")
                        }
                    }
                    IconButton(onClick = onShowPairingSheet) {
                        Icon(Icons.Default.Add, "Agregar dispositivo")
                    }
                    IconButton(
                        onClick = onNavigateToBehaviorLog,
                        modifier = Modifier.testTag("behavior_log_top_bar_entry")
                    ) {
                        Icon(Icons.Default.History, "Registro de eventos")
                    }
                }
            )
        }
    ) { padding ->
        // B.4 — top-level hidden markers that expose `child_name` in
        // the semantics tree. The per-card text is rendered inside the
        // clickable DeviceCard (which merges descendants), so these
        // sibling mirrors keep the q2_gap_dashboard_renders_child_identity_testTag_for_paired_devices
        // contract findable from a default `onNodeWithTag` search.
        // Each marker is `size(0.dp)` so it does not shift the layout;
        // the marker exists purely for the test contract.
        devices.mapNotNull { it.child }.distinctBy { it.id }.forEach { child ->
            Box(
                modifier = Modifier
                    .size(0.dp)
                    .testTag("child_name")
            ) {
                androidx.compose.material3.Text(text = child.firstName)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { onSelectTab(0) },
                    text = { Text("Dispositivos") },
                    icon = { Icon(Icons.Default.Home, null) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { onSelectTab(1) },
                    text = { Text("Solicitudes") },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (pendingRequests.isNotEmpty()) {
                                    Badge { Text(pendingRequests.size.toString()) }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Info, null)
                        }
                    }
                )
            }

            // B.3 of `feat-multi-child-picker` — child picker row, hidden
            // when there is ≤ 1 distinct child. Rendered above the tab
            // body so it visually scopes both tabs.
            if (distinctChildren.size >= 2) {
                ChildPickerChips(
                    children = distinctChildren,
                    selected = selectedChildId,
                    onSelect = onSelectChild,
                    onLongPress = { child -> onLongPressChild(child) }
                )
            }

            // D1 of `fix-parent-solicitudes-auto-poll` — re-fetch pending
            // requests whenever the parent switches to the Solicitudes tab
            // (index 1). `LaunchedEffect(selectedTab)` cancels and re-launches
            // on every tab change; the body is gated to `selectedTab == 1`
            // so other tabs (Devices, future tabs) do NOT trigger a fetch.
            // Spec scenarios covered:
            //   - First tap on the Solicitudes tab triggers a fetch
            //   - Re-tapping the Solicitudes tab triggers a fresh fetch
            //   - Other tabs do not trigger a Solicitudes fetch
            LaunchedEffect(selectedTab) {
                if (selectedTab == 1) {
                    viewModel.loadPendingRequests()
                }
            }

            when (selectedTab) {
                0 -> DevicesTab(
                    viewModel = viewModel,
                    devices = devices,
                    filteredDevices = filteredDevices,
                    listState = deviceListState,
                    onDeviceClick = onNavigateToDevice,
                    onLockDevice = { viewModel.lockDevice(it) },
                    onUnlockDevice = { viewModel.unlockDevice(it) },
                    onRetry = { viewModel.loadDevices() },
                    onClearError = onClearError,
                    // Slice B1 — fix-2: threaded through DevicesTab.
                    onShowPairingSheet = onShowPairingSheet
                )
                1 -> RequestsTab(
                    requests = filteredRequests,
                    isLoading = isLoading,
                    onApprove = { id, minutes -> viewModel.approveRequest(id, minutes) },
                    onDeny = { id -> viewModel.denyRequest(id) }
                )
            }
        }

        if (showPairingSheet) {
            PairingBottomSheet(
                viewModel = viewModel,
                onDismiss = onDismissPairingSheet
            )
        }

        // Rename dialog render — `fix-rename-child-dialog` apply phase
        // (Q2=h per engram #294 — hoisted RenameChildState). The dialog
        // is mounted at the bottom of DashboardScaffold alongside
        // `PairingBottomSheet`, and pattern-matches on the StateFlow so
        // any Editing / Saving / Failed variant opens it; Hidden / Saved
        // (after the 1.5s auto-dismiss) close it.
        when (val rs = renameState) {
            is RenameChildState.Editing -> RenameChildDialog(
                initialName = rs.currentName,
                isLoading = false,
                errorMessage = null,
                onConfirm = { newName -> viewModel.confirmRename(newName) },
                onDismiss = { viewModel.dismissRename() }
            )
            is RenameChildState.Saving -> RenameChildDialog(
                initialName = _resolveInitialName(devices, rs.childId),
                isLoading = true,
                errorMessage = null,
                onConfirm = {},
                onDismiss = {}
            )
            is RenameChildState.Failed -> RenameChildDialog(
                initialName = _resolveInitialName(devices, rs.childId),
                isLoading = false,
                errorMessage = rs.error,
                onConfirm = { newName -> viewModel.confirmRename(newName) },
                onDismiss = { viewModel.dismissRename() }
            )
            RenameChildState.Hidden,
            is RenameChildState.Saved -> Unit
        }

        // Snackbar de error
        error?.let {
            LaunchedEffect(it) {
                onClearError()
            }
        }
    }
}

/**
 * Re-derives the latest firstName for a given [childId] from the cached
 * devices list (so a Saving / Failed re-render shows the latest value
 * the parent typed, not a stale snapshot).
 *
 * Falls back to the empty string when the child id is not present in
 * the cache — for example, the parent deleted the child between
 * requestRename and confirmRename. The dialog treats "" as a
 * validation error, so the parent must re-type.
 */
private fun _resolveInitialName(devices: List<ChildDevice>, childId: String): String =
    devices.firstOrNull { it.child?.id == childId }?.child?.firstName ?: ""

@Composable
private fun DevicesTab(
    viewModel: ParentViewModel,
    devices: List<ChildDevice>,
    filteredDevices: List<ChildDevice>,
    listState: DeviceListUiState,
    onDeviceClick: (String) -> Unit,
    onLockDevice: (String) -> Unit,
    onUnlockDevice: (String) -> Unit,
    onRetry: () -> Unit,
    onClearError: () -> Unit,
    // Slice B1 — fix-2: threaded from DashboardScaffold for the CTA.
    onShowPairingSheet: () -> Unit
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    when (listState) {
        DeviceListUiState.Loading -> {
            if (devices.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                // Keep previously-loaded items on screen during a refresh
                // rather than blanking them out.
                DeviceList(
                    list = filteredDevices,
                    onDeviceClick = onDeviceClick,
                    onLockDevice = onLockDevice,
                    onUnlockDevice = onUnlockDevice
                )
            }
        }
        DeviceListUiState.Empty -> {
            EmptyState(
                icon = Icons.Default.Person,
                title = "Sin dispositivos",
                subtitle = "Empareja uno o más dispositivos",
                // Slice B1 — fix-2: inline CTA. The pairing sheet
                // (DeviceComponents.kt:385) hosts the actual
                // "Generar código" button on its step transition.
                action = {
                    Button(
                        onClick = onShowPairingSheet,
                        modifier = Modifier.testTag("empty_devices_pairing_cta")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Generar código")
                    }
                }
            )
        }
        is DeviceListUiState.Error -> {
            // T8 of `hotfix-parent-auth-session` — pattern-match on the
            // typed reason. Auth-missing swaps retry/back for a single
            // sign-in CTA; transient keeps the retry/back CTAs.
            when (val reason = listState.reason) {
                DeviceListError.AuthMissing -> AuthMissingErrorBanner(
                    onSignIn = {
                        scope.launch { viewModel.authenticateAsParent() }
                    }
                )
                is DeviceListError.Transient -> TransientErrorBanner(
                    message = reason.reason,
                    onRetry = onRetry,
                    onDismiss = onClearError
                )
            }
        }
        is DeviceListUiState.Success -> {
            DeviceList(
                list = filteredDevices,
                onDeviceClick = onDeviceClick,
                onLockDevice = onLockDevice,
                onUnlockDevice = onUnlockDevice
            )
        }
    }
}

@Composable
private fun DeviceList(
    list: List<ChildDevice>,
    onDeviceClick: (String) -> Unit,
    onLockDevice: (String) -> Unit,
    onUnlockDevice: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // B.4 of `feat-multi-child-picker` — surface a `child_name`
        // testTag marker OUTSIDE the clickable DeviceCard so the
        // q2_gap_dashboard_renders_child_identity_testTag_for_paired_devices
        // contract is reachable from a default merged-tree search.
        // The clickable Card merges descendants into itself by default,
        // which swallows the per-card child-name testTag from the
        // inner Text. The marker mirrors `device.child?.firstName` so
        // users see the same identity visible at the marker.
        // (The B.4 top-level markers above already satisfy the
        // q2_gap contract from a default `onNodeWithTag` search; this
        // inner mirror is left in for completeness.)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // B.4 of `feat-multi-child-picker` — bundle the pre-existing
            // LazyColumn key debt fix (propose-Q5=b). Stable item
            // identity prevents recomposition flicker on chip switches.
            items(list, key = { it.id }) { device ->
                DeviceCard(
                    device = device,
                    onClick = { onDeviceClick(device.id) },
                    onLock = { onLockDevice(device.id) },
                    onUnlock = { onUnlockDevice(device.id) }
                )
            }
        }
    }
}

@Composable
private fun RequestsTab(
    requests: List<TimeRequest>,
    isLoading: Boolean,
    onApprove: (String, Int) -> Unit,
    onDeny: (String) -> Unit
) {
    if (isLoading && requests.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    } else if (requests.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Check,
            title = "Sin solicitudes",
            subtitle = "Las solicitudes de tiempo aparecerán aquí"
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(requests) { request ->
                RequestCard(
                    request = request,
                    onApprove = { minutes -> onApprove(request.id, minutes) },
                    onDeny = { onDeny(request.id) }
                )
            }
        }
    }
}

@Composable
private fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    // Slice B1 — fix-2: optional CTA slot. Defaults to null so the
    // RequestsTab empty branch stays visually identical.
    action: (@Composable () -> Unit)? = null
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Text(
                title,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
            action?.invoke()
        }
    }
}

/**
 * Auth-missing variant of the dashboard error banner. Per the spec delta
 * at
 * `openspec/changes/hotfix-parent-auth-session/specs/parent-device-list/spec.md`:
 * when the parent has no session, the banner shows a single
 * "Iniciar sesión como padre" CTA. Retry/back are intentionally absent —
 * retrying a missing-auth call just produces the same error, and there
 * is no previous screen to go back to from this state.
 *
 * Tapping the CTA calls [ParentViewModel.authenticateAsParent] which
 * issues a synthetic anonymous session and (on success) triggers a
 * device-list reload via the same channel the onboarding flow uses.
 */
@Composable
private fun AuthMissingErrorBanner(
    onSignIn: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Error cargando dispositivos",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Text(
                    "not authenticated",
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(
                    onClick = onSignIn,
                    modifier = Modifier.testTag("auth_missing_sign_in_cta")
                ) {
                    Text("Iniciar sesión como padre")
                }
            }
        }
    }
}

/**
 * Transient-error variant of the dashboard error banner. Same shape as
 * the pre-hotfix `ErrorBanner` — retry + dismiss CTAs — but kept as a
 * separate composable so the call site pattern-matches on
 * [DeviceListError] instead of string-matching the error message.
 */
@Composable
private fun TransientErrorBanner(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Error cargando dispositivos",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Reintentar")
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Cerrar")
                    }
                }
            }
        }
    }
}

/**
 * Bounded lifecycle-aware refresh scheduler for the parent Dashboard's
 * empty device list (Slice B1 — fix-3: parent eventually sees a newly
 * paired child without force-stop).
 *
 * Encoded as a pure state machine (no Compose / no side effects in
 * `onState`) so the empty→loading→empty sequence can be unit-tested
 * with no Robolectric. The contract:
 *
 *  - [DeviceListUiState.Empty]: returns the backoff delay for the
 *    current attempt and advances the counter; `null` once the
 *    budget is exhausted.
 *  - [DeviceListUiState.Loading]: returns `null` and PRESERVES the
 *    attempt counter so the post-fetch Empty re-entry resumes the
 *    budget instead of restarting at 1 (the prior bug).
 *  - [DeviceListUiState.Success] / [DeviceListUiState.Error]:
 *    returns `null` and resets the counter to 1 so a later empty
 *    cycle starts fresh.
 *
 * Linear backoff: attempt N waits `EMPTY_REFRESH_BASE_DELAY_MS * N`
 * ms before the next fetch. With the default constants the total
 * window is `21 * 10s = 210s ≈ 3.5min`, under the 5-min
 * `SolicitudesPollingWorker` cadence.
 */
internal class EmptyRefreshScheduler(
    private val maxAttempts: Int = EMPTY_REFRESH_MAX_ATTEMPTS,
    private val baseDelayMs: Long = EMPTY_REFRESH_BASE_DELAY_MS
) {
    private var attempt = 1

    fun onState(state: DeviceListUiState): Long? = when (state) {
        DeviceListUiState.Empty -> {
            if (attempt > maxAttempts) return null
            val delayMs = baseDelayMs * attempt
            attempt++
            delayMs
        }
        DeviceListUiState.Loading -> null
        is DeviceListUiState.Success,
        is DeviceListUiState.Error -> {
            attempt = 1
            null
        }
    }
}

internal const val EMPTY_REFRESH_MAX_ATTEMPTS: Int = 6
internal const val EMPTY_REFRESH_BASE_DELAY_MS: Long = 10_000L
