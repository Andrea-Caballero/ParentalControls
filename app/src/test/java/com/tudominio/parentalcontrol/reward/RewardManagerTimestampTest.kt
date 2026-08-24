package com.tudominio.parentalcontrol.reward

import androidx.room.Room
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.time.FakeTimeProvider
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RewardManagerTimestampTest {
    @Test
    fun `reward grant stores truncated canonical timestamps`() = runTest {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            ParentalDatabase::class.java,
        ).allowMainThreadQueries().build()
        val manager = RewardManager(
            context,
            database,
            FakeTimeProvider(),
        )
        try {
            manager.processRewardGrant(
                grantId = "reward-1",
                deviceId = "device-1",
                minutes = 10,
                expiresAt = Instant.parse("2026-07-31T11:00:00.999999999Z"),
                grantedAt = Instant.parse("2026-07-31T10:00:00.123456789Z"),
            )
            val grant = database.grantDao()
                .getGrantsForDeviceFlow("device-1").first().single()
            assertEquals("2026-07-31T10:00:00.123Z", grant.granted_at)
            assertEquals("2026-07-31T11:00:00.999Z", grant.expires_at)
        } finally {
            database.close()
        }
    }
}
