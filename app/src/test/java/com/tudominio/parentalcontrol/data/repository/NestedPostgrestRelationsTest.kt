package com.tudominio.parentalcontrol.data.repository

import android.content.Context
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Contract tests for the nested PostgREST relation wire shapes the
 * production edge functions actually return. `child:children(id, first_name)`
 * yields an OBJECT (or null); `select=*,devices(device_name)` yields an
 * OBJECT (or null). The old flat `child_first_name` / `device_name`
 * columns are intentionally absent on the wire.
 */
class NestedPostgrestRelationsTest {

    private lateinit var mockClient: HttpClient
    private lateinit var authManager: DeviceAuthManager
    private lateinit var clientProvider: SupabaseClientProvider
    private lateinit var context: Context

    @Before
    fun setUp() {
        authManager = mockk()
        every { authManager.getAccessToken() } returns "test-jwt-token"
        clientProvider = mockk()
        context = mockk(relaxed = true)
    }

    @After
    fun tearDown() { if (::mockClient.isInitialized) mockClient.close() }

    private fun repo(body: String): ParentRepository {
        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    "application/json"
                )
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        every { clientProvider.httpClient } returns client
        mockClient = client
        return ParentRepository(context, authManager, clientProvider)
    }

    companion object {
        // Three-device fixture: dev-001 has a NESTED `child` object (precedence
        // test — top-level child_id disagrees with the nested child.id).
        // dev-002 has `child: null`. Covers nested object, null relation, and
        // ID precedence in one fixture.
        private const val DEVICES_FIXTURE = """[{
            "id":"dev-001","device_name":"Galaxy Tab S6 Lite","device_state":"ACTIVE",
            "policy_version":3,"last_seen_at":"2026-06-19T20:55:00Z",
            "child_id":"child-legacy-stale",
            "child":{"id":"child-lucas","first_name":"Lucas"}
        },{
            "id":"dev-002","device_name":"Moto G32","device_state":"DOWNTIME",
            "policy_version":1,"last_seen_at":"2026-06-19T20:58:00Z",
            "child_id":null,"child":null
        }]"""

        // Two-row pending-requests fixture: dev-001 emits `devices.device_name`;
        // dev-orphan emits `devices: null`. Covers nested object hydration AND
        // null relation in one fixture.
        private const val PENDING_FIXTURE = """[{
            "id":"req-001","device_id":"dev-001","app_name":"Instagram",
            "minutes_requested":15,"status":"PENDING",
            "created_at":"2026-06-19T20:50:00Z",
            "devices":{"device_name":"Galaxy Tab S6 Lite"}
        },{
            "id":"req-002","device_id":"dev-orphan","app_name":"WhatsApp",
            "minutes_requested":30,"status":"PENDING",
            "created_at":"2026-06-19T20:52:00Z","devices":null
        }]"""
    }

    @Test
    fun getDevices_hydrates_child_from_nested_relation_with_deterministic_precedence() = runTest {
        val devices = repo(DEVICES_FIXTURE).getDevices().getOrNull()!!

        // dev-001: nested child wins over legacy top-level child_id.
        val d1 = devices.first { it.id == "dev-001" }
        assertNotNull("child must hydrate from nested relation", d1.child)
        assertEquals("child.id follows nested, not legacy child_id", "child-lucas", d1.child!!.id)
        assertEquals("Lucas", d1.child!!.firstName)

        // dev-002: `child: null` on the wire → `child = null` (no orphan hydration).
        val d2 = devices.first { it.id == "dev-002" }
        assertNull(
            "ChildDevice.child must be null when nested `child` is null on the wire",
            d2.child
        )
    }

    @Test
    fun getPendingRequests_hydrates_deviceName_from_nested_devices_and_tolerates_null_relation() = runTest {
        val requests = repo(PENDING_FIXTURE).getPendingRequests().getOrNull()!!

        // req-001: nested `devices.device_name` hydrates TimeRequest.deviceName.
        val r1 = requests.first { it.id == "req-001" }
        assertEquals(
            "TimeRequest.deviceName must come from nested `devices.device_name`",
            "Galaxy Tab S6 Lite",
            r1.deviceName
        )

        // req-002: `devices: null` on the wire → deviceName = null (no crash).
        val r2 = requests.first { it.id == "req-002" }
        assertNull(
            "TimeRequest.deviceName must stay null when nested `devices` is null",
            r2.deviceName
        )
        // Sanity: the rest of the row still parses.
        assertEquals("WhatsApp", r2.appName)
    }
}
