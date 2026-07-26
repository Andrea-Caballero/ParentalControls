package com.tudominio.parentalcontrol.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tudominio.parentalcontrol.data.model.AppPolicyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AppPolicyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAppPolicy(appPolicy: AppPolicyEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAppPolicies(appPolicies: List<AppPolicyEntity>)

    @Query("SELECT * FROM app_policies WHERE package_name = :packageName")
    suspend fun getAppPolicy(packageName: String): AppPolicyEntity?

    @Query("SELECT * FROM app_policies WHERE device_id = :deviceId")
    fun getAppPoliciesForDeviceFlow(deviceId: String): Flow<List<AppPolicyEntity>>

    @Query("SELECT * FROM app_policies")
    fun getAllAppPoliciesFlow(): Flow<List<AppPolicyEntity>>

    @Query("DELETE FROM app_policies WHERE device_id = :deviceId")
    suspend fun deleteAppPoliciesForDevice(deviceId: String)
}
