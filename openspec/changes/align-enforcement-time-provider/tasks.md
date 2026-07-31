# Tasks: Align Enforcement with a Trusted Time Provider

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | 380–460 (implementation plus deterministic tests) |
| 400-line budget risk | High — the design range crosses the budget; controller wiring and tests are the main variance |
| Chained PRs recommended | Yes |
| Suggested split | PR 1: trusted-time provider and sync anchoring → PR 2: enforcement/DAO integration and lifecycle tests |
| Delivery strategy | ask-always (received) |
| Chain strategy | feature-branch-chain |

Decision needed before apply: No
Chained PRs recommended: Yes
Chain strategy: feature-branch-chain
400-line budget risk: High

### Suggested Work Units

| Unit | Goal | Likely PR | Notes |
|------|------|-----------|-------|
| 1 | Add trusted-time state and authenticated policy confirmation | PR 1 | Tests travel with provider/sync; independently verifies anchoring and fail-closed trust acquisition. |
| 2 | Enforce trusted-time grant lifecycle | PR 2 | Depends on PR 1; tests travel with controller/DAO behavior; preserve intentional dirty controller changes. |

## Phase 1: Trusted-Time Foundation (PR 1)

- [x] 1.1 RED: Extend `TimeProviderTest.kt` with failing tests for `Unavailable` startup, monotonic anchoring, clamping, elapsed-time progression, elapsed regression invalidation, and fake trust-loss/recovery/reboot transitions.
- [x] 1.2 GREEN: Modify `time/TimeProvider.kt` with `TrustedTimeState`, `StateFlow`, `trustedNow()`, thread-safe `confirmTrustedTime()`, monotonic anchors, clamp, and matching `FakeTimeProvider` controls without changing ordinary wall-time consumers.
- [x] 1.3 RED/GREEN: Inject `TimeProvider` into `sync/SyncManager.kt`; add `SyncManagerTrustedTimeTest.kt` using Ktor MockEngine to confirm only successful authenticated `server_time`, while missing/malformed/unavailable values remain fail-closed and existing offset behavior remains intact.
- [x] 1.4 Verify PR 1 with focused provider and sync tests, lint/type checks as available, and a diff limited to provider/sync plus their tests.

## Phase 2: Enforcement Integration (PR 2)

- [x] 2.1 RED: Add `EnforcementControllerTrustedTimeTest.kt` scenarios for strict before/equal/after expiry, active/expired rollback, unavailable/reboot fail-closed, recovery reactivation, and recovery after genuine expiry; use in-memory Room and virtual time.
- [x] 2.2 GREEN: Modify `enforcement/EnforcementController.kt` to share the Hilt singleton through its legacy entry point, query only with `trustedNow()`, cancel/publish empty grants when unavailable, re-query on trust/boundary generations, and schedule strict expiry reevaluation. Reconcile around existing intentional dirty changes; do not overwrite them.
- [x] 2.3 RED/GREEN: Update `data/db/GrantDao.kt` only if required and extend `GrantDaoIsolationTest.kt` to pin canonical UTC `expires_at > now` behavior, including equality exclusion and device isolation.
- [x] 2.4 Verify PR 2 against all specified lifecycle scenarios and inspect the diff for unrelated changes; rollback must remove only this unit’s controller/DAO behavior and tests.

## Phase 3: Scope and Delivery Gate

- [x] 3.1 Confirm no changes to `ChildStatusViewModel`, trusted-time backend/persistence protocol, approval, cleanup, or unrelated policy rules.
- [x] 3.2 Before `sdd-apply`, obtain the user’s chain strategy (`stacked-to-main`, `feature-branch-chain`, or approved `size-exception`); do not start oversized implementation while strategy is pending.
