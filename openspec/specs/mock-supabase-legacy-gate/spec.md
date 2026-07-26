# Spec: mock-supabase-legacy-gate

## Purpose

Closes the gap where the legacy `SupabaseClientProvider.getInstance(context)` accessor (used by 5 non-Hilt managers: `PairingManager`, `FcmPushService`, `RealtimeManager`, `SyncManager`, `PlayIntegrityManager`) builds an `HttpClient` backed by the real OkHttp engine even when `BuildConfig.USE_MOCK_SUPABASE == true`, producing `NETWORK_ERROR` against the placeholder Supabase URL. Aligns the legacy accessor with the Hilt-bound `@SupabaseClient` binding in `NetworkModule` so both paths select the mock or real engine from the same flag.

## Requirements

### Requirement: Legacy `getInstance()` selects engine from BuildConfig.USE_MOCK_SUPABASE

`SupabaseClientProvider.getInstance(context)` SHALL return a provider whose `httpClient` is backed by `MockSupabaseEngine(context).httpClient` when `BuildConfig.USE_MOCK_SUPABASE == true`, and SHALL return a provider whose `httpClient` is backed by the real OkHttp engine (placeholder `https://your-project.supabase.co`) when `BuildConfig.USE_MOCK_SUPABASE == false`.

#### Scenario: Flag true returns a mock-engine-backed provider
- GIVEN `BuildConfig.USE_MOCK_SUPABASE == true`
- WHEN `SupabaseClientProvider.getInstance(context)` is called
- THEN the returned provider's `httpClient` SHALL be a Ktor client whose engine is `MockSupabaseEngine`
- AND no `NetworkSecurityConfig.createSecureOkHttpClient(context)` call SHALL be issued during the `httpClient` lazy initialization.

#### Scenario: Flag false returns the real-engine-backed provider
- GIVEN `BuildConfig.USE_MOCK_SUPABASE == false`
- WHEN `SupabaseClientProvider.getInstance(context)` is called
- THEN the returned provider's `httpClient` SHALL be the real OkHttp engine bound to `SUPABASE_URL` (`https://your-project.supabase.co`)
- AND `MockSupabaseEngine` SHALL NOT be instantiated.

### Requirement: Release build is unaffected

The `release` build type SHALL hardcode `BuildConfig.USE_MOCK_SUPABASE` to `false` via `app/build.gradle.kts:71`, so a stale `local.properties` entry of `USE_MOCK_SUPABASE=true` SHALL NOT change release behavior. The legacy `getInstance()` SHALL return the real-engine-backed provider on release regardless of `local.properties`.

#### Scenario: Release variant defaults to the real client
- GIVEN the `release` build type is active
- AND `local.properties` contains `USE_MOCK_SUPABASE=true`
- WHEN `SupabaseClientProvider.getInstance(context)` is called
- THEN `BuildConfig.USE_MOCK_SUPABASE` SHALL be `false`
- AND the returned provider's `httpClient` SHALL be the real OkHttp engine
- AND `MockSupabaseEngine` SHALL NOT be instantiated.

### Requirement: Hilt injection path is unchanged

The Hilt-managed `SupabaseClientProvider` SHALL continue to be constructed by `RepositoryModule.provideSupabaseClientProvider` with the `@SupabaseClient` `HttpClient` binding from `NetworkModule`. The fix to the legacy path SHALL NOT alter `NetworkModule`, `RepositoryModule.provideSupabaseClientProvider`, or the `internal constructor` parameter resolution that prefers `injectedClient` when non-null.

#### Scenario: Hilt-injected provider still receives the @SupabaseClient binding
- GIVEN a Hilt-injected `SupabaseClientProvider` request (e.g., `ParentRepository`)
- WHEN the Hilt graph resolves the dependency
- THEN `RepositoryModule.provideSupabaseClientProvider` SHALL be invoked
- AND it SHALL pass the `@SupabaseClient httpClient: HttpClient` from `NetworkModule` as `injectedClient`
- AND the resulting provider's `httpClient` SHALL equal the `@SupabaseClient` binding (not a second lazy-initialized client).

#### Scenario: injectedClient short-circuits the engine-selection branch
- GIVEN the `internal constructor` is called with `injectedClient != null`
- WHEN the `httpClient` lazy initializer runs
- THEN the `BuildConfig.USE_MOCK_SUPABASE` branch SHALL NOT be evaluated by `SupabaseClientProvider` itself
- AND the returned `HttpClient` SHALL be the `injectedClient` reference.

### Requirement: Application context is captured safely by getInstance

`SupabaseClientProvider.getInstance(context)` SHALL normalize the incoming `context` to `context.applicationContext` before passing it to the `internal constructor`. The same singleton instance SHALL be returned for any `Context` (Activity, Application, Service) that resolves to the same application context within a process.

#### Scenario: Activity and application context return the same provider
- GIVEN `SupabaseClientProvider.getInstance(appContext)` was called first
- WHEN `SupabaseClientProvider.getInstance(activityContext)` is called next
- THEN the same singleton instance SHALL be returned
- AND its stored `context` SHALL be the `applicationContext` (not the activity).

#### Scenario: First caller wins regardless of context flavor
- GIVEN no prior call to `getInstance(...)` has been made
- WHEN `SupabaseClientProvider.getInstance(serviceContext)` is called
- THEN a new `SupabaseClientProvider` SHALL be constructed
- AND its `context` field SHALL be `serviceContext.applicationContext`.

## Verification hooks

| Req | Test |
|---|---|
| 1 | `SupabaseClientProviderTest` — flag-true returns mock, flag-false returns real. |
| 2 | `NetworkModuleTest.release_block_hardcodes_useMockSupabase_false` (existing) gates gradle wiring. |
| 3 | `ParentRepositoryTest` + `PairingManagerTest` regression; Hilt still passes `@SupabaseClient`. |
| 4 | `SupabaseClientProviderTest` — activity/service context normalizes to application context. |
