package com.tudominio.parentalcontrol.ui.parent.screens

import com.tudominio.parentalcontrol.domain.model.ChildDevice
import com.tudominio.parentalcontrol.domain.model.DeviceState
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceDetailScreenLoadStateTest {

    private val device = ChildDevice(
        id = "dev-1", name = "Moto G8 Plus", model = "moto g(8) plus",
        appVersion = "1.0.0", policyVersion = 7, state = DeviceState.ACTIVE,
        lastSeenAt = "2026-06-19T22:55:00Z", isOnline = true
    )

    @Test fun `cold empty cache with no prior load surfaces LOADING`() {
        val s = resolveDetailLoadState(
            devices = emptyList(), isLoading = false,
            hasLoadedDevices = false, deviceId = "dev-1"
        )
        assertEquals(
            "Cold empty cache must surface LOADING (spinner), " +
                "not NOT-FOUND.",
            DetailLoadState.Loading, s
        )
    }

    @Test fun `completed empty load surfaces NOT-FOUND`() {
        val s = resolveDetailLoadState(
            devices = emptyList(), isLoading = false,
            hasLoadedDevices = true, deviceId = "dev-1"
        )
        assertEquals(
            "Completed empty load must surface NOT-FOUND.",
            DetailLoadState.NotFound, s
        )
    }

    @Test fun `device present surfaces FOUND regardless of hasLoadedDevices`() {
        val s = resolveDetailLoadState(
            devices = listOf(device), isLoading = false,
            hasLoadedDevices = false, deviceId = "dev-1"
        )
        assertEquals(
            "Device present in cache must surface FOUND.",
            DetailLoadState.Found, s
        )
    }

    @Test fun `loading state suppresses not-found even when cache is empty`() {
        val s = resolveDetailLoadState(
            devices = emptyList(), isLoading = true,
            hasLoadedDevices = false, deviceId = "dev-1"
        )
        assertEquals(
            "Loading state with empty cache must show LOADING.",
            DetailLoadState.Loading, s
        )
    }

    @Test fun `loading with completed flag still surfaces LOADING (defensive)`() {
        val s = resolveDetailLoadState(
            devices = emptyList(), isLoading = true,
            hasLoadedDevices = true, deviceId = "dev-missing"
        )
        assertEquals(
            "Either isLoading or !hasLoadedDevices must keep " +
                "the screen in LOADING.",
            DetailLoadState.Loading, s
        )
    }
}
