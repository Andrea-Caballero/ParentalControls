package com.tudominio.parentalcontrol.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAdminPromptCoordinatorTest {

    @Test fun `fresh state is Idle`() {
        assertEquals(DeviceAdminPromptState.Idle, DeviceAdminPromptCoordinator().state.value)
    }

    @Test fun `recordFreshPairing transitions to NeedsActivation`() {
        val c = DeviceAdminPromptCoordinator()
        c.recordFreshPairing()
        assertTrue(c.state.value is DeviceAdminPromptState.NeedsActivation)
    }

    @Test fun `markAdminActive returns to Idle`() {
        val c = DeviceAdminPromptCoordinator()
        c.recordFreshPairing()
        c.markAdminActive()
        assertEquals(DeviceAdminPromptState.Idle, c.state.value)
    }

    @Test fun `markSkipped returns Dismissed(skipUsed=true)`() {
        val c = DeviceAdminPromptCoordinator()
        c.recordFreshPairing()
        c.markSkipped()
        val s = c.state.value
        assertTrue(s is DeviceAdminPromptState.Dismissed)
        assertTrue((s as DeviceAdminPromptState.Dismissed).skipUsed)
    }

    @Test fun `recordFreshPairing after Dismissed does NOT re-trigger`() {
        val c = DeviceAdminPromptCoordinator()
        c.recordFreshPairing()
        c.markSkipped()
        c.recordFreshPairing()
        assertTrue(c.state.value is DeviceAdminPromptState.Dismissed)
    }

    @Test fun `recordFreshPairing is idempotent within the same pairing`() {
        val c = DeviceAdminPromptCoordinator()
        c.recordFreshPairing()
        c.recordFreshPairing()
        assertTrue(c.state.value is DeviceAdminPromptState.NeedsActivation)
    }

    @Test fun `adminAlreadyActive skips the prompt entirely`() {
        val c = DeviceAdminPromptCoordinator(adminAlreadyActive = true)
        c.recordFreshPairing()
        assertEquals(DeviceAdminPromptState.Idle, c.state.value)
        assertFalse(c.state.value is DeviceAdminPromptState.NeedsActivation)
    }
}
