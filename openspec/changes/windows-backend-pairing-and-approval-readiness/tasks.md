# Tasks: Windows Backend Pairing and Approval Readiness

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | 220-360 |
| 400-line budget risk | Medium |
| Chained PRs recommended | No |
| Suggested split | Single PR |
| Delivery strategy | ask-on-risk |
| Chain strategy | pending |

Decision needed before apply: No
Chained PRs recommended: No
Chain strategy: pending
400-line budget risk: Medium

## Suggested Work Units

| Unit | Goal | Likely PR | Notes |
|------|------|-----------|-------|
| 1 | Atomic approval backend contract | Single PR | Migration first; keep pairing runtime unchanged. |
| 2 | Verification + docs alignment | Single PR | Tests and docs stay in the same delivery slice. |

## Phase 1: Schema / Contract Foundation

- [x] 1.1 Add `supabase/migrations/012_approve_request_atomic.sql` with `approve_request_atomic(...)` and `UNIQUE(request_id)` on `grants`.
- [x] 1.2 Encode the row-lock/update/insert transaction so one approval cannot create two grants on retry.
- [x] 1.3 Keep pairing unchanged, but confirm `supabase/functions/pairing/index.ts` still satisfies the single-use/expiry contract.

## Phase 2: Edge Function Implementation

- [x] 2.1 Refactor `supabase/functions/approve-request/index.ts` to call the RPC after auth and ownership checks.
- [x] 2.2 Preserve the current response envelope while returning idempotent retry results from the RPC.
- [x] 2.3 Keep FCM send-after-commit behavior and do not reintroduce the two-write approval path.

## Phase 3: Tests / Verification

- [x] 3.1 Extend `supabase/functions/approve-request/index_test.ts` for approve, deny, retry, unauthorized, and partial-failure cases.
- [x] 3.2 Re-run/extend `supabase/functions/pairing/index_test.ts` for replay and expiry so pairing stays atomic.
- [x] 3.3 Smoke-test the migration/RPC contract locally with the Supabase CLI or equivalent SQL verification.

## Phase 4: Docs / Readiness Cleanup

- [x] 4.1 Update `supabase/README.md` with the atomic approval contract, idempotency behavior, and response examples.
- [x] 4.2 Update `docs/backend-windows-readiness-report.md` to separate implemented pairing/approval behavior from deferred WNS, realtime, and integrity work.

## Phase 5: Pairing Atomicity Follow-up

- [x] 5.1 Add a transactional `redeem_pairing_code_atomic` RPC that locks the code, performs child/device/metadata/policy writes, and consumes the code last.
- [x] 5.2 Refactor `supabase/functions/pairing/index.ts` to use the RPC as the pairing commit boundary.
- [x] 5.3 Update `supabase/functions/pairing/index_test.ts` for downstream rollback, retry success, replay, concurrency, and normal type-checked execution.
- [x] 5.4 Reconcile pairing atomicity statements in `docs/backend-windows-readiness-report.md` without expanding WNS, realtime, or integrity scope.
