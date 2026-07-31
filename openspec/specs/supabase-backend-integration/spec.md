# Spec: supabase-backend-integration

## Purpose
Tech-agnostic acceptance criteria for 5 stubbed remote-backend integration methods in `ParentRepository.kt`. No SDK, protocol, or wire format is prescribed; each Requirement is for a future implementation change.

## ADDED Requirements

### Requirement: Policy template fetch returns the parent's templates from the remote store

Templates SHALL be fetched from the remote policy-template store associated with the authenticated parent. Empty result is valid. On unreachable or rejected remote store, the method SHALL signal an error; partial results SHALL NOT be returned.

#### Scenario: Authenticated parent has one or more templates
- **WHEN** `getTemplates()` is called for an authenticated parent whose remote store has at least one template,
- **THEN** the method SHALL return a non-empty list of templates available to that parent,
- **AND** the returned templates SHALL be in stable order across calls.

#### Scenario: Authenticated parent has no templates
- **WHEN** `getTemplates()` is called for an authenticated parent whose remote store has zero templates,
- **THEN** the method SHALL return an empty list (not null, not an error).

#### Scenario: Remote store is unreachable
- **WHEN** `getTemplates()` is called and the remote policy-template store cannot be reached,
- **THEN** the method SHALL signal an error to the caller,
- **AND** no partial list SHALL be returned.

### Requirement: Granting reward minutes persists the grant via the remote service

Reward minutes SHALL be persisted via the remote service; success SHALL NOT be reported unless the remote service confirms persistence.

#### Scenario: Successful grant
- **WHEN** `grantReward(deviceId, minutes, reason)` is called for an existing target device with positive minutes,
- **AND** the remote service confirms persistence,
- **THEN** the method SHALL return `true`.

#### Scenario: Remote service rejects the grant
- **WHEN** `grantReward(...)` is called and the remote service reports a failure,
- **THEN** the method SHALL signal an error to the caller,
- **AND** the caller SHALL NOT observe a successful grant.

### Requirement: Applying a policy template mutates the target device's state via the remote service

Applying a policy template SHALL cause the target device's policy to be replaced with the template's contents via the remote service. Success SHALL NOT be reported unless the remote service confirms the change.

#### Scenario: Successful apply
- **WHEN** `applyTemplate(deviceId, templateId)` is called with a valid template and an existing target device,
- **AND** the remote service confirms the policy replacement,
- **THEN** the method SHALL return `true`.

#### Scenario: Apply fails
- **WHEN** `applyTemplate(...)` is called and the remote service reports a failure,
- **THEN** the method SHALL signal an error to the caller,
- **AND** the target device's prior policy SHALL remain unchanged (rollback semantics).

### Requirement: Device-state mutations (lock and unlock) take effect via the remote service

`lockDevice` and `unlockDevice` SHALL change the target device's lock state via the remote service. Success SHALL NOT be reported unless the remote service confirms the new state.

#### Scenario: Lock device succeeds
- **WHEN** `lockDevice(deviceId)` is called for an existing target device,
- **AND** the remote service confirms the locked state,
- **THEN** the method SHALL return `true`.

#### Scenario: Unlock device succeeds
- **WHEN** `unlockDevice(deviceId)` is called for an existing locked target device,
- **AND** the remote service confirms the unlocked state,
- **THEN** the method SHALL return `true`.

#### Scenario: State mutation fails
- **WHEN** `lockDevice(...)` or `unlockDevice(...)` is called and the remote service reports a failure,
- **THEN** the method SHALL signal an error to the caller,
- **AND** the target device's prior state SHALL remain unchanged (rollback semantics).

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

## Out of scope
- Implementation choices (SDK, protocol, encoding); secrets, env config, network layer setup.
- Removing the 5 TODO comments from `ParentRepository.kt` (follow-up cleanup change).
- Authentication flow itself (assumed: parent is authenticated before any method is called).

## Verification hooks
- Future unit tests exercise each scenario with stubbed remote responses; `**WHEN**` → test name, `**THEN**` → assertion (return value or thrown error).
