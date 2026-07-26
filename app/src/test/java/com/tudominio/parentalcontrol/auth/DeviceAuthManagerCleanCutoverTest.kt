package com.tudominio.parentalcontrol.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RED→GREEN tests for the **clean cutover** of the legacy `parent_id`
 * prefs (Slice A of `openspec/changes/feat-cross-device-pairing-and-approval`).
 *
 * # Background
 *
 * PR #28 (`fix-migrate-stale-parent-id-on-load`, commit 5e392a6) added a
 * `migrateStaleParentId` helper at `DeviceAuthManager.kt:581-590` that
 * backfills `parent_id = MOCK_PARENT_ID` ("parent-demo") for pre-PR-#27
 * PARENT prefs. The helper was a lazy on-cold-start migration to keep
 * `BehaviorLogViewModel`'s DAO filter happy against the mock engine.
 *
 * Slice A ships the **clean cutover** (Q2=b): every existing install
 * re-authenticates against real Supabase on next cold start; the
 * `MOCK_PARENT_ID` lazy backfill is REMOVED. The helper is deleted from
 * `loadPersistedState` and replaced by an explicit wipe: if `parent_id`
 * is the legacy `"parent-demo"` sentinel (or any non-UUID string),
 * prefs are cleared so the user sees the parent sign-in screen instead
 * of the stale synthetic session.
 *
 * # Test cases (all GREEN on this slice)
 *
 * - **CC1** `loadPersistedState wipes stale parent-demo prefs`:
 *   pre-cloud legacy prefs (`role=PARENT, parent_id="parent-demo"`) are
 *   wiped on cold start. Replaces the previous M1+M4 from the migration
 *   test file (the OLD migration behavior is gone).
 * - **CC2** `loadPersistedState wipes non-UUID parent_id`:
 *   any non-UUID `parent_id` value is wiped (defensive — the cutover is
 *   over-eager by design so any future mock sentinel is also wiped).
 * - **CC3** `loadPersistedState keeps real UUID parent_id`:
 *   a real Supabase UUID `parent_id` is NOT wiped (regression guard so
 *   a future "always wipe" refactor doesn't accidentally nuke legitimate
 *   cloud-mode sessions).
 * - **CC4** `loadPersistedState does NOT touch CHILD prefs without parent_id`:
 *   CHILD prefs without `parent_id` are untouched (CHILD never has a
 *   parent_id in production — same invariant as the old M2 control).
 * - **CC5** `loadPersistedState does NOT touch prefs without role`:
 *   orphan-token prefs without `role` are untouched (same as old M5
 *   control).
 * - **CC6** `getParentId returns null after clean-cutover wipe`:
 *   end-to-end contract — after a cold start with stale PARENT prefs,
 *   `getParentId()` returns `null` (NOT "parent-demo"). The Onboarding
 *   screen would render the parent sign-in form.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeviceAuthManagerCleanCutoverTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        resetManagerInstance()
        // F1 — this suite pins the RELEASE-like clean-cutover wipe
        // contract ("legacy parent-demo sentinel always wipes, even
        // with a synthetic_access_token"). Force the seam to
        // release-like so the OPPO PARENT debug bypass doesn't kick
        // in. Cleared in tearDown.
        DeviceAuthManager.testIsMockOrSharedMockBuild = false
    }

    @org.junit.After
    fun tearDown() {
        DeviceAuthManager.testIsMockOrSharedMockBuild = null
    }

    private fun resetManagerInstance() {
        val field = DeviceAuthManager::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }

    private fun newManager(): DeviceAuthManager =
        DeviceAuthManager.getInstance(context)

    private fun prefs() =
        context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)

    /**
     * CC1 (was M1+M4 in the OLD migration test file): the clean-cutover
     * wipe of the legacy `parent-demo` sentinel. After Slice A lands,
     * `loadPersistedState` does NOT migrate — it WIPES.
     */
    @Test
    fun `loadPersistedState wipes stale parent-demo prefs`() {
        prefs().edit()
            .putString("role", "PARENT")
            .putString("parent_id", "parent-demo")
            .putString("synthetic_access_token", "anon-PARENT-pre-cloud-uuid")
            .commit()

        newManager() // cold start

        assertNull(
            "Clean cutover (Q2=b) MUST wipe legacy parent_id=\"parent-demo\" on " +
                "cold start. After Slice A, migrateStaleParentId is deleted and the " +
                "wipe replaces it. Keys after cold start: ${prefs().all.keys}",
            prefs().getString("parent_id", null)
        )
    }

    /**
     * CC2: a non-UUID `parent_id` (any other legacy sentinel) is also
     * wiped. Pins the over-eager-by-design wipe predicate.
     */
    @Test
    fun `loadPersistedState wipes non-UUID parent_id`() {
        prefs().edit()
            .putString("role", "PARENT")
            .putString("parent_id", "mock-parent-legacy-sentinel")
            .putString("synthetic_access_token", "anon-PARENT-legacy")
            .commit()

        newManager()

        assertNull(
            "Non-UUID parent_id values MUST be wiped (defense against future " +
                "mock-engine sentinel changes). Keys after cold start: ${prefs().all.keys}",
            prefs().getString("parent_id", null)
        )
    }

    /**
     * CC3: a real Supabase UUID parent_id is NOT wiped. Regression guard
     * so the wipe doesn't accidentally nuke legitimate cloud sessions.
     * (Was M3 in the OLD file, with slightly different semantics — the
     * "isNullOrEmpty" idempotency guard is replaced by a UUID format
     * check.)
     */
    @Test
    fun `loadPersistedState keeps real UUID parent_id`() {
        val realParentId = "550e8400-e29b-41d4-a716-446655440000"
        prefs().edit()
            .putString("role", "PARENT")
            .putString("parent_id", realParentId)
            .putString("synthetic_access_token", "anon-PARENT-post-cloud-uuid")
            .commit()

        newManager()

        assertEquals(
            "Real Supabase UUID parent_id MUST NOT be wiped. Only the legacy " +
                "\"parent-demo\" sentinel and other non-UUID strings are wiped. " +
                "Keys after cold start: ${prefs().all.keys}",
            realParentId,
            prefs().getString("parent_id", null)
        )
    }

    /**
     * CC4: CHILD prefs without parent_id are untouched. CHILD users
     * never have a parent_id in production (the role-aware
     * authenticateOrCreate at DeviceAuthManager.kt:218-225 only writes
     * parent_id for Role.PARENT). Same invariant as OLD M2.
     */
    @Test
    fun `loadPersistedState does NOT touch CHILD prefs without parent_id`() {
        prefs().edit()
            .putString("role", "CHILD")
            .putString("synthetic_access_token", "anon-CHILD-pre-cloud-uuid")
            .commit()

        newManager()

        assertTrue(
            "CHILD prefs without parent_id must remain parent_id-less. " +
                "Keys after cold start: ${prefs().all.keys}",
            !prefs().contains("parent_id")
        )
    }

    /**
     * CC5: orphan prefs without a `role` key are untouched (same as OLD M5).
     */
    @Test
    fun `loadPersistedState does NOT touch prefs without role`() {
        prefs().edit()
            .putString("synthetic_access_token", "orphan-token-no-role")
            .commit()

        newManager()

        assertNull(
            "Prefs without a role key must NOT have parent_id written. " +
                "Keys after cold start: ${prefs().all.keys}",
            prefs().getString("parent_id", null)
        )
    }

    /**
     * CC6: end-to-end contract — `getParentId()` returns `null` after
     * clean-cutover wipe. The OnboardingScreen would render the parent
     * sign-in form (the user is back to the "must re-authenticate"
     * state). This replaces OLD M4 (which expected "parent-demo" — that
     * was the migration behavior we're now deleting).
     */
    @Test
    fun `getParentId returns null after clean-cutover wipe`() {
        prefs().edit()
            .putString("role", "PARENT")
            .putString("parent_id", "parent-demo")
            .putString("synthetic_access_token", "anon-PARENT-pre-cloud-uuid")
            .commit()

        val coldStart = newManager()

        assertNull(
            "getParentId() MUST return null after clean-cutover wipe. " +
                "Today (Slice A GREEN) the legacy sentinel is wiped and getParentId() " +
                "returns null, surfacing the parent sign-in form. Pre-Slice-A " +
                "migrateStaleParentId would have written 'parent-demo' here.",
            coldStart.getParentId()
        )
    }
}
