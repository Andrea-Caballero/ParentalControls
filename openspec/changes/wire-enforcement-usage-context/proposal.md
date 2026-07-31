# Proposal: Wire Enforcement Usage Context

## Intent

Daily-limit enforcement currently calls the RulesEngine with `UsageContext.empty()`. As a result, per-app, per-category, and global daily limits can be evaluated against zero instead of the usage already aggregated in Room, allowing children to exceed configured limits. This change makes enforcement consume real usage and react when that usage changes.

## Scope

### In Scope
- Feed the existing daily usage aggregation into RulesEngine enforcement.
- Cache the latest usage snapshot so `evaluateAndEnforce` remains synchronous.
- Trigger enforcement reevaluation when the usage aggregation emits.
- Preserve minute-based units, current policy semantics, and existing time/trust behavior.

### Out of Scope
- Device-scoping or schema changes for `usage_today`.
- Production scheduling of the midnight date-rollover signal (the controller consumes an injectable date-flow seam; how the seam is driven at runtime is owned outside this change).
- FCM, TLS, accessibility, or unrelated audit blockers.
- Changes to limit calculations, precedence, grants, or trusted-time policy.

## Capabilities

### New Capabilities
- `usage-aware-enforcement`: Enforcement evaluates app, category, and global daily limits against the latest persisted daily usage and reevaluates when that usage changes.

### Modified Capabilities
None.

## Approach

Background-collect `LocalDataSource.getUsageContextFlow` for the active device and current local date, cache its latest `UsageContext`, and request reevaluation on emissions. Pass that snapshot to RulesEngine while retaining the synchronous `evaluateAndEnforce` contract. Exact collector lifecycle, initialization, and failure handling remain provisional for design.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `app/src/main/.../enforcement/EnforcementController.kt` | Modified | Collect, cache, and apply real usage; reevaluate on emissions. |
| `app/src/main/.../data/local/LocalDataSource.kt` | Reused | Existing aggregation remains the source of usage context. |
| `app/src/test/.../enforcement/` | Modified | Cover real-usage limit decisions and reactive reevaluation. |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| First evaluation precedes the initial usage emission | Medium | Define safe initialization behavior in design and verify startup coverage. |
| Collector continues using the previous date after midnight | Medium | Add an injectable date-flow seam so production can drive rollover; tests use a deterministic flow. The production alarm that drives the seam is owned outside this change. |
| Existing aggregation is not device-scoped | Medium | Preserve current schema behavior; do not imply stronger isolation. |

## Rollback Plan

Remove the usage collector/cache and restore the prior empty-context argument without changing policy, time, trust, or persistence behavior.

## Dependencies

- Existing `LocalDataSource.getUsageContextFlow` aggregation and Room usage data.
- Existing enforcement reevaluation mechanism.

## Success Criteria

- [ ] App, category, and global daily limits evaluate against persisted minute totals rather than zero.
- [ ] Usage emissions trigger enforcement reevaluation without making `evaluateAndEnforce` asynchronous.
- [ ] Existing policy, unit, time, and trust semantics remain unchanged.
