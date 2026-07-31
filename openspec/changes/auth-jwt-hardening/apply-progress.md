# Apply Progress: Auth JWT Hardening (PR 1)

## Slice

- **Work unit**: PR 1 — shared verifier + hook sanitization + initial tests
- **Chain strategy**: feature-branch-chain
- **PR boundary**: Only the shared auth boundary. The endpoint audit (`get-policy`, `heartbeat`, `register-token`, `approve-request`, `reward`, `verify-integrity`) is reserved for PR 2.
- **Rollback boundary**: revert the PR 1 commit, restoring the prior 401/403 contract (no `WWW-Authenticate` header) and the prior 16-test / 12-test suites. No endpoint behavior is touched in PR 1, so rollback is bounded to the shared verifier + hook.

## Discovery Summary

- The existing `supabase/functions/_shared/jwt.ts` and `supabase/functions/custom-access-token-hook/index.ts` already enforce the trust boundary: the verifier uses `supabase.auth.getUser(token)` (server-side cryptographic check), deduces `parentId` from `user.id` (never from the JWT `sub`), and reads `deviceId` from `user.app_metadata.device_id` (never from a top-level JWT claim). The hook strips the top-level `device_id` and re-derives it from `app_metadata.device_id`.
- The existing test suites (16 + 12 = 28 tests) already cover most of the spec scenarios. What PR 1 added is:
  1. New RFC 7235 `WWW-Authenticate` plumbing on 401 / 403 (real new behavior).
  2. Edge-case triangulation for `app_metadata: null`, `app_metadata: []`, multi-key `app_metadata`, and whitespace-only Bearer tokens.
  3. Edge-case triangulation for the hook: `app_metadata: null`, `app_metadata: {}`, non-object `app_metadata`, `app_metadata` array, and combined `user_metadata.device_id` + draft `device_id` + `app_metadata.device_id` events.
- Safety-net baseline (before any edits): 16/16 jwt tests + 12/12 hook tests passing. No pre-existing failures.

## Completed Tasks

- [x] **1.1** Add RED tests in `supabase/functions/_shared/jwt_test.ts` for missing/forged/expired tokens and missing `app_metadata.device_id`.
- [x] **1.2** Tighten `supabase/functions/_shared/jwt.ts` so `verifyAuth()` fails closed on absent/invalid verified device identity and returns `auth.uid()` + verified `device_id` only.
- [x] **1.3** Add RED tests in `supabase/functions/custom-access-token-hook/index_test.ts` for claim sanitization and top-level `device_id` stripping.
- [x] **1.4** Keep `supabase/functions/custom-access-token-hook/index.ts` deriving `device_id` only from verified `app_metadata`.

## Files Changed

| File | Action | Description |
|---|---|---|
| `supabase/functions/_shared/jwt.ts` | Modified | Added `WWW-Authenticate: Bearer` header to 401 (Bearer realm) and 403 (`insufficient_scope`) responses per RFC 7235 §4.1; updated file header + `verifyAuth` docstring to document the new contract. |
| `supabase/functions/_shared/jwt_test.ts` | Modified | Added 7 new RED/GREEN tests: 401 carries `WWW-Authenticate`, 403 carries `insufficient_scope`, `app_metadata: null` returns `deviceId=null`, `app_metadata` as array returns `deviceId=null`, multi-key `app_metadata` extracts `device_id` correctly, `requireDevice:true` happy path with verified `auth.uid()` + verified `device_id`, whitespace-only Bearer token rejected without fetch. |
| `supabase/functions/custom-access-token-hook/index_test.ts` | Modified | Added 7 new triangulation tests: `app_metadata: null` omits top-level device_id, `app_metadata: {}` omits top-level device_id, non-object `app_metadata` throws `INVALID_EVENT`, `app_metadata` array throws `INVALID_EVENT`, top-level device_id stripped even when `user_metadata.device_id` is also present, paired event re-derives from `app_metadata` when `user_metadata.device_id` is present, all non-device_id claims round-trip unchanged. |
| `openspec/changes/auth-jwt-hardening/tasks.md` | Modified | Marked Phase 1 (1.1, 1.2, 1.3, 1.4) as `[x]`. Set `Chain strategy: feature-branch-chain` (was `pending`). |

## Test Results

| Suite | Before PR 1 | After PR 1 |
|---|---|---|
| `supabase/functions/_shared/jwt_test.ts` | 16/16 pass | 23/23 pass |
| `supabase/functions/custom-access-token-hook/index_test.ts` | 12/12 pass | 19/19 pass |
| `deno check` (both modules) | clean | clean |
| `deno fmt` warnings | pre-existing long lines | pre-existing long lines (no new warnings) |
| `deno lint` warnings | pre-existing import-prefix / require-await | pre-existing import-prefix / require-await (no new warnings) |

### New tests (PR 1)

jwt_test.ts additions:
1. `verifyAuth — 401 responses include WWW-Authenticate: Bearer challenge (RFC 7235)` (RED → GREEN with implementation)
2. `verifyAuth — 403 (requireDevice with no device_id) includes WWW-Authenticate with insufficient_scope` (RED → GREEN with implementation)
3. `verifyAuth — app_metadata: null returns ok:true with deviceId=null` (triangulation)
4. `verifyAuth — app_metadata as an array returns deviceId=null (no device)` (triangulation)
5. `verifyAuth — multi-key app_metadata extracts device_id correctly without losing other keys` (triangulation)
6. `verifyAuth — requireDevice:true happy path with verified app_metadata.device_id returns ok with both parentId and deviceId` (triangulation of the spec "Verified session is accepted" scenario)
7. `verifyAuth — Bearer with only whitespace token ('Bearer   ') is rejected as 401, never reaches fetch` (triangulation of the regex `^Bearer\s+(\S+)$`)

