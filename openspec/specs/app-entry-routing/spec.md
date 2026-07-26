# Spec: app-entry-routing

## Purpose
Routes the launched `MainActivity` to the correct top-level screen based on device pairing state, and forwards `parentalcontrol://pair?code=...` deeplinks to the pairing flow. PR 4 of `align-with-guia-fedora44` extracts the inline `when { ... }` block from `MainActivity.kt` into a dedicated `@Composable fun NavGraph()` so `MainActivity` is reduced to lifecycle + deeplink plumbing.

## ADDED Requirements

### Requirement: MainActivity delegates routing to NavGraph
`MainActivity.onCreate` SHALL set the content to `ParentalControlTheme { NavGraph(prefilledPairingCode = prefilledPairingCode.value) }` and SHALL NOT contain a multi-branch `when` selecting top-level screens. The deeplink extraction (`handlePairingDeeplink`), `enableEdgeToEdge`, and `onNewIntent` handling SHALL remain in `MainActivity`.

#### Scenario: MainActivity no longer holds the routing when block
- **WHEN** `MainActivity.kt` is read,
- **THEN** it SHALL NOT contain a `when { !isPaired -> ... isChildDevice -> ... else -> ... }` block selecting among `OnboardingScreen` / `DashboardScreen` / `ChildStatusScreen`, and the `setContent` body SHALL be ≤ 5 lines.

#### Scenario: NavGraph composable exists and is invokable
- **WHEN** `app/src/main/java/com/tudominio/parentalcontrol/ui/navigation/NavGraph.kt` is loaded,
- **THEN** it SHALL declare `@Composable fun NavGraph(prefilledPairingCode: String? = null, ...)` and SHALL import every screen it composes.

### Requirement: Unpaired device lands on OnboardingScreen
The NavGraph SHALL route devices where `DeviceAuthManager.isPaired()` is `false` to `OnboardingScreen`, which advances to `DashboardScreen` (parent mode) or `PairingScreen` (child mode).

#### Scenario: Cold start with no pairing
- **WHEN** `MainActivity.onCreate` runs and `DeviceAuthManager.isPaired()` returns `false`,
- **THEN** `OnboardingScreen` SHALL be the first composition.

#### Scenario: Parent mode shows Dashboard
- **WHEN** the unpaired user selects "Parent",
- **THEN** `DashboardScreen` SHALL compose with `ParentViewModel` and `AppsViewModel` injected via `hiltViewModel()`.

#### Scenario: Child mode shows PairingScreen
- **WHEN** the unpaired user selects "Child",
- **THEN** `PairingScreen` SHALL compose with `PairingViewModel` injected via `hiltViewModel()` and the current `prefilledPairingCode` SHALL be passed as the `prefilledCode` parameter.

### Requirement: Paired child device lands on ChildStatusScreen
The NavGraph SHALL route devices where `isPaired() == true && parentId != null` to `ChildStatusScreen`, and SHALL swap to `ExtraTimeScreen` when the child requests extra time.

#### Scenario: Child opens app after pairing
- **WHEN** `MainActivity.onCreate` runs and the device is paired as a child,
- **THEN** `ChildStatusScreen` SHALL compose with `ChildStatusViewModel` injected via `hiltViewModel()`.

#### Scenario: Child taps Request extra time
- **WHEN** the child invokes `onRequestExtraTime` on `ChildStatusScreen`,
- **THEN** `ExtraTimeScreen` SHALL compose with `TimeExtraRepository`, `CopyManager`, and the child's `deviceId`, and `onBack` / `onRequestSent` SHALL return to `ChildStatusScreen`.

### Requirement: Paired parent device lands on DashboardScreen
The NavGraph SHALL route paired devices without a child `parentId` to `DashboardScreen`.

#### Scenario: Parent device shows Dashboard
- **WHEN** `MainActivity.onCreate` runs and `isPaired() == true && parentId == null`,
- **THEN** `DashboardScreen` SHALL compose with `ParentViewModel` and `AppsViewModel` injected via `hiltViewModel()`.

#### Scenario: ParentViewModel renameChildState observes Hidden by default
- **WHEN** a `ParentViewModel` instance is constructed (real or stubbed via `mockk(relaxed = true)`),
- **THEN** `renameChildState.value` SHALL observe `RenameChildState.Hidden`.

`DashboardScreen` reads `viewModel.renameChildState.collectAsState()` on every composition. Any test that mocks `ParentViewModel` with `mockk(relaxed = true)` MUST stub this flow with a real `MutableStateFlow(RenameChildState.Hidden)` so the unchecked `StateFlow<RenameChildState>` cast resolves cleanly; a relaxed mock returns `Any` for the property, which the unchecked cast rejects at runtime.

### Requirement: Pairing deeplink prefill survives extraction
`parentalcontrol://pair?code=<code>` intents SHALL pre-fill `PairingScreen.prefilledCode` on both cold start (`onCreate`) and warm start (`onNewIntent`).

#### Scenario: Deeplink delivered as cold-start intent
- **WHEN** `MainActivity` is launched with `Intent(ACTION_VIEW, "parentalcontrol://pair?code=ABC12345")`,
- **THEN** after the device picks "Child" mode, `PairingScreen` SHALL compose with `prefilledCode = "ABC12345"`.

#### Scenario: Deeplink delivered while app is running
- **WHEN** the app is running and `onNewIntent` receives `Intent(ACTION_VIEW, "parentalcontrol://pair?code=XYZ")`,
- **THEN** the next composition of `PairingScreen` SHALL observe `prefilledCode = "XYZ"` without an activity restart.

#### Scenario: Non-deeplink intent is a no-op
- **WHEN** the launched intent has a different scheme or `action != ACTION_VIEW`,
- **THEN** `prefilledPairingCode.value` SHALL remain `null` and `PairingScreen` SHALL compose with `prefilledCode = null`.

## Out of scope
- Introducing `androidx.navigation.compose` `NavHost` / `NavController` — this PR hoists the existing `when` into a `@Composable`, it does not migrate to the Navigation library.
- Wiring `navController.navigate("apps/{deviceId}")` from `DeviceDetailScreen` (see `app-block-policy/spec.md`) — follow-up, not required for PR 4.
- Decomposing `NavGraph` into `OnboardingNav` / `ParentNav` / `ChildNav` — single `NavGraph` composable is the contract.
- Removing `restartActivity()` from `MainActivity` — it MAY stay or move to `NavGraph.kt`; its behavior (calling `recreate()` after pairing) SHALL be preserved.