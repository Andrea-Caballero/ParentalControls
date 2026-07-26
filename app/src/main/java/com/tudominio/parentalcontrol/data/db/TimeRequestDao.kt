package com.tudominio.parentalcontrol.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tudominio.parentalcontrol.data.model.TimeRequestEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TimeRequestDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRequest(request: TimeRequestEntity)

    @Query("SELECT * FROM time_requests WHERE request_id = :requestId")
    suspend fun getRequestById(requestId: String): TimeRequestEntity?

    @Query("SELECT * FROM time_requests WHERE device_id = :deviceId ORDER BY created_at DESC")
    fun getRequestsForDeviceFlow(deviceId: String): Flow<List<TimeRequestEntity>>

    @Query("SELECT * FROM time_requests WHERE status = 'PENDING' ORDER BY created_at DESC")
    fun getPendingRequestsFlow(): Flow<List<TimeRequestEntity>>

    @Query("UPDATE time_requests SET status = :status, responded_at = :respondedAt WHERE request_id = :requestId")
    suspend fun updateRequestStatus(requestId: String, status: String, respondedAt: String)

    /**
     * Replaces the locally-generated `request_id` with the server's row id
     * returned by the POST. Without this, the post-boot `pullApprovedRequests`
     * (which queries by server id) cannot find the local row to update when
     * the parent approves the request.
     */
    @Query("UPDATE time_requests SET request_id = :newId WHERE request_id = :oldId")
    suspend fun updateRequestId(oldId: String, newId: String)

    @Query("DELETE FROM time_requests WHERE created_at < :cutoff")
    suspend fun deleteOldRequests(cutoff: String)
}
