# Spec: parent-device-list

## Purpose

Lets the parent app list every child device paired under their account, fetched live from Supabase through a new RLS-aware edge function, and rendered in the parent dashboard. Supports grouping by child via a `children` table reference (see `child-entity`) and a multi-child picker chip row that scopes both the Devices tab and the Solicitudes tab (the Solicitudes filter is owned by `time-request-approval`).

## ADDED Requirements

### Requirement: Edge function returns devices for the authenticated parent
A new `get-devices-for-parent` Supabase edge function (under `supabase/functions/get-devices-for-parent/index.ts`) SHALL return every `devices` row where `parent_id` equals the caller's `auth.uid()`, respecting the RLS policies in `002_rls_policies.sql`. The select clause SHALL include `child_id` and `child_first_name` so the parent app can render child identity alongside each device.

#### Scenario: Authenticated parent gets their own devices
- **WHEN** an authenticated parent invokes `POST /functions/v1/get-devices-for-parent` with a valid bearer token,
- **THEN** the response SHALL be an HTTP 200 JSON array of `devices` rows (`id`, `device_name`, `device_model`, `os_version`, `app_version`, `device_state`, `last_seen_at`, `child_id`, `child_first_name`) filtered by `parent_id = auth.uid()`.

#### Scenario: Unauthenticated or non-parent caller is rejected
- **WHEN** the bearer token is missing, expired, or RLS denies access,
- **THEN** the edge function SHALL return 401/403 and the parent app SHALL treat it as an empty list and surface a non-blocking error toast.

#### Scenario: Parent with no devices gets an empty array
- **WHEN** the parent has no paired devices,
- **THEN** the response SHALL be `200 OK` with body `[]` (not 404).

### Requirement: ParentRepository.getDevices returns the live list
`ParentRepository.getDevices()` SHALL call the `get-devices-for-parent` edge function and SHALL NOT read device state from local SharedPreferences (replaces the existing mock). The wire-shape parser SHALL hydrate `ChildDevice.child: Child?` from `child_id` + `child_first_name`, and SHALL leave `child = null` when both fields are absent so pre-migration fixtures stay parseable.

#### Scenario: Repository returns a typed Result
- **WHEN** `ParentViewModel` calls `ParentRepository.getDevices()`,
- **THEN** the call SHALL return `Result<List<Device>>` and SHALL emit a `Loading` state to the UI while in flight.

#### Scenario: Offline call surfaces a retryable error
- **WHEN** the network call fails because the device is offline,
- **THEN** the repository SHALL return `Result.failure(NetworkError)` and the UI SHALL show a retry button — the previous mock fallback SHALL NOT run.

#### Scenario: Pull-to-refresh re-invokes the edge function
- **WHEN** the user triggers pull-to-refresh on the dashboard,
- **THEN** the repository SHALL bypass any local cache and SHALL re-invoke the edge function unconditionally.

### Requirement: DashboardScreen renders the real device list
`DashboardScreen` SHALL display the devices returned by `ParentRepository.getDevices()`, ordered by `last_seen_at` DESC, with one device per row showing name, model, a relative "last seen" string, and the device's child name (or "Sin asignar" when `child == null`). When the parent has ≥ 2 distinct children, the dashboard SHALL render a Material 3 filter-chip row between the `TabRow` and the tab body, with an explicit "Todos" chip and one chip per child; selecting a chip SHALL scope the Devices tab to that child's devices. When the parent has ≤ 1 distinct child, the chip row SHALL be hidden entirely.

#### Scenario: Non-empty list renders DeviceCard rows
- **WHEN** the list is non-empty,
- **THEN** the dashboard SHALL render one `DeviceCard` per device, ordered by `last_seen_at` DESC, each showing `device_name`, `device_model`, a relative timestamp, and the child's first name (`testTag("device_card_child_name")`) when `child != null`; when `child == null` the card SHALL display the literal "Sin asignar" string.

#### Scenario: Empty list shows a pair-new-device CTA
- **WHEN** the list is empty,
- **THEN** the dashboard SHALL display an empty-state illustration with a "Pair a new device" call-to-action that opens `PairingBottomSheet`.

#### Scenario: Loading and error states have dedicated UI
- **WHEN** the call is in flight, the dashboard SHALL show a centered progress indicator; **WHEN** the call fails, an inline error banner with a "Retry" action SHALL appear above the list area.

### Requirement: Error banner CTAs adapt to error type

The parent device list's error banner SHALL present an "Iniciar sesión como padre" CTA when the underlying error indicates missing authentication (error message contains "not authenticated"), and SHALL fall back to the standard retry + back CTAs for transient errors.

#### Scenario: Auth-missing error shows the sign-in CTA
- **WHEN** `DeviceListUiState.Error(DeviceListError.AuthMissing)` is observed,
- **THEN** the rendered error banner SHALL contain a "Iniciar sesión como padre" CTA,
- **AND** the banner SHALL NOT show the "Reintentar" or "Volver" CTAs.

#### Scenario: Transient error shows retry + back
- **WHEN** `DeviceListUiState.Error(DeviceListError.Transient(reason))` is observed (or any non-AuthMissing error),
- **THEN** the rendered error banner SHALL contain "Reintentar" and "Volver" CTAs as before.

### Requirement: ChildPickerChips renders when N ≥ 2 distinct children

The dashboard SHALL render a `ChildPickerChips` composable (Material 3 `FilterChip` row in a `LazyRow`) between the `TabRow` and the tab body. The row SHALL be hidden when `devices.distinctBy { it.child?.id }.size <= 1` and SHALL be visible otherwise. The first chip SHALL be labeled "Todos" (`testTag("child_picker_chip_all")`); each subsequent chip SHALL be labeled with the child's `first_name` and SHALL carry `testTag("child_picker_chip_$childId")`. Selected state SHALL be highlighted per Material 3 defaults.

