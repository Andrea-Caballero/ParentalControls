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

    /**
     * Looks up a `time_requests` row by the server's id. Used by the
     * post-boot `pullApprovedRequests` cycle when the Supabase filter
     * returns approvals keyed by `server_id` rather than the
     * client-generated `request_id`. Returns null when no row matches
     * — callers should fall back to [getRequestById] in that case so
     * rows from older app versions (where `server_id` is null and
     * `request_id` was renamed in place by the pre-v9 reconciliation)
     * keep resolving.
     */
    @Query("SELECT * FROM time_requests WHERE server_id = :serverId LIMIT 1")
    suspend fun getRequestByServerId(serverId: String): TimeRequestEntity?

    /**
     * Combined lookup: tries `server_id` first, then `request_id`. This
     * is the lookup the post-boot `pullApprovedRequests` cycle uses
     * because either field may carry the identity the server returned
     * in the approval row.
     *
     *  - Post-v9 reconciliation writes the server's id to
     *    `time_requests.server_id` without touching `request_id`. The
     *    server's approval id will match `server_id` on the new
     *    install.
     *  - Pre-v9 reconciliation renamed `request_id` in place. The
     *    server's approval id will match the (renamed) `request_id`
     *    on the upgrade path. `server_id` is null on those rows.
     *
     * Both install paths resolve through this single query.
     */
    @Query(
        "SELECT * FROM time_requests " +
            "WHERE server_id = :id OR request_id = :id " +
            "LIMIT 1"
    )
    suspend fun getRequestByRequestIdOrServerId(id: String): TimeRequestEntity?

    @Query("SELECT * FROM time_requests WHERE device_id = :deviceId ORDER BY created_at DESC")
    fun getRequestsForDeviceFlow(deviceId: String): Flow<List<TimeRequestEntity>>

    @Query("SELECT * FROM time_requests WHERE status = 'PENDING' ORDER BY created_at DESC")
    fun getPendingRequestsFlow(): Flow<List<TimeRequestEntity>>

    /**
     * Device-scoped pending flow. The child status screen must use this
     * instead of [getPendingRequestsFlow] so one child's pending request
     * does not leak into another child's UI on the same database.
     */
    @Query(
        "SELECT * FROM time_requests " +
            "WHERE device_id = :deviceId AND status = 'PENDING' " +
            "ORDER BY created_at DESC"
    )
    fun getPendingRequestsForDeviceFlow(deviceId: String): Flow<List<TimeRequestEntity>>

    @Query("UPDATE time_requests SET status = :status, responded_at = :respondedAt WHERE request_id = :requestId")
    suspend fun updateRequestStatus(requestId: String, status: String, respondedAt: String)

    /**
     * Legacy reconciliation path. Kept for backwards compatibility with
     * the pre-v9 flow that renamed `request_id` in place. New callers
     * should prefer [setServerId] — the server-assigned id lives in a
     * separate column, which preserves the `GrantEntity.request_id`
     * soft-FK invariant if the server's id differs from the client's.
     */
    @Query("UPDATE time_requests SET request_id = :newId WHERE request_id = :oldId")
    suspend fun updateRequestId(oldId: String, newId: String)

    /**
     * Non-destructive reconciliation: writes the Supabase-assigned id
     * from `return=representation` to `time_requests.server_id`
     * WITHOUT touching `request_id`. The post-boot
     * `pullApprovedRequests` cycle then looks the row up via
     * [getRequestByRequestIdOrServerId].
     */
    @Query("UPDATE time_requests SET server_id = :serverId WHERE request_id = :requestId")
    suspend fun setServerId(requestId: String, serverId: String)

    @Query("DELETE FROM time_requests WHERE created_at < :cutoff")
    suspend fun deleteOldRequests(cutoff: String)
}
