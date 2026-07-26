package com.tudominio.parentalcontrol.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tudominio.parentalcontrol.data.model.OutboxEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOutboxItem(item: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE dedup_key = :dedupKey AND dedup_key IS NOT NULL LIMIT 1")
    suspend fun findByDedupKey(dedupKey: String): OutboxEntity?

    @Query("SELECT * FROM outbox WHERE processed = 0 AND retries < :maxAttempts ORDER BY created_at ASC LIMIT :limit")
    suspend fun getPendingItems(maxAttempts: Int, limit: Int): List<OutboxEntity>

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
