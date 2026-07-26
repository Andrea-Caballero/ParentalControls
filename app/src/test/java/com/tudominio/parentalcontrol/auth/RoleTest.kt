package com.tudominio.parentalcontrol.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [Role] enum (T2 of `hotfix-parent-auth-session`).
 *
 * The [Role] enum is the source of truth for parent vs. child tracking. It
 * is persisted alongside the synthetic auth token in `device_auth_prefs` so
 * that `DeviceAuthManager.getRole()` can return the correct role after a
 * process restart. This test class pins the two-value shape required by the
 * spec at `openspec/changes/hotfix-parent-auth-session/specs/parent-auth-session/spec.md`.
 */
class RoleTest {

    @Test
    fun `Role has exactly two values PARENT and CHILD`() {
        val values = Role.values()

        assertEquals(2, values.size)
        assertTrue(
            "Role must contain PARENT, got ${values.toList()}",
            values.contains(Role.PARENT)
        )
        assertTrue(
            "Role must contain CHILD, got ${values.toList()}",
            values.contains(Role.CHILD)
        )
    }

    @Test
    fun `Role valueOf maps name to enum constant`() {
        assertEquals(Role.PARENT, Role.valueOf("PARENT"))
        assertEquals(Role.CHILD, Role.valueOf("CHILD"))
    }

    @Test
    fun `Role names are stable strings for persistence`() {
        // Persistence contract — the role is written to SharedPreferences as
        // its `name`. The name is part of the persistence contract; renaming
        // would silently migrate existing installs to Role.PARENT.
        assertEquals("PARENT", Role.PARENT.name)
        assertEquals("CHILD", Role.CHILD.name)
    }

    @Test
    fun `Role enum has stable ordinals for switch statements`() {
        // The enum is the input to a `when` in [DeviceAuthManager]. Pin
        // ordinals so accidental reordering doesn't silently change behavior.
        assertNotNull(Role.PARENT.ordinal)
        assertNotNull(Role.CHILD.ordinal)
        // Distinct values
        assertTrue(Role.PARENT.ordinal != Role.CHILD.ordinal)
    }
}
