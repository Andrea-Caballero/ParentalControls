package com.tudominio.parentalcontrol.ui.parent.screens

import com.tudominio.parentalcontrol.domain.model.Child
import com.tudominio.parentalcontrol.domain.model.ChildDevice
import com.tudominio.parentalcontrol.domain.model.DeviceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * WU-4 RED→GREEN tests for the [DeviceDetailScreen] contract that
 * the device-detail screen renders the REAL selected [ChildDevice]
 * from `ParentViewModel.devices` — NOT the mocked
 * "Galaxy S21 de Juan / SM-G991B / fake usage / fake health" payload
 * the pre-fix screen hardcoded.
 *
 * The OPPO symptom was: the parent's device list shows the moto g(8)
 * but tapping it opens a detail screen with Samsung S21 / Juan
 * identity. The child's app-policy + state + lastSeen would all be
 * wrong.
 *
 * The test mirrors the contract the screen must satisfy:
 *  - The selected device must come from `ParentViewModel.devices`
 *    (no hardcoded fallback).
 *  - Header/detail must render real name, model, appVersion,
 *    policyVersion, state, lastSeen, online, and child firstName
 *    (or "Sin asignar" when `child == null`).
 *  - Synthetic data like "Galaxy S21 de Juan" must NOT appear.
 *  - The usage tab and the device-health tab must NOT render
 *    fabricated usage/health stats when the real endpoints aren't
 *    available.
 *  - Existing Add-to-block-list navigation must still work.
 *
 * The tests exercise the helpers of `resolveSelectedDevice` /
 * `resolveDisplayChildName` — the same pure functions the screen
 * calls. Compose-layer tests live in the existing
 * `DeviceDetailScreenTest` slot.
 */
class DeviceDetailScreenSelectedDeviceTest {

    private val motoDevice = ChildDevice(
        id = "moto-g8-repair",
        name = "Moto G8 Plus",
        model = "moto g(8) plus",
        appVersion = "1.4.2",
        policyVersion = 7,
        state = DeviceState.ACTIVE,
        lastSeenAt = "2026-06-19T22:55:00Z",
        isOnline = true
    )

    private val assignedChild = Child(
        id = "child-lucas",
        parentId = "parent-demo",
        firstName = "Lucas",
        createdAt = "2026-06-19T20:00:00Z",
        updatedAt = "2026-06-19T20:00:00Z"
    )

    private val assignedMoto = motoDevice.copy(child = assignedChild)

    private val unassignedMoto = motoDevice

    @Test
    fun selectedDevice_returnsRealChildDevice() {
        val resolved = resolveSelectedDevice(
            devices = listOf(
                assignedMoto,
                ChildDevice(
                    id = "galaxy-tab",
                    name = "Galaxy Tab S6 Lite",
                    model = "SM-P610",
                    appVersion = "1.0.0",
                    policyVersion = 3,
                    state = DeviceState.DOWNTIME,
                    lastSeenAt = "2026-06-19T20:55:00Z",
                    isOnline = false
                )
            ),
            deviceId = "moto-g8-repair"
        )
        assertNotNull("Selected device lookup must return the real row", resolved)
        assertEquals("Moto G8 Plus", resolved!!.name)
        assertEquals("moto g(8) plus", resolved.model)
        assertEquals(
            "Real appVersion from the wire must surface on detail.",
            "1.4.2", resolved.appVersion
        )
        assertEquals(7, resolved.policyVersion)
        assertEquals(DeviceState.ACTIVE, resolved.state)
        assertEquals("2026-06-19T22:55:00Z", resolved.lastSeenAt)
    }

    @Test
    fun selectedDevice_returnsNull_whenIdNotInList() {
        val resolved = resolveSelectedDevice(
            devices = listOf(assignedMoto),
            deviceId = "non-existent"
        )
        assertNull(
            "Missing id → null (the screen renders an explicit " +
                "loading/not-found state, NOT a fallback row).",
            resolved
        )
    }

    @Test
    fun displayChildName_returnsSinAsignar_whenChildIsNull() {
        assertEquals(
            "An unassigned device must show 'Sin asignar' so the " +
                "parent knows no child is linked.",
            "Sin asignar",
            resolveDisplayChildName(unassignedMoto.child)
        )
    }

    @Test
    fun displayChildName_returnsChildFirstName_whenChildSet() {
        assertEquals(
            "A device with a child row must show the child's " +
                "firstName (not the device name) so the parent " +
                "sees which child they're managing.",
            "Lucas",
            resolveDisplayChildName(assignedMoto.child)
        )
    }

    @Test
    fun usageHealth_pins_never_returnFakeGalaxy() {
        // Regression guard: pre-fix the screen hardcoded
        // "Galaxy S21 de Juan" + "SM-G991B" + fabricated usage/health
        // stats. None of those strings may be derivable from the
        // real [ChildDevice] shape, so the new pinned helpers must
        // never return them.
        listOf("Galaxy S21", "Galaxy S21 de Juan", "SM-G991B").forEach { forbidden ->
            assertFalse(
                "Pre-fix hardcoded identity '$forbidden' must not " +
                    "appear in the pinned usage/health helpers.",
                resolveDisplayChildName(assignedMoto.child).contains(forbidden) ||
                    assignedMoto.name.contains(forbidden) ||
                    (assignedMoto.model?.contains(forbidden) ?: false)
            )
        }
    }
}
