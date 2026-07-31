# Spec: app-block-policy

## Purpose
Lets the parent curate which apps a specific child device may launch by toggling `app_policies` rows between `ALLOWED` and `BLOCKED` from a per-device UI. Enforcement on the child already reads from Room (hotfix #2), so this change is parent-UI only.

## ADDED Requirements

### Requirement: Parent app-block UI lists installed launchable apps
`AppsScreen` (empty stub at `ui/screen/apps/AppsScreen.kt`) SHALL list every launchable app on the parent's reference device via `PackageManager.queryIntentActivities` with `category = CATEGORY_LAUNCHER`, showing label, package name, icon, and policy state for the selected child device.

#### Scenario: Lists every launchable package
- **WHEN** `AppsScreen` opens,
- **THEN** the `AppsViewModel` SHALL call `PackageManager.queryIntentActivities(Intent(ACTION_MAIN).addCategory(CATEGORY_LAUNCHER), 0)` and present each row with label, package name, icon, and a policy badge.

#### Scenario: Large list stays responsive
- **WHEN** the launchable list has more than 200 entries,
- **THEN** the screen SHALL render inside a `LazyColumn` and SHALL emit the first frame within 200 ms.

#### Scenario: Switching the active device reloads the policy badges
- **WHEN** the parent switches the active child device,
- **THEN** the per-app policy badges SHALL re-load from `app_policies` for the newly selected `device_id`.

### Requirement: Toggling an app updates its app_policies state
Tapping a row SHALL toggle that app's `app_policies` row for the selected device between `ALLOWED` and `BLOCKED`, persisting via `appPolicyDao.upsertAppPolicy`. The underlying policy mutation RPC path MUST be reachable only from the service boundary; direct public API-role execution MUST be denied.

#### Scenario: Tap to block an ALLOWED app
- **WHEN** the parent taps an app currently marked `ALLOWED`,
- **THEN** the badge SHALL optimistically flip to `BLOCKED` and `appPolicyDao.upsertAppPolicy` SHALL persist `(device_id, package_name, state = "BLOCKED")`; on Room failure the UI SHALL revert and show an error.

#### Scenario: Tap to unblock a BLOCKED app
- **WHEN** the parent taps an app currently marked `BLOCKED`,
- **THEN** the row SHALL flip to `ALLOWED` and the same upsert path SHALL run with `state = "ALLOWED"`.

#### Scenario: First-time toggle creates a new row
- **WHEN** there is no existing `app_policies` row for the app on that device,
- **THEN** the upsert SHALL create one with the chosen state, leaving `daily_limit_minutes = NULL` and `allowed_windows = []`.

### Requirement: Per-device policy isolation
Policies SHALL be scoped by `device_id`; toggling an app for one child SHALL NOT affect another child's policy for the same app.

#### Scenario: Sibling devices are unaffected
- **WHEN** the parent toggles `com.example.game` to `BLOCKED` for child A,
- **THEN** querying `app_policies` for child B SHALL return no row for `com.example.game`, or any existing `ALLOWED` row SHALL remain untouched.

#### Scenario: Enforcement reads the device-scoped row
- **WHEN** `EnforcementController` on child A reads its own `app_policies`,
- **THEN** it SHALL see the new `BLOCKED` row; child B's device SHALL see no change in its own snapshot.

### Requirement: DeviceDetailScreen Policy tab exposes block-list curation
The Policy tab of `DeviceDetailScreen` SHALL include an "Add to block list" affordance that opens `AppsScreen` pre-filtered to that child device.

#### Scenario: Policy tab surfaces the add action
- **WHEN** the parent opens the Policy tab,
- **THEN** an "Add to block list" button SHALL be visible alongside the existing per-policy list.

#### Scenario: Tap navigates to AppsScreen with the device id
- **WHEN** the parent taps "Add to block list",
- **THEN** the app SHALL navigate to `AppsScreen` with the `device_id` of the originating child as a navigation argument.

#### Scenario: AppsScreen honors the incoming device id
- **WHEN** `AppsScreen` is opened from `DeviceDetailScreen` with a `device_id` argument,
- **THEN** the screen SHALL skip the device picker and SHALL operate directly on the supplied `device_id` for all `app_policies` lookups and writes.

## Out of scope
- `LIMITED` and `ALWAYS_ALLOWED` states — parent UI only toggles `ALLOWED` ↔ `BLOCKED`.
- Editing `daily_limit_minutes` or `allowed_windows` — rows keep schema defaults.
- Pushing `app_policies` parent → child — handled by `get-policy` + `SyncManager` (hotfix #2); no changes here.
- Uninstall detection or cleanup of `app_policies` for removed packages.
- Category-level policies (the `category` column is left untouched).

## Verification hooks

### Requirement: Privileged Supabase RPCs are service-only
The system MUST expose privileged or internal Supabase RPCs only through the service boundary. API roles MUST be denied direct EXECUTE access, and SECURITY DEFINER functions MUST use a pinned search path.

#### Scenario: Service role can call guarded RPCs
- **GIVEN** a caller operating through the service boundary
- **WHEN** it invokes a privileged RPC
- **THEN** the call MUST succeed only if the authorization checks pass
- **AND** the function search path MUST remain pinned

#### Scenario: API role is blocked from direct access
- **GIVEN** a public API role
- **WHEN** it attempts to invoke the same RPC directly
- **THEN** the call MUST be rejected
- **AND** no internal helper MUST be reachable

### Requirement: Parent-facing check-in RPCs honor caller identity and RLS
Parent-facing check-in RPCs MUST validate the authenticated caller and MUST rely on row-level security for ownership checks. A caller MUST NOT check in or mutate a device owned by another parent.

#### Scenario: Parent checks in own device
- **GIVEN** an authenticated parent and their own device row
- **WHEN** the check-in RPC is called
- **THEN** the request MUST succeed only for that owned device

#### Scenario: Cross-parent check-in is rejected
- **GIVEN** an authenticated parent and a device owned by another parent
- **WHEN** the check-in RPC is called for that device
- **THEN** the request MUST be rejected
