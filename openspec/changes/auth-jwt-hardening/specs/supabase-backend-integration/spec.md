# Delta for supabase-backend-integration

## ADDED Requirements

### Requirement: Auth-gated backend calls fail closed without verified device identity

Every auth-gated Supabase backend operation in this slice MUST validate a verified `auth.uid()` and a verified `app_metadata.device_id` before any authorization check, data access, or mutation. Missing, malformed, expired, forged, or unverified identity data MUST be rejected as an authentication error.

#### Scenario: Verified session is accepted
- **GIVEN** an authenticated caller with a valid Supabase session
- **AND** the token resolves to a verified `auth.uid()` and verified `app_metadata.device_id`
- **WHEN** the backend operation is invoked
- **THEN** the request MUST proceed to authorization

#### Scenario: Missing device identity is rejected
- **GIVEN** an authenticated caller whose token lacks a verified `device_id`
- **WHEN** the backend operation is invoked
- **THEN** the request MUST fail closed with an authentication error
- **AND** no data access or mutation MUST occur

## MODIFIED Requirements

### Requirement: Privileged Supabase RPCs are service-only

The system MUST expose privileged or internal Supabase RPCs only through the service boundary. API roles MUST be denied direct EXECUTE access, SECURITY DEFINER functions MUST use a pinned search path, and the service boundary MUST reject any caller whose identity is not verified through the shared JWT boundary before invoking privileged helpers.

#### Scenario: Service role can call guarded RPCs
- **GIVEN** a caller operating through the service boundary
- **WHEN** it invokes a privileged RPC
- **THEN** the call MUST succeed only if the authorization checks pass
- **AND** the function search path MUST remain pinned
- **AND** the caller MUST have verified `auth.uid()` and verified `app_metadata.device_id`

#### Scenario: API role is blocked from direct access
- **GIVEN** a public API role
- **WHEN** it attempts to invoke the same RPC directly
- **THEN** the call MUST be rejected
- **AND** no internal helper MUST be reachable

### Requirement: Parent-facing check-in RPCs honor caller identity and RLS

Parent-facing check-in RPCs MUST validate the authenticated caller, MUST rely on row-level security for ownership checks, and MUST reject any request whose `device_id` is absent or unverified. A caller MUST NOT check in or mutate a device owned by another parent.
(Previously: Parent-facing check-in RPCs validated the authenticated caller and relied on row-level security for ownership checks.)

#### Scenario: Parent checks in own device
- **GIVEN** an authenticated parent and their own device row
- **WHEN** the check-in RPC is called
- **THEN** the request MUST succeed only for that owned device

#### Scenario: Cross-parent check-in is rejected
- **GIVEN** an authenticated parent and a device owned by another parent
- **WHEN** the check-in RPC is called for that device
- **THEN** the request MUST be rejected

#### Scenario: Unverified device identity is rejected
- **GIVEN** an authenticated parent whose session lacks a verified `device_id`
- **WHEN** the check-in RPC is called
- **THEN** the request MUST be rejected before any row is mutated
