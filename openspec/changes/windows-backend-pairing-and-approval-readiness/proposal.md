# Proposal: Windows Backend Pairing and Time-Approval Readiness

## Intent

Move the Windows backend from contract/demo readiness toward a dependable first production slice: a child device can pair with a parent, and a parent can approve or deny additional time without leaving inconsistent server state. This is the highest-value path because it establishes trust and the core parental-control decision loop before adding delivery and hardening features.

## Scope

### In Scope
- Define and close the Windows pairing contract: authenticated actors, device ownership, single-use/expiry rules, error behavior, and lifecycle state transitions.
- Make `approve-request` atomic: the request verdict and corresponding grant must commit together, with idempotent/retry-safe behavior and authorization enforced server-side.
- Align the relevant schema, RLS policies, Edge Function responses, and documentation with the current repository rather than stale readiness-report claims.
- Establish production-oriented acceptance evidence for success, replay, expiry, unauthorized access, duplicate approval, and partial-failure paths.

### Out of Scope
- WNS push registration, delivery, retries, or notification UX.
- Realtime/WebSocket delivery of requests, approvals, or policy changes.
- Integrity attestation, binary validation, or integrity-report enforcement.
- Batch/scheduled/automatic approvals, unpairing, or broad Windows telemetry and policy work.

## Capabilities

### New Capabilities
- `windows-pairing`: Production contract for pairing a Windows child device to a parent-owned account.

### Modified Capabilities
- `time-request-approval`: Approval must atomically produce the verdict and grant, and define retry/idempotency behavior.

## Approach

Treat pairing and approval as explicit state machines backed by database invariants and server-side authorization. Prefer a transactional database operation/RPC (or equivalent single transaction) for approval, with a stable request identity preventing duplicate grants. Reconcile implementation, migrations, RLS, and API documentation before expanding the surface area.

## Affected Areas

| Area | Impact | Description |
|---|---|---|
| `supabase/functions/` | Modified/New | Pairing and approval contracts and handlers |
| `supabase/migrations/` | Modified | Constraints, indexes, transactional support, and RLS |
| `openspec/specs/` | Modified/New | Windows pairing and approval requirements |
| `docs/backend-windows-readiness-report.md` | Modified | Correct stale readiness claims and record remaining gaps |

## Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| Existing clients depend on current response/error shapes | Medium | Version or preserve compatible contracts; specify migration behavior |
| Retrying after an uncertain network failure duplicates a grant | High | Transactional write plus request-scoped idempotency/invariant |
| Pairing rules are underspecified across parent/child roles | Medium | Resolve open questions before design and encode them as scenarios |

## Rollback Plan

Revert the Edge Function and migration changes as one deployment unit. Preserve additive schema changes until dependent code is removed; disable the new path behind its deployment/configuration boundary if rollback follows a partial release.

## Dependencies

- Confirm the authoritative Windows API contract and current production schema/RLS state.
- Decide whether approval transactionality is implemented through a Postgres RPC or an equivalent server-side transaction boundary.

## Success Criteria

- [ ] Pairing and approval requirements are testable and match the live schema and authorization model.
- [ ] No approved request can exist without exactly one corresponding grant, including retries and concurrent attempts.
- [ ] Production-readiness documentation distinguishes implemented behavior from deferred WNS, realtime, and integrity work.

## Proposal Question Round

- Is a Windows pairing code/device session the intended trust primitive, and may one child device be paired to more than one parent?
- Should approval retries return the original grant, or a conflict, when the request was already approved?
- Which actor owns the Windows device authentication lifecycle for this slice, and what credential rotation/revocation behavior is required?
- What compatibility obligation exists for any current Android/shared `time_requests` and `grants` consumers?
