package com.tudominio.parentalcontrol.data.model

import org.junit.Assert.assertThrows
import org.junit.Test

class GrantEntityTest {
    @Test
    fun `rejects malformed timestamp`() {
        assertThrows(IllegalArgumentException::class.java) { grant("not-a-timestamp") }
    }

    @Test
    fun `rejects timestamp without offset`() {
        assertThrows(IllegalArgumentException::class.java) { grant("2026-07-31T10:00:00") }
    }

    @Test
    fun `accepts historical nanosecond precision`() {
        grant("2026-07-31T10:00:00.123456789Z")
    }

    @Test
    fun `accepts millisecond precision`() {
        grant("2026-07-31T10:00:00.123Z")
    }

    @Test
    fun `rejects more than nanosecond precision`() {
        assertThrows(IllegalArgumentException::class.java) {
            grant("2026-07-31T10:00:00.1234567890Z")
        }
    }

    private fun grant(timestamp: String) = GrantEntity(
        id = "grant-1",
        device_id = "device-1",
        request_id = null,
        scope = "device",
        minutes = 10,
        source = "manual",
        granted_at = "2026-07-31T09:00:00.000Z",
        expires_at = timestamp,
    )
}
