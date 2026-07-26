package com.tudominio.parentalcontrol.ui.child.status

import com.tudominio.parentalcontrol.admin.DeviceAdminPromptCoordinator
import com.tudominio.parentalcontrol.admin.DeviceAdminPromptState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChildStatusDeviceAdminBannerTest {

    @Test fun `banner hidden when coordinator is Idle`() {
        val c = DeviceAdminPromptCoordinator()
        assertFalse(c.state.value is DeviceAdminPromptState.NeedsActivation)
    }

    @Test fun `banner shows when NeedsActivation`() {
        val c = DeviceAdminPromptCoordinator()
        c.recordFreshPairing()
        assertTrue(c.state.value is DeviceAdminPromptState.NeedsActivation)
    }

    @Test fun `banner shows when user skipped`() {
        val c = DeviceAdminPromptCoordinator()
        c.recordFreshPairing()
        c.markSkipped()
        assertTrue(c.state.value is DeviceAdminPromptState.Dismissed)
    }

    @Test fun `banner clears once admin is active`() {
        val c = DeviceAdminPromptCoordinator()
        c.recordFreshPairing()
        c.markAdminActive()
        assertTrue(c.state.value is DeviceAdminPromptState.Idle)
    }
}
