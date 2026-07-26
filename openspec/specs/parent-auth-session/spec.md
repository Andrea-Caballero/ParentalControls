# Spec: parent-auth-session

## Purpose

Gives the parent role a synthetic anonymous auth session and a toggleable Ktor `MockEngine` so the dashboard renders fixture devices without depending on a real Supabase instance. Replaced by `parent-auth-flow` once real parent sign-up/sign-in lands.

## ADDED Requirements

### Requirement: Stale-auth-state migration on cold start

The auth state MUST be migrated on cold start when `role = PARENT` and `parent_id` is null or empty: `parent_id` is written to the demo default `MOCK_PARENT_ID` so the BehavioralEventLog can surface the fixture's events. The migration MUST be role-gated (PARENT only) and idempotent.

#### Scenario: parent with pre-existing auth state, no parent_id

- **WHEN** the app cold-starts with SharedPreferences containing `role=PARENT` and `synthetic_access_token` but no `parent_id`,
- **THEN** the migration SHALL write `parent_id = MOCK_PARENT_ID`,
- **AND** the BehavioralEventLog screen SHALL surface the fixture's events on next navigation.

#### Scenario: child role, no parent_id

- **WHEN** the app cold-starts with `role=CHILD` and no `parent_id`,
- **THEN** the migration SHALL be a no-op (no `parent_id` written).

### Requirement: Role-aware authenticateOrCreate issues a session with the correct role flag

`DeviceAuthManager.authenticateOrCreate(role)` SHALL create an auth session and persist `role` to `device_auth_prefs`.

#### Scenario: Parent role flag is persisted
- **WHEN** `authManager.authenticateOrCreate(Role.PARENT)` is called,
- **THEN** a session SHALL be created,
- **AND** the role `PARENT` SHALL be persisted to `device_auth_prefs`.

#### Scenario: Child role flag is persisted
- **WHEN** `authManager.authenticateOrCreate(Role.CHILD)` is called,
- **THEN** a session SHALL be created,
- **AND** the role `CHILD` SHALL be persisted to `device_auth_prefs`.

### Requirement: OnboardingScreen wires authenticateAsParent before navigating to Dashboard

`OnboardingScreen` SHALL call `authenticateAsParent()` and SHALL only navigate to `Dashboard` on success. During auth the parent button SHALL be disabled and a loading indicator SHALL be visible.

#### Scenario: Parent button triggers auth before nav
- **WHEN** the user taps "Soy el padre" in `OnboardingScreen`,
- **THEN** `authenticateAsParent()` SHALL be called and complete,
- **AND THEN** navigation to `Dashboard` SHALL occur.

#### Scenario: Loading state shown during auth
- **WHILE** `authenticateAsParent()` is in progress,
- **THEN** the parent button SHALL be disabled and a loading indicator SHALL be shown.

#### Scenario: Auth failure does not navigate
- **WHEN** `authenticateAsParent()` fails,
- **THEN** navigation to `Dashboard` SHALL NOT occur,
- **AND** an error SHALL be surfaced to the user.

### Requirement: Mock Supabase engine is toggleable via BuildConfig.USE_MOCK_SUPABASE

The Ktor `HttpClient` SHALL bind to `MockEngine` when `BuildConfig.USE_MOCK_SUPABASE == true` and SHALL bind to the real engine otherwise. The mock engine SHALL return fixture JSON for `/devices`, `/pending-requests`, and `/templates`.

**AND** under the `debug` build type, the build SHALL read the `USE_MOCK_SUPABASE` flag from `local.properties` (falling back to `gradle.properties`) so the documented developer workflow takes effect.

#### Scenario: Mock engine used when flag is true
- **WHEN** `BuildConfig.USE_MOCK_SUPABASE == true`,
- **THEN** the Ktor `HttpClient` SHALL use `MockEngine` returning fixture JSON for `/devices`, `/pending-requests`, and `/templates`.

#### Scenario: Real engine used when flag is false
- **WHEN** `BuildConfig.USE_MOCK_SUPABASE == false`,
- **THEN** the Ktor `HttpClient` SHALL use the real engine (currently unreachable due to placeholder Supabase config, but bound).

#### Scenario: Build reads USE_MOCK_SUPABASE from local.properties under debug
- **GIVEN** the `debug` build type is active,
- **AND** `local.properties` contains `USE_MOCK_SUPABASE=true`,
- **THEN** `BuildConfig.USE_MOCK_SUPABASE` SHALL be `true`,
- **AND** the Ktor `HttpClient` SHALL bind to `MockEngine`.

