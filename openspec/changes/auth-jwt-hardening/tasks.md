# Tasks: Auth JWT Hardening

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | ~320-480 |
| 400-line budget risk | High |
| Chained PRs recommended | Yes |
| Suggested split | PR 1 shared verifier + hook; PR 2 endpoint audit + regression tests |
| Delivery strategy | ask-on-risk |
| Chain strategy | feature-branch-chain |

Decision needed before apply: Yes
Chained PRs recommended: Yes
Chain strategy: feature-branch-chain
400-line budget risk: High

| Unit | Goal | Likely PR | Notes |
|------|------|-----------|-------|
| 1 | Lock the shared auth boundary | PR 1 | Base `supabase/functions/_shared/jwt.ts` and `custom-access-token-hook/index.ts`. |
| 2 | Audit auth-gated Edge Functions | PR 2 | Base PR 1; covers `get-policy`, `heartbeat`, `register-token`, `approve-request`, `reward`, `verify-integrity`. |

## Phase 1: Shared boundary hardening

- [x] 1.1 Add RED tests in `supabase/functions/_shared/jwt_test.ts` for missing/forged/expired tokens and missing `app_metadata.device_id`.
- [x] 1.2 Tighten `supabase/functions/_shared/jwt.ts` so `verifyAuth()` fails closed on absent/invalid verified device identity and returns `auth.uid()` + verified `device_id` only.
- [x] 1.3 Add RED tests in `supabase/functions/custom-access-token-hook/index_test.ts` for claim sanitization and top-level `device_id` stripping.
- [x] 1.4 Keep `supabase/functions/custom-access-token-hook/index.ts` deriving `device_id` only from verified `app_metadata`.

## Phase 2: Backend function audit

- [ ] 2.1 Update `supabase/functions/get-policy/index.ts` and `supabase/functions/heartbeat/index.ts` to call the shared verifier before any read or authz path.
- [ ] 2.2 Update `supabase/functions/register-token/index.ts` and `supabase/functions/approve-request/index.ts` to reject unverified device identity before mutation.
- [ ] 2.3 Update `supabase/functions/reward/index.ts` and `supabase/functions/verify-integrity/index.ts` to use the same verified identity contract.
- [ ] 2.4 Remove any inline JWT decoding or legacy trust branches in the touched handlers.

## Phase 3: Endpoint regressions

- [ ] 3.1 Add failing-then-passing tests for `get-policy` and `heartbeat` in their `*_test.ts` files, asserting 401/403 before business logic.
- [ ] 3.2 Add representative device-scoped regression tests for `approve-request` and `register-token` covering cross-device and missing-device rejection.
- [ ] 3.3 Add one success-path test per auth family to prove verified `auth.uid()` + device identity still works.

## Phase 4: Cleanup / verification

- [ ] 4.1 Confirm `custom-access-token-hook` output still matches backend expectations; keep comments/docs in sync with the fail-closed contract.
- [ ] 4.2 Run the targeted Supabase function tests for the touched files and fix any remaining trust-model drift.
