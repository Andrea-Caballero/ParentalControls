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

    @Query("SELECT * FROM grants WHERE scope = :scope")
    fun getGrantsForScope(scope: String): Flow<List<GrantEntity>>

    @Query("SELECT * FROM grants WHERE device_id = :deviceId AND expires_at > :now")
    fun getActiveGrantsFlow(deviceId: String, now: String): Flow<List<GrantEntity>>

    @Query("DELETE FROM grants WHERE device_id = :deviceId")
    suspend fun deleteGrantsForDevice(deviceId: String)

    @Query("DELETE FROM grants WHERE expires_at < :now")
    suspend fun deleteExpiredGrants(now: String)
}
