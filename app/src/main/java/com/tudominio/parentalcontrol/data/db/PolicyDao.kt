package com.tudominio.parentalcontrol.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.tudominio.parentalcontrol.data.model.PolicyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PolicyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPolicy(policy: PolicyEntity)

    @Query("SELECT * FROM policy WHERE device_id = :deviceId")
    fun getPolicyFlow(deviceId: String): Flow<PolicyEntity?>

    @Query("SELECT version FROM policy WHERE device_id = :deviceId")
    suspend fun getLocalVersion(deviceId: String): Long?

    @Transaction
    suspend fun upsertPolicyIfNewer(policy: PolicyEntity): Boolean {
        val localVersion = getLocalVersion(policy.device_id)
        return if (localVersion == null || policy.version > localVersion) {
            insertPolicy(policy)
            true
        } else {
            false
        }
    }

    @Query("DELETE FROM policy WHERE device_id = :deviceId")
    suspend fun deletePolicy(deviceId: String)
}
