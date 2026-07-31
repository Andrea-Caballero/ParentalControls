# Apply Progress: align-enforcement-time-provider / Work Unit 1
- Attempt: `align-enforcement-time-provider-wu1-20260729-01`; runtime revision: `sha256:9194606800b3f87df59ad960981335d328b65ee82a960c21cb213dd6d040a28a`
- Delivery: feature-branch-chain; no branch or PR mechanics performed.
- [x] 1.1 RED provider state-machine tests
- [x] 1.2 GREEN provider state and fake controls
- [x] 1.3 RED/GREEN sync injection and policy confirmation tests
- [x] 1.4 Focused verification and scope review
| Task | RED | GREEN | TRIANGULATE | REFACTOR |
|---|---|---|---|---|
| 1.1 | Written, compile failed before production API | Provider tests passed | Startup, anchor/rollback, clamp/regression, loss/recovery/reboot | Clean |
| 1.2 | N/A — follows 1.1 | Focused provider suite passed | Multiple state transitions | Clean |
| 1.3 | Written, helper unresolved | MockEngine sync suite passed | Success, missing, malformed server time | Clean |
| 1.4 | N/A | Provider + sync + existing sync tests passed | Scope/diff inspection | No production refactor |
- RED: `./gradlew testDebugUnitTest --tests '*TimeProviderTest'` — failed on missing trusted-time API.
- GREEN: focused provider, trusted sync, and existing sync tests — **passed**.
- Lint: `./gradlew ktlintCheck` — failed on pre-existing wildcard imports/trailing whitespace, plus no remaining new test syntax errors.
- Preserved unrelated dirty hunks, including `EnforcementController.kt` and its test.
- No reset, stash, clean, branch, commit, tag, PR, or worktree operations.
- Work Unit 2 and all enforcement/DAO integration remain untouched.
- [ ] 2.1–2.4 Enforcement integration
- [x] 3.1–3.2 Scope and delivery gate follow-up

## Work Unit 2 — F3 Authorization-Boundary Remediation
- Attempt: `align-enforcement-time-provider-wu2-f3-begin-20260729-01`; runtime revision: `sha256:43673cc2e1679921d17a7a0ff308fca08a4299eaec6becf74d428e09ea25641e`.
- Scope: ONLY the user-approved F3 trusted-time recovery test strengthening; tasks 3.1–3.2, production behavior, final verify, archive, branches, commits, and PRs remain untouched.
- [x] F3: after recovery beyond expiry, the controller test asserts `currentPolicy.grants` is empty and drives `evaluateAndEnforce` through `decisionFlow`, requiring `Blocked`.
| Remediation | Safety Net | RED / Approval-Test | GREEN | TRIANGULATE | REFACTOR |
|---|---|---|---|---|---|
| F3 authorization boundary | Existing focused controller test: 3 tests passed | Controlled temporary regression retained policy grants; focused test failed as expected at `EnforcementControllerTrustedTimeTest.kt:90` | Focused controller test passed; required full runner passed | Verifies private grants, policy grants, and emitted controller decision after recovery | No production change; temporary mutation fully reverted |
- RED/approval-test: temporarily removed both production policy-grant synchronization assignments in `EnforcementController.kt`; `./gradlew testDebugUnitTest --tests '*EnforcementControllerTrustedTimeTest.trust loss clears controller grants before recovery can revive an expired grant'` — **failed as expected**.
- GREEN: `./gradlew testDebugUnitTest --tests '*EnforcementControllerTrustedTimeTest'` — **passed**; `./gradlew testDebugUnitTest` — **passed**.
- Evidence: combined SHA-256 `sha256:df167a9ab4a06a4bd9284a622e4bd173b8ca10bd7aef5996ae589a7cdf8cc29d` for the changed test and progress files after cleanup (progress evidence token normalized during hashing).
- Harness disposition: strict TDD honored; no virtual-time/OOM-prone setup introduced. The approval-test mutation was safe, temporary, failed the strengthened assertion, and was fully reverted before GREEN.
- Cleanup/process evidence: no reset, stash, clean, branch, commit, tag, PR, or worktree operations; unrelated dirty changes were preserved. No production behavior change remains.
- [ ] 3.1–3.2 Scope and delivery gate follow-up

