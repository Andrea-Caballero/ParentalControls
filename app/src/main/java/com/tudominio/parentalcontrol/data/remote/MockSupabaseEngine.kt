package com.tudominio.parentalcontrol.data.remote

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.utils.EmptyContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import com.tudominio.parentalcontrol.data.model.BehavioralEventEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Ktor `HttpClient` backed by a [MockEngine] that serves fixture JSON from
 * `app/src/main/assets/mock-supabase/`.
 *
 * Per design §D6 of `openspec/changes/hotfix-parent-auth-session/design.md`:
 * the fixtures live in `assets/` (not in `test/resources/`) so the flag
 * works in production-debug builds, not only under unit tests. The wire
 * shapes match the [com.tudominio.parentalcontrol.data.repository.ParentRepository]
 * `DeviceDto` / `TimeRequestDto` parsers, so the same `getDevices()` /
 * `getPendingRequests()` code paths work against both the real Supabase
 * endpoints and the mock engine without any branching.
 *
 * The [TemplateFixture] shape is NEW (the existing `getTemplates()` returns
 * a hardcoded list). It exists for future code that wants to call
 * `/functions/v1/get-templates` against the mock engine.
 */
class MockSupabaseEngine(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Returns the raw JSON body of the `/devices` fixture, parsed as the
     * same `DeviceDto` shape that `ParentRepository.DeviceDto.toChildDevice`
     * already handles. Kept as a public method so the test can assert the
     * wire-shape contract without going through the Ktor engine.
     */
    fun devices(): List<DeviceFixture> {
        val raw = readAsset("mock-supabase/devices.json")
        return json.decodeFromString<List<DeviceFixture>>(raw)
    }

    fun pendingRequests(): List<PendingFixture> {
        val raw = readAsset("mock-supabase/pending-requests.json")
        return json.decodeFromString<List<PendingFixture>>(raw)
    }

    fun templates(): List<TemplateFixture> {
        val raw = readAsset("mock-supabase/templates.json")
        return json.decodeFromString<List<TemplateFixture>>(raw)
    }

    /**
     * Change A of `feat-multi-child-picker` (design §A.9 + tasks A.4.7):
     * serves the new `children.json` fixture so the parent-side
     * RenameChildDialog (Change B §B.6) can do "save and refetch"
     * against the mock engine in production-debug builds. Mirrors the
     * `children` table rows owned by the demo parent today.
     */
    fun children(): List<ChildFixture> {
        val raw = readAsset("mock-supabase/children.json")
        return json.decodeFromString<List<ChildFixture>>(raw)
    }

    /**
     * Loads `behavioral_events.json` (5 events owned by `parent-demo`).
     * Mirrors the `devices()` / `pendingRequests()` test-seam pattern.
     * Change A of `feat-parent-behavioral-event-log`.
     */
    fun events(): List<BehavioralEventFixture> {
        val raw = readAsset("mock-supabase/behavioral_events.json")
        return json.decodeFromString<List<BehavioralEventFixture>>(raw)
    }

    /**
     * In-memory mirror of the `children` fixture, seeded once from
     * `children()` so subsequent PATCH /rest/v1/children calls can
     * mutate a single row and tests can verify the rename persisted
     * end-to-end (Q3=m — "tests can verify the rename persisted,
     * not just that the PATCH was called"). Exposed via
     * [currentChildren] for Robolectric tests; production code uses
     * the regular `GET /rest/v1/children` path which still hydrates
     * from the static fixture.
     */
    private val childrenState: MutableStateFlow<List<ChildFixture>> =
        MutableStateFlow(children())

    /**
     * WU-2 — in-memory mirror of the devices fixture, seeded once from
     * `devices()` so subsequent `POST /functions/v1/set-device-state`
     * calls can mutate `device_state` and `policy_version` and tests
     * can verify the lock/unlock round-trip without a re-fetch.
     * Mirrors [childrenState] for the device list.
     *
     * Production GET path returns the static fixture; the lock/unlock
     * mutation path updates this flow so the next read reflects the
     * state without requiring a disk round-trip. Test-only seam.
     */
    private val devicesState: MutableStateFlow<List<DeviceFixture>> =
        MutableStateFlow(devices())

    /**
     * Read-only view of the post-`POST /functions/v1/set-device-state`
     * state. Production code reaches the parent list via the regular
     * `get-devices-for-parent` HTTP path; this seam exists so the
     * V2-filter / WU-2 tests can prove a real mutation survived the
     * mock-engine's POST → state-shape transform.
     */
    fun currentDevices(): List<DeviceFixture> = devicesState.value

    /**
     * Read-only view of the post-PATCH `children` state. The production
     * GET path returns the static fixture; the PATCH path mutates this
     * flow so the next read reflects the rename without requiring a
     * disk round-trip. Test-only seam — production HTTP callers should
     * keep using `GET /rest/v1/children` to stay in sync with the
     * real PostgREST shape.
     */
    fun currentChildren(): List<ChildFixture> = childrenState.value

    /**
     * Ktor [HttpClient] backed by a [MockEngine] that dispatches by URL
     * path. The engine responds with the corresponding fixture JSON for
     * the three documented endpoints; everything else gets a 404 so a
     * wrongly-routed call surfaces immediately instead of silently
     * returning empty data.
     *
     * Change A adds `/rest/v1/children` to the routing table so the
     * mock engine doesn't 404 the parent's RenameChildDialog fetch (the
     * dialog itself lands in Change B, but the seam is wired here so
     * production-debug builds don't lose the existing picker).
     *
     * `fix-rename-child-dialog` adds a `PATCH /rest/v1/children` branch
     * that mutates the in-memory [childrenState] with the requested
     * `first_name` so the dialog's "save and refetch" flow roundtrips
     * through the same memory that the `currentChildren()` test seam
     * reads. Per Q3=m the mock mutates + echoes; per Q4=p the real
     * `ParentRepository.renameChild` awaits the 2xx before the dialog
     * transitions out of its loading state.
     */
    val httpClient: HttpClient = HttpClient(
        engine = MockEngine { request ->
            val path = request.url.encodedPath
            val body: String = when {
                // R1 — official Supabase anonymous auth entry point. The
                // shared-mock and in-process mock both serve the same
                // `auth-anonymous.json` fixture. Mirrors the production
                // AnonAccessTokenResponse shape.
                path.endsWith("/auth/v1/signup") ||
                    path.endsWith("/auth/v1/token") ->
                    // `auth/v1/token` is kept as a fallback for legacy
                    // callers (e.g. supabase-js refresh tokens); the
                    // new anonymous-auth contract routes through
                    // `/auth/v1/signup` with an empty body. The mock
                    // fixture shape is identical.
                    readAsset("mock-supabase/auth-anonymous.json")
                path.endsWith("/functions/v1/create-pairing-code") ->
                    readAsset("mock-supabase/create-pairing-code.json")
                path.endsWith("/functions/v1/get-devices-for-parent") ->
                    readAsset("mock-supabase/devices.json")
                path.endsWith("/functions/v1/get-templates") ||
                    path.endsWith("/rest/v1/templates") ->
                    readAsset("mock-supabase/templates.json")
                path.endsWith("/functions/v1/pairing") ->
                    readAsset("mock-supabase/pairing.json")
                path.endsWith("/functions/v1/set-device-state") ->
                    handleSetDeviceState(request)
                path.startsWith("/rest/v1/time_requests") &&
                    request.method == HttpMethod.Post ->
                    // POST = plain INSERT with Prefer: return=representation;
                    // no `select=` embed, so no nested `devices` projection
                    // (status 201 below).
                    readAsset("mock-supabase/time-request-insert.json")
                path.startsWith("/rest/v1/time_requests") ->
                    // GET keeps the embedded `devices.device_name` projection
                    // from the production `select=*,devices(device_name)`.
                    readAsset("mock-supabase/pending-requests.json")
                path.startsWith("/rest/v1/behavioral_events") ->
                    handleBehavioralEventsGet(request)
                path.endsWith("/rest/v1/children") && request.method == HttpMethod.Patch ->
                    handleChildrenPatch(request)
                path.endsWith("/rest/v1/children") ->
                    readAsset("mock-supabase/children.json")
                else ->
                    """{"error":"unknown route $path"}"""
            }
            val status = when {
                body.startsWith("""{"error":"Token requerido""") -> HttpStatusCode.Unauthorized
                body.startsWith("""{"error":"Token inv""") -> HttpStatusCode.Unauthorized
                body.startsWith("""{"error":"device_id no encontrado""") -> HttpStatusCode.NotFound
                body.startsWith("""{"error":"state debe ser""") -> HttpStatusCode.UnprocessableEntity
                body.startsWith("""{"error":"device_id es requerido""") -> HttpStatusCode.UnprocessableEntity
                body.startsWith("""{"error":"unknown route""") -> HttpStatusCode.NotFound
                body.startsWith("""{"error":""") -> HttpStatusCode.NotFound
                // POST /rest/v1/time_requests echoes CREATED 201 to match
                // the production `Prefer: return=representation` contract.
                path.startsWith("/rest/v1/time_requests") &&
                    request.method == HttpMethod.Post -> HttpStatusCode.Created
                else -> HttpStatusCode.OK
            }
            respond(
                content = ByteReadChannel(body),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
    ) {
        // Without `ContentNegotiation` installed, `response.body<T>()` throws
        // `JsonConvertException` because the engine can't decode the raw body
        // into a typed object. The legacy `DeviceAuthManager.httpClient` is
        // now wired to this mock (per the `fix-supabase-client-provider-legacy-
        // mock-gate` family), and `createAnonymousSession` does
        // `response.body<SupabaseAuthResponse>()` — without this install, the
        // auth call throws, falls back to `AuthResult.Error`, and the
        // `PairingManager` shows `NETWORK_ERROR` to the user even though the
        // mock fixture is reachable. Adding `ContentNegotiation` here makes
        // the mock client a drop-in replacement for the real engine in any
        // caller that does typed deserialization. `bodyAsText()`-style callers
        // (the existing `MockSupabaseEngineTest`) are unaffected.
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                }
            )
        }
    }

    private fun readAsset(path: String): String {
        val raw = context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return minifyJsonIfNeeded(raw)
    }

    /**
     * Reads the request body as text from a Ktor [HttpRequestData].
     * Returns the empty string for an `EmptyContent` body (no PATCH
     * payload supplied). Falls back to `body.toString()` for any other
     * body type so a future binary-content PATCH surfaces a
     * recognisable placeholder string instead of crashing the engine.
     */
    private fun requestBodyText(req: HttpRequestData): String = when (val body = req.body) {
        is TextContent -> body.text
        EmptyContent -> ""
        else -> body.toString()
    }

    /**
     * Handles `PATCH /rest/v1/children?id=eq.{childId}` for the
     * parent-side rename flow (`fix-rename-child-dialog`). Parses
     * `{"first_name":"<name>"}` from the body, applies the rename to
     * the in-memory [childrenState], and returns the updated row as a
     * JSON object — exactly the shape a real PostgREST
     * `Prefer: return=representation` PATCH echoes.
     *
     * 404 path: when the `id=eq.{childId}` filter does not match any
     * seeded child (e.g., a test that forgot to seed
     * `child-lucas`), we respond with 404 so the
     * `ParentRepository.renameChild` translates the failure into a
     * `DeviceListError.Transient("HTTP 404 ...")`.
     *
     * The URL query is parsed by substring because the PostgREST
     * `id=eq.<uuid>` shape carries a single equality filter; a future
     * multi-id filter (e.g., `id=in.(...)`) is out of scope and would
     * fall through to the first match.
     */
    private fun handleChildrenPatch(request: HttpRequestData): String {
        val bodyText = requestBodyText(request)
        val newName = parseFirstName(bodyText)
            ?: return """{"error":"missing first_name"}"""
        val childId = parseIdEqFilter(request.url.encodedQuery)
            ?: return """{"error":"missing id=eq filter"}"""

        val updated = childrenState.value
            .firstOrNull { it.id == childId }
            ?: return """{"error":"unknown child id $childId"}"""

        childrenState.update { list ->
            list.map { if (it.id == childId) it.copy(first_name = newName) else it }
        }

        // Echo the updated row. `firstName` alias kept in sync by the
        // computed getter on `ChildFixture`.
        return """{"id":"${updated.id}","parent_id":"${updated.parent_id}","first_name":"$newName",""" +
            """"created_at":"${updated.created_at}","updated_at":"${updated.updated_at}"}"""
    }

    /**
     * Handles `POST /functions/v1/set-device-state` (WU-2). Mirrors
     * the production edge function contract at
     * `supabase/functions/set-device-state/index.ts`. Validates
     * `{ device_id, state }` and applies the state + policy_version
     * bump to [devicesState]. Returns a Supabase-auth-shape JSON
     * envelope (single object — the production `.single()` contract).
     *
     * For test seam coverage the parent token is checked the same way
     * the production function does: `Authorization: Bearer ...`
     * header must be present. Validation of `state` (must be
     * `LOCKED` or `ACTIVE`) and `device_id` (must exist) is enforced
     * so a unit test that drives the WRONG state hits the 422 path.
     */
    private fun handleSetDeviceState(request: HttpRequestData): String {
        val authHeader = request.headers["Authorization"]
        if (authHeader == null) {
            return """{"error":"Token requerido"}"""
        }

        val bodyText = requestBodyText(request)
        val deviceId = parseJsonField(bodyText, "device_id")
            ?: return """{"error":"device_id es requerido"}"""
        val state = parseJsonField(bodyText, "state")?.uppercase()
            ?: return """{"error":"state debe ser uno de LOCKED, ACTIVE"}"""
        if (state != "LOCKED" && state != "ACTIVE") {
            return """{"error":"state debe ser uno de LOCKED, ACTIVE"}"""
        }

        val current = devicesState.value.firstOrNull { it.id == deviceId }
            ?: return """{"error":"device_id no encontrado"}"""

        val nextPolicyVersion = current.policy_version + 1
        val updatedAt = java.time.Instant.now().toString()
        devicesState.update { list ->
            list.map { d ->
                if (d.id == deviceId)
                    d.copy(
                        device_state = state,
                        policy_version = nextPolicyVersion
                    ) else d
            }
        }
        val updated = current.copy(
            device_state = state,
            policy_version = nextPolicyVersion
        )
        return buildString {
            append("""{"success":true,"device_id":"${updated.id}",""")
            append(""" "state":"${updated.device_state}",""")
            append(""" "policy_version":${updated.policy_version},"updated_at":"$updatedAt"}""")
        }
    }

    /**
     * Lightweight JSON-field extractor used by the mock handlers
     * (`handleChildrenPatch`, `handleSetDeviceState`) for the
     * test-seam-friendly mocks. Production code uses
     * kotlinx.serialization, but in the mock path we're already
     * hand-rolling JSON envelopes — a regex keeps that path
     * symmetric.
     */
    private fun parseJsonField(body: String, field: String): String? {
        val match = Regex("\"$field\"\\s*:\\s*\"([^\"]*)\"").find(body)
            ?: return null
        return match.groupValues[1].takeIf { it.isNotEmpty() }
    }

    /**
     * Lightweight `first_name` extractor for the mock PATCH body. The
     * fixture callers hand-roll the JSON payload
     * (`{"first_name":"..."}`), so a regex match is plenty — adding
     * `kotlinx.serialization` here would double the test time for
     * zero behaviour. Strings are trimmed by the dialog already.
     */
    private fun parseFirstName(body: String): String? {
        val match = Regex("\"first_name\"\\s*:\\s*\"([^\"]*)\"").find(body) ?: return null
        return match.groupValues[1]
    }

    /**
     * Lightweight `id=eq.<value>` filter extractor from the request
     * query. PostgREST emits `id=eq.<uuid>` for equality filtering; a
     * chained query (`id=eq.<x>&status=eq.ACTIVE`) is tolerated by the
     * first-match heuristic.
     */
    private fun parseIdEqFilter(query: String?): String? {
        if (query.isNullOrBlank()) return null
        val match = Regex("^id=eq\\.([^&]+)").find(query) ?: return null
        return match.groupValues[1]
    }

    /**
     * Handles `GET /rest/v1/behavioral_events` (Change A of
     * `feat-parent-behavioral-event-log`). Filters the
     * `behavioral_events.json` fixture client-side by
     * `parent_id=eq.<parentId>` and sorts newest-first by `created_at`.
     * V1 caps at `limit=50` (ignored here — the fixture is 5 rows).
     */
    private fun handleBehavioralEventsGet(request: HttpRequestData): String {
        val parentId = parseParentIdEqFilter(request.url.encodedQuery)
        val rows = events().let { all ->
            val filtered = if (parentId != null) all.filter { it.parent_id == parentId } else all
            filtered.sortedByDescending { it.created_at }
        }
        return json.encodeToString(
            ListSerializer(BehavioralEventFixture.serializer()),
            rows
        )
    }

    /**
     * `parent_id=eq.<value>` filter extractor. Mirrors [parseIdEqFilter]
     * for the `parent_id` column. Returns `null` when the query string
     * omits the filter, in which case the handler falls back to the
     * full fixture (PostgREST unfiltered-read semantics).
     */
    private fun parseParentIdEqFilter(query: String?): String? {
        if (query.isNullOrBlank()) return null
        val match = Regex("(^|&)parent_id=eq\\.([^&]+)").find(query) ?: return null
        return match.groupValues[2]
    }

    /**
     * Defensive JSON minifier for fixture responses.
     *
     * Fixtures live in `app/src/main/assets/mock-supabase/` and are often
     * pretty-printed (with newlines and spaces after colons) for review-ability
     * — that's the format a human wants to diff. But the wire format Supabase
     * actually returns is compact, and a hand-rolled JSON regex consumer (see
     * `PairingManager.extractDeviceId` / `extractParentId` / `extractError`)
     * only matches the compact form unless its regex explicitly tolerates
     * whitespace.
     *
     * Centralizing the minification here means a future fixture can stay
     * pretty-printed (good for review) AND a future regex consumer can stay
     * strict (good for catching real bugs), because the mock engine will
     * always serve compact JSON to consumers — exactly the format the real
     * Supabase returns.
     *
     * Implementation note: uses [kotlinx.serialization.json.Json.parseToJsonElement]
     * + [kotlinx.serialization.json.JsonElement.toString] rather than
     * `org.json.JSONObject.toString()` because the latter escapes forward
     * slashes inside strings (`/` → `\/`). Both forms are valid JSON, but
     * the escaped form breaks byte-for-byte string assertions in callers
     * (e.g. `deeplink.startsWith("parentalcontrol://pair?code=")`) and
     * shifts the actual `/` characters downstream of any `bodyAsText()`
     * call. kotlinx.serialization's encoder preserves `:` and `/` literally.
     *
     * Falls back to the raw text if parsing fails so a malformed fixture
     * surfaces as an immediate JSON parse error in the caller (typed
     * decoding) or as a non-compact response body (regex consumers),
     * instead of being silently swallowed here.
     */
    internal fun minifyJsonIfNeeded(json: String): String {
        val trimmed = json.trim()
        return try {
            when {
                trimmed.startsWith("[") -> Json.parseToJsonElement(trimmed).toString()
                trimmed.startsWith("{") -> Json.parseToJsonElement(trimmed).toString()
                else -> json
            }
        } catch (e: Exception) {
            json
        }
    }

    companion object {
        /**
         * Single source of truth for the demo parent id used by the mock
         * engine. Matches every `parent_id` field in
         * `app/src/main/assets/mock-supabase/behavioral_events.json` (the
         * 5 seeded events) byte-for-byte. Read by
         * `DeviceAuthManager.authenticateOrCreate(Role.PARENT)` so the
         * synthetic auth path writes the same value the DAO filter
         * `WHERE parent_id = :parentId` expects when serving the fixture.
         *
         * Only used by the synthetic hotfix path until the future
         * `parent-auth-flow` change retires both this constant and the
         * coupling together.
         */
        const val MOCK_PARENT_ID = "parent-demo"
    }
}

