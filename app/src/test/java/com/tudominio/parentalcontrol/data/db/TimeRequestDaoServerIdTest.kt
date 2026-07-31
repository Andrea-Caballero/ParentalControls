package com.tudominio.parentalcontrol.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tudominio.parentalcontrol.data.model.TimeRequestEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [TimeRequestDao] server-id methods.
 *
 * The outbox/sync remediation slice splits the locally-generated
 * `request_id` (primary key) from the server-assigned `server_id`
 * (Supabase `time_requests.id`). `SyncManager.sendOutboxItem` writes
 * the server's id to `server_id` instead of renaming `request_id` in
 * place — the rename was racy (parent approval could arrive before
 * the rename) and broke the `GrantEntity.request_id` soft-FK.
 *
 * `pullApprovedRequests` looks rows up via
 * [TimeRequestDao.getRequestByRequestIdOrServerId] so that:
 *
 *  - Post-v9 reconciliation writes the server's id to `server_id`
 *    and the approval row's id matches that column.
 *  - Pre-v9 reconciliation renamed `request_id` in place and the
 *    upgrade path keeps resolving through the same query (falling
 *    back to the renamed `request_id` when `server_id` is null).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TimeRequestDaoServerIdTest {

    private lateinit var context: Context
    private lateinit var db: ParentalDatabase
    private lateinit var timeRequestDao: TimeRequestDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ParentalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        timeRequestDao = db.timeRequestDao()
    }

    @After
    fun tearDown() {
        runCatching { db.close() }
    }

    private fun insertRequest(
        requestId: String = "req_test",
        deviceId: String = "dev-1",
        status: String = "PENDING",
        serverId: String? = null
    ): TimeRequestEntity = TimeRequestEntity(
        request_id = requestId,
        device_id = deviceId,
        package_name = null,
        minutes_requested = 15,
        reason = "homework",
        status = status,
        created_at = "2026-07-27T10:00:00Z",
        responded_at = null,
        parent_response = null,
        server_id = serverId
    ).also { runBlocking { timeRequestDao.insertRequest(it) } }

    @Test
    fun setServerId_writes_server_id_without_touching_request_id() = runBlocking {
        val req = insertRequest(requestId = "req_local")
        assertNull(
            "precondition: freshly inserted request has no server_id",
            timeRequestDao.getRequestById("req_local")!!.server_id
        )

        timeRequestDao.setServerId("req_local", "srv-remote-123")

        val after = timeRequestDao.getRequestById("req_local")!!
        assertEquals(
            "setServerId must keep the original request_id intact",
            "req_local",
            after.request_id
        )
        assertEquals(
            "setServerId must write the new server_id",
            "srv-remote-123",
            after.server_id
        )
    }

    @Test
    fun setServerId_is_idempotent_under_repeated_writes() = runBlocking {
        insertRequest(requestId = "req_idem")
        timeRequestDao.setServerId("req_idem", "srv-1")
        timeRequestDao.setServerId("req_idem", "srv-2")

        val after = timeRequestDao.getRequestById("req_idem")!!
        assertEquals("srv-2", after.server_id)
    }

    @Test
    fun getRequestByServerId_returns_row_matching_server_id() = runBlocking {
        insertRequest(requestId = "req_a", serverId = "srv_a")
        insertRequest(requestId = "req_b", serverId = "srv_b")
        insertRequest(requestId = "req_c", serverId = null)

        val hit = timeRequestDao.getRequestByServerId("srv_b")
        assertNotNull("must find the row by server_id", hit)
        assertEquals("req_b", hit!!.request_id)
    }

    @Test
    fun getRequestByServerId_returns_null_when_no_match() = runBlocking {
        insertRequest(requestId = "req_a", serverId = "srv_a")
        assertNull(timeRequestDao.getRequestByServerId("srv_unknown"))
    }

    @Test
    fun getRequestByRequestIdOrServerId_resolves_by_server_id_first() = runBlocking {
        // Two distinct rows: one matches by request_id, one by server_id.
        // The combined lookup must return the row that actually carries
        // the id the parent app used to look the approval up.
        val byRequestId = insertRequest(requestId = "req_match_request", serverId = null)
        val byServerId = insertRequest(requestId = "req_match_server", serverId = "srv_x")

        val hit = timeRequestDao.getRequestByRequestIdOrServerId("srv_x")
        assertNotNull("must find the row whose server_id matches", hit)
        assertEquals("req_match_server", hit!!.request_id)
        // Sanity: the request_id-keyed row is still findable by its own id.
        val direct = timeRequestDao.getRequestByRequestIdOrServerId("req_match_request")
        assertEquals(byRequestId.request_id, direct!!.request_id)
        assertEquals(byServerId.request_id, hit.request_id)
    }

    @Test
    fun getRequestByRequestIdOrServerId_falls_back_to_request_id_when_server_id_is_null() = runBlocking {
        // Pre-v9 reconciliation renamed request_id in place; on the
        // upgrade path server_id is null. The combined lookup must
        // still find the row by the (renamed) request_id.
        insertRequest(requestId = "req_legacy_renamed", serverId = null)

        val hit = timeRequestDao.getRequestByRequestIdOrServerId("req_legacy_renamed")
        assertNotNull("must find pre-v9 row by its renamed request_id", hit)
        assertEquals("req_legacy_renamed", hit!!.request_id)
    }

    @Test
    fun getRequestByRequestIdOrServerId_returns_null_when_neither_field_matches() = runBlocking {
        insertRequest(requestId = "req_a", serverId = "srv_a")
        assertNull(timeRequestDao.getRequestByRequestIdOrServerId("not_in_table"))
    }
}
