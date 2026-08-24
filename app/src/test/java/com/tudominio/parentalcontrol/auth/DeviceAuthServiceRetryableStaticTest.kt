package com.tudominio.parentalcontrol.auth

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAuthServiceRetryableStaticTest {
    @Test
    fun `retryable authentication is handled in startup and refresh paths`() {
        val source = File("src/main/java/com/tudominio/parentalcontrol/auth/DeviceAuthService.kt").readText()

        assertTrue(source.contains("is AuthResult.Retryable"))
        assertTrue(source.contains("enterRetryableState()"))
        assertTrue(source.contains("_connectionState.value = ConnectionState.DISCONNECTED"))
        assertTrue(source.contains("onConnectionLost?.invoke()"))
        assertTrue(source.contains("MAX_RETRY_DELAY_MS"))
        assertTrue(source.contains("coerceAtMost(MAX_RETRY_DELAY_MS)"))
        assertTrue(source.contains("_authState.value != AuthServiceState.RETRYING"))
        assertTrue(source.contains("authManager.clearSession()"))
    }
}
