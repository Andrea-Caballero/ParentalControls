package com.tudominio.parentalcontrol.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the cold-start session-restore path in [DeviceAuthManager].
 *
 * Bug surface: [DeviceAuthManager.loadPersistedState] only restores
 * `_deviceId` and `_sessionState`; it never decrypts the
 * `encrypted_session` blob. After process death, `currentAccessToken` is
 * null until [DeviceAuthManager.authenticateOrCreate] runs, so any
 * consumer that reads `getAccessToken()` between `Application.onCreate`
 * and `DeviceAuthService.start()` throws
 * `IllegalStateException("no access token")` (the throwing site is
 * `SyncManager.kt:491-494` in the OutboxDrainer).
 *
 * The 4 cases below pin the cold-start invariant:
 *  - **1.1 RED** (`init_with_valid_encrypted_session_populates_accessToken`):
 *    when [DeviceAuthManager.restoreSession] returns a non-null
 *    [StoredSession], the new manager instance's `init {}` →
 *    `loadPersistedState` must populate `currentAccessToken`,
 *    `currentRefreshToken`, and `sessionExpiresAt`. Today `loadPersistedState`
 *    doesn't decrypt the blob, so the in-memory fields stay null.
 *    Verified via `spyk(realManager)` + reflection-based `loadPersistedState`
 *    re-invocation — Robolectric 4.10.3 does not provide
 *    `KeyStore.getInstance("AndroidKeyStore")` (the project's JCA provider
 *    is BouncyCastle, which can't generate the GCM key the production
 *    `encryptWithKeystore` writes), so the standard "write blob via
 *    `authenticateOrCreate` no-arg, reset singleton, observe restored token"
 *    round-trip is not reproducible under the unit-test source set. The
 *    spy-driven approach tests the exact fix code path
 *    (`restoreSession()?.let { stored -> ... }`) with a controlled
 *    `StoredSession` instead of the real (Robolectric-incompatible) decrypt.
 *  - **1.2 RED** (`init_with_isPaired_but_missing_deviceId_sets_PAIRED_when_role_persisted`):
 *    OPPO edge case where `is_paired=true` + `role=PARENT` are persisted
 *    but `device_id` is missing. Today the `when` falls through to
 *    `SessionState.NONE`; the bugfix surfaces `SessionState.PAIRED`.
 *  - **1.3 GREEN pin** (`init_without_encrypted_session_leaves_token_null`):
 *    fresh-install path — token must stay null, no exception.
 *  - **1.4 GREEN pin** (`init_with_undecryptable_encrypted_session_leaves_token_null`):
 *    malformed/expired blob path — token must stay null, no exception.
 *    Pinned via the undecryptable-blob path because there is no public
 *    test seam to write an `encrypted_session` whose decrypt succeeds
 *    but carries an expired `expiresAt`. `restoreSession` returns null
 *    in both cases; for the cold-start invariant the distinction doesn't
 *    matter — what matters is "token stays null, init does not throw".
 *
 * Sibling to `DeviceAuthManagerRoleTest`, which covers the role-aware
 * `authenticateOrCreate(role: Role)` overload and does NOT exercise the
 * cold-start path. Both files share the `device_auth_prefs` SharedPreferences
 * namespace; `setUp` clears both the prefs and the [DeviceAuthManager]
 * singleton so each test sees a true cold start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeviceAuthManagerColdStartTest {

    private lateinit var context: Context

    // Mirrors `DeviceAuthManager.json` configuration so test-time
    // JSON encoding/decoding matches the production serializer.
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Wipe leftover prefs (clean slate).
        context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        // Reset the [DeviceAuthManager] companion-level `instance` field so
        // each test sees a fresh manager. The field is private; reflection
        // is the only way to clear it without exposing a test-only API on
        // production code.
        resetManagerInstance()
    }

    private fun freshManager(): DeviceAuthManager =
        DeviceAuthManager.getInstance(context)

    private fun resetManagerInstance() {
        // The companion-level `instance` field is compiled as a
        // `private static volatile` field on `DeviceAuthManager` itself
        // (the companion methods access it via synthetic accessors), so
        // we reflect on the outer class rather than `DeviceAuthManager.Companion`.
        val field = DeviceAuthManager::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }

    private fun newRealManagerForSpy(): DeviceAuthManager {
        // Reverted to the (Context) primary ctor — W1 cipher extraction
        // introduced `DeviceAuthManager.testCipherOverride` instead,
        // which the field initializer of `sessionCipher` consumes at
        // construction time. Tests that need to bypass the production
        // Keystore set the static before construction; this test uses
        // `spyk` to intercept `restoreSession()` instead, so the cipher
        // is never invoked from the test code path.
        val ctor = DeviceAuthManager::class.java
            .getDeclaredConstructor(Context::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(context) as DeviceAuthManager
    }

    private fun readStringField(manager: Any, name: String): String? {
        val f = manager::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(manager) as String?
    }

    private fun readLongField(manager: Any, name: String): Long {
        val f = manager::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(manager) as Long
    }

    private fun invokeLoadPersistedState(manager: Any) {
        val m = manager::class.java.getDeclaredMethod("loadPersistedState")
        m.isAccessible = true
        m.invoke(manager)
    }

    /**
     * RED on `master = 7cd7092`: cold-start restore must populate the 3
     * token fields when [DeviceAuthManager.restoreSession] returns a valid
     * [StoredSession].
     *
     * Verified via reflection + the [TestableAuthCipher] seam (mirrors
     * `DeviceAuthManagerParentSessionCipherTest.testableManager()`).
     * Earlier versions of this test used `spyk` (MockK) to stub
     * `restoreSession()`, but W1 (cipher extraction) added an `internal
     * var sessionCipher` field whose field initializer is consulted by
     * the [DeviceAuthManager] constructor — MockK's `spyk(real)` proxy
     * interferes with the field initializer (the autoHint invocation
     * during `every {}` setup observes the incomplete state). The
     * reflection-based seam is cleaner: pre-populate prefs with an
     * `encrypted_session` blob that decrypts cleanly via the test
     * cipher, then invoke the production `loadPersistedState` via
     * reflection and verify the in-memory fields are populated.
     */
    @Test
    fun `init with valid encrypted session populates accessToken`() {
        val expected = StoredSession(
            accessToken = "restored-access-token-abc",
            refreshToken = "restored-refresh-token-xyz",
            expiresAt = 9999999999L,
            deviceId = "device-anonymous-restored",
            userId = "user-restored"
        )
        // The production cipher requires AndroidKeyStore which Robolectric
        // can't supply. Persist an `encrypted_session` blob the test cipher
        // can decrypt cleanly so `loadPersistedState` populates the
        // fields symmetrically with the production write path.
        val expectedJson = json.encodeToString(expected)
        val encryptedBlob = TestableAuthCipher().encrypt(expectedJson)
        context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("encrypted_session", encryptedBlob)
            .commit()

        // Bind the test cipher BEFORE construction so `loadPersistedState`
        // (which runs in `init {}`) decrypts with the test cipher instead
        // of the production `AuthCipher`.
        DeviceAuthManager.testCipherOverride = TestableAuthCipher()

        val real = try {
            newRealManagerForSpy()
        } finally {
            DeviceAuthManager.testCipherOverride = null
        }

        // Assert the `init { loadPersistedState }` populated the fields from
        // the decrypted `StoredSession`.
        assertEquals(
            "loadPersistedState must populate currentAccessToken from restored session",
            expected.accessToken,
            readStringField(real, "currentAccessToken")
        )
        assertEquals(
            "loadPersistedState must populate currentRefreshToken from restored session",
            expected.refreshToken,
            readStringField(real, "currentRefreshToken")
        )
        assertEquals(
            "loadPersistedState must populate sessionExpiresAt from restored session",
            expected.expiresAt,
            readLongField(real, "sessionExpiresAt")
        )
    }

    /**
     * RED on `master = 7cd7092`: OPPO edge case where `is_paired=true` +
     * `role=PARENT` are persisted but `device_id` is missing. Today the
     * `when` block at `loadPersistedState` falls through to
     * `SessionState.NONE`.
     */
    @Test
    fun `init with isPaired but missing deviceId sets PAIRED when role persisted`() = runTest {
        context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("role", "PARENT")
            .putBoolean("is_paired", true)
            // device_id intentionally NOT written (OPPO simulated eviction)
            .commit()

        val coldStart = freshManager()
        assertEquals(
            "OPPO edge case: PAIRED with role must not fall to NONE",
            SessionState.PAIRED,
            coldStart.sessionState.value
        )
    }

    /**
     * CONTROL GREEN (must stay green): fresh-install path. No blob, no
     * throw, token stays null, state stays NONE.
     */
    @Test
    fun `init without encrypted session leaves token null and does not throw`() = runTest {
        val coldStart = freshManager()
        assertNull("No blob → token must remain null", coldStart.getAccessToken())
        assertEquals(SessionState.NONE, coldStart.sessionState.value)
    }

    /**
     * CONTROL GREEN (must stay green): malformed blob path. The decrypt
     * helper throws, [DeviceAuthManager.restoreSession] returns null via
     * the `catch` block, and `loadPersistedState` propagates that null to
     * `currentAccessToken`. Pinned here so the fix in Phase 3 does not
     * regress this path (e.g. by adding a crashy `.also { throw }`).
     */
    @Test
    fun `init with undecryptable encrypted session does not throw and leaves token null`() = runTest {
        context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("encrypted_session", "not_valid_base64!@#")
            .commit()

        val coldStart = freshManager()
        assertNull("Malformed blob → token must remain null", coldStart.getAccessToken())
    }
}
