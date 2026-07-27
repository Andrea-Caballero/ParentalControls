package com.tudominio.parentalcontrol.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tudominio.parentalcontrol.data.model.GrantEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GrantDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGrant(grant: GrantEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGrants(grants: List<GrantEntity>)

    @Query("SELECT * FROM grants WHERE device_id = :deviceId")
    fun getGrantsForDeviceFlow(deviceId: String): Flow<List<GrantEntity>>

    @Query("SELECT * FROM grants WHERE device_id = :deviceId AND scope = :scope")
    fun getGrantsForScopeFlow(deviceId: String, scope: String): Flow<List<GrantEntity>>

    @Query("SELECT * FROM grants WHERE device_id = :deviceId AND expires_at > :now")
    fun getActiveGrantsFlow(deviceId: String, now: String): Flow<List<GrantEntity>>

    /**
     * Returns the subset of [scope] grants whose `expires_at` is still
     * in the future for the given [deviceId]. Cross-device filtering is
     * done in SQL (not in Kotlin) so the DAO remains the single source
     * of truth for the isolation contract — callers must NOT stack an
     * in-memory `filter { it.device_id == ... }` on top of an unscoped
     * query because that path used to leak cross-device grants into the
     * reward balance / extra-time totals.
     */
    @Query("SELECT * FROM grants WHERE device_id = :deviceId AND scope = :scope AND expires_at > :now")
    fun getActiveGrantsForScopeFlow(deviceId: String, scope: String, now: String): Flow<List<GrantEntity>>

    /**
     * Suspending variant of [getActiveGrantsForScopeFlow] for one-shot
     * reads (e.g., the balance / active-grant probes that historically
     * called `getGrantsForScope(...).first()` and silently summed
     * grants across every paired device).
     */
    @Query("SELECT * FROM grants WHERE device_id = :deviceId AND scope = :scope AND expires_at > :now")
    suspend fun getActiveGrantsForScopeOnce(deviceId: String, scope: String, now: String): List<GrantEntity>

    @Query("DELETE FROM grants WHERE device_id = :deviceId")
    suspend fun deleteGrantsForDevice(deviceId: String)

    @Query("DELETE FROM grants WHERE expires_at < :now")
    suspend fun deleteExpiredGrants(now: String)
}