#### Scenario: Release build does not honor local.properties USE_MOCK_SUPABASE
- **GIVEN** the `release` build type is active,
- **AND** `local.properties` contains `USE_MOCK_SUPABASE=true`,
- **THEN** `BuildConfig.USE_MOCK_SUPABASE` SHALL be `false`,
- **AND** the Ktor `HttpClient` SHALL bind to the real engine.

### Requirement: DashboardScreen error banner CTAs swap for auth errors

The parent dashboard's error banner SHALL present an "Iniciar sesión como padre" CTA when the underlying error indicates missing authentication, and SHALL fall back to the standard retry + back CTAs for transient errors.

**AND THEN**, when the user taps the "Iniciar sesión como padre" CTA and authentication succeeds, `loadDevices()` SHALL be re-invoked automatically so the device list transitions out of the `Error(AuthMissing)` state.

#### Scenario: "Iniciar sesión como padre" CTA for auth errors
- **WHEN** the error message contains "not authenticated",
- **THEN** the error banner SHALL show a single "Iniciar sesión como padre" CTA (not retry/back),
- **AND** tapping it SHALL trigger the parent auth flow.

#### Scenario: Retry + back for transient errors
- **WHEN** the error message does NOT contain "not authenticated",
- **THEN** the error banner SHALL show "Reintentar" and "Volver" CTAs as before.

#### Scenario: Tapping sign-in CTA reloads devices after successful auth
- **GIVEN** the dashboard is showing `DeviceListUiState.Error(DeviceListError.AuthMissing)`,
- **WHEN** the user taps the "Iniciar sesión como padre" CTA,
- **AND** `authenticateAsParent()` completes with `Result.success(Unit)`,
- **THEN** `loadDevices()` SHALL be invoked,
- **AND THEN** `DeviceListUiState` SHALL transition to `Loading`,
- **AND THEN** `DeviceListUiState` SHALL transition to `Success(devices)` when the fetch completes (mock engine returns ≥ 2 devices per existing fixture contract).

#### Scenario: Tapping sign-in CTA does not reload on auth failure
- **GIVEN** the dashboard is showing `DeviceListUiState.Error(DeviceListError.AuthMissing)`,
- **WHEN** the user taps the "Iniciar sesión como padre" CTA,
- **AND** `authenticateAsParent()` completes with `Result.failure(throwable)`,
- **THEN** `loadDevices()` SHALL NOT be invoked,
- **AND** the error banner SHALL remain visible.

### Requirement: MockSupabaseFixtures contract returns realistic data

`MockSupabaseFixtures` SHALL expose ready-to-parse JSON payloads for `/devices`, `/pending-requests`, and `/templates`.

#### Scenario: Device list fixture
- **WHEN** the mock engine handles `GET /devices` for an authenticated parent,
- **THEN** it SHALL return JSON with a list of ≥ 2 child devices, each with `deviceId`, `displayName`, `lastSeen`, `status`.

#### Scenario: Templates fixture
- **WHEN** the mock engine handles `GET /templates`,
- **THEN** it SHALL return JSON with a list of ≥ 1 policy templates, each with `templateId`, `name`, `policyJson`.

## Out of scope

- Real parent sign-up/sign-in (formal `parent-auth-flow`).
- Real Supabase backend integration (archived `supabase-backend-integration`).
- Removing the 5 TODOs in `ParentRepository.kt`.
- Token refresh logic; changes to the child pairing flow.

## Verification hooks

| Req | Test |
|---|---|
| 1 | `DeviceAuthManagerTest` — `authenticateOrCreate_persistsParentRole`, `..._persistsChildRole` |
| 2 | `OnboardingViewModelTest` — `parent_tap_triggers_auth_then_navigates`; Compose UI test for loading + failure |
| 3 (modified) | `NetworkModuleTest` — add `debug_buildtype_reads_useMockSupabase_from_localProperties`, `release_buildtype_ignores_localProperties_useMockSupabase` |
| 4 (modified) | `ParentViewModelTest` — add `authenticateAsParent_success_invokesLoadDevices`, `authenticateAsParent_failure_doesNotInvokeLoadDevices` |
| 5 | `MockSupabaseFixturesTest` — `devices_returnsAtLeastTwoChildDevices`, `..._templates_returnsAtLeastOne` |
| 6 | `DeviceAuthManagerMigrationTest` — `loadPersistedState_migrates_stale_PARENT_prefs`, `getParentId_returns_MOCK_PARENT_ID_after_migration`, plus 3 GREEN-PIN control cases (CHILD no-op, idempotent, no role no-op) |