## Work Unit 2 — Fresh Review Remediation
- Attempt: `align-enforcement-time-provider-wu2-review-fix-begin-20260729-01`; runtime revision: `sha256:19cccbdf89ecc9ce14284f9cc806b9dd651e393972174268c0cba72d746638eb`.
- Scope: ONLY confirmed fresh-review defects F1–F3; tasks 3.1–3.2, final verify, archive, branches, commits, and PRs remain untouched.
- [x] F1: controller grant-time evaluation now blocks with `Decision.Bloquear` when trusted time is unavailable; it never falls back to `wallInstant()`.
- [x] F2: the `Unavailable` transition synchronously clears `currentGrants` and the policy's grants before generation bump/re-evaluation.
- [x] F3: controller-level regressions construct `EnforcementController`, prove unavailable-time blocking, and prove trust-loss clearing persists through recovery after expiry.
| Remediation | RED | GREEN | TRIANGULATE | REFACTOR |
|---|---|---|---|---|
| F1 | Controller test failed under wall-clock fallback | Focused controller suite passed | Unavailable grant evaluation plus recovery query | No refactor |
| F2/F3 | Controller test failed with stale grant state | Focused controller suite passed | Active grant, trust loss, expired recovery | No refactor |
- RED: `./gradlew testDebugUnitTest --tests '*EnforcementControllerTrustedTimeTest'` — **failed as expected** with 2 controller assertions before the production remediation (F1 fallback and F2 stale state).
- GREEN: `./gradlew testDebugUnitTest --tests '*EnforcementControllerTrustedTimeTest'` — **passed** (3 tests).
- Focused regression set: `./gradlew testDebugUnitTest --tests '*EnforcementControllerTrustedTimeTest' --tests '*EnforcementControllerDeviceStateTest' --tests '*GrantDaoIsolationTest'` — **passed**.
- Full required runner: `./gradlew testDebugUnitTest` — **passed**.
- Evidence: combined SHA-256 `sha256:86e93dff47575c24ac4300a10bf3340ebcbc4b634bdf1e4214f4eaa1e13d6c3c` for the controller/test remediation files.
- Changed-line budget attributable to this remediation: 84 controller/test lines; progress bookkeeping is additional and remains below the 140-line cap. Pre-existing Work Unit 2 and unrelated dirty hunks are excluded.
- Harness disposition: strict TDD honored. The invalidated exploratory virtual-time/OOM setup was not used; the new regressions use deterministic unconfined dispatching, while the pre-existing query test retains its bounded scheduler drain. Initial test-authoring compile errors were corrected before the substantive RED run. No harness failure remains.
- Cleanup/process evidence: no reset, stash, clean, branch, commit, tag, PR, or worktree operations; unrelated dirty changes were preserved.
- [ ] 3.1–3.2 Scope and delivery gate follow-up

## Fresh Review Remediation
- Attempt: `align-enforcement-time-provider-wu1-review-fix-20260729-02`; runtime revision: `sha256:5b9b236559967d1013baef4919e2beacb4854402208d258d527bbcf9927ce603`.
- Confirmed fix: both `DefaultTimeProvider` and `FakeTimeProvider` now select `serverTime` when no trusted time exists or the incoming confirmation is newer; older/equal confirmations preserve the current trusted time.
- Regression test: `newer confirmation advances the trusted anchor` in `TimeProviderTest.kt`.
| Remediation | RED | GREEN | REFACTOR |
|---|---|---|---|
| Forward confirmation clamp | Targeted test failed on current code (`AssertionError`, line 51) | `./gradlew testDebugUnitTest --tests '*TimeProviderTest'` passed; full `./gradlew testDebugUnitTest` passed | No refactor; minimum two-line production fix |
- Changed-line budget attributable to this attempt: 13 (11 test additions, 2 production line replacements; prior dirty hunks excluded).
- Evidence: `sha256:3b2294786b07960fe7fb4587638ca0dd38c6760c43ff5c23f00e8316ac949363` (`TimeProvider.kt`); `sha256:3cc7a80a00532cde0ac623373fec530e7429c1c8a5dd13459054c75cb9c40155` (`TimeProviderTest.kt`).
- Harness disposition: strict TDD honored; no harness failure. Existing unrelated dirty hunks preserved.
- Cleanup/process: no reset, stash, clean, branch, commit, tag, PR, or worktree operations; Work Unit 2 untouched.