/**
 * Wire shape that mirrors `ParentRepository.DeviceDto` so the parser
 * contract is shared. Kept here (not in the repository) because the
 * fixture is a test/demo artifact, not production wire data.
 *
 * Change A of `feat-multi-child-picker` (design §A.6 + §A.9): the
 * `child_id` + `child_first_name` columns mirror the new shape the real
 * `get-devices-for-parent` edge function returns after A.4.2.
 * Default-null so the seed shape stays back-compat with pre-migration
 * rows (the spec scenario "Pre-migration device keeps a NULL child_id").
 */
@Serializable
data class DeviceFixture(
    val id: String,
    val device_name: String,
    val device_model: String? = null,
    val os_version: String? = null,
    val app_version: String = "1.0.0",
    val device_state: String = "ACTIVE",
    val policy_version: Int = 1,
    val last_seen_at: String,
    val child_id: String? = null,
    val child: ChildRelationFixture? = null
) {
    /**
     * Hydrated child for in-memory consumers. Computed (no backing
     * field) so kotlinx.serialization ignores it — the on-disk fixture
     * carries the nested `child` object the production edge function
     * returns, and this getter exists for code that already imports the
     * camelCase [ChildFixture] shape. `parent_id` / timestamps are
     * placeholders for the mock fixture; the future standalone
     * `GET /rest/v1/children` fetch returns them.
     */
    val hydratedChild: ChildFixture?
        get() = child?.let {
            ChildFixture(
                id = it.id,
                parent_id = "parent-demo",
                first_name = it.first_name,
                created_at = "",
                updated_at = ""
            )
        }
}

