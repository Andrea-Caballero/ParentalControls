package com.tudominio.parentalcontrol.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceAuthEngineSelectionTest {

    @Test
    fun sharedMockTakesPrecedenceOverInProcessMock() {
        assertEquals(
            DeviceAuthEngine.SHARED_MOCK,
            selectDeviceAuthEngine(useSharedMock = true, useMockSupabase = true)
        )
    }

    @Test
    fun defaultMockUsesInProcessEngine() {
        assertEquals(
            DeviceAuthEngine.IN_PROCESS_MOCK,
            selectDeviceAuthEngine(useSharedMock = false, useMockSupabase = true)
        )
    }

    @Test
    fun realModeUsesRealEngine() {
        assertEquals(
            DeviceAuthEngine.REAL,
            selectDeviceAuthEngine(useSharedMock = false, useMockSupabase = false)
        )
    }

    @Test
    fun sharedModeUsesSharedServerForAuthAndPairingRoutes() {
        val sharedMockUrl = "http://shared-mock.test:8787/"
        val supabaseUrl = "https://project.supabase.co/"
        assertEquals(
            DeviceAuthEngine.SHARED_MOCK,
            selectDeviceAuthEngine(useSharedMock = true, useMockSupabase = false)
        )

        val routes = listOf(
            "/auth/v1/signup",
            "/functions/v1/pairing",
            "/auth/v1/token?grant_type=refresh_token",
            "/auth/v1/magiclink",
            "/auth/v1/verify?type=magiclink",
            "/auth/v1/dev-login",
            "/rest/v1/devices?parent_id=eq.parent"
        )
        routes.forEach { route ->
            assertEquals(
                "http://shared-mock.test:8787$route",
                effectiveDeviceAuthUrl(
                    useSharedMock = true,
                    supabaseUrl = supabaseUrl,
                    sharedMockUrl = sharedMockUrl,
                    path = route
                )
            )
        }
    }

    @Test
    fun realModeUsesSupabaseForAuthAndPairingRoutes() {
        assertEquals(
            "https://project.supabase.co/auth/v1/token?grant_type=refresh_token",
            effectiveDeviceAuthUrl(
                useSharedMock = false,
                supabaseUrl = "https://project.supabase.co/",
                sharedMockUrl = "http://shared-mock.test:8787",
                path = "/auth/v1/token?grant_type=refresh_token"
            )
        )
    }
}