#### Scenario: Picker is hidden for a single-child household
- **WHEN** the parent's device list contains exactly one distinct child (or all `child == null`),
- **THEN** the chip row SHALL NOT be rendered and no `child_picker_*` testTag SHALL be present in the composition tree.

#### Scenario: Picker is visible with explicit "Todos" plus per-child chips
- **WHEN** the parent's device list contains ≥ 2 distinct children,
- **THEN** the chip row SHALL render with a "Todos" chip first, followed by one chip per child labeled with `first_name`, and each chip SHALL carry its corresponding testTag. This scenario is pinned by the RED test `q2_gap_dashboard_renders_child_picker_or_filter_control` at `app/src/test/java/com/tudominio/parentalcontrol/ui/parent/screens/DashboardScreenTest.kt:504`.

#### Scenario: Per-card child identity testTag is rendered for paired devices
- **WHEN** the Devices tab renders a card for a device whose `child != null`,
- **THEN** the card SHALL expose a `device_card_child_name` testTag containing the child's `first_name`. This scenario is pinned by the RED test `q2_gap_dashboard_renders_child_identity_testTag_for_paired_devices` at `app/src/test/java/com/tudominio/parentalcontrol/ui/parent/screens/DashboardScreenTest.kt:462`.

### Requirement: ParentViewModel owns selectedChildId as in-memory state

`ParentViewModel` SHALL expose `_selectedChildId: MutableStateFlow<String?>(null)` plus a `fun setSelectedChild(id: String?)` setter. The default SHALL be `null` (= "Todos") on every cold start; the value SHALL NOT be persisted to DataStore or any other durable store in V1 (decision R2-V1). After every successful `loadDevices()`, if the cached `selectedChildId` no longer matches any device's `child.id`, the view model SHALL reset it to `null`.

#### Scenario: Cold start defaults to "Todos"
- **WHEN** the parent app launches and `ParentViewModel` is constructed,
- **THEN** `_selectedChildId.value` SHALL be `null` regardless of any prior session state.

#### Scenario: Stale selection is reset after a fetch
- **WHEN** `loadDevices()` completes successfully and the cached `selectedChildId` does not match any device's `child.id`,
- **THEN** `_selectedChildId.value` SHALL be reset to `null` and the dashboard SHALL re-render the unfiltered list.

#### Scenario: Selecting a chip updates the StateFlow
- **WHEN** the parent taps a per-child chip,
- **THEN** `setSelectedChild(childId)` SHALL update `_selectedChildId.value` and the Devices + Solicitudes tabs SHALL re-filter immediately.

### Requirement: Devices tab filters by selectedChildId

The Devices tab SHALL filter its rendered list by `_selectedChildId.value` at the UI layer in `DashboardScaffold`. When `_selectedChildId.value == null`, the tab SHALL render the full unfiltered list. The `LazyColumn` SHALL use `key = { it.id }` on its `items()` call so filter switches do not flicker. The notifications badge SHALL keep counting the unfiltered `_pendingRequests.size` (by design — the badge represents "you have N pending verdicts", independent of the current scope).

#### Scenario: "Todos" restores the unfiltered list
- **WHEN** the parent taps the "Todos" chip,
- **THEN** `_selectedChildId.value` SHALL be `null` and the Devices tab SHALL render every device regardless of `child`.

#### Scenario: Selecting a chip narrows the Devices tab
- **WHEN** the parent taps a per-child chip for childId = "c-123",
- **THEN** the Devices tab SHALL render only devices whose `child?.id == "c-123"`, the badge SHALL continue to count the unfiltered pending-request total, and the `LazyColumn` SHALL keep stable item identity via `key = { it.id }`.

### Requirement: PairingBottomSheet dismiss refreshes the device list

`PairingBottomSheet` SHALL attach a `DisposableEffect(Unit) { onDispose { viewModel.loadDevices() } }` so that, when the sheet is dismissed after a successful pair, the parent dashboard re-fetches the device list exactly once. The newly-paired child SHALL appear in the chip row on the next composition.

#### Scenario: A successful pair adds the new child to the chip row
- **WHEN** the parent completes the "name this child" prompt and the sheet dismisses,
- **THEN** `onDispose` SHALL invoke `ParentViewModel.loadDevices()` exactly once, the edge function SHALL return the new device + child row, and the picker SHALL re-render to include the new child chip.

#### Scenario: Dismissing the sheet without pairing is a no-op
- **WHEN** the parent dismisses the sheet without completing the pair,
- **THEN** `loadDevices()` SHALL still run once (the refresh is unconditional on dismiss) and the dashboard SHALL re-render the unchanged device list.

## Out of scope
- Initiating a new pairing from the dashboard (covered by `pairing-flow`).
- Showing per-device live policy or grant state (covered by `app-block-policy` / `time-request-approval`).
- Pagination — expected device count per parent is small (< 50).
- Manual sort controls (only `last_seen_at` DESC for now).
- Persistence of `selectedChildId` across cold start (deferred to V2; will revisit only if users complain).
- V2 server-side filter refactor on `ParentRepository.getPendingRequests` (the V1 filter is client-side; see `time-request-approval` delta).
- Real-time pairing notifications when the child pairs without the parent opening the sheet (acceptable stale window for V1).
- Renaming a child from the dashboard, deleting a child from the dashboard, or per-child policy overrides (future change).