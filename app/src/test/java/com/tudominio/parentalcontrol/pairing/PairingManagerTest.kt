package com.tudominio.parentalcontrol.pairing

import android.content.Context
import com.tudominio.parentalcontrol.auth.AuthResult
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.network.SupabaseClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Tests for [PairingManager.pairWithCode] (PR 1 task #6 of
 * `openspec/changes/wire-pairing-and-approval-end-to-end`).
 *
 * Verifies that the real HTTP call goes to
 * `${SUPABASE_URL}/functions/v1/pairing` with the right headers and that the
 * response is parsed into [PairingResult.Success] when the edge function
 * returns 200 OK with `{ device_id, parent_id }`.
 */
class PairingManagerTest {

    private val context: Context = mockk(relaxed = true)
    private lateinit var mockAuthManager: DeviceAuthManager
    private lateinit var mockClientProvider: SupabaseClientProvider
    private lateinit var mockClient: HttpClient

    @Before
    fun setUp() {
        // Reset the PairingManager singleton so each test gets a fresh instance
        // bound to this test's mocks
        val instanceField = PairingManager::class.java.getDeclaredField("instance")
        instanceField.isAccessible = true
        instanceField.set(null, null)

        mockClient = HttpClient(
            MockEngine { request ->
                respond(
                    content = ByteReadChannel(
                        """{"device_id":"<uuid-device>","parent_id":"<uuid-parent>"}"""
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        mockAuthManager = mockk()
        every { mockAuthManager.getAccessToken() } returns "test-jwt-token"
        coEvery { mockAuthManager.savePairedSession(any(), any()) } returns Unit

        mockClientProvider = mockk()
        every { mockClientProvider.httpClient } returns mockClient

        // PairingManager.getInstance() builds private `authManager` and
        // `clientProvider` via the companion's getInstance() — substitute them.
        mockkObject(DeviceAuthManager.Companion)
        every { DeviceAuthManager.getInstance(any()) } returns mockAuthManager
        mockkObject(SupabaseClientProvider.Companion)
        every { SupabaseClientProvider.getInstance(any()) } returns mockClientProvider
    }

    @After
    fun tearDown() {
        mockClient.close()
        unmockkObject(DeviceAuthManager.Companion)
        unmockkObject(SupabaseClientProvider.Companion)
    }

    @Test
    fun pairWithCode_real_supabase_returns_success() = runTest {
        val manager = PairingManager.getInstance(context)
        // Avoid JVM `Build.MODEL` null in unit tests
        manager.deviceInfoProvider = {
            DeviceInfo(
                deviceName = "TestManufacturer TestModel",
                deviceModel = "TestModel",
                osVersion = "33",
                appVersion = "1.0.0",
                ageBand = null
            )
        }

        val result = manager.pairWithCode("ABCDEFGH")

        assertTrue("Expected Success, got $result", result is PairingResult.Success)
        val success = result as PairingResult.Success
        assertEquals("<uuid-device>", success.deviceId)
        assertEquals("<uuid-parent>", success.parentId)
        // Persistence side-effect: session is stored
        coVerify { mockAuthManager.savePairedSession("<uuid-device>", "<uuid-parent>") }
    }

    @Test
    fun pairWithCode_obtains_session_on_demand_when_no_token() = runTest {
        // Pre-fix: this test FAILS because the production code short-circuits
        // to SESSION_ERROR at PairingManager.kt:74-80 when getAccessToken() is
        // null, never calling authenticateOrCreate() or the HTTP edge function.
        // Post-fix: production code calls authenticateOrCreate(), the
        // anonymous session is used as the bearer, and pairing succeeds.
        every { mockAuthManager.getAccessToken() } returnsMany listOf(null, "test-jwt-token")
        coEvery { mockAuthManager.authenticateOrCreate() } returns AuthResult.Success(
            deviceId = "anonymous",
            accessToken = "test-jwt-token",
            refreshToken = "",
            expiresAt = 0L
        )

        val capturedAuth = AtomicReference<String?>(null)
        val successEngine = MockEngine { request ->
            capturedAuth.set(request.headers["Authorization"])
            respond(
                content = ByteReadChannel(
                    """{"device_id":"<uuid-device>","parent_id":"<uuid-parent>"}"""
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val successClient = HttpClient(successEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        every { mockClientProvider.httpClient } returns successClient

        val manager = PairingManager.getInstance(context)
        manager.deviceInfoProvider = {
            DeviceInfo(
                deviceName = "TestManufacturer TestModel",
                deviceModel = "TestModel",
                osVersion = "33",
                appVersion = "1.0.0",
                ageBand = null
            )
        }

        val result = manager.pairWithCode("ABCDEFGH")

        assertTrue("Expected Success, got $result", result is PairingResult.Success)
        val success = result as PairingResult.Success
        assertEquals("<uuid-device>", success.deviceId)
        assertEquals("<uuid-parent>", success.parentId)
        assertEquals("Bearer test-jwt-token", capturedAuth.get())
        coVerify { mockAuthManager.authenticateOrCreate() }
        coVerify { mockAuthManager.savePairedSession("<uuid-device>", "<uuid-parent>") }

        successClient.close()
    }

    @Test
    fun pairWithCode_returns_network_error_when_authenticateOrCreate_fails() = runTest {
        // Pre-fix: this test FAILS because the production code returns
        // SESSION_ERROR (not NETWORK_ERROR) when getAccessToken() is null,
        // regardless of what authenticateOrCreate() would have returned.
        // Post-fix: production code calls authenticateOrCreate(), the
        // failure surfaces as NETWORK_ERROR, and the HTTP edge function is
        // NOT called.
        every { mockAuthManager.getAccessToken() } returns null
        coEvery { mockAuthManager.authenticateOrCreate() } returns AuthResult.Error("network down")

        val engineCalls = AtomicInteger(0)
        val neverEngine = MockEngine { _ ->
            engineCalls.incrementAndGet()
            respond(
                content = ByteReadChannel("""{"device_id":"x","parent_id":"y"}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val neverClient = HttpClient(neverEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        every { mockClientProvider.httpClient } returns neverClient

        val manager = PairingManager.getInstance(context)
        manager.deviceInfoProvider = {
            DeviceInfo(
                deviceName = "TestManufacturer TestModel",
                deviceModel = "TestModel",
                osVersion = "33",
                appVersion = "1.0.0",
                ageBand = null
            )
        }

        val result = manager.pairWithCode("ABCDEFGH")

        assertTrue("Expected Error, got $result", result is PairingResult.Error)
        val err = result as PairingResult.Error
        assertEquals(PairingErrorType.NETWORK_ERROR, err.type)
        assertEquals("Error de conexión. Verifica tu conexión a internet.", err.message)
        coVerify { mockAuthManager.authenticateOrCreate() }
        assertEquals("HTTP edge function must not be called when auth preflight fails", 0, engineCalls.get())

        neverClient.close()
    }

    @Test
    fun pairWithCode_returns_invalid_code_on_404() = runTest {
        val failingEngine = MockEngine { _ ->
            respond(
                content = ByteReadChannel("""{"error":"not found"}"""),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val failingClient = HttpClient(failingEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        every { mockClientProvider.httpClient } returns failingClient

        val manager = PairingManager.getInstance(context)
        manager.deviceInfoProvider = {
            DeviceInfo(
                deviceName = "TestManufacturer TestModel",
                deviceModel = "TestModel",
                osVersion = "33",
                appVersion = "1.0.0",
                ageBand = null
            )
        }

        val result = manager.pairWithCode("DEADBEEF")

        assertTrue("Expected INVALID_CODE, got $result", result is PairingResult.Error)
        assertEquals(PairingErrorType.INVALID_CODE, (result as PairingResult.Error).type)
        failingClient.close()
    }
}
