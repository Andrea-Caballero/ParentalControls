package com.tudominio.parentalcontrol.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R1.5 — RED→GREEN tests for [DeviceAuthManager.loadPersistedState]
 * clean-cutover wipe + [DeviceAuthManager.completePairing] /
 * [DeviceAuthManager.savePairedSession] session adoption.
 *
 * Pre-fix the clean-cutover wipe clobbered a freshly-restored child
 * session (the OPPO bug). Fix: short-circuit the wipe when ANY
 * restorable signal (paired, role=CHILD, encrypted_session, or
 * synthetic token) is present.
 *
 * `completePairing` no longer adopts token fields from the response
 * body (per R1.5 — the production pairing shape only carries
 * `{ device_id, parent_id }`); the agent's pre-pairing anon JWT
 * (carried in the Authorization header) stays valid and is persisted
 * by `savePairedSession` for cold-start restoration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeviceAuthManagerChildSessionRestoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        resetInstance()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        resetInstance()
    }

    private fun resetInstance() {
        DeviceAuthManager::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, null)
        }
    }

    private fun manager() = DeviceAuthManager.getInstance(context)

    /** Deterministic base64 round-trip cipher override. */
    private class TestableCipher : AuthCipher() {
        override fun encrypt(data: String): String =
            android.util.Base64.encodeToString(data.toByteArray(), android.util.Base64.NO_WRAP)
        override fun decrypt(encryptedData: String): String =
            String(android.util.Base64.decode(encryptedData, android.util.Base64.NO_WRAP))
    }

    private fun managerWithTestCipher(): DeviceAuthManager {
        resetInstance()
        DeviceAuthManager.testCipherOverride = TestableCipher()
        return try {
            DeviceAuthManager.getInstance(context)
        } finally {
            DeviceAuthManager.testCipherOverride = null
        }
    }

    /**
     * OPPO scenario: paired-child install whose mock placeholder
     * `parent_id` survives the clean-cutover wipe. Pre-fix the wipe
     * clobbered `encrypted_session` so the OutboxDrainer got
     * RetryableFailure. The role-aware predicate preserves the
     * synthetic hydration.
     */
    @Test
    fun cleanCutoverWipe_skippedForPairedChildAndSyntheticToken() = runBlocking {
        val childToken = "anon-CHILD-restore-target-uuid"
        context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("is_paired", true)
            .putString("device_id", "dev-moto-oppo-child")
            .putString("parent_id", "parent-uuid-aaaa-bbbb-cccc")
            .putString("role", Role.CHILD.name)
            .putString("synthetic_access_token", childToken)
            .apply()

        val m = manager()
        val prefs = context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
        assertTrue(
            "Paired-child prefs must survive cold start. Keys: " +
                prefs.all.keys,
            prefs.contains("parent_id")
        )
        assertEquals(
            "Paired-child + role=CHILD must short-circuit the legacy " +
                "clean-cutover wipe. Got: ${m.sessionState.value}",
            SessionState.PAIRED,
            m.sessionState.value
        )
        assertEquals(
            "Cold start must restore the synthetic CHILD token so the " +
                "OutboxDrainer can use it. Got: ${m.getAccessToken()}",
            childToken,
            m.getAccessToken()
        )
    }

    /**
     * Regression guard: a wholly-empty legacy install with only the
     * `parent-demo` sentinel MUST still wipe (pre-PR-#27 stale state).
     */
    @Test
    fun cleanCutoverWipe_stillFiresForLegacySentinelAlone() = runBlocking {
        context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .edit().putString("parent_id", "parent-demo").apply()

        val m = manager()
        val prefs = context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
        assertFalse(
            "Legacy parent-demo alone must wipe on cold start. " +
                "Keys: ${prefs.all.keys}",
            prefs.contains("parent_id")
        )
        assertEquals(SessionState.NONE, m.sessionState.value)
    }

    /**
     * R1.6 — release-like invalid session wipe. A `role=CHILD` install
     * whose `parent_id` is the legacy non-UUID sentinel BUT has NO
     * encrypted_session AND NO synthetic_token (i.e. nothing real to
     * hydrate) MUST still wipe so the user lands on sign-in for a
     * fresh session — the OPPO bug fix must NOT broadly preserve
     * malformed non-UUID sessions in release mode.
     */
    @Test
    fun cleanCutoverWipe_stillFiresForRoleChild_aloneWithLegacySentinel() = runBlocking {
        context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("is_paired", true)
            .putString("device_id", "dev-orphan-no-session")
            .putString("parent_id", "parent-demo")
            .putString("role", Role.CHILD.name)
            .apply()
        // Deliberately NO encrypted_session and NO synthetic_access_token
        // so there is nothing real to hydrate. The wipe MUST fire.

        val m = manager()
        val prefs = context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
        assertFalse(
            "Release-like invalid CHILD session (no encrypted_session, " +
                "no synthetic token) must WIPE on cold start so the " +
                "user re-auths. Keys: ${prefs.all.keys}",
            prefs.contains("is_paired")
        )
        assertEquals(
            "After the wipe the sessionState must be NONE — the user " +
                "lands on sign-in for a fresh session.",
            SessionState.NONE, m.sessionState.value
        )
        assertNull(
            "getAccessToken() must return null after the release wipe.",
            m.getAccessToken()
        )
    }

    /**
     * R1.6 — parent unaffected. A PARENT-side install whose `parent_id`
     * is a non-UUID sentinel MUST be migrated to a fresh sign-in
     * (the OPPO parent re-auth contract is preserved) regardless of
     * what the CHILD branch does.
     */
    @Test
    fun cleanCutoverWipe_stillFiresForRoleParent_aloneWithLegacySentinel() = runBlocking {
        context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("role", Role.PARENT.name)
            .putString("parent_id", "parent-demo")
            .apply()

        val m = manager()
        val prefs = context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
        assertFalse(
            "PARENT-side legacy 'parent-demo' install must still wipe " +
                "(parent re-auth contract is preserved, OPPO child fix " +
                "must not relax this). Keys: ${prefs.all.keys}",
            prefs.contains("parent_id")
        )
        assertEquals(SessionState.NONE, m.sessionState.value)
    }

    private fun assertNull(message: String, value: Any?) =
        org.junit.Assert.assertNull(message, value)

    /**
     * R1.5 — `completePairing` does NOT swap the in-memory token
     * from the response body. The pre-pairing anonymous JWT
     * (already in memory from `createAnonymousSession`) remains the
     * current bearer; `savePairedSession` persists it for cold
     * start. The response carries only `{ device_id, parent_id }`.
     */
    @Test
    fun completePairing_keepsAnonAccessTokenAfterPairing() = runBlocking {
        val first = managerWithTestCipher()
        val anonToken = "anon-CHILD-restored-after-pairing"
        val anonRefresh = "anon-refresh"
        DeviceAuthManager::class.java.getDeclaredField("currentAccessToken")
            .apply { isAccessible = true }.set(first, anonToken)
        DeviceAuthManager::class.java.getDeclaredField("currentRefreshToken")
            .apply { isAccessible = true }.set(first, anonRefresh)

        // Production pairing response shape: only `{ device_id, parent_id }`.
        val body = """{"device_id":"device-child-emulator-001",
            "parent_id":"00000000-0000-0000-0000-000000000001"}"""
        val client = HttpClient(
            MockEngine {
                respond(
                    content = ByteReadChannel(body),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        ) { install(ContentNegotiation) { json() } }
        DeviceAuthManager::class.java.getDeclaredField("httpClient")
            .apply { isAccessible = true }
            .set(first, client)

        val r = first.completePairing("ABCDEFGH")
        assertEquals(true, r is AuthResult.Success)
        assertEquals(
            "completePairing MUST NOT swap the anon access token — " +
                "the pre-pairing anon JWT remains the bearer so saved-" +
                "PairedSession persists it for cold-start restore.",
            anonToken,
            first.getAccessToken()
        )
    }
}
