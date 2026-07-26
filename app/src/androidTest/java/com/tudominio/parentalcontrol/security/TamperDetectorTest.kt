package com.tudominio.parentalcontrol.security

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.time.FakeTimeProvider
import com.tudominio.parentalcontrol.time.TimeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class TamperDetectorTest {

    private lateinit var context: Context
    private lateinit var database: ParentalDatabase
    private lateinit var timeProvider: FakeTimeProvider

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(
            context,
            ParentalDatabase::class.java
        ).allowMainThreadQueries().build()
        timeProvider = FakeTimeProvider()
    }

    @Test
    fun testTamperEventCreation() {
        val event = TamperEvent(
            type = TamperEvent.Type.ACCESSIBILITY_DISABLE_ATTEMPT,
            timestamp = Instant.now(),
            serverDate = "2026-06-04",
            details = mapOf("test" to "value")
        )
        
        assertNotNull(event)
        assertEquals(TamperEvent.Type.ACCESSIBILITY_DISABLE_ATTEMPT, event.type)
    }

    @Test
    fun testTamperEventToJson() {
        val event = TamperEvent(
            type = TamperEvent.Type.CLOCK_TAMPER,
            timestamp = Instant.parse("2026-06-04T12:00:00Z"),
            serverDate = "2026-06-04",
            details = mapOf("skew_type" to "FORWARD_JUMP")
        )
        
        val json = event.toJson()
        
        assertTrue(json.contains("clock_tamper_suspected"))
        assertTrue(json.contains("2026-06-04"))
        assertTrue(json.contains("FORWARD_JUMP"))
    }

    @Test
    fun testEventNamesAreCorrect() {
        assertEquals("accessibility_off_detected", TamperEvent.Type.ACCESSIBILITY_DISABLE_ATTEMPT.eventName)
        assertEquals("uninstall_attempt", TamperEvent.Type.UNINSTALL_ATTEMPT.eventName)
        assertEquals("clock_tamper_suspected", TamperEvent.Type.CLOCK_TAMPER.eventName)
        assertEquals("timezone_changed", TamperEvent.Type.TIMEZONE_CHANGE.eventName)
    }

    @Test
    fun testSuspicionLevelEnum() {
        assertEquals(4, SuspicionLevel.values().size)
        assertEquals(SuspicionLevel.NONE, SuspicionLevel.valueOf("NONE"))
        assertEquals(SuspicionLevel.LOW, SuspicionLevel.valueOf("LOW"))
        assertEquals(SuspicionLevel.MEDIUM, SuspicionLevel.valueOf("MEDIUM"))
        assertEquals(SuspicionLevel.HIGH, SuspicionLevel.valueOf("HIGH"))
    }

    @Test
    fun testClockSkewSignalCreation() {
        val signal = TimeProvider.ClockSkewSignal(
            type = TimeProvider.ClockSkewSignal.SignalType.FORWARD_JUMP,
            wallTimeBefore = 1000L,
            wallTimeAfter = 2000L,
            monotonicDelta = 100L,
            timestamp = System.currentTimeMillis()
        )
        
        assertEquals(TimeProvider.ClockSkewSignal.SignalType.FORWARD_JUMP, signal.type)
        assertEquals(1000L, signal.wallTimeBefore)
        assertEquals(2000L, signal.wallTimeAfter)
        assertEquals(100L, signal.monotonicDelta)
    }

    @Test
    fun testClockSkewSignalTypes() {
        val types = TimeProvider.ClockSkewSignal.SignalType.values()
        assertEquals(3, types.size)
        assertNotNull(types.find { it.name == "FORWARD_JUMP" })
        assertNotNull(types.find { it.name == "BACKWARD_JUMP" })
        assertNotNull(types.find { it.name == "TIMEZONE_CHANGE" })
    }

    @Test
    fun testZoneIdChange() {
        val oldZone = ZoneId.of("UTC")
        val newZone = ZoneId.of("America/New_York")
        
        assertNotEquals(oldZone, newZone)
        assertEquals("UTC", oldZone.id)
        assertEquals("America/New_York", newZone.id)
    }

    @Test
    fun testTamperEventDetails() {
        val details = mapOf(
            "old_zone" to "UTC",
            "new_zone" to "Europe/London"
        )
        
        assertEquals(2, details.size)
        assertEquals("UTC", details["old_zone"])
        assertEquals("Europe/London", details["new_zone"])
    }
}
