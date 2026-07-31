# Proposal: Auth JWT Hardening

## Intent

Make the Supabase Edge Function layer enforce one trustworthy authentication model. The backend is the security boundary; sessions or tokens without a verified `device_id` must fail closed rather than relying on client-controlled claims or legacy verification paths.

## Scope

### In Scope
- Standardize privileged backend functions on the shared JWT verification boundary.
- Require verified `auth.uid()` and `app_metadata.device_id` for device-scoped operations.
- Remove or reject inline, legacy, or claim-trust verification behavior; malformed or missing identity data is a hard error.
- Add focused tests for valid tokens, forged/malformed claims, missing `device_id`, expired/invalid tokens, and cross-device authorization.

### Out of Scope
- Android session storage, refresh, or token UX changes.
- Pairing, magic-link, OTP, or deep-link flow changes; these may consume the hardened boundary in a later slice.
- Legacy compatibility concessions for callers that cannot provide verified identity data.

## Capabilities

### New Capabilities
None. This change hardens existing authentication behavior.

### Modified Capabilities
- `supabase-backend-integration`: privileged and parent-facing backend operations must use verified caller identity and fail closed when `device_id` is absent or unverified.

## Approach

Use `supabase/functions/_shared/jwt.ts` as the sole verification contract. Audit each auth-gated function, route it through the shared verifier, validate verified claims before authorization, and preserve RLS/service-boundary protections. Treat missing or inconsistent `device_id` as an explicit authentication error, not as an anonymous or legacy fallback.

## Affected Areas

| Area | Impact | Description |
|---|---|---|
| `supabase/functions/_shared/jwt.ts` | Modified | Define strict verified identity and error behavior. |
| `supabase/functions/*.ts` auth-gated functions | Modified | Normalize verification and device authorization. |
| `supabase/functions/custom-access-token-hook/index.ts` | Modified/verified | Preserve sanitized, server-derived claims. |
| `supabase/functions/**/**test*` | New/Modified | Cover fail-closed authentication scenarios. |

## Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| Existing clients or functions lack verified `device_id` | High | Inventory callers first; return explicit errors and document required identity. |
| Incomplete audit leaves mixed trust models | Medium | Enforce shared-helper usage and add endpoint-level regression tests. |

## Rollback Plan

Revert the change commit and its tests, restoring the prior verifier behavior. Do not re-enable individual legacy bypasses; rollback is all-or-nothing at the backend boundary.

## Dependencies

- Supabase Auth `auth.getUser(token)` and verified `app_metadata.device_id`.
- Existing service-boundary and RLS protections.
- Pairing/magic-link remain unchanged and are not implementation dependencies for this slice.

## Success Criteria

- [ ] Every in-scope auth-gated function uses the shared verifier.
- [ ] Any missing, malformed, forged, expired, or unverified `device_id` is rejected before data access or mutation.
- [ ] Valid verified sessions retain authorized behavior, while cross-device access remains denied.
- [ ] Backend authentication tests pass without legacy compatibility exceptions.