/** Nested `child` from `child:children(id, first_name)` (OBJECT or null). */
@Serializable
data class ChildRelationFixture(
    val id: String,
    val first_name: String
)

/**
 * Change A of `feat-multi-child-picker` (design §A.9): wire shape that
 * mirrors the `children` table columns. The real `get-devices-for-parent`
 * response uses the nested resource embedding
 * `child:children(id, first_name)`; this top-level fixture mirrors the
 * `GET /rest/v1/children` response shape for the standalone fetch the
 * RenameChildDialog uses after a rename (Change B §B.6).
 */
@Serializable
data class ChildFixture(
    val id: String,
    val parent_id: String,
    val first_name: String,
    val created_at: String,
    val updated_at: String
) {
    /**
     * Kotlin-side alias matching `models.Child.firstName`. Computed
     * (no backing field) so kotlinx.serialization ignores it; the JSON
     * column stays `first_name` for wire compatibility. Lets callers
     * that already import `models.Child` keep their camelCase reads.
     */
    val firstName: String get() = first_name
    val parentId: String get() = parent_id
    val createdAt: String get() = created_at
    val updatedAt: String get() = updated_at
}

/**
 * Wire shape that mirrors `ParentRepository.TimeRequestDto` so the parser
 * contract is shared.
 */
