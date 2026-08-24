package com.tudominio.parentalcontrol.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.domain.AppPolicy
import com.tudominio.parentalcontrol.domain.AppPolicyState
import com.tudominio.parentalcontrol.domain.CategoryLimit
import com.tudominio.parentalcontrol.domain.DayOfWeek
import com.tudominio.parentalcontrol.domain.DeviceState
import com.tudominio.parentalcontrol.domain.Grant
import com.tudominio.parentalcontrol.domain.GrantSource
import com.tudominio.parentalcontrol.domain.Policy
import com.tudominio.parentalcontrol.domain.Schedule
import com.tudominio.parentalcontrol.domain.ScheduleAction
import com.tudominio.parentalcontrol.domain.Window
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocalDataSourcePolicyRoundTripTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `full policy survives sync and lower version cannot erase fields`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, ParentalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val source = LocalDataSource(db)
            val full = policy(version = 7)
            assertEquals(true, source.syncPolicy(full))
            assertEquals(
                full.copy(
                    grants = full.grants.map {
                        it.copy(
                            granted_at = "2026-07-31T10:00:00.000Z",
                            expires_at = "2026-07-31T11:00:00.000Z",
                        )
                    },
                ),
                source.getPolicyFlow(DEVICE).first(),
            )

            val stale = full.copy(
                version = 6,
                device_state = DeviceState.ACTIVE,
                daily_screen_time_minutes = 120,
                schedules = emptyList(),
                category_limits = emptyList(),
                app_policies = emptyList(),
                grants = emptyList(),
            )
            assertFalse(source.syncPolicy(stale))
            assertEquals(
                full.copy(
                    grants = full.grants.map {
                        it.copy(
                            granted_at = "2026-07-31T10:00:00.000Z",
                            expires_at = "2026-07-31T11:00:00.000Z",
                        )
                    },
                ),
                source.getPolicyFlow(DEVICE).first(),
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `policy sync stores offset timestamps as canonical UTC milliseconds`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, ParentalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val source = LocalDataSource(db)
            val synced = policy(version = 8).copy(
                grants = listOf(
                    Grant(
                        id = "grant-offset",
                        scope = "global",
                        minutes = 10,
                        source = GrantSource.MANUAL,
                        granted_at = "2026-07-31T12:00:00.123+02:00",
                        expires_at = "2026-07-31T13:00:00.456+02:00",
                    ),
                ),
            )

            assertEquals(true, source.syncPolicy(synced))
            val grant = db.grantDao().getGrantsForDeviceFlow(DEVICE).first().single()
            assertEquals("2026-07-31T10:00:00.123Z", grant.granted_at)
            assertEquals("2026-07-31T11:00:00.456Z", grant.expires_at)
        } finally {
            db.close()
        }
    }

    private fun policy(version: Int) = Policy(
        device_id = DEVICE,
        version = version,
        device_state = DeviceState.DOWNTIME,
        daily_screen_time_minutes = 47,
        schedules = listOf(Schedule("bedtime", listOf(DayOfWeek.MONDAY), "21:00", "07:00", ScheduleAction.LOCK)),
        category_limits = listOf(CategoryLimit("games", 19)),
        app_policies = listOf(
            AppPolicy(
                package_name = "com.example.game",
                state = AppPolicyState.LIMITED,
                daily_limit_minutes = 11,
                allowed_windows = listOf(Window(listOf(DayOfWeek.TUESDAY), "16:00", "17:00")),
                category = "games",
            ),
        ),
        category_assignments = mapOf("com.example.game" to "games"),
        grants = listOf(
            Grant(
                id = "grant-1",
                scope = "global",
                minutes = 10,
                source = GrantSource.MANUAL,
                granted_at = "2026-07-31T10:00:00Z",
                expires_at = "2026-07-31T11:00:00Z",
            ),
        ),
    )

    private companion object {
        const val DEVICE = "device-full"
    }
}
