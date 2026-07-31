# Design: Auth JWT Hardening

## Technical Approach

Make `supabase/functions/_shared/jwt.ts` the only verification boundary for auth-gated Edge Functions. Every in-scope function (`get-policy`, `heartbeat`, `register-token`, `approve-request`, `reward`, `verify-integrity`) will call `verifyAuth()` before any authorization, data access, or mutation, and will treat verified `user.id` as `auth.uid()` plus `app_metadata.device_id` as the only trusted device identity.

This slice is backend-first: no pairing, magic-link, or Android token-storage changes. The goal is fail-closed server enforcement, not client recovery.

## Architecture Decisions

### Decision: Centralize trust in shared JWT verification

| Choice | Tradeoff | Decision |
|---|---|---|
| Keep mixed inline auth checks | Lower short-term edits, but preserves inconsistent trust models | Rejected |
| Route all auth-gated functions through `verifyAuth()` | Slightly more touch points, but one boundary to harden and test | Chosen |

`verifyAuth()` already uses Supabase Auth server-side and reads `device_id` from verified `app_metadata`, so extending it is lower risk than inventing per-function auth logic.

### Decision: Fail closed on missing or unverified device identity

| Choice | Tradeoff | Decision |
|---|---|---|
| Allow legacy/no-device callers | Easier rollout, but weakens the boundary and reintroduces ambiguity | Rejected |
| Reject with 401/403 before handler logic | More explicit failures, but may surface latent client bugs | Chosen |

Missing/empty `device_id` is not “anonymous”; it is an invalid authenticated state for this change.

### Decision: Preserve claim sanitization in the hook, but do not trust top-level claims

| Choice | Tradeoff | Decision |
|---|---|---|
| Read top-level `device_id` directly | Matches JWT convenience, but trusts a forgeable surface | Rejected |
| Keep `custom-access-token-hook` re-deriving from `app_metadata.device_id` | Slightly more indirection, but keeps a single authoritative source | Chosen |

## Data Flow

`request → Edge Function → verifyAuth() → Supabase Auth user lookup → verified user/app_metadata → authz → business logic`

```text
Caller
  ↓ Bearer token
Edge Function
  ↓ verifyAuth()
Supabase Auth `getUser(token)`
  ↓ verified user
`user.id` + `app_metadata.device_id`
  ↓ fail closed if missing/unverified
Authorization / RPC / mutation
```

`custom-access-token-hook` remains the minting-side guard: it strips any stale top-level `device_id` and re-derives it from `app_metadata.device_id`, so the backend can rely on verified metadata instead of raw JWT payload shape.

## File Changes

| File | Action | Description |
|---|---|---|
| `supabase/functions/_shared/jwt.ts` | Modify | Tighten `verifyAuth()` contract and error mapping for device-required calls. |
| `supabase/functions/get-policy/index.ts` | Modify | Enforce shared verification before policy reads. |
| `supabase/functions/heartbeat/index.ts` | Modify | Fail closed for unauthenticated or device-less callers. |
| `supabase/functions/register-token/index.ts` | Modify | Require verified caller identity before token registration. |
| `supabase/functions/approve-request/index.ts` | Modify | Gate approval flow on verified identity/device identity. |
| `supabase/functions/reward/index.ts` | Modify | Gate reward actions on verified identity/device identity. |
| `supabase/functions/verify-integrity/index.ts` | Modify | Align caller authentication with the shared boundary. |
| `supabase/functions/custom-access-token-hook/index.ts` | Verify | Keep claim sanitization and device_id re-derivation behavior intact. |
| `supabase/functions/_shared/jwt_test.ts` | Modify | Add fail-closed and forged-claim coverage. |
| `supabase/functions/**/__tests__` or `*_test.ts` | Modify | Endpoint-level regression tests for representative auth-gated functions. |

## Interfaces / Contracts

- `verifyAuth(opts)` remains the single entrypoint.
- `VerifiedAuth.ok === true` means the caller is cryptographically verified; handlers must still reject missing device identity when the route is device-scoped.
- `parentId` is sourced from verified Supabase user identity (`auth.uid()`), not decoded JWT claims.
- `deviceId` is trusted only when derived from verified `app_metadata.device_id`.

## Testing Strategy

| Layer | What to Test | Approach |
|---|---|---|
| Unit | `verifyAuth()` and claim sanitization | Valid token, invalid/expired token, missing `device_id`, forged top-level `device_id`, non-string metadata. |
| Integration | Representative auth-gated functions | Ensure each function fails closed before business logic when identity/device data is absent or unverified. |
| E2E | Backend auth boundary behavior | No Android scope here; verify via Edge Function requests only. |

## Migration / Rollout

No migration required. Deploy the shared verifier and all affected Edge Function updates together so callers never see a mixed trust model. Android token/session changes are out of scope for this slice.

## Open Questions

- [ ] Are there any additional auth-gated Edge Functions or privileged RPC wrappers outside the representative set that must join this same boundary in the same release?
- [ ] Should `verify-integrity` be treated as strictly device-scoped everywhere, or only for the caller paths that mutate state?