## Work Unit 2: Enforcement Integration
- Attempt: `align-enforcement-time-provider-wu2-begin-20260729-01`; runtime revision: `sha256:13e0f17015cd025251fa0e729765a003354b656fbe5fa8a4404c466898ceb75a`.
- Delivery: feature-branch-chain; no branch or PR mechanics performed.
- [x] 2.1 RED: added trusted-time controller lifecycle tests and strict DAO equality coverage.
- [x] 2.2 GREEN: wired the legacy controller singleton to Hilt's `TimeProvider`, made grant reads fail closed on unavailable trust, and added trust/boundary generation reevaluation plus expiry scheduling.
- [x] 2.3 RED/GREEN: retained the existing canonical UTC `expires_at > now` DAO query and added equality exclusion coverage without changing unrelated DAO behavior.
- [x] 2.4 Focused and full unit verification completed; dirty controller/test hunks were preserved.
| Task | Safety Net | RED | GREEN | TRIANGULATE | REFACTOR |
|---|---|---|---|---|---|
| 2.1 | Existing controller + DAO tests: 39 passing | Controller test initially failed because raw `Instant.now()` was queried while trust was unavailable | Trusted-time lifecycle test passed | unavailable, recovery, trust loss/recovery query generation | Clean |
| 2.2 | Existing controller test passed | New controller test preceded production wiring | Focused controller test passed | unavailable and recovered trusted anchors | Removed unused imports; focused tests remained green |
| 2.3 | Grant DAO isolation baseline passed | Equality scenario added before final focused run | Equality exclusion and isolation tests passed | two devices plus equality boundary | No DAO production change required |
| 2.4 | Full baseline recorded | N/A — verification task | `./gradlew testDebugUnitTest` passed | focused controller/DAO suite passed | Diff/scope inspected |
- RED: `./gradlew testDebugUnitTest --tests '*EnforcementControllerTrustedTimeTest'` — failed on the expected unavailable-time assertion before controller integration.
- GREEN: `./gradlew testDebugUnitTest --tests '*EnforcementControllerTrustedTimeTest' --tests '*GrantDaoIsolationTest'` — passed; `./gradlew testDebugUnitTest` — passed.
- Refactor: removed unused controller imports; focused controller/DAO suite passed afterward.
- Harness disposition: strict TDD honored; one exploratory combined focused run hit a Gradle test-worker OOM after virtual-time scheduling test setup, then the non-looping focused tests passed. No harness failure remains.
- Cleanup/process: no reset, stash, clean, branch, commit, tag, PR, or worktree operations. Unrelated dirty changes, including pre-existing controller/test hunks, were preserved.
- Changed-line budget attributable to Work Unit 2: 118 lines (tests, controller integration, DAO equality test, and task/progress bookkeeping; unrelated pre-existing hunks excluded).
- [ ] 3.1–3.2 Scope and delivery gate follow-up

## Final Apply Evidence — Tasks 3.1–3.2
- Attempt: `align-enforcement-time-provider-final-apply-begin-20260729-01`; runtime revision: `sha256:84fdfb60abec875d791ecc024c43f6bc0a5cd3824bcd55ab2a6288df6a2f145a`.
- Scope gate: confirmed the change-specific implementation/test set is limited to trusted-time provider, sync confirmation, enforcement grant lifecycle, and DAO equality coverage. `ChildStatusViewModel`, trusted-time backend/persistence protocol, approval, cleanup, and unrelated policy rules were not part of this change; existing dirty hunks in those areas were preserved and not attributed.
- Delivery gate: `feature-branch-chain` was already recorded in the task artifact and explicitly supplied for this work unit; no branch or PR mechanics were performed.
- [x] 3.1 Scope and unrelated-change confirmation.
- [x] 3.2 Chain strategy obtained and confirmed before final apply evidence.
- Verification: `./gradlew testDebugUnitTest` — **passed** (BUILD SUCCESSFUL; 39 actionable tasks up-to-date). `./gradlew ktlintCheck` — **failed** on existing/unrelated style debt and dirty change-set violations; no formatting or cleanup was performed.
- Ktlint diagnosis: wildcard/unused imports and arrow newline in the pre-existing/modified `EnforcementController.kt`, unused import in `TimeProvider.kt`, and trailing-space/line-length/indentation findings in dirty test files. This is a quality-gate disposition, not a change-specific behavioral failure.
- Evidence: combined SHA-256 `sha256:cc66ff2d8b88d52e5df255eac5d87d6884b6bb8e7c0d5e76d30fb103f90c108c` over the final task statuses, verification results, scope disposition, delivery strategy, and cleanup/process evidence.
- Harness disposition: strict TDD remains active; final tasks were evidence/traceability only and introduced no production or test behavior.
- Cleanup/process evidence: no reset, stash, clean, formatting, branch, commit, tag, PR, or worktree operations; unrelated dirty hunks were preserved.
- Remaining: none for this apply change; final verification/archive are outside this request.
