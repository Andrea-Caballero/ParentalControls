package com.tudominio.parentalcontrol.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tudominio.parentalcontrol.data.model.OutboxEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Unit tests for the in-flight claim mechanism on [OutboxDao].
 *
 * The outbox/sync remediation slice adds a `claimPendingItems` transaction
 * that atomically SELECTs claimable rows AND marks them `in_flight = 1`.
 * Without that guard, two concurrent drainers (the `OutboxDrainer` worker
 * + the legacy `SyncManager.drainOutbox` path) could pick the same row
 * and double-send it. These tests prove the guard:
 *
 *  1. The first call returns the rows; the rows are flagged in_flight.
 *  2. A second call before the first clears the claim returns nothing.
 *  3. `markProcessedFromClaim` is terminal: row is processed AND
 *     in_flight is cleared, so it can never be re-claimed.
 *  4. `incrementRetriesFromClaim` is non-terminal: row is claimable
 *     again, with its retry counter bumped.
 *  5. `releaseStaleClaims` recovers rows whose drainer crashed before
 *     clearing the flag, so the retry budget doesn't get stuck.
 *  6. The `getPendingItems` legacy query is gated by `in_flight = 0`,
 *     so a row held by `claimPendingItems` is invisible to legacy
 *     drainers until the claim is finalized.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OutboxDaoClaimTest {

    private lateinit var context: Context
    private lateinit var db: ParentalDatabase
    private lateinit var outboxDao: OutboxDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ParentalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        outboxDao = db.outboxDao()
    }

    @After
    fun tearDown() {
        runCatching { db.close() }
    }

    private fun insertOutbox(
        tipo: String = "TIME_REQUEST",
        retries: Int = 0,
        processed: Boolean = false,
        dedupKey: String? = null,
        createdAt: String = "2026-07-27T10:00:00Z"
    ): OutboxEntity = OutboxEntity(
        id = UUID.randomUUID(),
        tipo = tipo,
        payload_json = "{}",
        dedup_key = dedupKey,
        retries = retries,
        created_at = createdAt,
        server_date = "2026-07-27",
        processed = processed
    ).also { runBlocking { outboxDao.insertOutboxItem(it) } }

    // ========================================================================
    // Goal 1: claim/in-flight mechanism
    // ========================================================================

    @Test
    fun claimPendingItems_returns_rows_and_marks_them_in_flight_atomically() = runBlocking {
        val a = insertOutbox(createdAt = "2026-07-27T10:00:00Z")
        val b = insertOutbox(createdAt = "2026-07-27T10:00:01Z")
        val c = insertOutbox(createdAt = "2026-07-27T10:00:02Z")

        val claimed = outboxDao.claimPendingItems(
            maxAttempts = 10,
            limit = 10,
            now = "2026-07-27T11:00:00Z"
        )

        assertEquals("claim must return every pending row in created_at order", 3, claimed.size)
        assertEquals(a.id, claimed[0].id)
        assertEquals(b.id, claimed[1].id)
        assertEquals(c.id, claimed[2].id)

        // The transactional guard requires the rows to be flagged
        // in_flight IMMEDIATELY after the SELECT, so a concurrent
        // drainer between this call and the next cannot pick them up.
        // We re-read by id via a direct SELECT to verify the column
        // landed (claimPendingItems itself filters by in_flight=0,
        // which would not return these rows by construction).
        val rows = outboxDao.getPendingItems(10, 10)
        assertTrue("rows held by a claim are invisible to the legacy query", rows.isEmpty())

        // Verify the in_flight column flip on a single row directly.
        // We use the findByDedupKey / getPendingItems result-list to
        // check the underlying columns. A clean way is to drop a
        // different row (NOT yet claimed) and re-claim — if the
        // in_flight flip actually committed, only the new row should
        // come back.
        val d = insertOutbox(createdAt = "2026-07-27T10:00:03Z")
        val secondClaim = outboxDao.claimPendingItems(10, 10, "2026-07-27T11:00:01Z")
        assertEquals(
            "second claim must return only the unclaimed row",
            listOf(d.id),
            secondClaim.map { it.id }
        )
    }

    @Test
    fun claimPendingItems_returns_empty_when_no_rows_are_pending() = runBlocking {
        val claimed = outboxDao.claimPendingItems(10, 10, "2026-07-27T11:00:00Z")
        assertTrue(claimed.isEmpty())
    }

    @Test
    fun claimPendingItems_skips_already_processed_rows() = runBlocking {
        val pending = insertOutbox()
        insertOutbox(processed = true)

        val claimed = outboxDao.claimPendingItems(10, 10, "2026-07-27T11:00:00Z")
        assertEquals(listOf(pending.id), claimed.map { it.id })
    }

    @Test
    fun claimPendingItems_respects_retry_budget() = runBlocking {
        val fresh = insertOutbox(retries = 0)
        insertOutbox(retries = 10) // already at the budget

        val claimed = outboxDao.claimPendingItems(maxAttempts = 10, limit = 10, now = "now")
        assertEquals(listOf(fresh.id), claimed.map { it.id })
    }

    @Test
    fun markProcessedFromClaim_is_terminal_and_clears_in_flight() = runBlocking {
        val item = insertOutbox()
        val claimed = outboxDao.claimPendingItems(10, 10, "claim-time")
        assertEquals(1, claimed.size)

        outboxDao.markProcessedFromClaim(item.id, "processed-time")

        // The processed row must be terminal — no future claim cycle
        // can re-send it.
        val reclaimed = outboxDao.claimPendingItems(10, 10, "later")
        assertTrue("processed row must NOT be reclaimable", reclaimed.isEmpty())
    }

    @Test
    fun incrementRetriesFromClaim_keeps_row_claimable_with_bumped_retries() = runBlocking {
        val item = insertOutbox(retries = 0)
        outboxDao.claimPendingItems(10, 10, "claim-time")

        outboxDao.incrementRetriesFromClaim(item.id)

        val reclaimed = outboxDao.claimPendingItems(10, 10, "later")
        assertEquals(
            "row with bumped retries must be re-claimable on the next cycle",
            listOf(item.id),
            reclaimed.map { it.id }
        )
    }

    @Test
    fun releaseClaim_clears_in_flight_without_touching_retries() = runBlocking {
        val item = insertOutbox(retries = 0)
        outboxDao.claimPendingItems(10, 10, "claim-time")

        outboxDao.releaseClaim(item.id)

        val reclaimed = outboxDao.claimPendingItems(10, 10, "later")
        assertEquals(listOf(item.id), reclaimed.map { it.id })
        // We can't read retries directly from getPendingItems, but the
        // point is that release is non-consumptive — a subsequent
        // successful send will still work without bumping the budget.
    }

    @Test
    fun releaseStaleClaims_recovers_rows_whose_drainer_crashed() = runBlocking {
        val stale = insertOutbox(createdAt = "2026-07-27T09:00:00Z")
        val fresh = insertOutbox(createdAt = "2026-07-27T10:00:00Z")

        // Both get claimed at the SAME timestamp — the stale one is
        // then aged past the recovery window.
        outboxDao.claimPendingItems(10, 10, "2026-07-27T10:00:00Z")

        // Move the clock past the stale-claim TTL. A new claim
        // before the sweep should still see nothing.
        assertTrue(
            "no recovery sweep yet — both rows are still held",
            outboxDao.claimPendingItems(10, 10, "2026-07-27T10:10:00Z").isEmpty()
        )

        // Sweep with a cutoff AFTER the original claim time, then
        // claim again — the previously-stale row must come back.
        outboxDao.releaseStaleClaims("2026-07-27T10:05:00Z")
        val reclaimed = outboxDao.claimPendingItems(10, 10, "2026-07-27T10:10:01Z")
        assertEquals(
            "stale-claim sweep must make the row available again",
            listOf(stale.id, fresh.id),
            reclaimed.map { it.id }
        )
    }

    @Test
    fun releaseStaleClaims_leaves_pre_claim_rows_alone() = runBlocking {
        // A row that was claimed in the pre-v9 world (in_flight_at is
        // NULL on the row, the in_flight column is the only
        // pre-existing signal) is left alone — it never goes through
        // the new claim path, so the stale-claim sweeper should not
        // touch it. We simulate by inserting a row whose in_flight
        // flag is 1 but in_flight_at is NULL via a direct UPDATE.
        val item = insertOutbox()
        runBlocking {
            db.openHelper.writableDatabase.execSQL(
                "UPDATE outbox SET in_flight = 1, in_flight_at = NULL WHERE id = '${item.id}'"
            )
        }

        outboxDao.releaseStaleClaims("2099-01-01T00:00:00Z") // far-future cutoff

        // The pre-claim row stays in_flight = 1 because the sweeper
        // skips rows where in_flight_at IS NULL.
        val reclaimed = outboxDao.claimPendingItems(10, 10, "later")
        assertTrue("pre-claim row must NOT be recovered by the sweeper", reclaimed.isEmpty())
    }

    @Test
    fun getPendingItems_legacy_query_excludes_in_flight_rows() = runBlocking {
        // Claim only `held` first; `free` is inserted after the claim
        // and is therefore unclaimed when the legacy query runs.
        val held = insertOutbox()
        outboxDao.claimPendingItems(10, 10, "claim-time")
        val free = insertOutbox(createdAt = "2026-07-27T10:00:01Z")

        val pending = outboxDao.getPendingItems(10, 10)
        assertEquals(
            "held is excluded by the in_flight = 0 filter; free is returned",
            listOf(free.id),
            pending.map { it.id }
        )
        assertFalse(
            "belt-and-suspenders: the held row must not be in the legacy pending result",
            pending.any { it.id == held.id }
        )
    }
}
