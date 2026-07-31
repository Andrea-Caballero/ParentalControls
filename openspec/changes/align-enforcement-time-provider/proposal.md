# Proposal: Align Enforcement with a Trusted Time Provider

## Intent

Make grant enforcement use one authoritative, testable time contract. `EnforcementController` currently mixes the injected `TimeProvider` with a direct system-clock read, allowing unsafe decisions after clock rollback, reboot, or loss of trusted time.

## Scope

### In Scope

- Define strict validity: active only while `now < expires_at`; equality is expired.
- Ensure wall-clock rollback cannot extend a grant.
- After reboot, block grants when trusted current time cannot be verified; automatically reactivate still-valid grants when trust returns.
- Add deterministic coverage for boundary, rollback, reboot, recovery, and expiry.

### Out of Scope

- The separate `ChildStatusViewModel` auth-state snapshot bug.
- A concrete trusted-time backend or synchronization protocol; selection and persistence are design responsibilities.
- Grant approval, server cleanup, or unrelated policy rules.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `time-request-approval`: strengthen the child grant-enforcement contract for strict expiry, clock rollback, trusted-time loss after reboot, fail-closed blocking, and automatic recovery.

## Approach

Extend `TimeProvider`, rather than making a one-line substitution. Design must define trusted-time representation, reboot continuity, invalidation on rollback/unavailability, and the enforcement-facing API without selecting a backend here. `EnforcementController` will use it for active-grant queries and reevaluation; Room keeps the strict `expires_at > now` cutoff. Tests will drive a fake provider through each transition.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `time/TimeProvider.kt` | Modified | Trusted-time state and rollback/reboot semantics. |
| `enforcement/EnforcementController.kt` | Modified | Fail-closed grant enforcement and automatic reactivation. |
| `data/db/GrantDao.kt` | Verified/possibly modified | Preserve strict cutoff. |
| `time-request-approval` delta and enforcement tests | Modified/New | Specify and prove lifecycle rules. |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Trusted-time design expands beyond the original fix | High | Resolve the provider contract and transitions in design first. |
| Reboot recovery creates an enforcement gap | Med | Fail closed by default; test every transition with controlled time. |
| Timestamp comparison is format-sensitive | Med | Retain canonical UTC/ISO-8601 fixtures and DAO contract. |

## Rollback Plan

Revert the implementation and spec delta together; this restores prior behavior and its documented clock-manipulation risk.

## Dependencies

- A design decision for trusted-time acquisition and reboot continuity.

## Success Criteria

- [ ] All five approved rules are requirements with deterministic scenarios.
- [ ] Rollback cannot extend grants; unavailable trusted time blocks them.
- [ ] Still-valid grants reactivate when trusted time returns.
