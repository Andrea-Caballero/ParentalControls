package com.tudominio.parentalcontrol.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class TimeProviderTest {

    @Test
    fun `FakeTimeProvider - elapsedRealtime returns correct value`() {
        val provider = FakeTimeProvider(fakeElapsed = 1000L)
        assertEquals(1000L, provider.elapsedRealtime())
    }

    @Test
    fun `FakeTimeProvider - wallTimeMillis returns correct value`() {
        val provider = FakeTimeProvider(fakeWallMillis = 5000L)
        assertEquals(5000L, provider.wallTimeMillis())
    }

    @Test
    fun `FakeTimeProvider - advanceTime increments correctly`() {
        val provider = FakeTimeProvider(fakeElapsed = 1000L, fakeWallMillis = 1000L)
        provider.advanceTime(500L)
        
        assertEquals(1500L, provider.elapsedRealtime())
        assertEquals(1500L, provider.wallTimeMillis())
    }

    @Test
    fun `FakeTimeProvider - currentZoneId returns configured zone`() {
        val zone = ZoneId.of("America/New_York")
        val provider = FakeTimeProvider(fakeZone = zone)
        assertEquals(zone, provider.currentZoneId())
    }

    @Test
    fun `FakeTimeProvider - currentDate returns fake date`() {
        val date = LocalDate.of(2024, 6, 15)
        val provider = FakeTimeProvider(fakeServerDate = date)
        
        assertEquals(date, provider.currentDate())
        assertTrue(provider.isServerTimeActive())
    }

    @Test
    fun `FakeTimeProvider - currentDate returns local date when no server date`() {
        val provider = FakeTimeProvider(fakeServerDate = null)
        
        assertNotNull(provider.currentDate())
        assertFalse(provider.isServerTimeActive())
    }

    @Test
    fun `FakeTimeProvider - setServerDate updates date`() {
        val provider = FakeTimeProvider()
        val newDate = LocalDate.of(2024, 12, 25)
        
        provider.setServerDate(newDate)
        
        assertEquals(newDate, provider.currentDate())
        assertTrue(provider.isServerTimeActive())
    }

    @Test
    fun `FakeTimeProvider - setZone updates zone`() {
        val provider = FakeTimeProvider()
        val newZone = ZoneId.of("Europe/London")
        
        provider.setZone(newZone)
        
        assertEquals(newZone, provider.currentZoneId())
    }

    @Test
    fun `FakeTimeProvider - currentDateTime with server date returns midnight`() {
        val date = LocalDate.of(2024, 6, 15)
        val provider = FakeTimeProvider(fakeServerDate = date)
        
        val dateTime = provider.currentDateTime()
        
        assertEquals(date, dateTime.toLocalDate())
        assertEquals(0, dateTime.hour)
        assertEquals(0, dateTime.minute)
    }

    @Test
    fun `FakeTimeProvider - currentDateTime without server date uses zone`() {
        val zone = ZoneId.of("America/Sao_Paulo")
        val wallTime = LocalDateTime.of(2024, 6, 15, 14, 30).toInstant(zone.rules.getOffset(java.time.Instant.now())).toEpochMilli()
        val provider = FakeTimeProvider(fakeWallMillis = wallTime, fakeZone = zone)
        
        val dateTime = provider.currentDateTime()
        
        assertEquals(14, dateTime.hour)
        assertEquals(30, dateTime.minute)
    }

    @Test
    fun `FakeTimeProvider - detectClockSkew returns null by default`() {
        val provider = FakeTimeProvider()
        assertNull(provider.detectClockSkew())
    }

    @Test
    fun `midnight crossing detection`() {
        // Simular cruce de medianoche
        val beforeMidnight = LocalDateTime.of(2024, 6, 15, 23, 59)
        val afterMidnight = LocalDateTime.of(2024, 6, 16, 0, 1)
        
        // Verificar que las fechas son diferentes
        assertNotEquals(beforeMidnight.toLocalDate(), afterMidnight.toLocalDate())
        
        // Simular con provider
        val zone = ZoneOffset.UTC
        val beforeMillis = beforeMidnight.toInstant(zone).toEpochMilli()
        val afterMillis = afterMidnight.toInstant(zone).toEpochMilli()
        
        val provider = FakeTimeProvider(fakeWallMillis = beforeMillis, fakeZone = zone)
        assertEquals(LocalDate.of(2024, 6, 15), provider.currentDate())
        
        provider.setTime(afterMillis)
        assertEquals(LocalDate.of(2024, 6, 16), provider.currentDate())
    }

    @Test
    fun `timezone change detection`() {
        val initialZone = ZoneId.of("UTC")
        val newZone = ZoneId.of("America/New_York")
        
        val provider = FakeTimeProvider(fakeZone = initialZone)
        
        assertEquals(initialZone, provider.currentZoneId())
        
        // Simular cambio de zona
        provider.setZone(newZone)
        
        assertEquals(newZone, provider.currentZoneId())
    }

    @Test
    fun `server date vs local date distinction`() {
        val serverDate = LocalDate.of(2024, 7, 1)
        val provider = FakeTimeProvider(fakeServerDate = serverDate)
        
        // Server time está activa
        assertTrue(provider.isServerTimeActive())
        assertEquals(serverDate, provider.currentDate())
        
        // Limpiar server date
        provider.setServerDate(null)
        
        // Ahora usa fecha local
        assertFalse(provider.isServerTimeActive())
        assertNotNull(provider.currentDate())
    }

    @Test
    fun `wallInstant returns correct instant`() {
        val millis = 1718500000000L
        val provider = FakeTimeProvider(fakeWallMillis = millis)
        
        val instant = provider.wallInstant()
        
        assertEquals(millis, instant.toEpochMilli())
    }

    @Test
    fun `elapsed time measurement accuracy`() {
        val provider = FakeTimeProvider(fakeElapsed = 0L)
        
        // Simular paso del tiempo
        provider.advanceTime(1000L)
        assertEquals(1000L, provider.elapsedRealtime())
        
        provider.advanceTime(500L)
        assertEquals(1500L, provider.elapsedRealtime())
    }
}
