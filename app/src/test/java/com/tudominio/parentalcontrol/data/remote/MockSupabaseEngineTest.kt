package com.tudominio.parentalcontrol.data.remote

import androidx.test.core.app.ApplicationProvider
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.serialization.Serializable

/**
 * Tests for the [MockSupabaseEngine] and the fixture JSON it serves (T5 of
 * `hotfix-parent-auth-session`).
 *
 * Per the abort condition in tasks.md §"Sequencing Concerns": if the
 * fixture JSON shape doesn't match what `ParentRepository` parses, STOP —
 * the fixture contract must match the parser, not vice versa. These tests
 * pin the wire-shape contract: the JSON in `app/src/main/assets/mock-supabase/`
 * is parseable by the same `DeviceDto` / `TimeRequestDto` shapes that
 * `ParentRepository` already uses for the real Supabase endpoints.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MockSupabaseEngineTest {

    private lateinit var engine: MockSupabaseEngine

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        engine = MockSupabaseEngine(context)
    }

    @Test
    fun `devices fixture parses as at least 2 child device rows`() = runTest {
        val devices = engine.devices()

        assertNotNull("devices payload must not be null", devices)
        assertTrue(
            "fixture must contain >= 2 devices per spec scenario, got ${devices.size}",
            devices.size >= 2
        )
        val first = devices.first()
        assertNotNull("id must not be null", first.id)
        assertNotNull("device_name must not be null", first.device_name)
        assertNotNull("last_seen_at must not be null", first.last_seen_at)
        // device_state defaults to ACTIVE in the parser; the fixture must use
        // a value the parser can map (ACTIVE, LOCKED, DOWNTIME).
        assertTrue(
            "device_state must be ACTIVE/LOCKED/DOWNTIME, got ${first.device_state}",
            first.device_state.uppercase() in setOf("ACTIVE", "LOCKED", "DOWNTIME")
        )
    }

    @Test
    fun `pending requests fixture contains at least one PENDING request`() = runTest {
        val requests = engine.pendingRequests()

        assertNotNull("pending requests must not be null", requests)
        assertTrue(
            "fixture must contain >= 1 pending request, got ${requests.size}",
            requests.size >= 1
        )
        val first = requests.first()
        assertEquals("PENDING", first.status.uppercase())
        assertNotNull("id must not be null", first.id)
        assertNotNull("device_id must not be null", first.device_id)
        assertTrue(
            "minutes_requested must be > 0, got ${first.minutes_requested}",
            first.minutes_requested > 0
        )
    }

    @Test
    fun `templates fixture contains at least one policy template`() = runTest {
        val templates = engine.templates()

        assertNotNull("templates must not be null", templates)
        assertTrue(
            "fixture must contain >= 1 template per spec scenario, got ${templates.size}",
            templates.size >= 1
        )
        val first = templates.first()
        assertNotNull("templateId must not be null", first.templateId)
        assertNotNull("name must not be null", first.name)
        assertNotNull("policyJson must not be null", first.policyJson)
    }

    @Test
    fun `MockSupabaseEngine exposes httpClient that responds to fixture paths`() = runTest {
        val client = engine.httpClient

        // The engine must respond to the paths the real ParentRepository
        // hits. We exercise the three documented paths and assert the
        // response is a 2xx with a non-empty body.
        val devicesResponse = client.get("/functions/v1/get-devices-for-parent")
        assertTrue(
            "devices path must return 2xx, got ${devicesResponse.status}",
            devicesResponse.status.value in 200..299
        )
        assertTrue(
            "devices body must be non-empty",
            devicesResponse.bodyAsText().isNotBlank()
        )

        val pendingResponse = client.get(
            "/rest/v1/time_requests?select=*&status=eq.PENDING&order=created_at.desc"
        )
        assertTrue(
            "pending path must return 2xx, got ${pendingResponse.status}",
            pendingResponse.status.value in 200..299
        )
        assertTrue(
            "pending body must be non-empty",
            pendingResponse.bodyAsText().isNotBlank()
        )

        val templatesResponse = client.get("/functions/v1/get-templates")
        assertTrue(
            "templates path must return 2xx, got ${templatesResponse.status}",
            templatesResponse.status.value in 200..299
        )
        assertTrue(
            "templates body must be non-empty",
            templatesResponse.bodyAsText().isNotBlank()
        )
    }

    /**
     * Regression: `MockSupabaseEngine.when` block must route
     * `POST /functions/v1/create-pairing-code` to the pairing-code fixture.
     * Without this branch the parent's "Generar código" call returns HTTP 404
     * in debug builds (`USE_MOCK_SUPABASE=true`), the ViewModel never sets
     * `_pairingCode`, and `PairingBottomSheet` step 2 renders empty. See
     * `openspec/changes/wire-pairing-and-approval-end-to-end` PR 2 (the mock
     * engine was added in commit `2d17041` AFTER PR 1 wired the repository
     * call — the fixture case was simply forgotten in that PR).
     */
    @Test
    fun `create-pairing-code POST returns pairing code response shape`() = runTest {
        val client = engine.httpClient

        val response = client.post("/functions/v1/create-pairing-code") {
            contentType(ContentType.Application.Json)
            setBody(
                "{\"device_name\":\"Test\",\"age_band\":\"6-8\",\"ttl_minutes\":15}"
            )
        }

        assertEquals(
            "create-pairing-code must return 200, got ${response.status}",
            200,
            response.status.value
        )

        val body = response.bodyAsText()
        assertTrue(
            "body must contain \"code\" field, got: $body",
            body.contains("\"code\"")
        )
        assertTrue(
            "body must contain \"expires_at\" field, got: $body",
            body.contains("\"expires_at\"")
        )
        assertTrue(
            "body must contain \"deeplink\" field, got: $body",
            body.contains("\"deeplink\"")
        )

        // Extract the deeplink value (lenient: find first "deeplink":"..." run).
        val deeplinkMatch = Regex("\"deeplink\"\\s*:\\s*\"([^\"]+)\"").find(body)
        assertNotNull("deeplink value must be present, got: $body", deeplinkMatch)
        val deeplink = deeplinkMatch!!.groupValues[1]
        assertTrue(
            "deeplink must start with parentalcontrol://pair?code=, got: $deeplink",
            deeplink.startsWith("parentalcontrol://pair?code=")
        )
    }

    /**
     * Regression: `MockSupabaseEngine.when` block must route
     * `POST /functions/v1/pairing` to the pairing-success fixture. Without
     * this branch the child's "Emparejar" call returns HTTP 404 in debug
     * builds (`USE_MOCK_SUPABASE=true`), `PairingManager.parsePairingResponse`
     * (`pairing/PairingManager.kt:231`) maps it to `INVALID_CODE`, and
     * `PairingViewModel` renders "Error de emparejamiento". Mirrors the
     * 22/06 `create-pairing-code` fix on the parent side (obs #82).
     */
    @Test
    fun `pairing POST returns device_id and parent_id response shape`() = runTest {
        val client = engine.httpClient

        val response = client.post("/functions/v1/pairing") {
            contentType(ContentType.Application.Json)
            setBody("{\"code\":\"ABCDEFGH\"}")
        }

        assertEquals(
            "pairing must return 200, got ${response.status}",
            200,
            response.status.value
        )

        val body = response.bodyAsText()
        assertTrue(
            "body must contain \"device_id\" field, got: $body",
            body.contains("\"device_id\"")
        )
        assertTrue(
            "body must contain \"parent_id\" field, got: $body",
            body.contains("\"parent_id\"")
        )
    }

    /**
     * Regression: `MockSupabaseEngine.when` block must route
     * `POST /auth/v1/token?grant_type=password` to the
     * `auth-anonymous.json` fixture. Without this branch the legacy
     * `DeviceAuthManager.httpClient` (which is now also routed through
     * the mock per the `fix-supabase-client-provider-legacy-mock-gate`
     * family) gets a 404 for the anonymous-session POST, the auth call
     * returns `AuthResult.NeedsPairing`, and `PairingManager.pairWithCode`
     * short-circuits to `NETWORK_ERROR` before the pairing call can even
     * be made. See `DeviceAuthManager.createAnonymousSession`
     * (`auth/DeviceAuthManager.kt:213`).
     */
    @Test
    fun `auth anonymous POST returns SupabaseAuthResponse shape`() = runTest {
        val client = engine.httpClient

        val response = client.post("/auth/v1/token?grant_type=password") {
            contentType(ContentType.Application.Json)
            setBody(
                "{\"email\":\"anonymous@test.local\",\"password\":\"test-pass\"}"
            )
        }

        assertEquals(
            "auth/v1/token must return 200, got ${response.status}",
            200,
            response.status.value
        )

        val body = response.bodyAsText()
        // The fixture is parsed by kotlinx.serialization into
        // `SupabaseAuthResponse` inside `DeviceAuthManager.handleAuthSuccess`
        // (`auth/DeviceAuthManager.kt:237`). Every field referenced there
        // MUST be present in the JSON, or the deserializer throws and the
        // auth call falls back to `AuthResult.Error` (caller sees
        // `NETWORK_ERROR`).
        for (field in listOf("access_token", "refresh_token", "expires_in", "user")) {
            assertTrue(
                "body must contain \"$field\" field, got: $body",
                body.contains("\"$field\"")
            )
        }
        // The `user.app_metadata.device_id` is what `handleAuthSuccess` uses
        // to set `_deviceId.value` (line 242); without it the device id
        // chain downstream of the auth call breaks.
        assertTrue(
            "body must contain user.app_metadata.device_id, got: $body",
            body.contains("\"device_id\"")
        )
    }

    /**
     * Regression: `MockSupabaseEngine.httpClient` MUST install
     * `ContentNegotiation` so `response.body<T>()` deserializes typed
     * responses (the path used by `DeviceAuthManager.createAnonymousSession`
     * for `SupabaseAuthResponse`). Without `ContentNegotiation`, the
     * engine can't decode the raw body into a typed object, throws
     * `JsonConvertException`, the auth call returns `AuthResult.Error`,
     * and `PairingManager` surfaces `NETWORK_ERROR` even though the
     * mock fixture is reachable. Mirrors the real-client behavior
     * (see `NetworkModule.buildRealHttpClient` which installs the same
     * `ContentNegotiation` with the same lenient `Json` config).
     */
    @Serializable
    data class AuthAnonymousResponse(
        val access_token: String,
        val refresh_token: String,
        val expires_in: Long,
        val expires_at: Long? = null,
        val user: AuthAnonymousUser? = null
    )

    @Serializable
    data class AuthAnonymousUser(
        val id: String,
        val email: String? = null,
        val app_metadata: Map<String, String>? = null
    )

    @Test
    fun `auth anonymous POST deserializes into typed response`() = runTest {
        val client = engine.httpClient

        val response = client.post("/auth/v1/token?grant_type=password") {
            contentType(ContentType.Application.Json)
            setBody(
                "{\"email\":\"anonymous@test.local\",\"password\":\"test-pass\"}"
            )
        }

        assertEquals(
            "auth/v1/token must return 200, got ${response.status}",
            200,
            response.status.value
        )

        // The crucial assertion: this `body<T>()` call is what the prod
        // `DeviceAuthManager.createAnonymousSession` does. If
        // `ContentNegotiation` is missing, Ktor throws and this test
        // fails with `NoTransformationFoundException` /
        // `JsonConvertException` — exactly the prod symptom.
        val typed: AuthAnonymousResponse = response.body()

        assertEquals(
            "access_token must be the mock fixture value",
            "mock-access-token-anonymous-session-001",
            typed.access_token
        )
        assertEquals(
            "refresh_token must be the mock fixture value",
            "mock-refresh-token-anonymous-session-001",
            typed.refresh_token
        )
        assertEquals(3600L, typed.expires_in)
        assertNotNull("user must be non-null", typed.user)
        assertEquals(
            "device_id from app_metadata must be the fixture value",
            "device-anonymous-001",
            typed.user?.app_metadata?.get("device_id")
        )
    }

    // ---------------------------------------------------------------
    // SUGGESTION #1 follow-up: centralize JSON minification so a
    // future hand-rolled regex consumer can stay strict against the
    // compact shape Supabase actually returns. The fixture files in
    // `assets/mock-supabase/` stay pretty-printed for review, and
    // [MockSupabaseEngine.minifyJsonIfNeeded] rewrites them on read.
    // ---------------------------------------------------------------

    @Test
    fun `minifyJsonIfNeeded strips whitespace from pretty-printed object`() {
        val pretty = """
            {
              "device_id": "device-child-emulator-001",
              "parent_id": "parent-uuid-aaaa-bbbb-cccc"
            }
        """.trimIndent()

        val minified = engine.minifyJsonIfNeeded(pretty)

        assertEquals(
            "object must be minified to a single line with no extra whitespace",
            """{"device_id":"device-child-emulator-001","parent_id":"parent-uuid-aaaa-bbbb-cccc"}""",
            minified
        )
        assertFalse(
            "minified output must not contain newlines",
            minified.contains('\n')
        )
        assertFalse(
            "minified output must not contain the ': ' separator (colon-then-space)",
            minified.contains(": ")
        )
    }

    @Test
    fun `minifyJsonIfNeeded strips whitespace from pretty-printed array`() {
        val pretty = """
            [
              { "id": "a", "name": "first" },
              { "id": "b", "name": "second" }
            ]
        """.trimIndent()

        val minified = engine.minifyJsonIfNeeded(pretty)

        assertEquals(
            "array must be minified",
            """[{"id":"a","name":"first"},{"id":"b","name":"second"}]""",
            minified
        )
    }

    @Test
    fun `minifyJsonIfNeeded is a no-op on already-compact JSON`() {
        val compact = """{"device_id":"x","parent_id":"y"}"""

        val minified = engine.minifyJsonIfNeeded(compact)

        assertEquals(
            "compact JSON must round-trip unchanged",
            compact,
            minified
        )
    }

    @Test
    fun `minifyJsonIfNeeded preserves string contents with special chars`() {
        // Supabase JWTs, URLs, deeplinks and similar wire values contain
        // characters that look like JSON syntax (`://`, dots, slashes) but
        // are inside string literals. The minifier must NOT touch them.
        val pretty = """
            {
              "deeplink": "parentalcontrol://pair?code=ABCDEFGH",
              "url": "https://example.com/path?x=1&y=2"
            }
        """.trimIndent()

        val minified = engine.minifyJsonIfNeeded(pretty)

        assertTrue(
            "deeplink value must survive minification intact, got: $minified",
            minified.contains("\"deeplink\":\"parentalcontrol://pair?code=ABCDEFGH\"")
        )
        assertTrue(
            "url value must survive minification intact, got: $minified",
            minified.contains("\"url\":\"https://example.com/path?x=1&y=2\"")
        )
    }

    @Test
    fun `minifyJsonIfNeeded returns the raw text on invalid input`() {
        // If a fixture becomes malformed (broken JSON), we don't want the
        // mock engine to mask that — let the caller surface the parse error
        // with the original text, not a half-cooked re-serialization.
        val broken = "{ this is not json"

        val result = engine.minifyJsonIfNeeded(broken)

        assertEquals(
            "malformed JSON must fall through to the raw text",
            broken,
            result
        )
    }

    @Test
    fun `minifyJsonIfNeeded returns non-JSON input unchanged`() {
        // A path that happens to not start with `{` or `[` (e.g. an XML
        // body or a plain-text 404 page) is passed through verbatim. Today
        // every fixture is JSON, but this keeps the helper safe for future
        // non-JSON endpoints.
        val plain = "hello world"

        assertEquals(plain, engine.minifyJsonIfNeeded(plain))
    }

    // ---------------------------------------------------------------
    // Phase A.1.2 — RED coverage for `feat-multi-child-picker`.
    //
    // Per openspec/changes/2026-07-06-feat-multi-child-picker/design.md §A.9
    // the 3-device fixture in `assets/mock-supabase/devices.json` must
    // span 2 children: dev-001 + dev-002 → Lucas (child-lucas); dev-003
    // → Sofía (child-sofia). dev-002 is intentionally child-less to
    // represent a device paired before the migration (the nullable FK
    // backfill boundary).
    //
    // Today `DeviceFixture` has no `child_id`/`child_first_name` and the
    // JSON has no `child_id`/`child_first_name`; the assertions below
    // would not even compile. RED before A.4.5 + A.4.6; GREEN after.
    // ---------------------------------------------------------------

    @Test
    fun `devices fixture dev-001 parses with child Lucas from child-lucas`() = runTest {
        val devices = engine.devices()

        val dev001 = devices.firstOrNull { it.id == "dev-001" }
        assertNotNull(
            "devices fixture must contain dev-001 per design A.9; got ids=" +
                devices.map { it.id },
            dev001
        )
        assertNotNull(
            "dev-001 must carry a hydrated child after A.4.6 — the mock " +
                "fixture is the seam `MockSupabaseEngine` shares with the " +
                "real `get-devices-for-parent` edge function; without it " +
                "the production-debug build cannot surface the picker",
            dev001!!.child
        )
        assertEquals(
            "dev-001.child.id must match the fixtures/children.json child-lucas",
            "child-lucas",
            dev001.child!!.id
        )
        // Wire field is snake_case `first_name`; the camelCase domain
        // getter is preserved via `hydratedChild`.
        assertEquals(
            "dev-001.child.firstName must be Lucas (not the UID-style " +
                "anonymous entry that ships today)",
            "Lucas",
            dev001.child!!.first_name
        )
        assertEquals(
            "hydratedChild.firstName must surface the camelCase Child shape",
            "Lucas",
            dev001.hydratedChild!!.firstName
        )
    }

    @Test
    fun `devices fixture dev-002 parses without child`() = runTest {
        val devices = engine.devices()

        val dev002 = devices.firstOrNull { it.id == "dev-002" }
        assertNotNull(
            "devices fixture must contain dev-002 per design A.9",
            dev002
        )
        // dev-002 is paired pre-migration in the design's narrative (the
        // nullable FK allows it). The parser MUST leave `child = null`
        // so the dashboard renders `Sin asignar` per the spec scenario
        // at child-entity/spec.md:31-35.
        assertEquals(
            "dev-002.child must be null when child_id/child_first_name are " +
                "absent on the wire — pre-migration devices keep child_id=NULL " +
                "until the backfill script assigns one",
            null,
            dev002!!.child
        )
    }

    @Test
    fun `devices fixture dev-003 parses with child Sofia from child-sofia`() = runTest {
        val devices = engine.devices()

        val dev003 = devices.firstOrNull { it.id == "dev-003" }
        assertNotNull(
            "devices fixture must contain dev-003 per design A.9",
            dev003
        )
        assertNotNull(
            "dev-003 must carry a hydrated child (Sofía)",
            dev003!!.child
        )
        assertEquals(
            "dev-003.child.id must match the fixtures/children.json child-sofia",
            "child-sofia",
            dev003.child!!.id
        )
        assertEquals(
            "dev-003.child.firstName must be Sofía with the accent preserved",
            "Sofía",
            dev003.child!!.first_name
        )
    }

    /**
     * `POST /rest/v1/time_requests` is a plain INSERT with
     * `Prefer: return=representation` (no `select=` embed). Mock
     * must echo 201 + a bare inserted-row list — no nested `devices`,
     * no flat `device_name` (the `time_requests` table has no such
     * column). The companion regression test pins the GET path still
     * carries the embedded `devices.device_name` projection.
     */
    @Test
    fun `time_requests POST returns 201 with bare inserted row and no devices relation`() = runTest {
        val body = engine.httpClient.post("/rest/v1/time_requests") {
            contentType(ContentType.Application.Json)
            setBody("{\"device_id\":\"dev-001\",\"minutes_requested\":15,\"reason\":\"x\"}")
        }.let { assertEquals(201, it.status.value); it.bodyAsText() }
        assertFalse("must NOT include nested `devices`, got: $body", body.contains("\"devices\":"))
        assertFalse("must NOT include flat `device_name`, got: $body", body.contains("\"device_name\":"))
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(body)
        val obj = (parsed as kotlinx.serialization.json.JsonArray)
            .first() as kotlinx.serialization.json.JsonObject
        assertEquals(kotlinx.serialization.json.JsonPrimitive(15), obj["minutes_requested"]!!)
        assertEquals(kotlinx.serialization.json.JsonPrimitive("PENDING"), obj["status"]!!)
    }

    @Test
    fun `time_requests GET still returns nested devices relation`() = runTest {
        val body = engine.httpClient.get(
            "/rest/v1/time_requests?select=*,devices(device_name)&status=eq.PENDING"
        ).let { assertTrue(it.status.value in 200..299); it.bodyAsText() }
        assertTrue("must include nested `devices`, got: $body", body.contains("\"devices\":"))
    }

    /**
     * WU-2 — MockSupabaseEngine `set-device-state` route must echo
     * 200 + Supabase-auth-shape single-object envelope on a valid
     * lock request, mutate the in-memory devicesState mirror, and
     * preserve the policy_version increment contract. Mirrors the
     * shared-mock + production edge-function shape.
     */
    @Test
    fun `set-device-state lock returns 200 with bumped policy_version and updated mirror`() = runTest {
        val baseline = engine.currentDevices()
        val target = baseline.first()
        val baselinePolicy = target.policy_version

        val response = engine.httpClient.post("/functions/v1/set-device-state") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer test-jwt")
            setBody("""{"device_id":"${target.id}","state":"LOCKED"}""")
        }
        assertEquals(
            "set-device-state must return 2xx on a valid request, got: " +
                response.status,
            200,
            response.status.value
        )
        val body = response.bodyAsText()
        assertTrue(
            "body must carry success=true, got: $body",
            body.contains("\"success\":true")
        )
        assertTrue(
            "body must carry state=LOCKED, got: $body",
            body.contains("\"state\":\"LOCKED\"")
        )
        assertTrue(
            "body must carry device_id=<id>, got: $body",
            body.contains("\"device_id\":\"${target.id}\"")
        )
        assertTrue(
            "policy_version must increment, got: $body",
            body.contains("\"policy_version\":${baselinePolicy + 1}")
        )

        // The in-memory mirror must reflect the new state. We can
        // re-read the engine's currently-held devices list to verify
        // the side-effect.
        val after = engine.currentDevices().first { it.id == target.id }
        assertEquals(
            "In-memory mirror must reflect LOCKED, got: ${after.device_state}",
            "LOCKED",
            after.device_state
        )
        assertEquals(
            "policy_version must increment by 1, got: ${after.policy_version}",
            baselinePolicy + 1,
            after.policy_version
        )
    }

    /**
     * WU-2 — set-device-state must reject missing token (401) and
     * invalid state (422). Regression guard so the mock-engine
     * gateway matches the production edge-function contract and the
     * ParentRepository contract tests stay honest.
     */
    @Test
    fun `set-device-state missing auth header returns 401`() = runTest {
        val response = engine.httpClient.post("/functions/v1/set-device-state") {
            contentType(ContentType.Application.Json)
            setBody("""{"device_id":"dev-001","state":"LOCKED"}""")
        }
        assertEquals(401, response.status.value)
    }

    @Test
    fun `set-device-state invalid state returns 422`() = runTest {
        val response = engine.httpClient.post("/functions/v1/set-device-state") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer test-jwt")
            setBody("""{"device_id":"dev-001","state":"BOGUS"}""")
        }
        assertEquals(422, response.status.value)
    }
}
