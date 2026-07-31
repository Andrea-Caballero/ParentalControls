# Delta for time-request-approval

## MODIFIED Requirements

### Requirement: Child receives the verdict and enforcement applies it

On FCM receipt the child SHALL sync grants; `EnforcementController` SHALL pick up the new `grants` row from Room without an app restart. Validity is strict: active only while `now < expires_at`; equality at `expires_at` is expired.

(Previously: Expiry was "`expires_at` passes" with no equality boundary; enforcement mixed `TimeProvider` with a direct `Instant.now()` cutoff.)

#### Scenario: FCM triggers a grants sync

- **WHEN** the child receives an FCM of type `grant.approved`,
- **THEN** `FirebaseMessagingService` SHALL trigger `SyncManager.syncGrants()` which upserts the new `grants` row into Room.

#### Scenario: New grant is reflected in remaining time

- **WHEN** the next `EnforcementController` poll runs after the sync,
- **THEN** the new `grants` row SHALL be reflected in remaining screen time and the UI SHALL show the new allowance.

#### Scenario: Strict expiry boundary governs grant validity

- **GIVEN** a grant with `expires_at = T` and trusted current time `now`,
- **WHEN** `now < T`,
- **THEN** the grant SHALL be active;
- **AND WHEN** `now >= T` (equality at `T` is expired),
- **THEN** the grant SHALL be expired.

#### Scenario: Expired grant reverts to the pre-grant state

- **WHEN** the trusted current time reaches or passes `expires_at`,
- **THEN** `EnforcementController` SHALL revert to the pre-grant state using the strict `now < expires_at` cutoff from Room (`cleanup_expired_grants` remains a background concern).

## ADDED Requirements

### Requirement: Enforcement derives grant validity from a single trusted time source

Enforcement MUST evaluate validity against one authoritative trusted-time value and MUST NOT let a wall-clock rollback extend or revive a grant.

#### Scenario: Wall-clock rollback cannot extend an active grant

- **GIVEN** an active grant with `expires_at = T` and last-observed trusted time `t_last < T`,
- **WHEN** the raw system clock is moved back before `t_last`,
- **THEN** enforcement SHALL use the trusted time and SHALL NOT extend the grant beyond `T`.

#### Scenario: Wall-clock rollback cannot revive an expired grant

- **GIVEN** a grant whose trusted time already reached `expires_at`,
- **WHEN** the raw system clock is moved back before `expires_at`,
- **THEN** the grant SHALL stay expired and SHALL NOT reactivate.

### Requirement: Enforcement fails closed when trusted time is unverified after reboot

After reboot, the system MUST treat trusted time as unverified until the provider confirms a value. While unverified, enforcement MUST fail closed: no grant is active.

#### Scenario: Unverified trusted time after reboot blocks all grants

- **GIVEN** the device just rebooted and the trusted-time provider has not confirmed a value,
- **WHEN** `EnforcementController` evaluates active grants,
- **THEN** it SHALL treat no grant as active and SHALL enforce the pre-grant (blocked) state.

#### Scenario: Unavailable trusted-time provider fails closed

- **GIVEN** the trusted-time provider cannot supply verified current time,
- **WHEN** grant validity is evaluated,
- **THEN** enforcement SHALL fail closed and SHALL NOT fall back to the raw system clock.

### Requirement: Still-valid grants reactivate when trusted time returns

Once the provider confirms a value, the system MUST re-evaluate grants against the restored trusted time. A grant still valid (`now < expires_at`) MUST reactivate automatically, with no app restart or manual action.

#### Scenario: Still-valid grant reactivates on trusted-time recovery

- **GIVEN** a grant was active before trusted time was lost and `expires_at` has not been reached,
- **WHEN** the trusted-time provider confirms `now < expires_at`,
- **THEN** `EnforcementController` SHALL reactivate the grant automatically on its next evaluation.

#### Scenario: Grant that genuinely expired during the gap stays expired

- **GIVEN** the grant's true `expires_at` was reached while trusted time was unavailable,
- **WHEN** trusted time returns with `now >= expires_at`,
- **THEN** the grant SHALL stay expired and SHALL NOT reactivate.

## Out of scope

- `ChildStatusViewModel` auth-state reactivity (separate snapshot bug).
- Selecting/persisting a trusted-time backend or sync protocol (design responsibility).
- Grant approval, server-side `cleanup_expired_grants`, and unrelated policy rules.
