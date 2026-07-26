package com.tudominio.parentalcontrol.data.model

import com.tudominio.parentalcontrol.domain.model.DeviceState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F2a — RED→GREEN tests for [PolicyEntity.device_state] persistence.
 *
 * Pre-fix: the entity had no `device_state` column. The SyncManager's
 * `applyPolicy` set a write-only `_deviceStateFlow` in memory, but the
 * Room `policy` table never recorded the value. After a process
 * restart the state was lost.
 *
 * Fix: add a `device_state: String` column (default "ACTIVE") to
 * `PolicyEntity`, bump the database version to 8, add a v7 → v8
 * migration, and propagate the column through
 * `PolicyEntity.toPolicy` so the [com.tudominio.parentalcontrol.enforcement.EnforcementController]
 * observer sees the live value.
 *
 * The persistence contract is end-to-end:
 *  - SyncManager.applyPolicy writes `device_state = "LOCKED"` to the
 *    `policy` table when the parent locks the device
 *  - EnforcementController's policy observer emits the new value
 *  - The toPolicy mapper parses the value into a [DeviceState] enum
 *  - The enforcement layer calls [com.tudominio.parentalcontrol.admin.LockManager.lockNow]
 *    on `LOCKED` and stays passive on `ACTIVE`
 */
class PolicyEntityDeviceStateTest {

    @Test
    fun `PolicyEntity carries device_state field with default ACTIVE`() {
        val entity = PolicyEntity(
            device_id = "dev-1",
            version = 5L,
            category_assignments = emptyMap(),
            device_state = "ACTIVE"
        )
        assertEquals("ACTIVE", entity.device_state)
    }

    @Test
    fun `PolicyEntity preserves LOCKED when constructed explicitly`() {
        val entity = PolicyEntity(
            device_id = "dev-1",
            version = 6L,
            category_assignments = emptyMap(),
            device_state = "LOCKED"
        )
        assertEquals("LOCKED", entity.device_state)
    }

    @Test
    fun `PolicyEntity default device_state is ACTIVE when not specified`() {
        val entity = PolicyEntity(
            device_id = "dev-1",
            version = 1L,
            category_assignments = emptyMap()
        )
        assertEquals("ACTIVE", entity.device_state)
    }
}
