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
import io.ktor.http.content.TextContent
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    /**
     * `supabase/functions/pairing/index.ts` returns HTTP 400 with body
     * `{ "error": "INVALID_CODE_FORMAT", "code": "INVALID_CODE_FORMAT" }`
     * when the user-submitted code is well-formed HTTP-wise but does not
     * match the `create-pairing-code` generator alphabet (e.g. malformed
     * manual entry).
     *
     * Pre-fix: the `else` branch in `parsePairingResponse` fell through
     * to `SERVER_ERROR` and exposed the raw `INVALID_CODE_FORMAT` token
     * to the UI, which is a UX defect — the user sees a leaky server
     * constant instead of a friendly error.
     *
     * Post-fix: 400 maps to `PairingErrorType.INVALID_CODE` with a
     * safe, localized fallback message, and the raw token never reaches
     * the user-facing field.
     */
    @Test
    fun pairWithCode_returns_invalid_code_on_400_with_invalid_code_format() = runTest {
        val failingEngine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(
                    """{"error":"INVALID_CODE_FORMAT","code":"INVALID_CODE_FORMAT"}"""
                ),
                status = HttpStatusCode.BadRequest,
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

        val result = manager.pairWithCode("BAD-CODE!")

        assertTrue("Expected INVALID_CODE, got $result", result is PairingResult.Error)
        val error = result as PairingResult.Error
        assertEquals(PairingErrorType.INVALID_CODE, error.type)
        // The raw server token MUST NOT leak into the user-facing message.
        assertTrue(
            "User-facing message must not expose the raw INVALID_CODE_FORMAT token, got='${error.message}'",
            error.message.isNotBlank() && "INVALID_CODE_FORMAT" !in error.message
        )
        failingClient.close()
    }

    /**
     * Companion to [pairWithCode_returns_invalid_code_on_400_with_invalid_code_format]:
     * `supabase/functions/pairing/index.ts` also returns HTTP 400 for
     * non-code validation failures (e.g. `child_first_name` length
     * outside the 1..32 range, missing required fields). These 400s use
     * the same status code as `INVALID_CODE_FORMAT` but carry a human
     * `error` string and NO machine `code` field, so the client must
     * NOT route them to `INVALID_CODE` — the pairing code itself is
     * fine, the request body is what the server rejected.
     *
     * Pre-fix: all 400s were mapped to `INVALID_CODE` with the message
     * "El código no es válido", which wrongly blamed the pairing code
     * (a valid 8-char code) for a server-side `child_first_name`
     * length rejection.
     *
     * Post-fix: 400 with a non-`INVALID_CODE_FORMAT` body maps to a
     * non-code error type with a safe, truthful localized message that
     * does not blame the code and does not expose the raw server
     * `child_first_name es requerido (1..32 caracteres)` message.
     */
    @Test
    fun pairWithCode_does_not_blame_code_on_400_when_body_is_not_invalid_code_format() = runTest {
        val failingEngine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(
                    """{"error":"child_first_name es requerido (1..32 caracteres)"}"""
                ),
                status = HttpStatusCode.BadRequest,
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
        manager.childFirstNameProvider = {
            // A 33+ char name that the server will reject with 400 — the
            // code itself is a valid 8-char code per the generator alphabet.
            "A".repeat(40)
        }

        // A perfectly valid 8-char pairing code (matches the
        // `create-pairing-code` generator alphabet A-HJ-NP-Z2-9).
        val result = manager.pairWithCode("ABCDEFGH")

        assertTrue("Expected Error, got $result", result is PairingResult.Error)
        val error = result as PairingResult.Error
        // The pairing code is NOT the cause — the server rejected the body.
        // Mapping to INVALID_CODE would blame the user for a server-side
        // validation failure on a field they can't see.
        assertNotEquals(
            "400 with a non-INVALID_CODE_FORMAT body must NOT map to INVALID_CODE",
            PairingErrorType.INVALID_CODE,
            error.type
        )
        // The raw server `error` string MUST NOT leak into the UI.
        assertTrue(
            "User-facing message must not expose the raw child_first_name server error, got='${error.message}'",
            error.message.isNotBlank() &&
                "child_first_name" !in error.message &&
                "1..32" !in error.message
        )
        // The message must NOT blame the pairing code itself.
        val codeBlamingFragments = listOf(
            "código no es válido",
            "Código inválido",
            "INVALID_CODE",
        )
        for (fragment in codeBlamingFragments) {
            assertTrue(
                "Non-code 400 must not blame the pairing code ('$fragment' in message), got='${error.message}'",
                fragment !in error.message
            )
        }
        failingClient.close()
    }

    /**
     * Defensive counterpart to the two 400 tests above: a 400 with a
     * malformed body (no JSON, empty body, or unparseable payload) must
     * still NOT map to `INVALID_CODE`. The parser returns `null` for the
     * machine token in every malformed case, so the routing falls
     * through to the non-code branch. This protects against silent
     * regressions if a future server change emits a new 400 token the
     * client does not yet recognize.
     */
    @Test
    fun pairWithCode_does_not_blame_code_on_400_with_malformed_body() = runTest {
        val failingEngine = MockEngine { _ ->
            respond(
                content = ByteReadChannel("not-json-at-all"),
                status = HttpStatusCode.BadRequest,
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

        val result = manager.pairWithCode("ABCDEFGH")

        assertTrue("Expected Error, got $result", result is PairingResult.Error)
        val error = result as PairingResult.Error
        assertNotEquals(
            "400 with a malformed body must NOT map to INVALID_CODE",
            PairingErrorType.INVALID_CODE,
            error.type
        )
        assertTrue(
            "User-facing message must not be blank, got='${error.message}'",
            error.message.isNotBlank()
        )
        failingClient.close()
    }

    /**
     * Follow-up to `windows-backend-pairing-and-approval-readiness`:
     * `supabase/functions/pairing/index.ts` now requires `child_first_name`
     * (1..32 chars, non-blank) in the request body (HTTP 400 otherwise).
     * Verifies the Android client propagates the value returned by
     * [PairingManager.childFirstNameProvider] into the JSON body sent
     * to the edge function, and that the existing required fields
     * (`code`, `device_name`, `device_model`, `os_version`, `app_version`,
     * `age_band`) stay intact.
     */
    @Test
    fun pairWithCode_includes_child_first_name_in_request_body() = runTest {
        val capturedBody = AtomicReference<String?>(null)
        val captureEngine = MockEngine { request ->
            capturedBody.set((request.body as TextContent).text)
            respond(
                content = ByteReadChannel(
                    """{"device_id":"<uuid-device>","parent_id":"<uuid-parent>"}"""
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val captureClient = HttpClient(captureEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        every { mockClientProvider.httpClient } returns captureClient

        val manager = PairingManager.getInstance(context)
        manager.deviceInfoProvider = {
            DeviceInfo(
                deviceName = "TestManufacturer TestModel",
                deviceModel = "TestModel",
                osVersion = "33",
                appVersion = "1.0.0",
                ageBand = "7-12"
            )
        }
        manager.childFirstNameProvider = { "Lucia" }

        val result = manager.pairWithCode("ABCDEFGH")

        assertTrue("Expected Success, got $result", result is PairingResult.Success)
        val body = capturedBody.get()
        assertNotNull(
            "MockEngine MUST capture the request body; got null. " +
                "Did setBody() go through ContentNegotiation?",
            body
        )
        val json = Json.parseToJsonElement(body!!).jsonObject
        // The exact field name the edge function validates — keep this
        // assertion on the JSON property name, not on a Kotlin-side
        // alias, so a future DTO rename cannot silently break this
        // contract.
        val childNameNode = json["child_first_name"]
        assertNotNull(
            "Outbound JSON MUST contain the child_first_name key to match " +
                "supabase/functions/pairing/index.ts — got body=$body",
            childNameNode
        )
        assertEquals(
            "child_first_name must round-trip through childFirstNameProvider",
            "Lucia",
            childNameNode!!.jsonPrimitive.contentOrNull
        )
        // Existing required fields stay untouched after the new field
        // is appended. Re-assert them so a DTO reshape can't quietly
        // break the contract on either side.
        assertEquals("ABCDEFGH", json["code"]!!.jsonPrimitive.content)
        assertEquals(
            "TestManufacturer TestModel",
            json["device_name"]!!.jsonPrimitive.content
        )
        assertEquals("TestModel", json["device_model"]!!.jsonPrimitive.content)
        assertEquals("33", json["os_version"]!!.jsonPrimitive.content)
        assertEquals("1.0.0", json["app_version"]!!.jsonPrimitive.content)
        assertEquals("7-12", json["age_band"]!!.jsonPrimitive.content)

        captureClient.close()
    }

    /**
     * Default behavior of [PairingManager.childFirstNameProvider] is
     * `{ null }` — there is no production UI for the child's name in the
     * current pairing flow. The wire-shape contract requires the FIELD
     * to be present in the JSON body, even when its value is null; the
     * server-side `trimmed.length < 1` validation is enforced by the
     * real Supabase edge function and would be caught there, not in
     * this unit-test layer. This pins down "key present, value null"
     * as the current contract until the device-side capture UX lands.
     */
    @Test
    fun pairWithCode_request_body_contains_child_first_name_key_when_provider_defaults_to_null() = runTest {
        val capturedBody = AtomicReference<String?>(null)
        val captureEngine = MockEngine { request ->
            capturedBody.set((request.body as TextContent).text)
            respond(
                content = ByteReadChannel(
                    """{"device_id":"<uuid-device>","parent_id":"<uuid-parent>"}"""
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val captureClient = HttpClient(captureEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        every { mockClientProvider.httpClient } returns captureClient

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
        // childFirstNameProvider is left untouched → returns its default `null`.

        val result = manager.pairWithCode("ABCDEFGH")

        assertTrue("Expected Success, got $result", result is PairingResult.Success)
        val body = capturedBody.get()
        assertNotNull("MockEngine MUST capture the request body", body)
        val obj: JsonObject = Json.parseToJsonElement(body!!).jsonObject
        assertTrue(
            "child_first_name MUST be a key in the JSON body even when the " +
                "provider returns null — got body=$body",
            "child_first_name" in obj
        )
        assertTrue(
            "Default provider returns null → serialized value should be JsonNull",
            obj["child_first_name"] is JsonNull
        )
        assertNull(
            "Default provider returns null → JsonPrimitive.contentOrNull should be null",
            obj["child_first_name"]?.jsonPrimitive?.contentOrNull
        )

        captureClient.close()
    }
}