@Serializable
data class PendingFixture(
    val id: String,
    val device_id: String,
    val package_name: String? = null,
    val app_name: String? = null,
    val minutes_requested: Int,
    val reason: String? = null,
    val status: String,
    val created_at: String,
    val responded_at: String? = null,
    val parent_response: String? = null,
    val devices: DeviceRelationFixture? = null
)

/** Nested `devices` from `select=*,devices(device_name)` (OBJECT or null). */
@Serializable
data class DeviceRelationFixture(
    val device_name: String
)

/**
 * New fixture shape for policy templates. The existing
 * `ParentRepository.getTemplates()` returns a hardcoded list and does not
 * hit Supabase yet; this fixture is the seam for the future
 * `getTemplates()` HTTP call.
 */
@Serializable
data class TemplateFixture(
    val templateId: String,
    val name: String,
    val policyJson: String
)

/**
 * Wire shape for `GET /rest/v1/behavioral_events`. Mirrors the
 * `BehavioralEventEntity` columns added by
 * `ParentalDatabase.MIGRATION_6_7` (Change A of
 * `feat-parent-behavioral-event-log`).
 */
@Serializable
data class BehavioralEventFixture(
    val id: Long = 0,
    val event_type: String,
    val event_version: Int = 1,
    val device_id: String,
    val client_ts: String,
    val props: String,
    val synced: Boolean = true,
    val created_at: String,
    val parent_id: String? = null
) {
    /**
     * Maps the wire row to the [BehavioralEventEntity] the DAO writes.
     * The `id` is forwarded verbatim so `OnConflictStrategy.REPLACE`
     * keeps the row identity stable across replays (the contract the
     * `BehavioralEventsRepositoryTest.refresh_idempotency_…` case
     * exercises).
     */
    fun toEntity(): BehavioralEventEntity =
        BehavioralEventEntity(
            id = id,
            event_type = event_type,
            event_version = event_version,
            device_id = device_id,
            client_ts = client_ts,
            props = props,
            synced = synced,
            created_at = created_at,
            parentId = parent_id
        )
}