custom-access-token-hook/index_test.ts additions:
1. `hook — app_metadata: null omits top-level device_id (no crash, no claim)` (triangulation)
2. `hook — app_metadata: {} omits top-level device_id` (triangulation)
3. `hook — app_metadata with a non-object value throws INVALID_EVENT` (triangulation)
4. `hook — app_metadata as an array throws INVALID_EVENT` (triangulation)
5. `hook — strips top-level device_id even when user_metadata.device_id is also present` (triangulation; contract pin)
6. `hook — paired event: device_id is re-derived from app_metadata even when paired user_metadata also has device_id` (triangulation)
7. `hook — all non-device_id claims are preserved verbatim across the sanitization pass` (triangulation)

## TDD Cycle Evidence

| Task | Test File | Layer | Safety Net | RED | GREEN | TRIANGULATE | REFACTOR |
|------|-----------|-------|------------|-----|-------|-------------|----------|
| 1.1 | `supabase/functions/_shared/jwt_test.ts` | Unit (Deno) | ✅ 16/16 baseline | ✅ 7 new tests written (2 RED, 5 GREEN-by-triangulation) | ✅ 23/23 passing | ✅ 7 cases (2 new + 5 triangulation) | ✅ No code refactor needed |
| 1.2 | `supabase/functions/_shared/jwt.ts` | Unit (Deno) | ✅ 16/16 baseline | ✅ WWW-Authenticate tests fail header check | ✅ `WWW-Authenticate` header added to 401 + 403; docstring + file header updated | ✅ Tested across 401 / 403 / no-fetch / app_metadata variations | ✅ Docstring updated; no logic refactor |
| 1.3 | `supabase/functions/custom-access-token-hook/index_test.ts` | Unit (Deno) | ✅ 12/12 baseline | ✅ 7 new tests written (all GREEN-by-triangulation — the hook already implements the contract) | ✅ 19/19 passing | ✅ 7 cases | ✅ No code refactor needed |
| 1.4 | `supabase/functions/custom-access-token-hook/index.ts` | Unit (Deno) | ✅ 12/12 baseline | ➖ No new behavior (hook already derives solely from `app_metadata`) | ➖ No implementation change needed | ➖ Triangulation tests pin the existing behavior | ✅ No code refactor needed |

### Test Summary

- **Total tests written**: 14 (7 in `jwt_test.ts`, 7 in `index_test.ts`)
- **Total tests passing**: 42 (23 + 19)
- **Total tests before PR 1**: 28 (16 + 12)
- **Layers used**: Unit (Deno) — the affected code is pure / Edge-Function verification logic; no UI / integration layer available for these targets.
- **Approval tests (refactoring)**: 0 — no existing behavior was changed; only documentation was updated.
- **Pure functions in production code**: 5 (`verifyAuth`, `readDeviceIdFromUser`, `unauthorized`, `forbidden`, `customAccessTokenHook`, `sanitizeClaims`, `readAppMetadata`, `isValidUuid`, `isHookEvent`, `hookErrorResponse` — all already pure or boundary helpers).

## Deviations from Design

- None — implementation matches design. The verifier's contract documented in the design file ("`verifyAuth(opts)` remains the single entrypoint", "`VerifiedAuth.ok === true` means the caller is cryptographically verified", "`parentId` is sourced from verified Supabase user identity", "`deviceId` is trusted only when derived from verified `app_metadata.device_id`") is preserved exactly. The new `WWW-Authenticate` header is a hardening RFC plumbing on the existing failure envelope, not a contract change.

## Issues Found

- None. The `WWW-Authenticate` header was the only NEW behavior added; the rest of the tests pin the existing contract.
- `deno fmt --check` reports long test-name lines (80+ chars). This is the established style of the existing test files (the original 16 tests also have long names that deno fmt would rewrap). Not introduced by PR 1; not in scope for this slice.
- `deno lint` reports pre-existing `no-import-prefix` (inline `https://` / `jsr:` imports) and one `require-await` warning. These are the existing import style; the project has not configured `deno.json` to whitelist these. Not introduced by PR 1; not in scope for this slice.

## PR 1 Boundary

- **In scope**:
  - `supabase/functions/_shared/jwt.ts` (verifier contract + `WWW-Authenticate` RFC plumbing)
  - `supabase/functions/_shared/jwt_test.ts` (verification suite)
  - `supabase/functions/custom-access-token-hook/index.ts` (claimed sanitization — verified unchanged)
  - `supabase/functions/custom-access-token-hook/index_test.ts` (hook suite)
  - `openspec/changes/auth-jwt-hardening/tasks.md` (status tracking)
- **Out of scope** (PR 2):
  - `supabase/functions/get-policy/index.ts`
  - `supabase/functions/heartbeat/index.ts`
  - `supabase/functions/register-token/index.ts`
  - `supabase/functions/approve-request/index.ts`
  - `supabase/functions/reward/index.ts`
  - `supabase/functions/verify-integrity/index.ts`
  - Endpoint-level regression tests
  - Android session storage / pairing / magic-link / OTP changes

## Rollback Boundary

- **Revert the PR 1 commit.** The verifier loses the `WWW-Authenticate` header; the test counts revert to 16 (jwt) + 12 (hook). No endpoint behavior is touched by PR 1, so rollback is bounded to the shared boundary.
- **No endpoint-level rollback is required** because no endpoint was changed.
- The hook is unchanged in implementation; only the test suite was extended with edge-case triangulation. A no-op revert of the test additions is safe.

## Status

**PR 1 is ready for verify.** All Phase 1 tasks are complete; the verifier + hook suite is 42/42 green; the `WWW-Authenticate` plumbing is wired in; the new tests pin the trust boundary against future regressions; the existing contract is preserved end-to-end.
