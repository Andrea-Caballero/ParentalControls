package com.tudominio.parentalcontrol.integration

import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * RLS regression test for `behavioral_events_parent_select` (Slice A of
 * `openspec/changes/feat-cross-device-pairing-and-approval`).
 *
 * Covers `openspec/changes/feat-cross-device-pairing-and-approval/specs/supabase-backend-integration/spec.md`
 * + `openspec/changes/feat-cross-device-pairing-and-approval/specs/parent-auth-session/spec.md`
 * + the migration `007_behavioral_events_parent_select.sql`.
 *
 * **A.1.7** — `parentCanReadOwnEvents`:
 *
 * Setup (requires `supabase start` running on port 54321):
 *  1. Seed two `auth.users` rows (`parent-A`, `parent-B`) with
 *     `app_metadata.parent_id = <their respective UUIDs>`.
 *  2. Seed three `behavioral_events` rows owned by `parent-A`
 *     (`parent_id = parent-A-uuid`).
 *  3. Issue an RLS-forced JWT as `parent-A` → expect 3 rows returned.
 *  4. Issue an RLS-forced JWT as `parent-B` → expect 0 rows returned
 *     (the **sibling-parent denial** invariant).
 *
 * Gated by `./gradlew integrationTest -PrunIntegration=true` per Q2=B
 * (tasks.md). On a dev machine without `supabase start`, this test is
 * skipped (`assumeTrue` with the `-PrunIntegration=true` project
 * property) and the assertion is verified in CI on the ephemeral
 * Supabase project (`.github/workflows/cross-device-smoke.yml`, Slice C).
 *
 * The test is RED on `master = 3f3a81d` because migration
 * `007_behavioral_events_parent_select.sql` does not exist; the existing
 * `parent_events` policy at `004_parent_outcome_checkins.sql:89-99` does
 * not follow the `*_parent_select` naming convention.
 *
 * The actual SQL is implemented in Slice C's CI harness (the JVM-side
 * scaffold below is the contract placeholder that lights up once the
 * integration task exists).
 */
class BehavioralEventsRlsIT {

    /**
     * Returns true when the JVM is launched with
     * `-PrunIntegration=true` (the gate opt-in per Q2=B).
     */
    private fun integrationEnabled(): Boolean =
        System.getProperty("runIntegration") == "true"

    @Test
    fun parentCanReadOwnEvents() {
        // Skipped unless `-PrunIntegration=true` AND `supabase start` is up.
        assumeTrue(
            "RLS IT requires `supabase start` on :54321 and -PrunIntegration=true; " +
                "deferred to CI per tasks.md Q2=B",
            integrationEnabled()
        )
        // Slice C wires the actual `psql` round-trip + JWT minting.
        // The contract: 3 rows visible as parent-A, 0 rows visible as parent-B.
        // Placeholder assertion to fail loudly if the slice-C harness
        // is wired without updating this scaffold.
        throw UnsupportedOperationException(
            "BehavioralEventsRlsIT scaffold — implement in Slice C (cross-device " +
                "harness) against `supabase start`. The test must assert " +
                "parent-A sees 3 events and parent-B sees 0 events under the " +
                "behavioral_events_parent_select policy."
        )
    }
}
