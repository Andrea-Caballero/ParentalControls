package com.tudominio.parentalcontrol.auth

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAuthManagerRestoreOutcomeTest {
    @Test
    fun `restore outcomes keep transient failures separate from first run`() {
        val source = File("src/main/java/com/tudominio/parentalcontrol/auth/DeviceAuthManager.kt").readText()
        assertTrue(source.contains("SessionRestoreOutcome.NoPersistedSession"))
        assertTrue(source.contains("SessionRestoreOutcome.TransientFailure"))
        assertTrue(source.contains("SessionRestoreOutcome.InvalidSession"))
        assertTrue(source.contains("auth_session_reauthentication_unavailable"))
        assertTrue(source.contains("isMalformedAuthStorageFailure"))
        assertTrue(source.contains("AEADBadTagException"))
        assertFalse(source.contains("TransientFailure -> createAnonymousSession"))
        assertTrue(source.contains("NetworkSecurityConfig.createSecureOkHttpClient(context)"))
        assertTrue(source.contains("preconfigured = NetworkSecurityConfig.createSecureOkHttpClient(context)"))
    }
}
