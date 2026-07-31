# Tasks: Wire Enforcement Usage Context

## Review Workload Forecast

| Field | Value |
|---|---|
| Estimated changed lines | Product: 45–75; tests: 120–180; artifacts: 0–10; total: 165–265 |
| 400-line budget risk | Low |
| Chained PRs recommended | No |
| Suggested split | One PR, with two autonomous behavior work units |
| Delivery strategy | ask-on-risk |
| Chain strategy | pending |

Decision needed before apply: No
Chained PRs recommended: No
Chain strategy: pending
400-line budget risk: Low

### Suggested Work Units

| Unit | Goal | Rollback boundary |
|---|---|---|
| 1 | Usage cache/selection and synchronous RulesEngine wiring, with startup, identity/date, and limit tests | Revert only the new usage seam/cache/argument and its tests |
| 2 | Reactive distinct-snapshot reevaluation, with emission and duplicate tests | Revert only collector/reevaluation logic and its tests |

## Phase 1: Safety and RED Tests

- [x] 1.1 Snapshot `git diff` for `app/src/main/java/com/tudominio/parentalcontrol/enforcement/EnforcementController.kt` to a review artifact; mark unrelated trusted-time/grant hunks as immutable. Do not clean, stash, reset, or revert dirty work.
- [x] 1.2 **RED/WU1:** Create `app/src/test/java/com/tudominio/parentalcontrol/enforcement/EnforcementControllerUsageContextTest.kt` with injected auth, flow, scope, and `FakeTimeProvider`; assert pre-emission empty behavior, first valid replacement, app limit, category/global limits, blank identity suppression, current-date selection, and identity restart.
- [x] 1.3 **RED/WU2:** Add failing assertions for a usage emission crossing the foreground app limit, reevaluation without policy refresh, and duplicate snapshots causing no second reevaluation.
- [x] 1.4 Run focused RED tests with `./gradlew --no-daemon --max-workers=1 testDebugUnitTest --tests "com.tudominio.parentalcontrol.enforcement.EnforcementControllerUsageContextTest"` and record the expected failures.

## Phase 2: GREEN Product Wiring

- [x] 2.1 **GREEN/WU1:** In `EnforcementController.kt`, add the injectable `(deviceId, dateKey) -> Flow<UsageContext>` seam, `@Volatile` empty-initialized cache, and process-lifetime collector using `deviceId.distinctUntilChanged().flatMapLatest`, resetting empty before authenticated collection.
- [x] 2.2 Pass a locally captured cached `UsageContext` to `evaluar` while keeping `evaluateAndEnforce` synchronous and trusted-time validation before RulesEngine invocation; preserve policy, grants, units, and time behavior.
- [x] 2.3 **GREEN/WU2:** Apply `distinctUntilChanged`, update the cache before `forceReevaluation()`, and reevaluate the last foreground package on each semantic emission through the existing scope.
- [x] 2.4 Run the focused usage-context and existing trusted-time classes with bounded workers; every RED assertion must pass.

## Phase 4: Remediation (R1 review findings)

- [x] 4.1 **RED/Remediation:** Add failing tests proving date rollover resubscribes to the new date key and that resubscription does not synchronously clear the authoritative usage cache before the new provider emits.
- [x] 4.2 **GREEN/Remediation:** Add `usageDateFlowProvider: () -> Flow<String>` seam (default seeds with `timeProvider.currentDate().toString()`) and switch `loadUsageContext` to `combine(deviceId, dateFlow).distinctUntilChanged().flatMapLatest`. Remove `.onStart { emit(empty) }` so resubscription never clears the cache before the provider's first emission.
- [x] 4.3 Update `design.md`, `specs/usage-aware-enforcement/spec.md`, and `proposal.md` so the reactive date seam and the removed synthetic-empty pattern are reflected, and the deferred-debt wording shifts from "midnight rollover" to "the production alarm that drives the date-flow seam".

## Phase 3: Refactor and Verification

- [x] 3.1 **REFACTOR:** Keep constructor seams testable, scope ownership deterministic, and production defaults delegating to `LocalDataSource.getUsageContextFlow`; avoid wholesale edits to `EnforcementController.kt`.
- [x] 3.2 Verify the snapshot against the pre-change diff: trusted-time/grant hunks remain byte-preserved, no unrelated dirty file is modified, and no midnight rollover or schema/device-isolation behavior is added.
- [x] 3.3 Run focused verification: `./gradlew --no-daemon --max-workers=1 testDebugUnitTest --tests "com.tudominio.parentalcontrol.enforcement.EnforcementControllerUsageContextTest" --tests "com.tudominio.parentalcontrol.enforcement.EnforcementControllerTrustedTimeTest"`.
- [x] 3.4 Run the final relevant suite with bounded resources: `./gradlew --no-daemon --max-workers=1 testDebugUnitTest`; retain intentional skipped-test results and distinguish unrelated baseline failures.
