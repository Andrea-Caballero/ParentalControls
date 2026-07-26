package com.tudominio.parentalcontrol.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RED tests for the cold-start session-restore bug discovered during the
 * 2026-07-07 live test session (`sdd/discovery/live-test-2026-07-07`):
 * after `adb shell am force-stop com.tudominio.parentalcontrol` and
 * reopening, the parent user is dropped back to the "Iniciar sesión como
 * padre" CTA (auth-missing error banner) on the dashboard.
 *
 * # Root cause
 *
 * The role-aware [DeviceAuthManager.authenticateOrCreate] overload (the
 * synthetic parent hotfix path from `hotfix-parent-auth-session`, see
 * `DeviceAuthManager.kt:193-213`) writes the in-memory token and
 * `role=PARENT` to SharedPreferences, but **does NOT** call
 * `persistSession(StoredSession(...))` for the synthetic token.
 *
 * Asymmetric persistence consequence (cold start):
 *  - `loadPersistedState()` reads `role=PARENT` and falls through the
 *    `when` block at `DeviceAuthManager.kt:481-485` to
 *    `SessionState.PAIRED` via the `hasRole` branch.
 *  - `loadPersistedState()` calls `restoreSession()` → returns null
 *    (no `encrypted_session` blob was ever written).
 *  - `currentAccessToken` stays null.
 *  - `AppNavHost.isPaired` returns true → `resolveInitialRoute(...)`
 *    returns `NavRoute.Dashboard` (no Onboarding screen).
 *  - `ParentViewModel.init { loadDevices() }` →
 *    `ParentRepository.getDevices()` returns
 *    `Result.failure(DeviceListError.AuthMissing)` because
 *    `authManager.getAccessToken()` is null (`ParentRepository.kt:294-295`).
 *  - `DashboardScreen` renders the `AuthMissingErrorBanner` ("Iniciar
 *    sesión como padre" CTA — `DashboardScreen.kt:529-571`).
 *
 * The 2026-07-02 fix at
 * `archive/2026-07-02-fix-auth-session-restore-on-cold-start/` only
 * covered the `handleAuthSuccess` write path (which DID call
 * `persistSession`). The synthetic parent path was missed.
 *
 * # Fix shape (proposed for the proposal phase)
 *
 * Persist the synthetic token to a cleartext `synthetic_access_token`
 * key in `device_auth_prefs` (the synthetic token has no real security
 * value — it's a local-only identifier; Keystore encryption is overkill
 * for the hotfix path). Mirror the write in
 * `authenticateOrCreate(role: Role)` and the read in
 * `loadPersistedState` (analogous to the
 * `restoreSession()?.let { stored -> ... }` block at
 * `DeviceAuthManager.kt:498-502`).
 *
 * Alternative (encrypted_session via `persistSession`) is also valid; the
 * proposal phase picks the mechanism. The RED tests below pin the
 * **persistence invariant** without binding to a specific key name —
 * the apply phase may use `synthetic_access_token` (cleartext, no
 * Keystore dependency) or `encrypted_session` (consistent with the
 * device-auth path; Robolectric tests would need the
 * `encryptWithKeystore` spy pattern from `DeviceAuthManagerColdStartTest`
 * to assert GREEN cleanly).
 *
 * Both tests are RED on `master = f54f2b0` because the synthetic parent
 * path does not persist the token anywhere reachable on cold start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeviceAuthManagerAuthenticatePersistTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Wipe leftover prefs (clean slate). Matches the existing
        // DeviceAuthManagerRoleTest.setUp pattern.
        context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        // Reset the [DeviceAuthManager] companion-level `instance` field so
        // each test sees a fresh manager. Same reflection seam as
        // DeviceAuthManagerColdStartTest.resetManagerInstance().
        resetManagerInstance()
    }

    private fun resetManagerInstance() {
        val field = DeviceAuthManager::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }

    private fun newManager(): DeviceAuthManager =
        DeviceAuthManager.getInstance(context)

    /**
     * RED on `master = f54f2b0`: the synthetic parent path
     * (`authenticateOrCreate(role = PARENT)`) must persist the access
     * token to a prefs key that survives process death, so a cold-start
     * `loadPersistedState` can repopulate `currentAccessToken`.
     *
     * Today the role-aware overload only writes `role` to
     * `device_auth_prefs` (`DeviceAuthManager.kt:203-206`); the token
     * lives only in `currentAccessToken` (line 197), which is reset by
     * process death. The cold-start manager's `getAccessToken()` returns
     * null, surfacing as the "Iniciar sesión como padre" CTA on the
     * dashboard.
     *
     * The exact prefs key name is a fix-design decision deferred to the
     * proposal phase (`synthetic_access_token` for the cleartext option
     * is the most likely candidate). The assertion is intentionally
     * loose — any persistence key under the `device_auth_prefs` namespace
     * that the apply phase introduces qualifies.
     */
    @Test
    fun `authenticateOrCreate with PARENT persists access token to prefs for cold-start hydration`() = runTest {
        val manager = newManager()

        manager.authenticateOrCreate(Role.PARENT)

        val prefs = context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
        // The apply phase introduces exactly one of:
        //   - `synthetic_access_token` (cleartext; proposal pick)
        //   - `encrypted_session` (mirrors the device-auth path; requires
        //     `persistSession` call)
        // Both end up in `device_auth_prefs`. Today neither is written
        // by the role-aware overload — RED.
        val allKeys = prefs.all.keys
        val persistedTokenKey = allKeys.firstOrNull { key ->
            key == "synthetic_access_token" || key == "encrypted_session"
        }
        assertNotNull(
            "authenticateOrCreate(role=PARENT) must persist the synthetic token under " +
                "`synthetic_access_token` or `encrypted_session` in `device_auth_prefs`. " +
                "Today it only writes `role`; on cold start getAccessToken() returns null " +
                "and the dashboard shows the 'Iniciar sesión como padre' CTA. " +
                "Keys present: $allKeys",
            persistedTokenKey
        )
    }

    /**
     * RED on `master = f54f2b0`: a fresh `DeviceAuthManager` instance
     * created AFTER `authenticateOrCreate(Role.PARENT)` (simulating
     * process death + cold start) must observe a non-null
     * `getAccessToken()`.
     *
     * Today: `loadPersistedState()` does not read any synthetic-token key
     * (the only key the synthetic path writes is `role`, which is used
     * only for the `_sessionState` `when` branch — not for the token
     * fields at lines 481-502). `restoreSession()` returns null
     * because no `encrypted_session` blob was written. Result:
     * `currentAccessToken` stays null → `getAccessToken()` returns null.
     *
     * Reproduces the live-test 2026-07-07 bug exactly. The fix adds a
     * read-path for the new synthetic-token key (parallel to the
     * existing `restoreSession()?.let { ... }` block at
     * `DeviceAuthManager.kt:498-502`), so the cold-start manager's
     * `getAccessToken()` returns the synthetic token and the dashboard
     * renders the device list instead of the auth-missing banner.
     *
     * NOTE: This test is a direct contract test for the cold-start
     * invariant. The fix may choose to (a) add a new cleartext
     * `synthetic_access_token` key + read in `loadPersistedState`, or
     * (b) call `persistSession` from `authenticateOrCreate(role)` (and
     * rely on the existing `restoreSession` read path). Either way, the
     * second manager's `getAccessToken()` must be non-null.
     */
    @Test
    fun `cold start after authenticateOrCreate with PARENT restores accessToken`() = runTest {
        val first = newManager()
        first.authenticateOrCreate(Role.PARENT)
        val tokenAfterFirstAuth = first.getAccessToken()
        assertNotNull(
            "Sanity: getAccessToken() must be non-null right after authenticateOrCreate",
            tokenAfterFirstAuth
        )

        // Simulate process death: reset the singleton so the next
        // getInstance() builds a fresh manager that re-runs
        // `init { loadPersistedState() }`.
        resetManagerInstance()
        val coldStart = newManager()

        assertNotNull(
            "After cold start following authenticateOrCreate(Role.PARENT), " +
                "getAccessToken() must be non-null. Today the synthetic parent path " +
                "does not persist the token to disk; loadPersistedState leaves " +
                "currentAccessToken = null; the dashboard shows the 'Iniciar sesión " +
                "como padre' CTA. First auth produced: $tokenAfterFirstAuth",
            coldStart.getAccessToken()
        )
        assertEquals(
            "Cold-start restored token must equal the token the previous session wrote",
            tokenAfterFirstAuth,
            coldStart.getAccessToken()
        )
    }

    /**
     * NEW GREEN (Q2=y symmetry): mirrors T1.2 with `Role.CHILD`. The
     * role-aware overload at `DeviceAuthManager.kt:193` covers both roles,
     * so the bug is symmetric — the fix must restore the token regardless
     * of which role was used.
     */
    @Test
    fun `cold start after authenticateOrCreate with CHILD restores accessToken`() = runTest {
        val first = newManager()
        first.authenticateOrCreate(Role.CHILD)
        val tokenAfterFirstAuth = first.getAccessToken()
        assertNotNull(
            "Sanity: getAccessToken() must be non-null right after authenticateOrCreate",
            tokenAfterFirstAuth
        )

        resetManagerInstance()
        val coldStart = newManager()

        assertNotNull(
            "After cold start following authenticateOrCreate(Role.CHILD), " +
                "getAccessToken() must be non-null (Q2=y symmetry).",
            coldStart.getAccessToken()
        )
        assertEquals(
            "Cold-start restored CHILD token must equal the token the previous session wrote",
            tokenAfterFirstAuth,
            coldStart.getAccessToken()
        )
    }

    /**
     * NEW GREEN (Q3=y defense-in-depth): `clearSession()` at
     * `DeviceAuthManager.kt:405-419` calls `.clear()` on
     * `device_auth_prefs`, which wipes ALL keys including the new
     * `synthetic_access_token`. This test pins that invariant so a
     * future refactor (e.g., switching to `remove("...")` for one key)
     * doesn't accidentally leave the synthetic token behind.
     */
    @Test
    fun `clearSession also clears synthetic_access_token`() = runTest {
        val manager = newManager()
        manager.authenticateOrCreate(Role.PARENT)

        val prefs = context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
        assertTrue(
            "Sanity: synthetic_access_token must be present after authenticateOrCreate(PARENT)",
            prefs.contains("synthetic_access_token")
        )

        manager.clearSession()

        assertFalse(
            "clearSession() must remove the synthetic_access_token key (Q3=y defense-in-depth). " +
                "Today the .clear() call at DeviceAuthManager.kt:415 covers it; this test pins " +
                "that invariant against future remove(...) refactors.",
            prefs.contains("synthetic_access_token")
        )
    }

    /**
     * NEW GREEN (negative case): a stray `synthetic_access_token` key
     * without a matching `role` key must NOT hydrate
     * `currentAccessToken`. Pins the `prefs.contains("role")` guard in
     * `loadPersistedState()` — defends against a partial-write regression
     * where `apply()` is interrupted between the two `putString` calls.
     */
    @Test
    fun `cold start with synthetic_access_token but missing role leaves token null`() = runTest {
        context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("synthetic_access_token", "orphan-token")
            // role intentionally NOT written
            .commit()

        val coldStart = newManager()

        assertNull(
            "A synthetic_access_token without a matching role key must NOT hydrate " +
                "currentAccessToken. Pins the prefs.contains(\"role\") guard in loadPersistedState.",
            coldStart.getAccessToken()
        )
    }

    /**
     * NEW GREEN (round-trip): after authenticateOrCreate(PARENT) →
     * clearSession() → authenticateOrCreate(PARENT), a fresh cold-start
     * manager must observe the SECOND auth's token, not the FIRST.
     * Defends against ordering bugs between in-memory and on-disk writes
     * (e.g., a stale on-disk value being re-hydrated by the second
     * auth's read).
     */
    @Test
    fun `round-trip after clearSession produces fresh token`() = runTest {
        val first = newManager()
        first.authenticateOrCreate(Role.PARENT)
        val tokenA = first.getAccessToken()
        assertNotNull(tokenA)

        first.clearSession()

        val tokenAAfterClear = first.getAccessToken()
        assertNull(
            "Sanity: clearSession must wipe the in-memory token before the second auth",
            tokenAAfterClear
        )

        first.authenticateOrCreate(Role.PARENT)
        val tokenB = first.getAccessToken()
        assertNotNull(tokenB)
        assertFalse(
            "Second authenticateOrCreate must produce a fresh in-memory token (different UUID)",
            tokenA == tokenB
        )

        // Now simulate process death and verify cold-start reads token B,
        // not a stale token A from before the clear.
        resetManagerInstance()
        val coldStart = newManager()

        assertEquals(
            "Cold start after the round-trip must restore token B (the last auth), " +
                "NOT token A (the auth before clearSession).",
            tokenB,
            coldStart.getAccessToken()
        )
    }

    /**
     * F1 RED→GREEN — release-like invalid PARENT install. The OPPO
     * PARENT synthetic bypass is NARROWED to debug/mock/shared builds
     * (`USE_MOCK_SUPABASE || USE_SHARED_MOCK`). In a release build
     * the clean-cutover wipe still fires so a stale `parent-demo`
     * sentinel cannot leak into a real cloud session.
     *
     * The `DeviceAuthManager.testForceReleaseLikeBuild` companion
     * seam lets us pin the release-like branch without mutating
     * BuildConfig finals (JDK 17+ removed `Field.modifiers`
     * reflection tricks — see loadPersistedState kdoc).
     */
    @Test
    fun `cold start with PARENT synthetic in release-like build must still wipe`() = runTest {
        try {
            // Force the build-mode gate to release-like (wipe fires
            // even with a synthetic token present at role=PARENT).
            DeviceAuthManager.testIsMockOrSharedMockBuild = false

            context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("role", Role.PARENT.name)
                .putString("parent_id", "parent-demo")
                .putString("synthetic_access_token", "anon-PARENT-release-flag")
                .commit()

            newManager()

            val prefs = context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            assertNull(
                "Release-like build (USE_MOCK_SUPABASE=false, " +
                    "USE_SHARED_MOCK=false) with role=PARENT + " +
                    "synthetic_access_token + parent_id=parent-demo MUST " +
                    "still wipe — OPPO PARENT bypass must be debug-only.",
                prefs.getString("parent_id", null)
            )
        } finally {
            DeviceAuthManager.testIsMockOrSharedMockBuild = null
        }
    }

    /**
     * F1 RED→GREEN — debug/MockSupabaseEngine build: PARENT synthetic
     * + parent_id=parent-demo + role=PARENT + synthetic_access_token
     * preserves the synthetic session across cold start (OPPO bug
     * analog for PARENT).
     */
    @Test
    fun `cold start with PARENT synthetic in debug build preserves the synthetic token`() = runTest {
        // The default JVM BuildConfig has `USE_MOCK_SUPABASE=true`,
        // the same shape as the OPPO PARENT debug/mock scenario
        // the OPPO task fix targets.
        val token = "anon-PARENT-restore-target-uuid"
        context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("role", Role.PARENT.name)
            .putString("parent_id", "parent-demo")
            .putString("synthetic_access_token", token)
            .commit()

        val coldStart = newManager()

        assertEquals(
            "Debug build (USE_MOCK_SUPABASE=true) with role=PARENT + " +
                "synthetic_access_token + parent_id=parent-demo MUST " +
                "preserve the synthetic token — OPPO PARENT analog fix.",
            token,
            coldStart.getAccessToken()
        )
    }
}
