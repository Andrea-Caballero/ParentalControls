---
delta:
  spec: parent-auth-session
  type: ADDED
  ref: openspec/specs/parent-auth-session/spec.md
---

# Delta for parent-auth-session

Lifts the synthetic-anonymous parent path into real Supabase Auth via
**magic-link** (Q1=b design-gate decision). The password-based flow
described in earlier revisions was superseded at the design gate; this
spec reflects the shipped `signInWithMagicLink(email)` +
`verifyMagicLinkOtp(token, email)` API. Synthetic path stays for tests
+ mock-mode debug. Drops the `MOCK_PARENT_ID` lazy backfill from PR #28
as a clean cutover per Q2=b.

## ADDED Requirements

### Requirement: Parent sign-in starts a magic-link flow

`DeviceAuthManager.signInWithMagicLink(email)` SHALL POST
`${SUPABASE_URL}/auth/v1/magiclink` with
`{ email, create_user: true, gotrue_meta_security: { captcha_token: "" } }`
so Supabase dispatches the magic link to the parent's inbox. A
`Result.failure(ParentAuthError.InvalidEmail)` is returned for any
syntactically invalid email before any network round-trip
(`isValidEmail` short-circuit). A `Result.failure(ParentAuthError.Unknown)`
is returned on any 5xx / decoding / network error. The
`device_auth_prefs` namespace is NEVER touched on the failure path.

#### Scenario: Valid email submits and Supabase accepts

- **WHEN** `signInWithMagicLink(email)` is invoked with a syntactically
  valid email against real Supabase Auth,
- **THEN** Supabase returns `200 { message_id: "<uuid>" }`,
- **AND** the call returns `Result.success(MagicLinkSent(messageId))`,
- **AND** `device_auth_prefs` is unchanged.

#### Scenario: Invalid email format is rejected with sanitized error

- **WHEN** `signInWithMagicLink("not-an-email")` is invoked,
- **THEN** the call returns `Result.failure(ParentAuthError.InvalidEmail)`,
- **AND** no row is inserted into `auth.users`,
- **AND** no network call is made (fail-fast on the client).

#### Scenario: Invalid email surfaces an upstream 400 cleanly

- **WHEN** Supabase Auth returns `400 { error: "invalid_email" }`,
- **THEN** the call returns `Result.failure(ParentAuthError.InvalidEmail)`,
- **AND** `device_auth_prefs` is unchanged (no `parent_id`, no `role`,
  no `access_token` writes).

### Requirement: Magic-link OTP exchange authenticates against real Supabase Auth

`DeviceAuthManager.verifyMagicLinkOtp(tokenHash, email)` SHALL
authenticate the parent by POSTing
`${SUPABASE_URL}/auth/v1/verify?type=magiclink` with
`{ email, token: tokenHash }`. The Supabase response includes the
`access_token` JWT (whose `parent_id` claim was injected by the
custom-access-token-hook from `app_metadata.parent_id`) and a
`user.id` (= parentId). On success, the issued `ParentSession` is
written atomically to `device_auth_prefs` as a single
`encrypted_parent_session` blob (ciphered via `encryptWithKeystore`)
alongside the cleartext `role=PARENT` and `parent_id` keys.

#### Scenario: Valid token returns JWT with matching parent_id claim

- **GIVEN** `auth.users.id = "uuid-p"` for the parent,
- **WHEN** `verifyMagicLinkOtp(tokenHash, email)` is invoked,
- **THEN** the issued JWT carries a first-level `parent_id = "uuid-p"`
  claim,
- **AND** the `ParentSession.parentId` returned to the caller equals
  `"uuid-p"`,
- **AND** the session is atomically persisted to `device_auth_prefs`
  as `{ role: PARENT, parent_id: "uuid-p", encrypted_parent_session: <blob> }`.

#### Scenario: Expired or already-used token surfaces a 401

- **WHEN** Supabase Auth returns `401 { error: "otp_expired" }` for a
  token that has been used or has aged past its TTL,
- **THEN** `verifyMagicLinkOtp` returns
  `Result.failure(ParentAuthError.TokenExpired)`,
- **AND** no JWT, no `parent_id`, and no session data is exposed to
  the caller,
- **AND** `device_auth_prefs` is unchanged (atomic-prefs invariant).

#### Scenario: Malformed verify request surfaces a 422

- **WHEN** Supabase Auth returns `422` (e.g. malformed email / token
  shape),
- **THEN** `verifyMagicLinkOtp` returns
  `Result.failure(ParentAuthError.InvalidRequest)`,
- **AND** no session is persisted.

### Requirement: DeviceAuthManager exposes magic-link public API

The methods SHALL return `Result<...>` and SHALL persist the session
atomically to `device_auth_prefs` (single `encrypted_parent_session`
blob + cleartext `role` + `parent_id` keys). They SHALL coexist with
the child path's `authenticateOrCreate(role: Role)`.

Public API surface:

- `suspend fun signInWithMagicLink(email: String): Result<MagicLinkSent>`
  — Stage 1 of the magic-link flow. Validates the email format
  client-side, then POSTs `/auth/v1/magiclink`.
- `suspend fun verifyMagicLinkOtp(tokenHash: String, email: String):
  Result<ParentSession>` — Stage 2 of the magic-link flow. Exchanges
  the OTP for a real `ParentSession` (with the `parent_id` claim
  injected by the custom-access-token-hook) and persists it to disk.

#### Scenario: Successful verify persists ParentSession to device_auth_prefs

- **WHEN** `verifyMagicLinkOtp` returns `Result.success(ParentSession)`,
- **THEN** the session is written atomically to `device_auth_prefs`
  as `{ role: PARENT, parent_id, encrypted_parent_session: <ciphered JSON> }`,
- **AND** `loadPersistedState()` returns the same session without
  re-hitting Supabase Auth,
- **AND** the cleartext prefs NEVER contain `access_token` or
  `refresh_token` keys (WARNING-1 closure — W1).

#### Scenario: Failure leaves device_auth_prefs untouched

- **WHEN** `verifyMagicLinkOtp` returns `Result.failure(...)`,
- **THEN** no row in `device_auth_prefs` is written and existing
  prefs (if any) are preserved (atomic-prefs invariant).

### Requirement: Clean cutover — every existing install re-auths on next cold start

On cold start against `-PuseRealSupabase=true`, the system SHALL wipe
prefs containing `parent_id = "parent-demo"` (legacy `MOCK_PARENT_ID`
from PR #28) and SHALL route the parent to the sign-in screen. The
`MOCK_PARENT_ID` migration helper in `DeviceAuthManager.loadPersistedState`
is removed per Q2=b.

#### Scenario: Legacy parent-demo prefs are wiped on first cloud cold start

- **GIVEN** `device_auth_prefs` has `{ role: PARENT, parent_id: "parent-demo",
  synthetic_access_token }` from a pre-cloud install,
- **WHEN** the app cold-starts with `-PuseRealSupabase=true`,
- **THEN** legacy prefs are wiped
- **AND** `OnboardingScreen` shows the parent sign-in form
- **AND** no `parent_id = "parent-demo"` is exposed in any subsequent
  JWT or RLS query.

#### Scenario: Mock-mode debug build is unaffected

- **GIVEN** `BuildConfig.USE_MOCK_SUPABASE == true`,
- **WHEN** the app cold-starts with the same legacy prefs,
- **THEN** the legacy `parent-demo` prefs SHALL remain intact (cutover
  is cloud-mode only).
