package com.tudominio.parentalcontrol.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests para los tipos de datos de autenticación.
 */
class SessionStateTest {

    @Test
    fun `sessionState enum has correct values`() {
        val states = SessionState.entries.toTypedArray()
        assertEquals(5, states.size)
        assertTrue(states.contains(SessionState.NONE))
        assertTrue(states.contains(SessionState.ANONYMOUS))
        assertTrue(states.contains(SessionState.PAIRED))
        assertTrue(states.contains(SessionState.EXPIRED))
        assertTrue(states.contains(SessionState.INVALID))
    }
}

class AuthServiceStateTest {

    @Test
    fun `authServiceState enum has correct values`() {
        val states = AuthServiceState.entries.toTypedArray()
        assertEquals(11, states.size)
        assertTrue(states.contains(AuthServiceState.IDLE))
        assertTrue(states.contains(AuthServiceState.STARTING))
        assertTrue(states.contains(AuthServiceState.AUTHENTICATED))
        assertTrue(states.contains(AuthServiceState.PAIRING))
        assertTrue(states.contains(AuthServiceState.NEEDS_PAIRING))
        assertTrue(states.contains(AuthServiceState.REAUTHING))
        assertTrue(states.contains(AuthServiceState.RETRYING))
        assertTrue(states.contains(AuthServiceState.HANDLING_ANOMALY))
        assertTrue(states.contains(AuthServiceState.INTEGRITY_ERROR))
        assertTrue(states.contains(AuthServiceState.ERROR))
        assertTrue(states.contains(AuthServiceState.FAILED))
    }
}

class StoredSessionTest {

    @Test
    fun `storedSession can be created with all fields`() {
        val session = StoredSession(
            accessToken = "token123",
            refreshToken = "refresh456",
            expiresAt = System.currentTimeMillis() / 1000 + 3600,
            deviceId = "device-abc",
            userId = "user-123"
        )
        
        assertEquals("token123", session.accessToken)
        assertEquals("refresh456", session.refreshToken)
        assertEquals("device-abc", session.deviceId)
        assertEquals("user-123", session.userId)
    }

    @Test
    fun `storedSession deviceId can be null for anonymous`() {
        val session = StoredSession(
            accessToken = "token123",
            refreshToken = "refresh456",
            expiresAt = System.currentTimeMillis() / 1000 + 3600,
            deviceId = null,
            userId = "user-123"
        )
        
        assertNull(session.deviceId)
    }

    @Test
    fun `storedSession expiresAt can be 0 for no expiration check`() {
        val session = StoredSession(
            accessToken = "token123",
            refreshToken = "refresh456",
            expiresAt = 0,
            deviceId = null,
            userId = "user-123"
        )
        
        assertEquals(0L, session.expiresAt)
    }
}

class ConnectionStateTest {

    @Test
    fun `connectionState enum has correct values`() {
        val states = com.tudominio.parentalcontrol.network.ConnectionState.entries.toTypedArray()
        assertEquals(6, states.size)
        assertTrue(states.contains(com.tudominio.parentalcontrol.network.ConnectionState.DISCONNECTED))
        assertTrue(states.contains(com.tudominio.parentalcontrol.network.ConnectionState.CONNECTING))
        assertTrue(states.contains(com.tudominio.parentalcontrol.network.ConnectionState.CONNECTED))
        assertTrue(states.contains(com.tudominio.parentalcontrol.network.ConnectionState.NEEDS_PAIRING))
        assertTrue(states.contains(com.tudominio.parentalcontrol.network.ConnectionState.INTEGRITY_ERROR))
        assertTrue(states.contains(com.tudominio.parentalcontrol.network.ConnectionState.ERROR))
    }
}

class AuthResultTest {

    @Test
    fun `authResult success contains deviceId and tokens`() {
        val result = AuthResult.Success(
            deviceId = "device-123",
            accessToken = "access-token",
            refreshToken = "refresh-token",
            expiresAt = 1234567890L
        )
        
        assertEquals("device-123", result.deviceId)
        assertEquals("access-token", result.accessToken)
        assertEquals("refresh-token", result.refreshToken)
        assertEquals(1234567890L, result.expiresAt)
    }

    @Test
    fun `authResult needsPairing contains message`() {
        val result = AuthResult.NeedsPairing("Please pair this device")
        
        assertEquals("Please pair this device", result.message)
    }

    @Test
    fun `authResult error contains message`() {
        val result = AuthResult.Error("Connection failed")
        
        assertEquals("Connection failed", result.message)
    }
}
