# Design: Align Enforcement with a Trusted Time Provider

## Technical Approach

Add a grant-specific trusted-time state machine to the existing `TimeProvider`; do not redefine `wallInstant()` as trusted because its current consumers still use ordinary device wall time. `SyncManager.pullPolicy()` will confirm the authenticated `server_time` already returned by the HTTPS `get-policy` edge function. `DefaultTimeProvider` anchors that instant to `SystemClock.elapsedRealtime()`, and `EnforcementController` uses only this anchored time for grant queries. No trusted value means an empty active-grant set.

## Architecture Decisions

| Decision | Alternatives considered | Rationale |
|---|---|---|
| Represent trust as `Unavailable` or `Available(anchorInstant, anchorElapsedMs)` and expose it as `StateFlow` plus `trustedNow(): Instant?` | Nullable wall time; boolean `isServerTimeActive` | Availability is explicit and observable. Current time is `anchorInstant + (elapsedRealtime - anchorElapsedMs)`, independent of user-adjustable wall time. |
| Accept `server_time` only from a successful, authenticated `get-policy` response | Device wall time; Android network time; new time endpoint | Reuses the existing backend contract and avoids a new protocol. Android's own documentation warns network time is unsuitable for security decisions and may be out of order. Missing/malformed server time does not establish trust. |
| Clamp every accepted anchor to `max(incoming, currentTrustedNow)` | Replace the anchor unconditionally | Trusted time cannot move backward within the process/boot session; rollback may expire early after a bad forward value but cannot extend or revive a grant. |
| Keep anchors in memory only and start `Unavailable` after process start | DataStore/Room or encrypted anchor persistence | Process restart and reboot fail closed until the next policy sync. Persistence could improve offline availability but requires tamper-resistant replay protection and a trustworthy boot identity; encryption alone does not prevent rollback. |
| Reuse the Hilt singleton `TimeProvider` through a small entry point in legacy `EnforcementController.getInstance()` | Convert the controller to Hilt; construct another provider | Ensures `SyncManager` confirmation reaches enforcement without a broad lifecycle refactor. |

## Data Flow

```text
authenticated get-policy(server_time)
  -> SyncManager.confirmTrustedTime()
  -> TimeProvider Available(anchor + elapsedRealtime delta)
  -> EnforcementController re-queries Room(expires_at > trustedNow)
  -> policy grants updated -> forceReevaluation()
Unavailable ---------------------------------------> empty grants / fail closed
```

`EnforcementController` combines device ID, trusted-time state, and an internal reevaluation generation. On `Unavailable`, it cancels any expiry job, publishes no grants, and reevaluates. On `Available`, it queries with canonical UTC `Instant.toString()`. It schedules one coroutine for the nearest returned expiry; at the boundary it increments the generation, causing a new Room query. Therefore `expires_at > now` excludes equality. A new trusted anchor, including recovery, also re-queries and automatically reactivates only grants where `now < expires_at`.

## Interfaces / Contracts

```kotlin
sealed interface TrustedTimeState {
    data object Unavailable : TrustedTimeState
    data class Available(val anchor: Instant, val anchorElapsedMs: Long) : TrustedTimeState
}

interface TimeProvider {
    val trustedTimeState: StateFlow<TrustedTimeState>
    fun trustedNow(): Instant?
    fun confirmTrustedTime(serverTime: Instant)
}
```

`confirmTrustedTime` is thread-safe. If elapsed time regresses, `trustedNow()` atomically transitions to `Unavailable`. Grant timestamps remain canonical UTC ISO-8601 strings; changing storage representation is out of scope.

## File Changes

| File | Action | Description |
|---|---|---|
| `app/src/main/java/com/tudominio/parentalcontrol/time/TimeProvider.kt` | Modify | Add trusted state, monotonic anchoring, rollback clamp, and fake transitions. |
| `app/src/main/java/com/tudominio/parentalcontrol/sync/SyncManager.kt` | Modify | Inject the provider and confirm valid `server_time`; retain unrelated offset behavior. |
| `app/src/main/java/com/tudominio/parentalcontrol/enforcement/EnforcementController.kt` | Modify | Share the Hilt provider, fail closed, re-query on state/boundary changes, and force reevaluation. |
| `app/src/test/java/com/tudominio/parentalcontrol/time/TimeProviderTest.kt` | Modify | State-machine tests. |
| `app/src/test/java/com/tudominio/parentalcontrol/sync/SyncManagerTrustedTimeTest.kt` | Create | Verify authenticated server-time confirmation with Ktor MockEngine. |
| `app/src/test/java/com/tudominio/parentalcontrol/enforcement/EnforcementControllerTrustedTimeTest.kt` | Create | Deterministic Room/controller lifecycle tests. |
| `app/src/test/java/com/tudominio/parentalcontrol/data/db/GrantDaoIsolationTest.kt` | Modify | Pin strict equality and canonical UTC cutoff. |

## Testing Strategy

Strict TDD order is RED provider tests, GREEN state machine; RED DAO/controller tests, GREEN enforcement; then refactor. `FakeTimeProvider` explicitly supports confirm, wall rollback, trust loss, reboot, and monotonic advance. Robolectric uses in-memory Room, direct executors, and `runTest`/`TestDispatcher`, so expiry jobs advance virtually. Cases cover before/equal/after expiry, rollback of active and expired grants, cold start/reboot fail-closed, unavailable state, recovery reactivation, and recovery after genuine expiry. A Ktor MockEngine test verifies `server_time` confirmation and missing-time fail-closed behavior.

## Persistence, Security, and Non-goals

The server response is trusted because it is produced after JWT verification and transported over configured HTTPS; ordinary device wall time is never a fallback. This change does not add an NTP protocol, persist anchors, alter grant approval/cleanup, fix `ChildStatusViewModel`, or refactor other wall-time consumers.

## Delivery Forecast

Estimated implementation: **320-430 changed lines**. **400-line budget risk: Medium**; tests and legacy singleton wiring can cross the limit. With `ask-always`, delivery strategy must be confirmed before apply; if task planning remains above 400 lines, split provider/sync anchoring from controller/DAO enforcement.

## Open Questions

None.
