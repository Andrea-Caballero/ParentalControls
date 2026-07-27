# Design: Windows Backend Pairing and Approval Readiness

## Technical Approach

Keep pairing on the existing atomic claim path: `pairing/index.ts` already uses `UPDATE ... RETURNING` on `pairing_codes`, `009_pairing_status_consumed.sql` gives redeemed codes a terminal `CONSUMED` state, and `010_enable_pairing_cron.sql` keeps expiry cleanup separate. For this slice, pairing is a contract check and doc alignment problem, not a new backend flow.

The real code change is approval: move `approve-request` from two sequential writes (`time_requests` update, then `grants` insert) to one server-side transaction exposed as a Postgres RPC. The Edge Function stays the auth boundary, but the database becomes the commit boundary so verdict + grant cannot diverge on retry or partial failure. WNS, realtime, integrity, and broader telemetry remain out of scope.

## Architecture Decisions

| Decision | Tradeoff | Rationale |
|---|---|---|
| Keep pairing runtime mostly unchanged | Less churn; relies on current code paths | Current pairing code already enforces single-use and expiry atomically, so rework would add risk without new value. |
| Implement approval atomically in Postgres RPC | Requires a new migration and server contract | One commit boundary is the only way to guarantee “one approved request = one grant” under retries and network failure. |
| Add a uniqueness guard on `grants.request_id` | Adds a small schema constraint | Gives stable idempotency keyed by the request itself, which matches the spec and current payload shape. |
| Preserve current response shape where possible | Slightly more backend branching | Reduces client breakage while the readiness report and docs catch up. |

## Data Flow

```text
Pairing:
parent creates code -> child redeems -> claimPairingCode() flips code to CONSUMED -> device/child rows created -> response returns device_id/parent_id

Approval:
parent JWT -> approve-request validates auth -> RPC locks time_request -> update verdict + insert grant in one transaction -> Edge Function sends FCM only after commit
```

## File Changes

| File | Action | Description |
|---|---|---|
| `supabase/migrations/012_approve_request_atomic.sql` | Create | Add the atomic approval RPC and `UNIQUE(request_id)` guard for grants idempotency. |
| `supabase/functions/approve-request/index.ts` | Modify | Validate the parent token, call the RPC, keep the current API envelope, and only notify after commit. |
| `supabase/functions/approve-request/index_test.ts` | Modify | Add approval/denial/retry/unauthorized coverage against the new RPC-backed flow. |
| `supabase/README.md` | Modify | Update the approve-request contract and examples to match the atomic/idempotent behavior. |
| `docs/backend-windows-readiness-report.md` | Modify | Correct stale readiness claims and separate implemented backend behavior from deferred WNS/realtime/integrity work. |

## Interfaces / Contracts

```sql
-- New DB contract
approve_request_atomic(
  p_request_id uuid,
  p_parent_id uuid,
  p_minutes integer,
  p_response_text text default null
) returns jsonb
```

Expected result shape stays close to the current Edge Function response:
`{ success, decision, grant_id?, idempotent?, minutes?, expires_at?, policy_version? }`.

## Testing Strategy

| Layer | What to Test | Approach |
|---|---|---|
| Unit | Approval success, denial, retry idempotency, unauthorized caller | Deno tests for `approve-request/index.ts` with fetch/RPC mocks. |
| Unit | Pairing replay safety | Keep/extend the existing `pairing/index_test.ts` atomic-claim coverage. |
| Integration | Schema/constraint behavior | Local Supabase migration smoke test for `UNIQUE(request_id)` and RPC behavior. |
| E2E | Happy path and replay path | One staging pair + approve run, plus one approval retry after a simulated timeout. |

## Migration / Rollout

Add the migration first, then deploy the updated Edge Function. No data backfill is required. Roll forward is safe because the new RPC is additive; rollback means redeploying the prior function while leaving the constraint in place unless a full schema revert is needed.

## Open Questions

- [ ] Should a duplicate approval return `200` with `idempotent: true` or `409` with the resolved grant id?
- [ ] Should the RPC or the Edge Function own the final ownership check?
- [ ] Do we keep the existing best-effort stale-request sweep in `approve-request` unchanged?
