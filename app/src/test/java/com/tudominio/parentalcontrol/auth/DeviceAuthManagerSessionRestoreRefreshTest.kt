package com.tudominio.parentalcontrol.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
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
 * RED→GREEN tests for the moto bug: [DeviceAuthManager.restoreSession]
 * silently returned `null` when the persisted `expires_at` was in the
 * past, leaving the `OutboxDrainer` retrying every ~30s with `no access
 * token`. The fix refreshes the persisted session via the stored
 * `refresh_token`, persists the refreshed `StoredSession`, and returns
 * it. HTTP failure (401/4xx/5xx/network) → log + return `null`
 * (pre-fix behaviour preserved). The HTTP path is shared with
 * `refreshSession()` via the private `performTokenRefresh(...)` helper
 * so the network call + JSON parsing live in exactly one place.
 *
 * # Test ordering note
 * `init { loadPersistedState() }` runs the FIRST `restoreSession()` against
 * the production-default `MockSupabaseEngine` httpClient (Robolectric's
 * `USE_MOCK_SUPABASE=true`). Tests that exercise the expiry path MUST
 * build manager → inject mock → seed expired → call `restoreSession()`,
 * otherwise the init-time call hits the default mock, persists a
 * refreshed blob, and the explicit call short-circuits via the
 * unexpired branch without ever touching the injected mock.
 *
 * # Coverage
 *  - T1 (happy path): expired `StoredSession` + 200 → refreshed session
 *    returned; blob re-persisted; deviceId preserved.
 *  - T2 (HTTP failure): expired `StoredSession` + 401 → returns `null`;
 *    on-disk blob untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeviceAuthManagerSessionRestoreRefreshTest {

    private lateinit var context: Context

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        resetManagerInstance()
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

    /** Build a fresh [DeviceAuthManager] with [TestableAuthCipher] bound
     * BEFORE `init { loadPersistedState }` runs (Robolectric has no
     * AndroidKeyStore, so the production cipher can't be instantiated). */
    private fun managerWithTestCipher(): DeviceAuthManager {
        resetManagerInstance()
        DeviceAuthManager.testCipherOverride = TestableAuthCipher()
        return try {
            DeviceAuthManager.getInstance(context)
        } finally {
            DeviceAuthManager.testCipherOverride = null
        }
    }

    private fun seedExpiredSession(
        refreshToken: String = "stale-refresh-token-must-be-used",
        deviceId: String? = "device-moto-child",
        userId: String = "user-stale-anon",
        accessToken: String = "stale-access-token"
    ): StoredSession {
        val expired = StoredSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAt = (System.currentTimeMillis() / 1000) - 60,
            deviceId = deviceId,
            userId = userId
        )
        val encryptedBlob = TestableAuthCipher().encrypt(json.encodeToString(expired))
        context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("encrypted_session", encryptedBlob)
            .commit()
        return expired
    }

    /** T1 — refresh happy path. Order: build → inject mock → seed expired → call. */
    @Test
    fun restoreSession_refreshesExpiredSessionUsingStoredRefreshToken() = runBlocking {
        val manager = managerWithTestCipher()
        val newExpiresAt = (System.currentTimeMillis() / 1000) + 3600
        val refreshResponse = """
            {
              "access_token": "refreshed-access-token",
              "refresh_token": "refreshed-refresh-token-rotated",
              "expires_in": 3600,
              "expires_at": $newExpiresAt,
              "user": { "id": "user-stale-anon", "email": "anonymous@placeholder.local" }
            }
        """.trimIndent()
        var capturedPath: String? = null
        var capturedBodyText: String? = null
        val mockClient = HttpClient(
            MockEngine { request ->
                capturedPath = request.url.encodedPath + "?" + request.url.encodedQuery
                capturedBodyText = (request.body as TextContent).text
                respond(
                    content = ByteReadChannel(refreshResponse),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        ) { install(ContentNegotiation) { json() } }
        injectHttpClient(manager, mockClient)

        val original = seedExpiredSession()

        val restored = manager.restoreSession()

        assertNotNull(
            "restoreSession MUST return the refreshed session (moto bug " +
                "returns null and OutboxDrainer loops with 'no access token').",
            restored
        )
        assertEquals(
            "refreshed access_token from the mock response",
            "refreshed-access-token",
            restored!!.accessToken
        )
        assertEquals(
            "rotated refresh_token from the mock response",
            "refreshed-refresh-token-rotated",
            restored.refreshToken
        )
        assertEquals(
            "updated expires_at from the mock response",
            newExpiresAt,
            restored.expiresAt
        )
        assertEquals(
            "deviceId preserved across refresh (not erased)",
            original.deviceId,
            restored.deviceId
        )
        val requestPath = capturedPath!!
        assertTrue(
            "request must hit /auth/v1/token?grant_type=refresh_token. Got: $requestPath",
            requestPath.contains("/auth/v1/token") &&
                requestPath.contains("grant_type=refresh_token")
        )
        val requestBody = capturedBodyText!!
        assertTrue(
            "request body must contain the stored refresh_token. Got: $requestBody",
            requestBody.contains(original.refreshToken)
        )

        // Persistence invariant: blob on disk must carry the refreshed token.
        val updatedBlob = context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .getString("encrypted_session", null)
        assertNotNull("encrypted_session must be re-persisted after refresh", updatedBlob)
        val decrypted = TestableAuthCipher().decrypt(updatedBlob!!)
        assertTrue(
            "persisted blob must contain refreshed access_token. Got: $decrypted",
            decrypted.contains("refreshed-access-token")
        )
        assertTrue(
            "persisted blob must contain rotated refresh_token. Got: $decrypted",
            decrypted.contains("refreshed-refresh-token-rotated")
        )
    }

    /** T2 — refresh endpoint 401 → returns `null`, blob untouched. */
    @Test
    fun restoreSession_returnsNullWhenRefreshEndpointReturns401() = runBlocking {
        val manager = managerWithTestCipher()
        val failingClient = HttpClient(
            MockEngine {
                respond(
                    content = ByteReadChannel("""{"error":"invalid_grant"}"""),
                    status = HttpStatusCode.Unauthorized,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        ) { install(ContentNegotiation) { json() } }
        injectHttpClient(manager, failingClient)

        seedExpiredSession()

        val restored = manager.restoreSession()
        assertNull(
            "On 401, restoreSession must return null (pre-fix behaviour).",
            restored
        )
        val blob = context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .getString("encrypted_session", null)
        assertNotNull("original blob must remain on disk after refresh failure", blob)
        val decrypted = TestableAuthCipher().decrypt(blob!!)
        assertFalse(
            "persisted blob must NOT contain refreshed access_token after 401. Got: $decrypted",
            decrypted.contains("refreshed-access-token")
        )
        assertTrue(
            "persisted blob must STILL contain the stale access_token. Got: $decrypted",
            decrypted.contains("stale-access-token")
        )
    }
}
