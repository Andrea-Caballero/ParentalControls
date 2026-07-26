package com.tudominio.parentalcontrol.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.tudominio.parentalcontrol.data.model.UsageTodayEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UsageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUsage(usage: UsageTodayEntity)

    @Query("SELECT * FROM usage_today WHERE package_name = :packageName AND server_date = :serverDate")
    suspend fun getUsage(packageName: String, serverDate: String): UsageTodayEntity?

    @Query("SELECT * FROM usage_today WHERE server_date = :serverDate")
    fun getUsageForDateFlow(serverDate: String): Flow<List<UsageTodayEntity>>

    @Query("SELECT SUM(usage_minutes) FROM usage_today WHERE server_date = :serverDate")
    fun getGlobalUsageFlow(serverDate: String): Flow<Int?>

    @Query(
        """
        SELECT SUM(u.usage_minutes)
        FROM usage_today u
        INNER JOIN app_policies a ON u.package_name = a.package_name
        WHERE u.server_date = :serverDate
          AND a.category = :category
          AND a.state != 'ALWAYS_ALLOWED'
        """
    )
    fun getCategoryUsageFlow(serverDate: String, category: String): Flow<Int?>

    @Query("SELECT usage_minutes FROM usage_today WHERE package_name = :packageName AND server_date = :serverDate")
    fun getUsageForPackageFlow(packageName: String, serverDate: String): Flow<Int?>

    @Transaction
    suspend fun incrementUsage(packageName: String, serverDate: String, deltaMinutes: Int) {
        val existing = getUsage(packageName, serverDate)
        if (existing != null) {
            upsertUsage(existing.copy(usage_minutes = existing.usage_minutes + deltaMinutes))
        } else {
            upsertUsage(
                UsageTodayEntity(
                    package_name = packageName,
                    server_date = serverDate,
                    usage_minutes = deltaMinutes
                )
            )
        }
    }

    @Query("DELETE FROM usage_today WHERE server_date < :cutoffDate")
    suspend fun deleteOldUsage(cutoffDate: String)
}
