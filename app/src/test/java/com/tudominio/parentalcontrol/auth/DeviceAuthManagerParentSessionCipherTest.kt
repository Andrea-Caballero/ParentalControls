package com.tudominio.parentalcontrol.auth

import android.content.Context
import android.util.Base64
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RED→GREEN tests for **WARNING-1 (W1) — Cipher the parent session at rest**
 * (`openspec/changes/feat-cross-device-pairing-and-approval/verify-report.md`).
 *
 * Before this slice, `DeviceAuthManager.persistParentSession` at the Slice
 * A master version wrote `access_token` and `refresh_token` as cleartext
 * SharedPreferences strings alongside `parent_id` + `role`. The child
 * anonymous-auth path uses `persistSession(StoredSession)` +
 * `encryptWithKeystore` to write a single encrypted `encrypted_session`
 * blob. This divergence meant parent tokens — including the JWT that
 * carries the `parent_id` claim — sat on disk in cleartext, recoverable
 * via `adb backup` on rooted devices or `run-as` on a debuggable build.
 *
 * This file pins the encrypted-at-rest contract with three RED tests. The
 * fix mirrors the child path: serialize `ParentSession` to JSON, encrypt
 * with `encryptWithKeystore`, write as a single `encrypted_parent_session`
 * blob, and decrypt on `loadPersistedState`.
 *
 * # Robolectric Keystore caveat
 *
 * Robolectric 4.10.3 (this project's JCA provider is BouncyCastle, NOT
 * the Android Keystore) does not provide `KeyStore.getInstance(
 * "AndroidKeyStore")`, which `encryptWithKeystore` requires via
 * `getOrCreateAuthKey()`. See `DeviceAuthManagerColdStartTest` kdoc lines
 * 36–43 for the long explanation.
 *
 * The two round-trip tests below work around this with the
 * `TestableAuthCipher` — a small test-only `AuthCipher` subclass that
 * overrides `encrypt` / `decrypt` with a deterministic base64
 * round-trip. Tests rebind it onto the manager's `sessionCipher` field
 * via reflection. This exercises the EXACT production write-and-read
 * code paths (`persistParentSession` → `encrypt`,
 * `loadPersistedState` → `decrypt`) while sidestepping the cipher that
 * the JVM unit-test runtime cannot supply. The third test deliberately
 * writes a tampered blob to verify the decryption-failure branch (the
 * override throws on invalid base64, simulating a real decryption
 * failure).
 *
 * # Production surfacing
 *
 * The seam requires `AuthCipher` to be `internal open` (declared at the
 * bottom of `DeviceAuthManager.kt`), `encryptWithKeystore` /
 * `decryptWithKeystore` to be `internal fun` (mirrors the existing
 * `restoreSession` `internal` visibility — same seam pattern), and the
 * `sessionCipher` field to be `internal var` so reflection-rebinding
 * works without exposing the seam to library consumers. None of these
 * changes increase the public API surface that consumers of the library
 * can see — `DeviceAuthManager` is still `class` (final) with `private
 * constructor`, library consumers continue to use `getInstance`.
 *
 * # Test cases
 *
 * - **PSC-1** `persistParentSession_writes_encrypted_blob_not_plaintext_access_token_in_prefs`:
 *   invoking `verifyMagicLinkOtp` happy path must produce
 *   `encrypted_parent_session` (encrypted blob) and must NOT have any
 *   `access_token` or `refresh_token` key in cleartext under
 *   `device_auth_prefs`.
 * - **PSC-2** `loadPersistedState_after_persistParentSession_restores_parent_session_via_decrypt`:
 *   write via `persistParentSession` (round-trip via the test override),
 *   simulate process death, then a cold-start manager's `getAccessToken()`
 *   must equal the original.
 * - **PSC-3** `loadPersistedState_with_corrupted_encrypted_blob_returns_null_no_throw`:
 *   pre-populate prefs with a `role=PARENT`, `parent_id=<UUID>`,
 *   `encrypted_parent_session=<tampered string>` triple. Cold-start manager
 *   must not throw, and `getParentId()` must return null (decryption
 *   failure path wipes parent-specific keys so the user lands on the
 *   sign-in screen).
 *
 * Mirrors the red→green→refactor approach used by
 * `DeviceAuthManagerColdStartTest` (three test cases pinning the
 * cold-start invariant via a similar reflection-based bypass).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeviceAuthManagerParentSessionCipherTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clean slate (mirror DeviceAuthManagerMagicLinkTest.setUp).
        context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        resetManagerInstance()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        resetManagerInstance()
    }

    private fun resetManagerInstance() {
        val field = DeviceAuthManager::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }

    private fun prefs() =
        context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)

    /**
     * Constructs a fresh [DeviceAuthManager] with the
     * [TestableAuthCipher] bound **before** the `init { loadPersistedState }`
     * block runs. Uses the
     * `DeviceAuthManager.testCipherOverride` static seam (in
     * `DeviceAuthManager.kt`): tests set the static to a
     * [TestableAuthCipher], construct a fresh manager via
     * [DeviceAuthManager.getInstance] (which calls the production
     * `AuthCipher()` constructor), and the field initializer reads the
     * static and uses the test cipher. After construction, the static
     * is cleared by the field initializer's `also { … = null }` block,
     * so subsequent constructions revert to the production cipher.
     *
     * Robolectric 4.10.3 cannot instantiate `AndroidKeyStore`, and the
     * production cipher requires it (the project's JVM JCA provider is
     * BouncyCastle, which lacks the Android Keystore implementation).
     * The round-trip + write tests in this file use this seam so the
     * production `init { loadPersistedState }` decrypt path can run
     * end-to-end inside the JVM unit test.
     */
    private fun testableManager(): DeviceAuthManager {
        resetManagerInstance()
        DeviceAuthManager.testCipherOverride = TestableAuthCipher()
        try {
            return DeviceAuthManager.getInstance(context)
        } finally {
            // Defensive: if getInstance throws, clear the override
            // so it doesn't leak into other tests.
            DeviceAuthManager.testCipherOverride = null
        }
    }

    private fun injectHttpClient(manager: DeviceAuthManager, client: HttpClient) {
        val field = DeviceAuthManager::class.java.getDeclaredField("httpClient")
        field.isAccessible = true
        field.set(manager, client)
    }

    /**
     * PSC-1 (RED on master `@ b56cc9c`): the write side of the encrypted
     * parent blob contract.
     *
     * Before the fix, `persistParentSession` writes `access_token` +
     * `refresh_token` as cleartext. The test asserts:
     *  - `access_token` and `refresh_token` are NOT present in
     *    `device_auth_prefs` (cleartext invariant).
     *  - `encrypted_parent_session` IS present and contains the
     *    parentId / accessToken / refreshToken (encrypted blob invariant).
     *  - `role` is `PARENT` and `parent_id` equals the original UUID
     *    (per the existing magic-link verify contract pinned by
     *    `DeviceAuthManagerMagicLinkTest.verifyMagicLinkOtp_validToken`).
     *
     * The `TestableAuthCipher` (declared at the bottom of this file)
     * replaces `AuthCipher.encrypt` with a base64-of-input passthrough, so
     * the encrypted blob in prefs is a base64-encoded JSON envelope we
     * can decode and inspect.
     */
    @Test
    fun persistParentSession_writes_encrypted_blob_not_plaintext_access_token_in_prefs() = runBlocking {
        val parentId = "550e8400-e29b-41d4-a716-446655440000"
        val accessToken = "jwt-access-token-cipher-test"
        val refreshToken = "jwt-refresh-token-cipher-test"
        val tokenHash = "token-hash-cipher"
        val email = "[email protected]"

        val manager = testableManager()

        // Ktor MockEngine returns a valid verify response. The path mirrors
        // `DeviceAuthManagerMagicLinkTest.verifyMagicLinkOtp_validToken`.
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/auth/v1/verify")) {
                respond(
                    content = ByteReadChannel(
                        """
                        {
                          "access_token":"$accessToken",
                          "refresh_token":"$refreshToken",
                          "expires_in":3600,
                          "user":{
                            "id":"$parentId",
                            "email":"$email",
                            "app_metadata":{"parent_id":"$parentId"}
                          }
                        }
                        """.trimIndent()
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                respond(content = ByteReadChannel("{}"), status = HttpStatusCode.InternalServerError)
            }
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json() } }
        injectHttpClient(manager, client)

        val result = manager.verifyMagicLinkOtp(tokenHash, email)

        assertTrue(
            "verifyMagicLinkOtp must succeed so persistParentSession runs. Got: $result",
            result.isSuccess
        )

        // Assertion 1: NO cleartext access_token key.
        assertNull(
            "access_token must NOT be written as cleartext under device_auth_prefs " +
                "(W1 contract — WARNING-1 closure). Keys present: ${prefs().all.keys}",
            prefs().getString("access_token", null)
        )
        // Assertion 2: NO cleartext refresh_token key.
        assertNull(
            "refresh_token must NOT be written as cleartext under device_auth_prefs " +
                "(W1 contract). Keys present: ${prefs().all.keys}",
            prefs().getString("refresh_token", null)
        )
        // Assertion 3: encrypted_parent_session MUST be present.
        assertTrue(
            "encrypted_parent_session MUST be present after persistParentSession " +
                "(W1 contract). Keys present: ${prefs().all.keys}",
            prefs().contains("encrypted_parent_session")
        )
        // Assertion 4: the encrypted blob, when decoded via the override's
        // base64 passthrough, must contain the parentId, accessToken, and
        // refreshToken (the JSON envelope from `persistParentSession`).
        val encryptedBlob = prefs().getString("encrypted_parent_session", null)
        assertNotNull("encrypted_parent_session value is non-null", encryptedBlob)
        val decryptedJson = String(Base64.decode(encryptedBlob!!, Base64.NO_WRAP))
        assertTrue(
            "Encrypted blob must contain accessToken. Decoded: $decryptedJson",
            decryptedJson.contains(accessToken)
        )
        assertTrue(
            "Encrypted blob must contain refreshToken. Decoded: $decryptedJson",
            decryptedJson.contains(refreshToken)
        )
        assertTrue(
            "Encrypted blob must contain parentId. Decoded: $decryptedJson",
            decryptedJson.contains(parentId)
        )
        // Assertion 5: parent_id + role cleartext keys (NOT secret, used by
        // getParentId() and the existing clean-cutover UUID check).
        assertEquals(
            "parent_id cleartext key is preserved at $parentId",
            parentId,
            prefs().getString("parent_id", null)
        )
        assertEquals(
            "role=PARENT cleartext key is preserved",
            "PARENT",
            prefs().getString("role", null)
        )
    }

    /**
     * PSC-2 (RED on master `@ b56cc9c`): the round-trip side of the
     * encrypted parent blob contract.
     *
     * After `persistParentSession` writes the encrypted blob, a cold-start
     * manager's `getAccessToken()` must equal the original access token
     * (and `getParentId()` must equal the parentId). This pins the
     * `loadPersistedState` decrypt + populate the way the existing
     * `DeviceAuthManagerColdStartTest.init_with_valid_encrypted_session_populates_accessToken`
     * pins the `encrypted_session` (child) restore path.
     *
     * `TestableAuthCipher` overrides encrypt/decrypt to be a
     * deterministic base64 round-trip so the real write-and-read code
     * paths run end-to-end inside the JVM test (without the real Android
     * Keystore). The second `testableManager()` simulates process death
     * (a fresh singleton instance triggers `init { loadPersistedState }`).
     */
    @Test
    fun loadPersistedState_after_persistParentSession_restores_parent_session_via_decrypt() = runBlocking {
        val parentId = "550e8400-e29b-41d4-a716-446655440000"
        val accessToken = "jwt-round-trip-access-token-xyz"
        val refreshToken = "jwt-round-trip-refresh-token-xyz"
        val tokenHash = "token-hash-round-trip"
        val email = "[email protected]"

        val first = testableManager()

        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/auth/v1/verify")) {
                respond(
                    content = ByteReadChannel(
                        """
                        {
                          "access_token":"$accessToken",
                          "refresh_token":"$refreshToken",
                          "expires_in":3600,
                          "user":{
                            "id":"$parentId",
                            "email":"$email",
                            "app_metadata":{"parent_id":"$parentId"}
                          }
                        }
                        """.trimIndent()
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                respond(content = ByteReadChannel("{}"), status = HttpStatusCode.InternalServerError)
            }
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json() } }
        injectHttpClient(first, client)

        // Write the encrypted blob via the production write path.
        val result = first.verifyMagicLinkOtp(tokenHash, email)
        assertTrue(
            "verifyMagicLinkOtp must succeed for the round-trip setup. Got: $result",
            result.isSuccess
        )

        // Sanity: encrypted_parent_session is present in prefs.
        assertNotNull(
            "Sanity: encrypted_parent_session must be persisted before cold start",
            prefs().getString("encrypted_parent_session", null)
        )

        // Simulate process death: a fresh manager instance triggers
        // `init { loadPersistedState() }`. The round-trip must restore
        // the original accessToken via the encrypted blob.
        val coldStart = testableManager()
        assertEquals(
            "Cold start must restore the original accessToken via the encrypted blob",
            accessToken,
            coldStart.getAccessToken()
        )
        assertEquals(
            "Cold start must restore the parentId (mirrors getParentId for the " +
                "encrypted parent path)",
            parentId,
            coldStart.getParentId()
        )
    }

    /**
     * PSC-3 (RED on master `@ b56cc9c`): the decryption-failure branch.
     *
     * Pre-populate prefs with role=PARENT + parent_id=<UUID> +
     * encrypted_parent_session=<tampered garbage string>. A cold-start
     * manager must NOT throw and must wipe the parent-specific keys so
     * `getParentId()` returns null (the user re-authenticates on the next
     * interaction rather than seeing a stale PAIRED state with no token).
     *
     * `TestableAuthCipher.decryptWithKeystore` does
     * `String(Base64.decode(...))` — this throws on invalid base64
     * (`not_valid_base64!@#` is not valid base64 per the strict charset),
     * so the production `try { ... } catch (e: Exception) { ... wipe ... }`
     * branch in `loadPersistedState` fires.
     *
     * Mirrors `DeviceAuthManagerColdStartTest.init_with_undecryptable_encrypted_session`
     * for the child path (`encrypted_session`).
     */
    @Test
    fun loadPersistedState_with_corrupted_encrypted_blob_returns_null_no_throw() = runBlocking {
        val realParentId = "550e8400-e29b-41d4-a716-446655440000"
        prefs().edit()
            .putString("role", "PARENT")
            .putString("parent_id", realParentId)
            .putString("encrypted_parent_session", "not_valid_base64!@#")
            .commit()

        // Cold start: must NOT throw, must wipe parent_id so the user
        // sees the sign-in screen on next interaction.
        val coldStart = testableManager()

        assertNull(
            "Corrupted encrypted_parent_session must NOT crash loadPersistedState. " +
                "After the decrypt-failed path the wipe must leave parent_id=null " +
                "so getParentId() returns null. " +
                "Keys after cold start: ${prefs().all.keys}",
            coldStart.getParentId()
        )
        // The corrupted blob key should also be cleared by the wipe so a
        // subsequent cold start doesn't repeatedly attempt + fail.
        assertTrue(
            "Corrupted blob must be cleared from prefs after the failure path. " +
                "Keys after cold start: ${prefs().all.keys}",
            !prefs().contains("encrypted_parent_session")
        )
    }
}

/**
 * Test-only [AuthCipher] that overrides `encrypt` / `decrypt` with a
 * deterministic base64 round-trip. Used by [DeviceAuthManagerParentSessionCipherTest]
 * to verify the production write-and-read code paths under Robolectric 4.10.3,
 * which cannot instantiate `AndroidKeyStore` (the project's JVM JCA provider is
 * BouncyCastle, which lacks the Android Keystore implementation that the real
 * `AuthCipher` requires).
 *
 * The override is a 100% symmetric round-trip — `Base64.encodeToString`
 * followed by `Base64.decode` returns the original UTF-8 bytes. The real
 * cipher (`AES/GCM/NoPadding`) is exercised only on-device + on real
 * installs (the test confirms the JSON-envelope shape; runtime
 * correctness of the AES cipher is covered by Android Keystore itself).
 */
internal class TestableAuthCipher : AuthCipher() {
    override fun encrypt(data: String): String =
        Base64.encodeToString(data.toByteArray(), Base64.NO_WRAP)

    override fun decrypt(encryptedData: String): String =
        String(Base64.decode(encryptedData, Base64.NO_WRAP))
}
