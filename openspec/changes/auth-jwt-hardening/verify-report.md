# Verification Report: Auth JWT Hardening (PR 1)

**Change**: `auth-jwt-hardening`
**Slice**: PR 1 — shared verifier and hook sanitization only
**Mode**: Strict TDD
**Date**: 2026-07-28
**Verdict**: **FAIL**

The PR 1 Supabase boundary is behaviorally green (42/42 targeted Deno tests), type-checks, and matches the covered design. The required repository test gate is not green: `./gradlew testDebugUnitTest` reproducibly fails one Android unit test outside the PR 1 file boundary.

## Completeness

| Metric | Value |
|---|---:|
| PR 1 tasks | 4 |
| PR 1 complete | 4 |
| PR 1 incomplete | 0 |
| Deferred PR 2 / final verification tasks | 9 |

Tasks 1.1–1.4 are checked and implemented. Tasks 2.1–4.2 remain intentionally unchecked and are not treated as PR 1 completion claims. The overall change is not archive-ready.

## Build and Test Evidence

| Command | Result | Evidence |
|---|---|---|
| `./gradlew assembleDebug` | PASS | `BUILD SUCCESSFUL`; 45 tasks up-to-date |
| `./gradlew testDebugUnitTest` | **FAIL** | 909 tests completed, 1 failed, 2 skipped; `EnforcementControllerDeviceStateTest > lockNow fires when deviceState is LOCKED for the real device id` failed at line 73 |
| Focused rerun of the failing Gradle test | **FAIL** | 1 test completed, 1 failed; confirms the failure is reproducible |
| `_shared: deno task test` | PASS | 23 passed, 0 failed |
| `custom-access-token-hook: deno task test` | PASS | 19 passed, 0 failed |
| `deno check` on both changed module/test pairs | PASS | Both commands exited 0 |

The Gradle failure is outside the declared PR 1 paths, but strict verification requires the configured runner to exit successfully; therefore it blocks a passing verdict.

## Spec Compliance Matrix — PR 1 Boundary

| Requirement / scenario | Runtime evidence | Result |
|---|---|---|
| Verified session reaches authorization with verified `auth.uid()` and `app_metadata.device_id` | `_shared/jwt_test.ts` — `requireDevice:true happy path...` | COMPLIANT for shared verifier |
| Missing verified device identity fails closed | `_shared/jwt_test.ts` — `requireDevice:true with no app_metadata.device_id...`; 403 challenge test | COMPLIANT for shared verifier |
| Forged, malformed, expired, or top-level claim data is not trusted | `_shared/jwt_test.ts` forged/expired/top-level claim cases; hook signature and sanitization cases | COMPLIANT for shared verifier/hook |
| Hook derives top-level `device_id` only from `app_metadata.device_id` | `custom-access-token-hook/index_test.ts` paired, stale claim, and `user_metadata` cases | COMPLIANT |
| No endpoint data access or mutation occurs before rejection | Endpoint regression tests | DEFERRED to PR 2 |
| Service-only RPC and parent check-in scenarios | Endpoint/RPC tests | DEFERRED to PR 2 |

**PR 1 compliance**: 4/4 shared-boundary behaviors covered by passing runtime tests. Endpoint-level clauses remain explicitly deferred.

## Correctness and Design Coherence

| Decision | Status | Evidence |
|---|---|---|
| Use `verifyAuth()` as the shared cryptographic boundary | Followed | Calls `supabase.auth.getUser(token)` and returns verified `user.id` |
| Trust device identity only from verified `app_metadata` | Followed | `readDeviceIdFromUser()` ignores top-level JWT claims |
| Fail closed for device-required calls | Followed | Missing/invalid device identity returns 403 before caller continuation |
| Strip and re-derive hook `device_id` | Followed | Hook deletes draft `device_id`, then reads only `claims.app_metadata.device_id` |
| Audit all endpoint handlers | Deferred | Correctly reserved for PR 2 |

## TDD Compliance

| Check | Result | Details |
|---|---|---|
| TDD evidence reported | PASS | `apply-progress.md` contains the required table |
| All PR 1 tasks have test coverage | PASS | 4/4 task behaviors map to the two targeted suites |
| RED evidence confirmed | WARNING | Only the new `WWW-Authenticate` behavior is documented as genuinely RED; task 1.3's seven additions are explicitly GREEN-by-triangulation and task 1.4 had no behavior change |
| GREEN confirmed | PASS | 42/42 targeted tests pass now |
| Triangulation adequate | WARNING | One claimed hook case is named `app_metadata: null`, but its setup deletes `app_metadata`; literal `null` is not exercised |
| Safety net | PASS | Reported baseline is 28/28 before edits; current suites are 42/42 |

Strict RED→GREEN provenance is therefore only partially demonstrated, although current behavioral coverage is green.

## Test Layer Distribution

| Layer | Tests | Files | Notes |
|---|---:|---:|---|
| Unit | 37 | 2 | Verifier and pure hook/claim tests |
| Integration | 5 | 1 | Direct `handleRequest` tests with real Standard Webhooks signing/verification |
| E2E | 0 | 0 | Deferred / unavailable for this slice |
| **Total** | **42** | **2** | All passed |

## Coverage and Assertion Quality

Coverage analysis skipped — no coverage tool is configured.

**Assertion quality**: PASS. No tautologies, production-free assertions, ghost loops, or smoke-only assertions were found. Loops use explicit non-empty fixtures and invoke production code.

## Quality Metrics

- **Type checker**: PASS — both `deno check` commands exited 0.
- **Linter**: WARNING — `deno lint` exits non-zero with 6 findings: five `no-import-prefix` findings and one `require-await` finding.
- **Formatter**: WARNING — `deno fmt --check` exits non-zero for all four PR 1 source/test files. This is broader than the apply-progress claim that only long test-name lines are reported.

## Issues

### CRITICAL

1. The mandatory runner `./gradlew testDebugUnitTest` fails reproducibly: `EnforcementControllerDeviceStateTest.lockNow fires when deviceState is LOCKED for the real device id` at line 73. The failure is outside the PR 1 boundary, but the configured verification gate is not green.

### WARNING

1. Strict TDD provenance is incomplete: most newly added tests were GREEN-by-triangulation, and task 1.3 has no demonstrated RED state despite being written as a RED-test task.
2. `custom-access-token-hook/index_test.ts:662-669` claims to test `app_metadata: null` but actually removes the property, so the reported literal-null case is not covered.
3. Deno lint and format checks exit non-zero on the PR 1 files; the formatter drift includes production files as well as test names.

### SUGGESTION

1. Keep PR 2 endpoint scenarios explicitly deferred in subsequent reports; do not interpret this PR 1 report as whole-change or archive readiness.

## Final Verdict

**FAIL** — the PR 1 Supabase implementation itself is green and coherent, but the required repository test command fails. PR 1 should not pass the verification gate until the mandatory runner is green (or the orchestrator explicitly establishes and documents an accepted unrelated-baseline exception).
