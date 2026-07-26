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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RED tests for the parent magic-link sign-in path (Slice A of
 * `openspec/changes/feat-cross-device-pairing-and-approval`).
 *
 * Q1=b at the design gate picked magic-link over email+password (per
 * `tasks.md` resolved-questions Q1=B). The apply phase implements
 * [DeviceAuthManager.signInWithMagicLink] + [DeviceAuthManager.verifyMagicLinkOtp]
 * + [ParentSession] + atomic persistence in `device_auth_prefs`. These
 * four RED tests pin the contract from `parent-auth-session/spec.md`
 * `ADDED Requirements` (magic-link variants):
 *
 *  - **A.1.1** `signInWithMagicLink_happyPath` — Ktor `MockEngine` returns
 *    `200 { message_id: "<uuid>" }` on `POST /auth/v1/magiclink`. The method
 *    must return success carrying the message id.
 *  - **A.1.2** `signInWithMagicLink_invalidEmail` — Ktor `MockEngine`
 *    returns `400 { error: "invalid_email" }`. The method must surface a
 *    `ParentAuthError.InvalidEmail` and must NOT touch `device_auth_prefs`.
 *  - **A.1.3** `verifyMagicLinkOtp_validToken` — Ktor `MockEngine` returns
 *    a full `SupabaseAuthResponse` with `user.app_metadata.parent_id =
 *    "uuid-p"`. The method must return `ParentSession(parentId="uuid-p")`
 *    and must persist the session to `device_auth_prefs` with
 *    `role=PARENT, parent_id=uuid-p`.
 *  - **A.1.4** `cleanCutover_staleParentIdWiped` — pre-cloud legacy prefs
 *    (`role=PARENT, parent_id="parent-demo"`) are wiped on cold start
 *    (Q2=b clean cutover; no `MOCK_PARENT_ID` fallback). `getParentId()`
 *    must return `null` after a fresh manager init against such prefs.
 *
 * # Implementation note: HttpClient injection without MockK
 *
 * The pre-existing test infrastructure on this dev machine shows
 * pre-existing MockK + JDK 21 incompatibility (the 83 pre-existing failures
 * observed at slice start; see apply-progress baseline). This file
 * therefore avoids MockK entirely and uses Robolectric + a Ktor
 * [MockEngine] injected via reflection on the private `httpClient` field —
 * the same reflection seam `DeviceAuthManagerColdStartTest` already uses
 * for `currentAccessToken` etc.
 *
 * For GREEN to make these tests pass, [DeviceAuthManager] must accept an
 * `HttpClient` via either (a) a new secondary constructor or (b) a public
 * setter/test seam. The apply phase picks the lightest option (a
 * secondary constructor) — see `DeviceAuthManager.kt` comments near the
 * `private val httpClient` field.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeviceAuthManagerMagicLinkTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clean slate: wipe both prefs and the singleton.
        context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        resetManagerInstance()
    }

    @After
    fun tearDown() {
        // Reset again so a subsequent test class sees a fresh singleton.
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

    private fun freshManager(): DeviceAuthManager =
        DeviceAuthManager.getInstance(context)

    /**
     * W1 (cipher-at-rest) seam — constructs a fresh manager with a
     * `TestableAuthCipher` bound BEFORE `init { loadPersistedState
     * }` runs. Uses the `DeviceAuthManager.testCipherOverride` static
     * (see `DeviceAuthManager.kt` companion) — the same seam the
     * cipher tests use. Robolectric 4.10.3 cannot instantiate
     * `AndroidKeyStore`, so the production `AuthCipher` throws
     * `KeyStoreException` and `persistParentSession` aborts with
     * `ParentAuthError.Unknown`. Setting the override to
     * [TestableAuthCipher] before construction lets the verifyMagicLink
     * happy path run end-to-end inside the JVM unit test.
     */
    private fun freshManagerWithTestCipher(): DeviceAuthManager {
        // Avoid leaking into other tests: each call pairs Set/Reset.
        DeviceAuthManager.testCipherOverride = TestableAuthCipher()
        return try {
            DeviceAuthManager.getInstance(context)
        } finally {
            DeviceAuthManager.testCipherOverride = null
        }
    }

    /**
     * Helper that injects a [HttpClient] into a [DeviceAuthManager] via
     * reflection. The private `httpClient` field is the seam — same
     * pattern as `DeviceAuthManagerColdStartTest` for the token fields.
     */
    private fun injectHttpClient(manager: DeviceAuthManager, client: HttpClient) {
        val field = DeviceAuthManager::class.java.getDeclaredField("httpClient")
        field.isAccessible = true
        field.set(manager, client)
    }

    /**
     * **A.1.1** RED on `master = 3f3a81d`: `signInWithMagicLink` does not
     * exist yet — compile error on this test file's reference to the
     * method (the strictest form of RED: the production symbol is
     * literally missing, so even the test class won't load).
     *
     * Once the GREEN step adds `signInWithMagicLink`, the test must pass:
     * Ktor `MockEngine` returns `200 { message_id: "<uuid>" }` on
     * `POST /auth/v1/magiclink` and the method returns
     * `Result.success(MagicLinkSent(messageId="<uuid>"))`.
     */
    @Test
    fun signInWithMagicLink_happyPath() = runBlocking {
        val messageId = "msg-uuid-aaaa"
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/auth/v1/magiclink")) {
                respond(
                    content = ByteReadChannel("""{"message_id":"$messageId"}"""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                respond(
                    content = ByteReadChannel("""{"error":"unhandled"}"""),
                    status = HttpStatusCode.InternalServerError
                )
            }
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json() }
        }
        val manager = freshManager()
        injectHttpClient(manager, client)

        val result = manager.signInWithMagicLink("parent@example.com")

        assertTrue(
            "signInWithMagicLink(happy) must return Result.success. Got: $result",
            result.isSuccess
        )
        val sent = result.getOrNull()
        assertNotNull("Result must carry a non-null MagicLinkSent payload", sent)
        assertEquals(
            "MagicLinkSent.messageId must equal the value Supabase returned",
            messageId,
            sent!!.messageId
        )
    }

    /**
     * **A.1.2** RED on `master = 3f3a81d`: same compile-time RED as
     * A.1.1. Once GREEN lands, the test must pass: a 400
     * `invalid_email` from Supabase surfaces as
     * `Result.failure(ParentAuthError.InvalidEmail)` and `device_auth_prefs`
     * is untouched (no `parent_id`, no `role`, no `access_token` writes).
     */
    @Test
    fun signInWithMagicLink_invalidEmail() = runBlocking {
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/auth/v1/magiclink")) {
                respond(
                    content = ByteReadChannel("""{"error":"invalid_email"}"""),
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                respond(
                    content = ByteReadChannel("{}"),
                    status = HttpStatusCode.InternalServerError
                )
            }
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json() }
        }
        val manager = freshManager()
        injectHttpClient(manager, client)

        val result = manager.signInWithMagicLink("not-an-email")

        assertTrue(
            "signInWithMagicLink(invalid email) must return Result.failure. Got: $result",
            result.isFailure
        )
        val err = result.exceptionOrNull()
        assertNotNull("Failure must carry a non-null exception", err)
        assertEquals(
            "Failure must be ParentAuthError.InvalidEmail (per slice A contract)",
            "InvalidEmail",
            err!!.javaClass.simpleName
        )
        // Atomic-prefs invariant: the failure path must NOT touch
        // device_auth_prefs (no parent_id, no role, no token).
        assertTrue(
            "Invalid-email path must NOT persist parent_id. Keys present: ${prefs().all.keys}",
            !prefs().contains("parent_id")
        )
        assertTrue(
            "Invalid-email path must NOT persist role. Keys present: ${prefs().all.keys}",
            !prefs().contains("role")
        )
    }

    /**
     * **A.1.3** RED on `master = 3f3a81d`: same compile-time RED. Once
     * GREEN lands, the test must pass: Ktor `MockEngine` returns a full
     * `SupabaseAuthResponse` with `user.app_metadata.parent_id =
     * "uuid-p"`. The method must return
     * `ParentSession(parentId="uuid-p", accessToken=...)` AND must
     * atomically persist the session to `device_auth_prefs` with
     * `role=PARENT` and `parent_id="uuid-p"`.
     */
    @Test
    fun verifyMagicLinkOtp_validToken() = runBlocking {
        val accessToken = "jwt-access-token-xyz"
        val refreshToken = "jwt-refresh-token-xyz"
        val parentId = "uuid-p"
        val tokenHash = "token-hash-abc"
        val email = "parent@example.com"
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/auth/v1/verify")) {
                respond(
                    content = ByteReadChannel(
                        """
                        {
                          "access_token":"$accessToken",
                          "refresh_token":"$refreshToken",
                          "expires_in":3600,
                          "expires_at":${System.currentTimeMillis() / 1000 + 3600},
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
                respond(
                    content = ByteReadChannel("{}"),
                    status = HttpStatusCode.InternalServerError
                )
            }
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json() }
        }
        // W1 (cipher-at-rest) seam — construct with TestableAuthCipher so
        // `persistParentSession` can run under Robolectric 4.10.3 (which
        // cannot instantiate AndroidKeyStore).
        val manager = freshManagerWithTestCipher()
        injectHttpClient(manager, client)

        val result = manager.verifyMagicLinkOtp(tokenHash, email)

        assertTrue(
            "verifyMagicLinkOtp(valid) must return Result.success. Got: $result",
            result.isSuccess
        )
        val session = result.getOrNull()
        assertNotNull("Result must carry a non-null ParentSession", session)
        assertEquals(
            "ParentSession.parentId must equal app_metadata.parent_id from Supabase",
            parentId,
            session!!.parentId
        )
        assertEquals(
            "ParentSession.accessToken must equal the access_token from Supabase",
            accessToken,
            session.accessToken
        )
        // Atomic persistence (W1 closure): role=PARENT + parent_id=uuid-p + encrypted_parent_session=<blob>
        // must be in prefs. The `access_token` + `refresh_token` keys are
        // NEVER written in cleartext — WARNING-1 closure.
        assertEquals(
            "verifyMagicLinkOtp must persist role=PARENT to device_auth_prefs. Keys: ${prefs().all.keys}",
            "PARENT",
            prefs().getString("role", null)
        )
        assertEquals(
            "verifyMagicLinkOtp must persist parent_id=$parentId to device_auth_prefs",
            parentId,
            prefs().getString("parent_id", null)
        )
        assertTrue(
            "verifyMagicLinkOtp must persist encrypted_parent_session " +
                "to device_auth_prefs (W1 closure). Keys: " +
                "${prefs().all.keys}",
            prefs().contains("encrypted_parent_session")
        )
        assertNull(
            "access_token must NEVER be in cleartext under device_auth_prefs (W1 closure). Keys: ${prefs().all.keys}",
            prefs().getString("access_token", null)
        )
        assertNull(
            "refresh_token must NEVER be in cleartext under device_auth_prefs (W1 closure). Keys: ${prefs().all.keys}",
            prefs().getString("refresh_token", null)
        )
    }

    /**
     * **A.1.4** RED on `master = 3f3a81d`: the clean-cutover wipe.
     * Pre-cloud legacy prefs (`role=PARENT, parent_id="parent-demo",
     * synthetic_access_token=...`) MUST be wiped on the next cold-start
     * `loadPersistedState` (per Q2=b; the `migrateStaleParentId` helper at
     * `DeviceAuthManager.kt:581-590` from PR #28 is deleted). The wipe is
     * unconditional on `parent_id == "parent-demo"` because the legacy
     * `MOCK_PARENT_ID` sentinel is not a real Supabase UUID.
     *
     * Once GREEN lands, this test must pass: after a fresh manager init,
     * `device_auth_prefs` contains no `parent_id` key (no migration to
     * `MOCK_PARENT_ID`), `getParentId()` returns `null`, and the
     * OnboardingScreen would render the parent sign-in form (the latter
     * is verified by the absence of the legacy sentinel, not by rendering
     * the Compose UI in this unit test).
     */
    @Test
    fun cleanCutover_staleParentIdWiped() = runBlocking {
        // Pre-cloud legacy state. F1 narrows the OPPO PARENT synthetic
        // bypass to debug/mock/shared builds only; this test pins the
        // release-like wipe path so the legacy migration contract is
        // not regressed. We force the seam to release-like so the
        // wipe fires even in the JVM unit-test debug build.
        DeviceAuthManager.testIsMockOrSharedMockBuild = false
        try {
            prefs().edit()
                .putString("role", "PARENT")
                .putString("parent_id", "parent-demo")
                .putString("synthetic_access_token", "anon-PARENT-pre-cloud-uuid")
                .commit()

            // Cold start: build a fresh manager whose init { loadPersistedState }
            // must wipe the stale `parent-demo` key.
            val coldStart = freshManager()

            assertNull(
                "Clean-cutover (Q2=b) MUST wipe legacy parent_id=\"parent-demo\" on " +
                    "cold start. Today loadPersistedState calls migrateStaleParentId " +
                    "which re-writes \"parent-demo\" from MOCK_PARENT_ID (PR #28). " +
                    "Keys present after cold start: ${prefs().all.keys}",
                prefs().getString("parent_id", null)
            )
            assertNull(
                "getParentId() must return null after clean-cutover wipe",
                coldStart.getParentId()
            )
        } finally {
            DeviceAuthManager.testIsMockOrSharedMockBuild = null
        }
    }

    /**
     * Negative-control companion to **A.1.4**: a LEGITIMATE Supabase UUID
     * parent_id (not the `"parent-demo"` sentinel) must NOT be wiped.
     * Pins the wipe predicate against an over-eager "wipe all parent_id"
     * implementation that would break parents who actually have a real
     * `auth.users.id` in their prefs (e.g., a previous cloud install).
     *
     * GREEN must keep this test green.
     */
    @Test
    fun cleanCutover_doesNotWipeRealUuidParentId() = runBlocking {
        val realParentId = "550e8400-e29b-41d4-a716-446655440000"
        prefs().edit()
            .putString("role", "PARENT")
            .putString("parent_id", realParentId)
            .putString("synthetic_access_token", "anon-PARENT-post-cloud-uuid")
            .commit()

        freshManager() // cold start

        assertEquals(
            "Clean-cutover MUST NOT wipe a real Supabase UUID parent_id. " +
                "Only the legacy \"parent-demo\" sentinel is wiped. " +
                "Keys present after cold start: ${prefs().all.keys}",
            realParentId,
            prefs().getString("parent_id", null)
        )
    }
}
