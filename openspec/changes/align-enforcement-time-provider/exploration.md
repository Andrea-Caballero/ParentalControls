## Exploration: align-enforcement-time-provider

### Current State
`EnforcementController` already injects `TimeProvider` and uses it for policy evaluation (`LocalDateTime.now(timeProvider.currentZoneId())`), but grant polling still calls `java.time.Instant.now()` inside `loadGrants()`. That means the active-grant cutoff is tied to the system clock instead of the project time abstraction, which can diverge in tests and makes the controller inconsistent with the rest of the time-dependent enforcement flow.

### Affected Areas
- `app/src/main/java/com/tudominio/parentalcontrol/enforcement/EnforcementController.kt` — replace the direct `Instant.now()` cutoff used by `loadGrants()`.
- `app/src/androidTest/java/com/tudominio/parentalcontrol/enforcement/EnforcementControllerTest.kt` (or a narrowly scoped enforcement test) — prove active grants are loaded through the injected time source.

### Approaches
1. **Use the existing `TimeProvider` wall clock** — derive the grant cutoff with `timeProvider.wallInstant().toString()` and keep the DAO query shape unchanged.
   - Pros: smallest change, consistent with `TimeExtraRepository` / `RewardManager`, preserves the current Room query contract.
   - Cons: still relies on string comparison semantics in SQL, so test data must remain ISO-8601/UTC-safe.
   - Effort: Low

2. **Introduce a dedicated grant-clock helper/supplier** — abstract the cutoff computation behind a small function or injected supplier.
   - Pros: slightly clearer seam for future time-sensitive enforcement work.
   - Cons: extra indirection for a one-line bug, larger review surface, not needed because `TimeProvider` already exists.
   - Effort: Medium

### Recommendation
Take approach 1. The controller already depends on `TimeProvider`, and other time-sensitive repositories in this codebase use `wallInstant()` for grant expiry checks. This keeps the fix within the expected one review unit and should stay within the 20–40 line target.

### Risks
- The test must control wall time explicitly; otherwise the grant boundary can still be flaky.
- `expires_at > :now` depends on lexicographic ISO-8601 ordering, so fixtures must keep the same UTC timestamp format.
- Do not expand scope to the separate `ChildStatusViewModel` auth-state snapshot bug.

### Ready for Proposal
Yes — the scope is confirmed and narrow enough to move to proposal/spec next.
