package com.tudominio.parentalcontrol.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.tudominio.parentalcontrol.data.model.OutboxEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOutboxItem(item: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE dedup_key = :dedupKey AND dedup_key IS NOT NULL LIMIT 1")
    suspend fun findByDedupKey(dedupKey: String): OutboxEntity?

    /**
     * Returns unprocessed outbox rows that are NOT currently in flight
     * (i.e., not being sent by a drainer) and are still under the retry
     * budget. Used by the legacy `SyncManager.drainOutbox` path; the
     * canonical `OutboxDrainer` worker uses [claimPendingItems] which
     * atomically SELECTs + claims in a single transaction.
     */
    @Query(
        "SELECT * FROM outbox " +
            "WHERE processed = 0 AND in_flight = 0 AND retries < :maxAttempts " +
            "ORDER BY created_at ASC LIMIT :limit"
    )
    suspend fun getPendingItems(maxAttempts: Int, limit: Int): List<OutboxEntity>

    /**
     * Atomically selects up to `limit` claimable rows and marks them
     * `in_flight = 1` in the same transaction, returning the selected
     * rows. A subsequent call will NOT see the same rows (the `in_flight
     * = 0` filter excludes them) until the caller clears the flag via
     * [markProcessedFromClaim], [incrementRetriesFromClaim], or
     * [releaseStaleClaims].
     *
     * Crash safety: a drainer that crashes between SELECT and
     * mark-processed leaves the row `in_flight = 1`. The stale-claim
     * sweeper ([releaseStaleClaims]) reclaims those rows after a
     * configurable TTL.
     */
    @Transaction
    suspend fun claimPendingItems(
        maxAttempts: Int,
        limit: Int,
        now: String
    ): List<OutboxEntity> {
        val rows = selectClaimableItems(maxAttempts, limit)
        if (rows.isNotEmpty()) {
            markInFlight(rows.map { it.id }, now)
        }
        return rows
    }

    @Query(
        "SELECT * FROM outbox " +
            "WHERE processed = 0 AND in_flight = 0 AND retries < :maxAttempts " +
            "ORDER BY created_at ASC LIMIT :limit"
    )
    suspend fun selectClaimableItems(maxAttempts: Int, limit: Int): List<OutboxEntity>

    @Query("UPDATE outbox SET in_flight = 1, in_flight_at = :now WHERE id IN (:ids)")
    suspend fun markInFlight(ids: List<UUID>, now: String)

    /**
     * Terminal: marks the row processed AND clears the in-flight flag
     * in one statement so a sweeper can't reclaim a row that already
     * reached the server.
     */
    @Query(
        "UPDATE outbox SET in_flight = 0, in_flight_at = NULL, " +
            "processed = 1, processed_at = :processedAt " +
            "WHERE id = :id"
    )
    suspend fun markProcessedFromClaim(id: UUID, processedAt: String)

    /**
     * Retryable failure path: clears the in-flight flag AND bumps the
     * retry counter in one statement so the row becomes claimable again
     * (with its new retry count) on the next drain cycle.
     */
    @Query(
        "UPDATE outbox SET in_flight = 0, in_flight_at = NULL, " +
            "retries = retries + 1 " +
            "WHERE id = :id"
    )
    suspend fun incrementRetriesFromClaim(id: UUID)

    /**
     * Releases the in-flight flag on a single row without touching
     * `processed` or `retries`. Used by callers that need to abort a
     * claim without consuming a retry or marking the row terminal
     * (e.g., a worker cancelled mid-iteration).
     */
    @Query("UPDATE outbox SET in_flight = 0, in_flight_at = NULL WHERE id = :id")
    suspend fun releaseClaim(id: UUID)

    /**
     * Clears the in-flight flag for rows whose `in_flight_at` is
     * strictly older than `olderThan` (ISO-8601 string compare). Used
     * by drainers to recover rows claimed by a previous run that
     * crashed before clearing the flag. Rows with `in_flight_at = NULL`
     * (i.e., pre-v9 rows) are left alone — they were claimed in the
     * pre-claim world and are still under the original retry budget.
     */
    @Query(
        "UPDATE outbox SET in_flight = 0, in_flight_at = NULL " +
            "WHERE in_flight = 1 AND in_flight_at IS NOT NULL " +
            "AND in_flight_at < :olderThan"
    )
    suspend fun releaseStaleClaims(olderThan: String)

    @Query("UPDATE outbox SET retries = retries + 1 WHERE id = :id")
    suspend fun incrementRetries(id: UUID)

    @Query("UPDATE outbox SET processed = 1, processed_at = :processedAt WHERE id = :id")
    suspend fun markProcessed(id: UUID, processedAt: String)

    @Query("DELETE FROM outbox WHERE processed = 1 AND processed_at < :cutoff")
    suspend fun deleteProcessedOlderThan(cutoff: String)

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun deleteItem(id: UUID)

    @Query("DELETE FROM outbox WHERE retries >= :maxAttempts")
    suspend fun deleteFailedItems(maxAttempts: Int)

    @Query("SELECT COUNT(*) FROM outbox")
    fun getPendingCountFlow(): Flow<Int>
}
