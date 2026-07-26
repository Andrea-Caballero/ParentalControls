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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R1.1 — RED→GREEN tests for [DeviceAuthManager.createAnonymousSession]
 * migrating from random-email `/auth/v1/token?grant_type=password`
 * (which required the server to enable password auth + a synthetic
 * user) to the official Supabase flow `POST /auth/v1/signup` with
 * `{}` that creates a real anonymous-auth user (per Supabase docs).
 *
 * Anonymous sign-ins MUST be enabled at the Supabase project level
 * (`enable_anonymous_sign_ins=true` or Dashboard config). The
 * shared-mock backend mirrors the production signup shape via the
 * `mock-supabase/auth-anonymous.json` fixture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeviceAuthManagerAnonymousSignupTest {

    private lateinit var context: Context
    private lateinit var manager: DeviceAuthManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        resetManagerInstance()
        // Robolectric 4.10.3 lacks AndroidKeyStore. Install the
        // deterministic base64 test-cipher override BEFORE the manager
        // constructs so `init { loadPersistedState }` runs the
        // test-decrypt path.
        DeviceAuthManager.testCipherOverride = TestableCipher()
        manager = DeviceAuthManager.getInstance(context)
        injectHttpClient(manager, buildMockClient(SIGNUP_RESPONSE_BODY))
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        resetManagerInstance()
        DeviceAuthManager.testCipherOverride = null
    }

    private fun resetManagerInstance() {
        DeviceAuthManager::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, null)
        }
    }

    private fun injectHttpClient(m: DeviceAuthManager, c: HttpClient) {
        DeviceAuthManager::class.java.getDeclaredField("httpClient").apply {
            isAccessible = true
            set(m, c)
        }
    }

    private fun buildMockClient(body: String): HttpClient =
        HttpClient(MockEngine {
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }) { install(ContentNegotiation) { json() } }

    /** Deterministic base64 round-trip cipher override. Mirrors the
     * `TestableAuthCipher` from `DeviceAuthManagerParentSessionCipherTest`. */
    private class TestableCipher : AuthCipher() {
        override fun encrypt(data: String): String =
            android.util.Base64.encodeToString(data.toByteArray(), android.util.Base64.NO_WRAP)
        override fun decrypt(encryptedData: String): String =
            String(android.util.Base64.decode(encryptedData, android.util.Base64.NO_WRAP))
    }

    /**
     * RED on master: `createAnonymousSession` must hit
     * `/auth/v1/signup` (NOT `/auth/v1/token?grant_type=password`).
     */
    @Test
    fun `createAnonymousSession calls POST auth signup with empty body`() = runBlocking {
        var capturedPath: String? = null
        val client = HttpClient(MockEngine { request ->
            capturedPath = request.url.encodedPath
            respond(
                content = ByteReadChannel(SIGNUP_RESPONSE_BODY),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }) { install(ContentNegotiation) { json() } }
        injectHttpClient(manager, client)

        val result = manager.createAnonymousSession()
        assertEquals(
            "createAnonymousSession must report success. Got: $result",
            true,
            result is AuthResult.Success
        )
        assertEquals(
            "createAnonymousSession must hit /auth/v1/signup (official " +
                "Supabase anonymous-auth entry point), not the deprecated " +
                "/auth/v1/token?grant_type=password.",
            "/auth/v1/signup",
            capturedPath
        )
    }

    /**
     * RED on master: session persisted to SharedPreferences must carry
     * the REAL `access_token` from the signup response — NOT a
     * fabricated string. Cold-start restore must observe the same
     * token the previous session wrote.
     */
    @Test
    fun `cold start restores REAL signup tokens, never a fabricated string`() = runBlocking {
        val first = DeviceAuthManager.getInstance(context)
        val firstClient = buildMockClient(SIGNUP_RESPONSE_BODY)
        injectHttpClient(first, firstClient)

        val r = first.createAnonymousSession()
        assertEquals(true, r is AuthResult.Success)
        assertEquals(
            "getAccessToken must equal the access_token the signup " +
                "response returned — never fabricate anon-...",
            "mock-access-token-anonymous-session-001",
            first.getAccessToken()
        )

        // Cold start: a fresh manager must restore.
        DeviceAuthManager.testCipherOverride = TestableCipher()
        resetManagerInstance()
        val coldStart = DeviceAuthManager.getInstance(context)
        assertEquals(
            "Cold start must restore the access_token that was " +
                "persisted. Got null.",
            "mock-access-token-anonymous-session-001",
            coldStart.getAccessToken()
        )
    }

    companion object {
        // Production-shaped AnonAccessTokenResponse (per Supabase docs).
        private const val SIGNUP_RESPONSE_BODY = """
            {
              "access_token": "mock-access-token-anonymous-session-001",
              "refresh_token": "mock-refresh-token-anonymous-session-001",
              "expires_in": 3600,
              "expires_at": 9999999999,
              "user": {
                "id": "00000000-0000-0000-0000-000000000001",
                "email": "anonymous@placeholder.local",
                "app_metadata": {
                  "device_id": "device-anonymous-001"
                }
              }
            }
        """
    }
}
