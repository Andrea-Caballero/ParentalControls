package com.tudominio.parentalcontrol.data.repository

import android.content.Context
import androidx.room.Room
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.data.db.BehavioralEventDao
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.network.SupabaseClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Field

/**
 * RED→GREEN coverage for the data-layer slice of
 * `feat-parent-behavioral-event-log` (PR A). Mirrors the
 * `ParentRepositoryRenameTest` pattern (Robolectric + a real
 * `SupabaseClientProvider(context, injectedClient = mockClient)` +
 * reflection-injected `DeviceAuthManager.getAccessToken()`) because
 * mockk 1.13.7 has project-wide issues mocking the Kotlin
 * final-class private constructors on this codebase.
 *
 * Each test owns its own [HttpClient] + [SupabaseClientProvider] so a
 * mid-test client close never trips Ktor's "Parent job is Completed"
 * on a stale delegate (the bug we hit in the first iteration).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BehavioralEventsRepositoryTest {

    private lateinit var context: Context
    private lateinit var database: ParentalDatabase
    private lateinit var dao: BehavioralEventDao
    private lateinit var authManager: DeviceAuthManager
    private val openClients = mutableListOf<HttpClient>()

    @Before
    fun setUp() {
        context = org.robolectric.RuntimeEnvironment.getApplication()
        openClients.clear()
        authManager = DeviceAuthManager.getInstance(context)
        injectAccessToken(authManager, "test-jwt-token")
        database = Room.inMemoryDatabaseBuilder(
            context,
            ParentalDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.behavioralEventDao()
    }

    @After
    fun tearDown() {
        injectAccessToken(authManager, null)
        database.close()
        openClients.forEach { runCatching { it.close() } }
    }

    private fun injectAccessToken(target: DeviceAuthManager, token: String?) {
        val field: Field = DeviceAuthManager::class.java
            .getDeclaredField("currentAccessToken")
        field.isAccessible = true
        field.set(target, token)
    }

    /** Per-test holder: client + provider + repository wired together. */
    private inner class MockClientHolder(responseBody: String) {
        val captured = mutableListOf<HttpRequestData>()
        val client: HttpClient = HttpClient(
            MockEngine { request ->
                captured.add(request)
                respond(
                    content = ByteReadChannel(responseBody),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }.also { openClients.add(it) }

        val provider = SupabaseClientProvider(context, injectedClient = client)
        val repository = BehavioralEventsRepository(
            clientProvider = provider,
            dao = this@BehavioralEventsRepositoryTest.dao,
            authManager = this@BehavioralEventsRepositoryTest.authManager
        )
    }

    /** Asserts the captured GET carries the expected wire shape. */
    private fun assertBehavioralEventsGet(captured: List<HttpRequestData>, parentId: String) {
        assertEquals(
            "refresh must issue exactly one HTTP call, got ${captured.size}",
            1,
            captured.size
        )
        val req = captured.first()
        assertEquals(HttpMethod.Get, req.method)
        val url = req.url.toString()
        assertTrue(
            "URL should target /rest/v1/behavioral_events, got $url",
            url.contains("/rest/v1/behavioral_events")
        )
        assertTrue(
            "URL should include parent_id=eq.$parentId, got $url",
            url.contains("parent_id=eq.$parentId")
        )
        assertTrue(
            "URL should include order=created_at.desc, got $url",
            url.contains("order=created_at.desc")
        )
        assertEquals("Bearer test-jwt-token", req.headers[HttpHeaders.Authorization])
        assertEquals(SupabaseClientProvider.SUPABASE_ANON_KEY, req.headers["apikey"])
    }

    /**
     * Empty 200 response leaves the DAO at zero rows. Asserts BOTH the
     * wire shape AND the observable DAO state — the former is the
     * PostgREST contract and the latter is what the parent UI binds to.
     */
    @Test
    fun empty_events_response_yields_zero_dao_rows() = runTest {
        val h = MockClientHolder(responseBody = "[]")

        val result = h.repository.refresh(parentId = "parent-demo")

        assertTrue("Expected Result.success on 200 + [], got $result", result.isSuccess)
        assertBehavioralEventsGet(h.captured, "parent-demo")
        assertEquals(
            "DAO row count after empty refresh must be 0",
            0,
            dao.flowByParent("parent-demo").first().size
        )
    }

    /** Single-event response writes one row carrying the wire-side `parent_id`. */
    @Test
    fun single_event_response_yields_one_dao_row() = runTest {
        val h = MockClientHolder(responseBody = SINGLE_EVENT_RESPONSE)

        val result = h.repository.refresh(parentId = "parent-demo")

        assertTrue("Expected Result.success on 200 + 1 row, got $result", result.isSuccess)
        assertBehavioralEventsGet(h.captured, "parent-demo")

        val rows = dao.flowByParent("parent-demo").first()
        assertEquals("DAO row count must be 1", 1, rows.size)
        assertEquals("event_type must match the wire response", "limit_reached", rows.first().event_type)
        assertEquals("parentId must match the request filter", "parent-demo", rows.first().parentId)
    }

    /** 3-row response writes 3 rows ordered newest-first (DAO `ORDER BY created_at DESC`). */
    @Test
    fun multiple_events_response_yields_three_dao_rows_in_desc_order() = runTest {
        val h = MockClientHolder(responseBody = MULTI_EVENT_RESPONSE)

        val result = h.repository.refresh(parentId = "parent-demo")

        assertTrue("Expected Result.success on 200 + 3 rows, got $result", result.isSuccess)

        val rows = dao.flowByParent("parent-demo").first()
        assertEquals("DAO row count must be 3", 3, rows.size)
        assertEquals(
            "Rows must be ordered newest-first",
            listOf("2026-07-08T11:00:00Z", "2026-07-08T10:00:00Z", "2026-07-08T09:00:00Z"),
            rows.map { it.created_at }
        )
    }

    /**
     * Two `refresh()` calls against the same payload leave the DAO at
     * 3 rows — `OnConflictStrategy.REPLACE` keeps the `id` stable so
     * the second upsert replaces in-place rather than appending.
     */
    @Test
    fun refresh_idempotency_keeps_row_count_stable_across_replays() = runTest {
        val h = MockClientHolder(responseBody = MULTI_EVENT_RESPONSE)

        val first = h.repository.refresh(parentId = "parent-demo")
        val second = h.repository.refresh(parentId = "parent-demo")

        assertTrue("First refresh must succeed, got $first", first.isSuccess)
        assertTrue("Second refresh must succeed, got $second", second.isSuccess)
        assertEquals(
            "Two refreshes against the same response must yield exactly 3 rows (no duplicates)",
            3,
            dao.flowByParent("parent-demo").first().size
        )
        assertEquals("Two refreshes must issue exactly two HTTP calls", 2, h.captured.size)
    }

    companion object {
        private const val SINGLE_EVENT_RESPONSE = """[
            {
                "id": 1, "event_type": "limit_reached", "event_version": 1,
                "device_id": "dev-001", "client_ts": "2026-07-08T10:00:00Z",
                "props": "{}", "synced": false,
                "created_at": "2026-07-08T10:00:00Z", "parent_id": "parent-demo"
            }
        ]"""

        private const val MULTI_EVENT_RESPONSE = """[
            {
                "id": 3, "event_type": "block_overlay_shown", "event_version": 1,
                "device_id": "dev-002", "client_ts": "2026-07-08T11:00:00Z",
                "props": "{}", "synced": false,
                "created_at": "2026-07-08T11:00:00Z", "parent_id": "parent-demo"
            },
            {
                "id": 2, "event_type": "time_warning_shown", "event_version": 1,
                "device_id": "dev-001", "client_ts": "2026-07-08T10:00:00Z",
                "props": "{}", "synced": false,
                "created_at": "2026-07-08T10:00:00Z", "parent_id": "parent-demo"
            },
            {
                "id": 1, "event_type": "limit_reached", "event_version": 1,
                "device_id": "dev-001", "client_ts": "2026-07-08T09:00:00Z",
                "props": "{}", "synced": false,
                "created_at": "2026-07-08T09:00:00Z", "parent_id": "parent-demo"
            }
        ]"""
    }
}
