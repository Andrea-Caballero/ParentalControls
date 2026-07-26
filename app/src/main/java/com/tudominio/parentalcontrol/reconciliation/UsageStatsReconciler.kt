package com.tudominio.parentalcontrol.reconciliation

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.data.model.UsageTodayEntity
import com.tudominio.parentalcontrol.time.DefaultTimeProvider
import com.tudominio.parentalcontrol.time.TimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class UsageStatsReconciler(
    private val context: Context,
    private val database: ParentalDatabase,
    private val timeProvider: TimeProvider = DefaultTimeProvider(context)
) {
    companion object {
        private const val TAG = "UsageStatsReconciler"
    }

    fun hasUsageStatsPermission(): Boolean {
        val packageManager = context.packageManager
        val intent = android.content.Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        return intent.resolveActivity(packageManager) != null
    }

    suspend fun getUsageStatsForPackage(packageName: String): Long = withContext(Dispatchers.IO) {
        if (!hasUsageStatsPermission()) {
            return@withContext -1L
        }

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return@withContext -1L

        val calendar = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        val usageStats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        )

        usageStats.find { it.packageName == packageName }?.totalTimeInForeground ?: 0L
    }

    suspend fun getUsageEventsForToday(): List<UsageEvent> = withContext(Dispatchers.IO) {
        if (!hasUsageStatsPermission()) {
            return@withContext emptyList()
        }

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return@withContext emptyList()

        val calendar = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        val events = mutableListOf<UsageEvent>()
        val usageEvents = usageStatsManager.queryEvents(startTime, endTime)

        var lastForegroundPackage: String? = null
        var lastForegroundTime: Long = 0L

        while (usageEvents.hasNextEvent()) {
            val event = UsageEvents.Event()
            usageEvents.getNextEvent(event)

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    lastForegroundPackage = event.packageName
                    lastForegroundTime = event.timeStamp
                }
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    if (lastForegroundPackage != null && lastForegroundTime > 0) {
                        val duration = event.timeStamp - lastForegroundTime
                        if (duration > 0 && duration < 24 * 60 * 60 * 1000) {
                            events.add(
                                UsageEvent(
                                    packageName = lastForegroundPackage,
                                    startTime = lastForegroundTime,
                                    endTime = event.timeStamp,
                                    duration = duration
                                )
                            )
                        }
                    }
                    lastForegroundPackage = null
                    lastForegroundTime = 0L
                }
            }
        }

        events
    }

    suspend fun reconcileToday(): ReconciliationResult = withContext(Dispatchers.IO) {
        if (!hasUsageStatsPermission()) {
            return@withContext ReconciliationResult.NoPermission
        }

        val serverDate = timeProvider.currentDate().toString()
        val localUsage = database.usageDao().getUsageForDateFlow(serverDate).first()

        if (localUsage.isEmpty()) {
            return@withContext ReconciliationResult.Success(
                localTotalMinutes = 0,
                statsTotalMinutes = 0,
                discrepancy = 0,
                wasUpdated = false
            )
        }

        val localTotal = localUsage.sumOf { it.usage_minutes }

        val statsUsage = getUsageStatsForPackage(localUsage.first().package_name)

        if (statsUsage < 0) {
            return@withContext ReconciliationResult.NoPermission
        }

        val statsTotalMinutes = (statsUsage / 60_000).toInt()
        val discrepancy = statsTotalMinutes - localTotal

        if (kotlin.math.abs(discrepancy) > 1) {
            logDiscrepancy(serverDate, localTotal, statsTotalMinutes, discrepancy)
        }

        ReconciliationResult.Success(
            localTotalMinutes = localTotal,
            statsTotalMinutes = statsTotalMinutes,
            discrepancy = discrepancy,
            wasUpdated = false
        )
    }

    suspend fun backfillFromUsageStats(): Boolean = withContext(Dispatchers.IO) {
        if (!hasUsageStatsPermission()) {
            return@withContext false
        }

        val serverDate = timeProvider.currentDate().toString()
        val events = getUsageEventsForToday()

        if (events.isEmpty()) {
            return@withContext false
        }

        val usageByPackage = events.groupBy { it.packageName }
            .mapValues { entry -> entry.value.sumOf { it.duration } }

        for ((packageName, totalMs) in usageByPackage) {
            val minutesDelta = (totalMs / 60_000).toInt()

            val existing = database.usageDao().getUsage(packageName, serverDate)
            if (existing == null || minutesDelta > existing.usage_minutes) {
                database.usageDao().upsertUsage(
                    com.tudominio.parentalcontrol.data.model.UsageTodayEntity(
                        package_name = packageName,
                        server_date = serverDate,
                        usage_minutes = minutesDelta
                    )
                )
            }
        }

        true
    }

    private fun logDiscrepancy(
        date: String,
        localMinutes: Int,
        statsMinutes: Int,
        discrepancy: Int
    ) {
        android.util.Log.w(
            TAG,
            "Discrepancia de uso detectada: fecha=$date, local=$localMinutes, stats=$statsMinutes, diff=$discrepancy"
        )
    }

    data class UsageEvent(
        val packageName: String,
        val startTime: Long,
        val endTime: Long,
        val duration: Long
    )

    sealed class ReconciliationResult {
        abstract val localTotalMinutes: Int
        abstract val statsTotalMinutes: Int
        abstract val discrepancy: Int
        abstract val wasUpdated: Boolean

        data class Success(
            override val localTotalMinutes: Int,
            override val statsTotalMinutes: Int,
            override val discrepancy: Int,
            override val wasUpdated: Boolean
        ) : ReconciliationResult()

        object NoPermission : ReconciliationResult() {
            override val localTotalMinutes: Int = 0
            override val statsTotalMinutes: Int = 0
            override val discrepancy: Int = 0
            override val wasUpdated: Boolean = false
        }
    }
}
