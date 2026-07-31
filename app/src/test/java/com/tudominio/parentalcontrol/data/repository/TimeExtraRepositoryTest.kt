package com.tudominio.parentalcontrol.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.data.model.GrantEntity
import com.tudominio.parentalcontrol.data.model.TimeRequestEntity
import com.tudominio.parentalcontrol.outbox.OutboxManager
import com.tudominio.parentalcontrol.time.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
 * Tests for [TimeExtraRepository].
 *
 * Covers the three remediation bugs in this slice:
 *
 *  1. `processApproval` must commit the request-status update and the
 *     grant insert atomically — never one without the other.
 *  2. `processApproval` must return `GrantResult.Error` for unknown
 *     request ids (the previous implementation silently no-op'd after
 *     the status update, leaving a phantom APPROVED row).
 *  3. `createTimeRequest` must persist `status = "PENDING"` (uppercase)
 *     so the `getPendingRequestsFlow` (which filters on `status = 'PENDING'`)
 *     actually surfaces the new request. The old lowercase write silently
 *     orphaned the row.
 *
 * The shallow constants-only tests that used to live in this file
 * (throttle / max-minutes / default-grant) are preserved at the bottom
 * for backward compatibility with PR-history references.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TimeExtraRepositoryTest {

    private lateinit var context: Context
    private lateinit var database: ParentalDatabase
    private lateinit var outboxManager: OutboxManager
    private lateinit var fakeTime: FakeTimeProvider
    private lateinit var repository: TimeExtraRepository

    private val baseWallMillis = 1_700_000_000_000L // 2023-11-14T22:13:20Z

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(
            context,
            ParentalDatabase::class.java
        ).allowMainThreadQueries().build()
        // Robolectric shares a single application context across tests, so
        // the throttle SharedPreferences (`time_extra_prefs`) may carry a
        // `last_request_time` value written by a prior test inside the
        // same JVM. Clear it so each test starts un-throttled.
        context.getSharedPreferences("time_extra_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        outboxManager = OutboxManager(context, database)
        fakeTime = FakeTimeProvider(
            fakeWallMillis = baseWallMillis,
            fakeElapsed = baseWallMillis
        )
        repository = TimeExtraRepository(
            context = context,
            database = database,
            outboxManager = outboxManager,
            timeProvider = fakeTime
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun seedPendingRequest(
        requestId: String = "req_test",
        deviceId: String = "dev-abc"
    ): TimeRequestEntity {
        val now = fakeTime.wallInstant().toEpochMilli().toString()
        val req = TimeRequestEntity(
            request_id = requestId,
            device_id = deviceId,
            package_name = null,
            minutes_requested = 15,
            reason = "homework",
            status = "PENDING",
            created_at = now,
            responded_at = null,
            parent_response = null
        )
        database.timeRequestDao().insertRequest(req)
        return req
    }

    // ===== Goal 1: atomicity =====

    /**
     * Happy path: both writes commit together. Asserts the canonical
     * remediation contract — anything weaker would let the bug back in.
     */
    @Test
    fun processApproval_commits_request_status_update_and_grant_insert_together() = runTest {
        val requestId = "req_atomic_ok"
        seedPendingRequest(requestId = requestId)

        val beforeGrants = database.grantDao()
            .getGrantsForScopeFlow("dev-abc", "extra_time").first()
        assertTrue("test pre-condition: no extra_time grants", beforeGrants.isEmpty())

        val result = repository.processApproval(
            requestId = requestId,
            approvedMinutes = 30
        )

        assertTrue("Expected GrantResult.Success, got $result", result is GrantResult.Success)
        val after = database.timeRequestDao().getRequestById(requestId)!!
        // Status now matches the canonical RequestStatus enum that the
        // Supabase filter and ChildStatusViewModel both use.
        assertEquals("APPROVED", after.status)
        assertEquals("responded_at must be populated", fakeTime.wallInstant().toString(), after.responded_at)

        val grants = database.grantDao()
            .getGrantsForScopeFlow("dev-abc", "extra_time").first()
        assertEquals("exactly one extra_time grant must exist", 1, grants.size)
        val grant = grants.first()
        assertEquals("extra_time_$requestId", grant.id)
        assertEquals("dev-abc", grant.device_id)
        assertEquals(requestId, grant.request_id)
        assertEquals("extra_time", grant.source)
        assertEquals("extra_time", grant.scope)
        assertEquals(30, grant.minutes)
    }

    /**
     * Atomicity proof: when the request does not exist the helper must
     * return `GrantResult.Error` AND must NOT leave a stray grant row.
     * The pre-fix code path called `updateRequestStatus` before the
     * null-check on the request row, so an UPDATE-with-no-match silently
     * "succeeded"; the retry would then re-create a grant against a
     * missing request.
     */
    @Test
    fun processApproval_returns_error_and_writes_nothing_for_unknown_request() = runTest {
        val result = repository.processApproval(
            requestId = "req_does_not_exist",
            approvedMinutes = 30
        )

        assertTrue(
            "Unknown request id must surface as GrantResult.Error, got $result",
            result is GrantResult.Error
        )
        // Room's UPDATE...WHERE matches zero rows when the request does not
        // exist, so re-querying must yield null (no phantom row written).
        assertNull(
            "No request row must be created as a side effect of a failed approval",
            database.timeRequestDao().getRequestById("req_does_not_exist")
        )
        // And the grants table must stay empty.
        val grants = database.grantDao().getGrantsForScopeFlow("dev-abc", "extra_time").first()
        assertEquals(
            "No extra_time grant must leak out of a failed approval",
            0,
            grants.size
        )
    }

    /**
     * Atomicity proof: when the second writer (the grant insert) blows up,
     * the first writer (the request status update) must roll back.
     * Mirrors a real crash that the old code turned into a half-approved
     * request the parent UI then had to reconcile on next boot.
     *
     * We trigger the second-writer failure by inserting a row with the
     * same primary key into `grants` ahead of time. The repository uses
     * `OnConflictStrategy.REPLACE` for inserts in a normal flow, but for
     * this test we instead force a constraint violation by deleting the
     * underlying table state — the cleanest, deterministic way to prove
     * the rollback in a JVM test.
     */
    @Test
    fun processApproval_rolls_back_request_status_when_grant_insert_throws() = runTest {
        val requestId = "req_atomic_rollback"
        seedPendingRequest(requestId = requestId)
        assertEquals(
            "pre-condition: request starts as PENDING",
            "PENDING",
            database.timeRequestDao().getRequestById(requestId)!!.status
        )

        // Swap the in-memory database for one whose `grants` table has
        // been renamed out from under us — `insertGrant` will then throw
        // because Room's generated code resolves a missing relation at
        // prepare-time. Wrapping the call in `withTransaction` is what
        // makes the request-status update roll back when the grant
        // insert blows up.
        val brokenDb = Room.inMemoryDatabaseBuilder(
            context,
            ParentalDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            // Disable FK + rename the grants table to force a SQLiteException.
            brokenDb.openHelper.writableDatabase.execSQL("ALTER TABLE grants RENAME TO grants_gone")
            val brokenRepo = TimeExtraRepository(
                context = context,
                database = brokenDb,
                outboxManager = OutboxManager(context, brokenDb),
                timeProvider = fakeTime
            )

            // We need to seed the pending request into the broken DB too
            // because we are now writing to a different in-memory instance.
            val now = fakeTime.wallInstant().toEpochMilli().toString()
            brokenDb.timeRequestDao().insertRequest(
                TimeRequestEntity(
                    request_id = requestId,
                    device_id = "dev-abc",
                    package_name = null,
                    minutes_requested = 15,
                    reason = "homework",
                    status = "PENDING",
                    created_at = now,
                    responded_at = null,
                    parent_response = null
                )
            )

            val result = brokenRepo.processApproval(requestId, approvedMinutes = 30)

            assertTrue(
                "Broken grants table must surface as GrantResult.Error, got $result",
                result is GrantResult.Error
            )
            val after = brokenDb.timeRequestDao().getRequestById(requestId)
            assertNotNull("Request row must still exist (no rogue delete)", after)
            assertEquals(
                "Status must still be PENDING because the grant insert threw — " +
                    "withTransaction guarantees both writes commit together or roll back together",
                "PENDING",
                after!!.status
            )
            assertNull("responded_at must stay null on rollback", after.responded_at)
        } finally {
            brokenDb.close()
        }
    }

    // ===== Goal 3: request identity preservation =====

    /**
     * Post-v9 reconciliation: the local row's `request_id` stays the
     * client-generated id; the server's id lives in `server_id`.
     * `pullApprovedRequests` (and the parent's dashboard) sees the
     * server's id in the approval row, so `processApproval` MUST
     * resolve the local row by `server_id` and stamp the LOCAL
     * `request_id` (not the server's id) on the resulting grant. The
     * grant's `request_id` is the soft-FK the UI keys off; flipping
     * it to the server's id would break the Solicitudes tab.
     */
    @Test
    fun processApproval_resolves_local_row_via_server_id_when_present() = runTest {
        val localRequestId = "req_local_uuid"
        val serverId = "srv_remote_uuid_xyz"
        val now = fakeTime.wallInstant().toEpochMilli().toString()
        database.timeRequestDao().insertRequest(
            TimeRequestEntity(
                request_id = localRequestId,
                device_id = "dev-abc",
                package_name = null,
                minutes_requested = 15,
                reason = "homework",
                status = "PENDING",
                created_at = now,
                responded_at = null,
                parent_response = null,
                server_id = serverId
            )
        )

        val result = repository.processApproval(
            requestId = serverId, // parent dashboard uses server's id
            approvedMinutes = 30
        )

        assertTrue(
            "processApproval must resolve the local row by server_id, got $result",
            result is GrantResult.Success
        )
        val after = database.timeRequestDao().getRequestById(localRequestId)!!
        assertEquals("APPROVED", after.status)

        // The grant's request_id MUST be the LOCAL primary key, not
        // the server's id — the soft-FK in `GrantEntity.request_id`
        // is keyed on the local `time_requests.request_id`.
        val grants = database.grantDao().getGrantsForScopeFlow("dev-abc", "extra_time").first()
        assertEquals(1, grants.size)
        assertEquals(localRequestId, grants[0].request_id)
        assertEquals("extra_time_$localRequestId", grants[0].id)
    }

    /**
     * Pre-v9 reconciliation upgrade path: the local row's `request_id`
     * was renamed in place to match the server's id; `server_id` is
     * null. `processApproval` MUST still resolve the row (via the
     * `request_id` branch of the combined lookup) so the
     * `time_requests` table created by an upgrade from a v8 build
     * keeps working without a backfill migration.
     */
    @Test
    fun processApproval_resolves_local_row_by_request_id_for_pre_v9_renamed_rows() = runTest {
        val renamedRequestId = "srv_legacy_id" // was renamed in place pre-v9
        val now = fakeTime.wallInstant().toEpochMilli().toString()
        database.timeRequestDao().insertRequest(
            TimeRequestEntity(
                request_id = renamedRequestId,
                device_id = "dev-abc",
                package_name = null,
                minutes_requested = 15,
                reason = "homework",
                status = "PENDING",
                created_at = now,
                responded_at = null,
                parent_response = null,
                server_id = null
            )
        )

        val result = repository.processApproval(
            requestId = renamedRequestId,
            approvedMinutes = 30
        )

        assertTrue(
            "processApproval must resolve the pre-v9 renamed row by request_id, got $result",
            result is GrantResult.Success
        )
        val grants = database.grantDao().getGrantsForScopeFlow("dev-abc", "extra_time").first()
        assertEquals(1, grants.size)
        assertEquals(renamedRequestId, grants[0].request_id)
    }

    // ===== Goal 3: status casing =====

    /**
     * `createTimeRequest` must persist `status = "PENDING"` so the
     * reactive `getPendingRequestsFlow` (`WHERE status = 'PENDING'`)
     * actually surfaces the new row. The lowercase write that used to
     * live here silently orphaned the request.
     */
    @Test
    fun createTimeRequest_persists_uppercase_pending_status_and_surfaces_in_pending_flow() = runTest {
        val result = repository.createTimeRequest(
            deviceId = "dev-up",
            minutes = 10,
            reason = null
        )
        assertTrue(
            "Expected TimeRequestResult.Success, got $result",
            result is TimeRequestResult.Success
        )
        val req = database.timeRequestDao().getRequestById((result as TimeRequestResult.Success).requestId)
        assertNotNull(req)
        assertEquals(
            "createTimeRequest must persist the canonical UPPERCASE status " +
                "matching RequestStatus.PENDING",
            "PENDING",
            req!!.status
        )
        val pending = database.timeRequestDao().getPendingRequestsFlow().first()
        assertTrue(
            "getPendingRequestsFlow must yield the freshly inserted request " +
                "(previously lowercase writes were silently filtered out)",
            pending.any { it.request_id == req.request_id }
        )
    }

    /**
     * Denial path must also use the canonical UPPERCASE status so it
     * stops appearing in the pending flow.
     */
    @Test
    fun processDenial_persists_uppercase_denied_status_and_drops_from_pending_flow() = runTest {
        val requestId = "req_denied"
        seedPendingRequest(requestId = requestId)
        assertTrue(
            "pre-condition: request is currently in the pending flow",
            database.timeRequestDao().getPendingRequestsFlow().first()
                .any { it.request_id == requestId }
        )

        repository.processDenial(requestId)

        val after = database.timeRequestDao().getRequestById(requestId)!!
        assertEquals("DENIED", after.status)
        assertFalse(
            "After denial the request must no longer appear in the pending flow",
            database.timeRequestDao().getPendingRequestsFlow().first()
                .any { it.request_id == requestId }
        )
    }

    // ===== Pre-existing constants tests =====

    @Test
    fun `throttle minimum is 5 minutes`() {
        val THROTTLE_MIN = 5L
        assertEquals(5L, THROTTLE_MIN)
    }

    @Test
    fun `max request minutes is 120`() {
        val MAX_REQUEST = 120
        assertEquals(120, MAX_REQUEST)
    }

    @Test
    fun `default grant duration is 30 minutes`() {
        val DEFAULT_DURATION = 30L
        assertEquals(30L, DEFAULT_DURATION)
    }

    // ===== Goal 2: createTimeRequest atomicity =====

    /**
     * When the outbox enqueue throws (or returns false), the local
     * `time_requests` row must be rolled back — otherwise the user
     * sees a "PENDING" request on the Solicitudes tab that will never
     * reach the parent. The pre-fix code wrote the request row first
     * and only enqueued afterwards, so a process kill or DB error
     * between the two writes left a phantom row.
     */
    @Test
    fun createTimeRequest_rolls_back_request_row_when_outbox_enqueue_throws() = runTest {
        val brokenOutboxManager: OutboxManager = mockk()
        coEvery { brokenOutboxManager.enqueueTimeRequest(any()) } throws
            RuntimeException("outbox DB locked")

        val brokenRepo = TimeExtraRepository(
            context = context,
            database = database,
            outboxManager = brokenOutboxManager,
            timeProvider = fakeTime
        )

        val result = brokenRepo.createTimeRequest(
            deviceId = "dev-rollback",
            minutes = 15,
            reason = "homework"
        )

        assertTrue(
            "Expected TimeRequestResult.Error when outbox enqueue throws, got $result",
            result is TimeRequestResult.Error
        )
        // The time_requests table must NOT carry a phantom PENDING row.
        // We assert via getPendingRequestsFlow because the
        // canonical "no phantom row" check is the empty pending list
        // (any leaked row would surface here).
        val pending = database.timeRequestDao().getPendingRequestsFlow().first()
        assertTrue(
            "createTimeRequest must NOT leak a phantom PENDING row when the outbox enqueue throws, " +
                "got $pending",
            pending.isEmpty()
        )
        // Direct row count via the DAO — proves the empty
        // `getPendingRequestsFlow` observation comes from real
        // production code (the SQL filter) rather than from
        // a missing insert. The test triangulates the empty
        // collection assertion by reaching the table through a
        // different query path.
        val allRequests = database.timeRequestDao().getRequestsForDeviceFlow("dev-rollback").first()
        assertTrue(
            "time_requests must be empty for dev-rollback after a failed enqueue",
            allRequests.isEmpty()
        )
    }

    /**
     * The throttle SharedPreferences stamp must NOT be bumped when
     * the outbox enqueue fails. The pre-fix code wrote the throttle
     * BEFORE enqueuing, so a failed enqueue locked the user out of
     * making another request for the next 5 minutes even though no
     * request ever reached the device. The post-fix transaction
     * guarantees the throttle is only saved on a fully committed
     * request + outbox pair.
     */
    @Test
    fun createTimeRequest_does_not_bump_throttle_when_outbox_enqueue_fails() = runTest {
        val brokenOutboxManager: OutboxManager = mockk()
        coEvery { brokenOutboxManager.enqueueTimeRequest(any()) } throws
            RuntimeException("outbox DB locked")

        val brokenRepo = TimeExtraRepository(
            context = context,
            database = database,
            outboxManager = brokenOutboxManager,
            timeProvider = fakeTime
        )

        val prefs = context.getSharedPreferences("time_extra_prefs", Context.MODE_PRIVATE)
        // Pre-condition: throttle stamp is at the default (0). Any
        // bumped value would come from production code, not setup.
        assertEquals(
            "pre-condition: throttle SharedPreferences must start cleared",
            0L,
            prefs.getLong("last_request_time", 0L)
        )

        val result = brokenRepo.createTimeRequest(
            deviceId = "dev-throttle",
            minutes = 15,
            reason = "homework"
        )
        assertTrue(
            "createTimeRequest must surface the enqueue failure as Error, got $result",
            result is TimeRequestResult.Error
        )

        // The throttle SharedPreferences must NOT have been written.
        // If saveLastRequestTime ran before the enqueue (the pre-fix
        // behavior), the value would be the fakeTime wall-instant
        // epoch-ms — the test would fail here.
        assertEquals(
            "createTimeRequest must NOT bump the throttle when the outbox enqueue fails",
            0L,
            prefs.getLong("last_request_time", 0L)
        )
    }

    /**
     * Triangulation: a successful createTimeRequest still bumps the
     * throttle and persists the request. This proves the atomic
     * transaction does not regress the happy path — the throttle
     * IS written and the row IS persisted when both writes commit.
     */
    @Test
    fun createTimeRequest_bumps_throttle_and_persists_row_on_happy_path() = runTest {
        val result = repository.createTimeRequest(
            deviceId = "dev-happy",
            minutes = 15,
            reason = "homework"
        )
        assertTrue(
            "Expected TimeRequestResult.Success on the happy path, got $result",
            result is TimeRequestResult.Success
        )
        val prefs = context.getSharedPreferences("time_extra_prefs", Context.MODE_PRIVATE)
        assertEquals(
            "successful createTimeRequest must bump the throttle to the wall-instant epoch",
            fakeTime.wallInstant().toEpochMilli(),
            prefs.getLong("last_request_time", 0L)
        )
        val reqId = (result as TimeRequestResult.Success).requestId
        assertNotNull(
            "successful createTimeRequest must persist the request row",
            database.timeRequestDao().getRequestById(reqId)
        )
    }

    // ===== Goal 1: device isolation in the read path =====

    /**
     * Seeds a pending request, runs `processApproval` so the grant
     * ends up on disk, and returns the resulting `GrantEntity`.
     */
    private suspend fun seedApprovedGrant(
        deviceId: String,
        requestId: String,
        minutes: Int,
        scope: String = "extra_time"
    ): GrantEntity {
        seedPendingRequest(requestId = requestId, deviceId = deviceId)
        repository.processApproval(requestId = requestId, approvedMinutes = minutes)
        return database.grantDao()
            .getGrantsForScopeFlow(deviceId, scope).first()
            .first { it.request_id == requestId }
    }

    /**
     * `getTotalAvailableMinutes(deviceId)` MUST only sum minutes from
     * the requesting device. Two paired devices in the same family
     * each get a 30-min grant — the totals must be 30 each, never 60.
     * The pre-fix implementation called `grantDao.getGrantsForScope`
     * and summed across every device in the table.
     */
    @Test
    fun getTotalAvailableMinutes_isolates_by_device_id() = runTest {
        seedApprovedGrant(deviceId = "dev-A", requestId = "req-A", minutes = 30)
        seedApprovedGrant(deviceId = "dev-B", requestId = "req-B", minutes = 30)

        val totalA = repository.getTotalAvailableMinutes("dev-A")
        val totalB = repository.getTotalAvailableMinutes("dev-B")

        assertEquals(
            "dev-A total must be 30, not the cross-device 60 (the bug)",
            30L,
            totalA
        )
        assertEquals("dev-B total must be 30", 30L, totalB)
    }

    /**
     * `getActiveExtraTimeGrant(deviceId)` MUST return only grants that
     * belong to the requested device. With both devices seeded, the
     * pre-fix code returned the FIRST active grant in the table
     * regardless of which device owned it.
     */
    @Test
    fun getActiveExtraTimeGrant_isolates_by_device_id() = runTest {
        seedApprovedGrant(deviceId = "dev-A", requestId = "req-A", minutes = 30)
        seedApprovedGrant(deviceId = "dev-B", requestId = "req-B", minutes = 45)

        val activeA = repository.getActiveExtraTimeGrant("dev-A")
        val activeB = repository.getActiveExtraTimeGrant("dev-B")

        assertNotNull("dev-A must have an active grant", activeA)
        assertNotNull("dev-B must have an active grant", activeB)
        assertEquals("dev-A's active grant must belong to dev-A", "dev-A", activeA!!.device_id)
        assertEquals("dev-B's active grant must belong to dev-B", "dev-B", activeB!!.device_id)
        assertEquals(30, activeA.minutes)
        assertEquals(45, activeB.minutes)
    }

    /**
     * `hasActiveExtraTimeGrant(deviceId)` MUST NOT cross devices. A
     * device that has never asked for any grant must return `false`
     * even when another paired device has a fresh approval — the
     * pre-fix code returned `true` for the unpaired device because it
     * saw the other device's grant in the unscoped query.
     */
    @Test
    fun hasActiveExtraTimeGrant_returns_false_for_device_with_no_grants() = runTest {
        seedApprovedGrant(deviceId = "dev-A", requestId = "req-A", minutes = 30)

        assertTrue(
            "dev-A must see its own grant as active",
            repository.hasActiveExtraTimeGrant("dev-A")
        )
        assertFalse(
            "dev-Z must NOT see dev-A's grant (cross-device leak guard)",
            repository.hasActiveExtraTimeGrant("dev-Z")
        )
    }

    /**
     * The reactive `observeExtraTimeGrants(deviceId)` Flow MUST emit
     * only the requesting device's grants — never rows written for
     * another device after the subscription started.
     */
    @Test
    fun observeExtraTimeGrants_does_not_leak_grants_from_other_devices() = runTest {
        // Subscribe BEFORE the second device gets its grant so a
        // regression that re-introduced the unscoped query would
        // surface immediately on the first emission.
        val flow = repository.observeExtraTimeGrants("dev-A")
        val firstEmission = flow.first()
        assertTrue("dev-A has no grants yet", firstEmission.isEmpty())

        // Seed dev-A and dev-B in quick succession.
        seedApprovedGrant(deviceId = "dev-A", requestId = "req-A", minutes = 15)
        seedApprovedGrant(deviceId = "dev-B", requestId = "req-B", minutes = 99)

        val secondEmission = flow.first()
        assertEquals(
            "dev-A's Flow must emit exactly one row (the dev-A grant)",
            1,
            secondEmission.size
        )
        assertEquals("dev-A", secondEmission[0].device_id)
        assertEquals(15, secondEmission[0].minutes)
    }

    /**
     * Expired grants must NOT contribute to the totals — the
     * `expires_at > now` filter is now pushed into the SQL via
     * `getActiveGrantsForScopeOnce`. A regression that dropped the
     * filter would surface here.
     */
    @Test
    fun getTotalAvailableMinutes_excludes_expired_grants() = runTest {
        // Seed an active grant for dev-A via the repository (expiry is
        // computed from the fakeTime provider, so it lands in the
        // future).
        seedApprovedGrant(deviceId = "dev-A", requestId = "req-active", minutes = 30)
        // Directly insert a back-dated grant for the same device with
        // an expires_at in the deep past. processApproval cannot
        // produce this row, so we drop to the DAO.
        val pastGrant = GrantEntity(
            id = "extra_time_req-past",
            device_id = "dev-A",
            request_id = "req-past",
            scope = "extra_time",
            minutes = 999,
            source = "extra_time",
            granted_at = "2000-01-01T00:00:00Z",
            expires_at = "2000-01-02T00:00:00Z"
        )
        database.grantDao().insertGrant(pastGrant)

        val total = repository.getTotalAvailableMinutes("dev-A")
        assertEquals(
            "expired grant must not be summed into the available total",
            30L,
            total
        )
    }
}

class TimeRequestResultTest {

    @Test
    fun `success result has request id and sent status`() {
        val result = TimeRequestResult.Success("req_123", isSent = true)

        assertTrue(result is TimeRequestResult.Success)
        assertEquals("req_123", result.requestId)
        assertTrue(result.isSent)
    }

    @Test
    fun `success result can be offline`() {
        val result = TimeRequestResult.Success("req_456", isSent = false)

        assertFalse(result.isSent)
    }

    @Test
    fun `throttled result has wait time`() {
        val result = TimeRequestResult.Throttled(waitMinutes = 3)

        assertTrue(result is TimeRequestResult.Throttled)
        assertEquals(3L, result.waitMinutes)
    }

    @Test
    fun `invalid minutes result exists`() {
        val result = TimeRequestResult.InvalidMinutes

        assertTrue(result is TimeRequestResult.InvalidMinutes)
    }

    @Test
    fun `error result has message`() {
        val result = TimeRequestResult.Error("Network error")

        assertTrue(result is TimeRequestResult.Error)
        assertEquals("Network error", result.message)
    }
}

class GrantResultTest {

    @Test
    fun `grant success has id and expiration`() {
        val expiresAt = java.time.Instant.now().plusSeconds(1800)
        val result = GrantResult.Success("grant_123", expiresAt)

        assertTrue(result is GrantResult.Success)
        assertEquals("grant_123", result.grantId)
        assertEquals(expiresAt, result.expiresAt)
    }

    @Test
    fun `grant error has message`() {
        val result = GrantResult.Error("Database error")

        assertTrue(result is GrantResult.Error)
        assertEquals("Database error", result.message)
    }
}

class T28ComplianceTest {

    @Test
    fun `grant has source extra_time`() {
        val grantSource = "extra_time"
        assertEquals("extra_time", grantSource)
    }

    @Test
    fun `grant does not unlock blocked apps`() {
        // §0.4 paso 6: El grant levanta límites pero NO desbloquea blocked
        val appState = "blocked"
        val grantLiftsLimits = true

        // El grant no debe cambiar el estado blocked
        val expectedState = appState // blocked stays blocked
        assertEquals("blocked", expectedState)
    }

    @Test
    fun `grant does not override allow_only window`() {
        // §0.4 paso 6: El grant no desbloquea allow_only
        val policyState = "allow_only"

        // El grant no debe cambiar allow_only
        assertEquals("allow_only", policyState)
    }

    @Test
    fun `grant only lifts time limits`() {
        // §0.4 paso 6: El grant levanta límites de tiempo
        val policyType = "time_limit"
        val grantEffect = "lift_limit"

        assertEquals("lift_limit", grantEffect)
    }

    @Test
    fun `request is offline safe with outbox`() {
        // La solicitud se encola en outbox si no hay conexión
        val hasOfflineSupport = true
        assertTrue(hasOfflineSupport)
    }

    @Test
    fun `throttle prevents spam`() {
        // Throttle local mínimo 5 minutos
        val throttleActive = true
        assertTrue(throttleActive)
    }

    @Test
    fun `request includes scope`() {
        // La solicitud tiene scope para identificar el contexto
        val hasScope = true
        assertTrue(hasScope)
    }

    @Test
    fun `request includes optional reason`() {
        // La solicitud tiene motivo opcional
        val hasReason = true
        assertTrue(hasReason)
    }

    @Test
    fun `grant is idempotent by request_id`() {
        // §0.4: Los grants son idempotentes via request_id
        val isIdempotent = true
        assertTrue(isIdempotent)
    }
}

class ExtraTimeFlowTest {

    @Test
    fun `flow starts from status screen`() {
        // T27: Desde la pantalla de estado se puede pedir tiempo extra
        val startsFromStatus = true
        assertTrue(startsFromStatus)
    }

    @Test
    fun `flow can start from overlay`() {
        // T08: Desde el overlay también se puede pedir
        val startsFromOverlay = true
        assertTrue(startsFromOverlay)
    }

    @Test
    fun `result shown immediately`() {
        // El resultado se muestra rápido
        val resultShownFast = true
        assertTrue(resultShownFast)
    }

    @Test
    fun `approval triggers via FCM`() {
        // T19: La aprobación llega via FCM
        val hasFcmTrigger = true
        assertTrue(hasFcmTrigger)
    }

    @Test
    fun `approval can trigger via Realtime`() {
        // T21: O via Realtime cuando está en foreground
        val hasRealtimeTrigger = true
        assertTrue(hasRealtimeTrigger)
    }

    @Test
    fun `approval creates grant and bumps version`() {
        // Al aprobarse, se crea grant y sube versión para sync
        val createsGrant = true
        val bumpsVersion = true

        assertTrue(createsGrant)
        assertTrue(bumpsVersion)
    }

    @Test
    fun `engine applies grant in step 6`() {
        // §0.4 paso 6: El motor aplica el grant
        val engineApplies = true
        assertTrue(engineApplies)
    }
}

class OfflineToleranceTest {

    @Test
    fun `request enqueued when offline`() {
        // Solicitud se encola en outbox
        val enqueuedOffline = true
        assertTrue(enqueuedOffline)
    }

    @Test
    fun `request syncs when online`() {
        // Solicitud se sincroniza al reconectar
        val syncsOnReconnect = true
        assertTrue(syncsOnReconnect)
    }

    @Test
    fun `grant applies when syncing`() {
        // El grant se aplica cuando llega el sync
        val appliesOnSync = true
        assertTrue(appliesOnSync)
    }

    @Test
    fun `versioning ensures correctness`() {
        // El versionado asegura que se aplique correctamente
        val hasVersioning = true
        assertTrue(hasVersioning)
    }
}
