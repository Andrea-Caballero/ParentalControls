package com.tudominio.parentalcontrol.data.repository

import android.content.Context
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.network.SupabaseClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WU-2 — `lockDevice` / `unlockDevice` POST the auth-gated
 * `set-device-state` edge function. Pre-fix the methods were TODO
 * stubs returning `true`; the OPPO bug surfaced as the UI reloading
 * the unchanged ACTIVE state on every callback.
 *
 * RLS contract (`supabase/migrations/002_rls_policies.sql`):
 * `devices_parent_update` does NOT exist — parents cannot PATCH
 * directly. The edge function is the only safe surface to mutate
 * `device_state` from a parent's session; it runs as `service_role`
 * after validating the parent's JWT + ownership (mirrors
 * `create-pairing-code`).
 */
class ParentRepositorySetDeviceStateTest {

    private lateinit var captured: MutableList<HttpRequestData>
    private lateinit var authManager: DeviceAuthManager
    private lateinit var clientProvider: SupabaseClientProvider
    private lateinit var context: Context
    private lateinit var repository: ParentRepository

    @Before
    fun setUp() {
        captured = mutableListOf()
        val mockEngine = MockEngine { request ->
            captured.add(request)
            respond(
                content = ByteReadChannel(SUCCESS_BODY),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        authManager = mockk()
        every { authManager.getAccessToken() } returns "test-parent-jwt"
        clientProvider = mockk()
        every { clientProvider.httpClient } returns client
        context = mockk(relaxed = true)
        repository = ParentRepository(
            context = context,
            authManager = authManager,
            clientProvider = clientProvider,
        )
    }

    @After
    fun tearDown() { /* mock clients are scoped */ }

    @Test
    fun lockDevice_postsSetDeviceStateEdgeFunction() = runTest {
        val ok = repository.lockDevice("dev-001")
        assertTrue("lockDevice must report success on 2xx. Got: $ok", ok)
        val req = captured.single()
        assertEquals(HttpMethod.Post, req.method)
        assertTrue(
            "URL must contain /functions/v1/set-device-state, got: ${req.url}",
            req.url.toString().contains("/functions/v1/set-device-state")
        )
        assertEquals(
            "Authorization must carry the parent's bearer token.",
            "Bearer test-parent-jwt", req.headers[HttpHeaders.Authorization]
        )
        assertEquals(
            "apikey must be the Supabase anon key.",
            SupabaseClientProvider.SUPABASE_ANON_KEY, req.headers["apikey"]
        )
        val body = textOf(req)
        assertTrue(
            "Body must carry device_id. Got: $body",
            body.contains("\"device_id\":\"dev-001\"")
        )
        assertTrue(
            "Body must carry state=LOCKED. Got: $body",
            body.contains("\"state\":\"LOCKED\"")
        )
    }

    @Test
    fun unlockDevice_postsSetDeviceStateEdgeFunction() = runTest {
        val ok = repository.unlockDevice("dev-001")
        assertTrue("unlockDevice must report success on 2xx. Got: $ok", ok)
        val body = textOf(captured.single())
        assertTrue(
            "Body must carry state=ACTIVE. Got: $body",
            body.contains("\"state\":\"ACTIVE\"")
        )
    }

    @Test
    fun lockDevice_returnsFalse_whenNoAccessToken() = runTest {
        every { authManager.getAccessToken() } returns null
        assertFalse(
            "lockDevice must return false on missing token.",
            repository.lockDevice("dev-001")
        )
        assertTrue(
            "Must short-circuit before issuing the HTTP call.",
            captured.isEmpty()
        )
    }

    @Test
    fun unlockDevice_returnsFalse_onNon2xx() = runTest {
        val failingEngine = MockEngine { _ ->
            respondError(HttpStatusCode.Unauthorized)
        }
        every { clientProvider.httpClient } returns HttpClient(failingEngine)
        assertFalse(
            "unlockDevice must return false on HTTP 401.",
            repository.unlockDevice("dev-001")
        )
    }

    private fun textOf(req: HttpRequestData): String = when (val body = req.body) {
        is io.ktor.http.content.TextContent -> body.text
        else -> body.toString()
    }

    companion object {
        private const val SUCCESS_BODY =
            """{"success":true,"device_id":"dev-001","state":"LOCKED",""" +
                """"policy_version":8,"updated_at":"2026-07-17T22:00:00Z"}"""
    }
}
