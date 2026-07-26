package com.tudominio.parentalcontrol.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tudominio.parentalcontrol.data.model.BehavioralEventEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO para eventos conductuales.
 */
@Dao
interface BehavioralEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: BehavioralEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<BehavioralEventEntity>)

    @Query("SELECT * FROM behavioral_events WHERE synced = 0 ORDER BY created_at ASC LIMIT :limit")
    suspend fun getUnsyncedEvents(limit: Int = 100): List<BehavioralEventEntity>

    @Query("UPDATE behavioral_events SET synced = 1 WHERE id IN (:eventIds)")
    suspend fun markSynced(eventIds: List<Long>)

    @Query("DELETE FROM behavioral_events WHERE synced = 1 AND created_at < datetime('now', '-' || :days || ' days')")
    suspend fun deleteOldSyncedEvents(days: Int = 7)

    @Query("SELECT COUNT(*) FROM behavioral_events WHERE synced = 0")
    suspend fun getUnsyncedCount(): Int

    @Query("DELETE FROM behavioral_events")
    suspend fun deleteAll()

    /**
     * Returns a [Flow] of the parent's behavioral events ordered newest-first.
     *
     * Change A of `feat-parent-behavioral-event-log` (PR A only): the
     * `parent_id = :parentId` filter is the read-side counterpart of the
     * `parent_id` column added by
     * [ParentalDatabase.MIGRATION_6_7]. Rows whose `parent_id IS NULL`
     * (i.e., written before the migration) are excluded by the equality
     * predicate — that's the lazy-backfill strategy from proposal.md
     * open question #3: pre-migration rows are simply not visible until
     * a subsequent `AnalyticsManager.track()` re-writes them with a
     * non-null `parent_id`.
     *
     * `ORDER BY created_at DESC` mirrors the PostgREST
     * `order=created_at.desc` clause the repository appends to its GET
     * request — keeping the wire-side ordering and the local-DAO
     * ordering consistent so the UI never has to re-sort.
     */
    @Query("SELECT * FROM behavioral_events WHERE parent_id = :parentId ORDER BY created_at DESC")
    fun flowByParent(parentId: String): Flow<List<BehavioralEventEntity>>

    /**
     * Writes-through the server response into the local cache.
     *
     * `OnConflictStrategy.REPLACE` collapses duplicate `id`s into a
     * single row so a second `refresh(parentId)` against the same
     * payload does NOT inflate the row count. This is the contract the
     * `BehavioralEventsRepositoryTest.refresh_idempotency_…` case
     * exercises.
     *
     * Change A of `feat-parent-behavioral-event-log` (PR A only).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(events: List<BehavioralEventEntity>)
}
