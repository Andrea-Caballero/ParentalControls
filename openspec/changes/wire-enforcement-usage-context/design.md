# Design: Wire Enforcement Usage Context

## Technical Approach

`EnforcementController` will start one process-lifetime collector that gates on `DeviceAuthManager.deviceId`, subscribes to the existing `LocalDataSource.getUsageContextFlow(deviceId, dateKey)`, caches the latest immutable `UsageContext`, and explicitly reevaluates the last foreground package after each distinct snapshot. `evaluateAndEnforce` remains synchronous and passes a locally captured cache value to `evaluar`; its trusted-time null check remains before RulesEngine invocation.

The date key is sourced from an injectable `usageDateFlowProvider: () -> Flow<String>` seam. The default seeds with `timeProvider.currentDate().toString()` (matching `MonitorForegroundService` and `UsageStatsReconciler`); production wires a stream that re-emits when the local date changes (e.g. an alarm-driven `SharedFlow`), tests inject a deterministic flow they control directly. The collector combines the identity flow with the date flow so the inner `getUsageContextFlow` resubscribes whenever EITHER changes. `LocalDataSource.getServerDate()` is not used.

## Architecture Decisions

| Decision | Alternatives / tradeoff | Rationale |
|---|---|---|
| Reuse `getUsageContextFlow` through an injectable `(deviceId, dateKey) -> Flow<UsageContext>` constructor dependency whose default delegates to `LocalDataSource(database)` | Inline DAO aggregation would improve control but duplicate existing logic; making evaluation suspend would break callers | Smallest wiring change, preserves units and synchronous evaluation, and provides a strict-TDD seam |
| Inject `usageDateFlowProvider: () -> Flow<String>` whose default seeds with `timeProvider.currentDate().toString()`; production wires a midnight-aware stream | Capture `currentDate()` once at `flatMapLatest` time | The current code captures the date once per identity, so after midnight enforcement continues observing yesterday's usage. A reactive date seam lets the controller resubscribe on every rollover while keeping `evaluateAndEnforce` synchronous |
| Cache `@Volatile currentUsage`, initialized to `UsageContext.empty()` | Query per evaluation; block while loading | An immutable reference gives synchronous, cross-thread visibility; empty preserves deterministic startup behavior |
| Use `combine(deviceId, dateFlow).distinctUntilChanged().flatMapLatest` and emit empty only when the identity itself is blank; never emit a synthetic empty on resubscription | Keep the old snapshot until Room emits; inject `.onStart { emit(empty) }` on every subscription | Blank identity cancels collection and clears the cache. A synthetic empty on every resubscription clears an otherwise-authoritative cache and forces a synchronous fail-open reevaluation; the deterministic empty-init plus `distinctUntilChanged` is sufficient |
| Apply `distinctUntilChanged`, then update cache before `forceReevaluation()` | Route through normal evaluation throttle | Each semantic usage change gets one explicit invalidation, including a limit crossing. Ordinary repeated foreground calls retain the one-second throttle; explicit usage/grant invalidations retain the existing intentional throttle bypass |

The `deviceId` argument is an authentication/lifecycle gate only: `usage_today` has no device column, and `getUsageContextFlow` also uses all app-policy rows for category mapping. This design does not claim device isolation.

## Data Flow and Lifecycle

```text
deviceId ─┐
          ├─combine(distinctUntilChanged)──> flatMapLatest ──> empty while unauthenticated
dateFlow ─┘                                  └─ authenticated ──> getUsageContextFlow(id, dateKey)
                                                              │ distinct snapshots
                                                              v
                                                      currentUsage (cache)
                                                              │
foreground event / forced invalidation ──> evaluateAndEnforce ──> RulesEngine
```

The collector starts exactly once from `init` and is owned by the controller's existing `SupervisorJob` scope. Production ownership matches the process singleton, so there is no shorter lifecycle to cancel. `combine + flatMapLatest` cancels the previous inner flow whenever EITHER the identity or the date changes, so a midnight rollover resubscribes to today's usage. An injected scope lets tests cancel all jobs deterministically. No evaluation writes usage, so reevaluation cannot feed back into the source flow.

The blank-identity `flowOf(empty)` is the only synthetic emission. When the inner flow re-subscribes for an authenticated identity, the cache holds the previous authoritative snapshot until the new provider emits; `distinctUntilChanged` filters equal snapshots so a re-collect of the same value does not re-run `forceReevaluation`. Snapshot assignment and forced reevaluation run sequentially on the controller scope; evaluation captures one reference. `@Volatile` covers external-thread calls. Distinct filtering limits duplicate decisions, while Room may still produce successive genuinely different aggregate states; those are evaluated in emission order.

## File Changes

| File | Action | Description |
|---|---|---|
| `app/src/main/java/com/tudominio/parentalcontrol/enforcement/EnforcementController.kt` | Modify | Add flow/scope seams, reactive date-flow seam, usage cache/collector, and pass the cached snapshot to RulesEngine |
| `app/src/test/java/com/tudominio/parentalcontrol/enforcement/EnforcementControllerUsageContextTest.kt` | Create | Robolectric coroutine regressions for startup, identity/date selection, limits, reactive reevaluation, and date rollover |
| `app/src/main/java/com/tudominio/parentalcontrol/data/local/LocalDataSource.kt` | Reuse unchanged | Existing app/category/global minute aggregation remains authoritative |

## Testing Strategy

Strict TDD starts with failing controller tests using `FakeTimeProvider`, injected auth/flow/date/scope, and decision-flow assertions. Cover: evaluation before first emission uses empty; app, category, and global totals at their limits block; a later emission changes allowed to blocked without policy refresh; duplicate snapshots do not reevaluate; blank identity does not invoke the provider; authenticated selection uses `currentDate().toString()`; identity change cancels/restarts collection; identity change does not synchronously clear the cache before the new provider emits; and a date-flow rollover re-invokes the provider with the new date key. Existing trusted-time tests must continue proving unavailable time fails closed.

Focused command: `./gradlew testDebugUnitTest --tests "com.tudominio.parentalcontrol.enforcement.EnforcementControllerUsageContextTest" --tests "com.tudominio.parentalcontrol.enforcement.EnforcementControllerTrustedTimeTest"`.

## Rollout, Rollback, and Safety

No migration or flag is required. Roll back only the collector/cache/seams and restore `UsageContext.empty()`.

Because `EnforcementController.kt` contains unrelated trusted-time/grant edits, implementation must capture its pre-change diff, apply narrow hunks only around constructor/scope, state, `init`, a new loader, and the RulesEngine argument, then verify the trusted-time/grant hunks are byte-preserved. Do not replace the file wholesale.

Deferred debt: the production-side midnight alarm that drives the date-flow seam, and true device-scoped usage/category persistence, require separate schema/lifecycle changes.

## Open Questions

None.
