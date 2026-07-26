package com.tudominio.parentalcontrol.integration

import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * RLS regression test for `device_push_tokens_parent_select` (Slice A of
 * `openspec/changes/feat-cross-device-pairing-and-approval`).
 *
 * Covers migration `008_device_push_tokens_parent_id.sql` (column +
 * RLS policies) — the symmetric rollback surface for Slice D.
 *
 * **A.1.8** — `parentCanReadOwnTokens`:
 *
 * Setup (requires `supabase start` running on port 54321):
 *  1. Seed two `auth.users` rows (`parent-A`, `parent-B`).
 *  2. Seed two `device_push_tokens` rows with
 *     `parent_id = parent-A-uuid` and `parent_id = parent-B-uuid`.
 *  3. Issue an RLS-forced JWT as `parent-A` → expect 1 row returned.
 *  4. Issue an RLS-forced JWT as `parent-B` → expect 1 row returned
 *     (their own token), NOT parent-A's.
 *
 * Gated by `./gradlew integrationTest -PrunIntegration=true` per Q2=B
 * (tasks.md). On a dev machine without `supabase start`, this test is
 * skipped and the assertion is verified in CI.
 *
 * The test is RED on `master = 3f3a81d` because migration
 * `008_device_push_tokens_parent_id.sql` does not exist; the existing
 * `device_push_tokens` table has no `parent_id` column and only an
 * agent-scoped policy (`device_push_tokens_agent_all` at
 * `002_rls_policies.sql:200-204`).
 *
 * Slice C wires the actual `psql` round-trip + JWT minting in CI.
 */
class DevicePushTokensRlsIT {

    private fun integrationEnabled(): Boolean =
        System.getProperty("runIntegration") == "true"

    @Test
    fun parentCanReadOwnTokens() {
        assumeTrue(
            "RLS IT requires `supabase start` on :54321 and -PrunIntegration=true; " +
                "deferred to CI per tasks.md Q2=B",
            integrationEnabled()
        )
        // Slice C wires the actual `psql` round-trip + JWT minting.
        throw UnsupportedOperationException(
            "DevicePushTokensRlsIT scaffold — implement in Slice C (cross-device " +
                "harness) against `supabase start`. The test must assert " +
                "parent-A sees their own push token, parent-B sees their own " +
                "(not parent-A's), under device_push_tokens_parent_select."
        )
    }
}
