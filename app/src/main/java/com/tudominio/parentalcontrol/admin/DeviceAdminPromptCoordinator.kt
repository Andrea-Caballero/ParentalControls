package com.tudominio.parentalcontrol.admin

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * WU-D — one-shot state machine for the Device Admin activation
 * prompt. Pinned by DeviceAdminPromptCoordinatorTest.
 *
 * Lifecycle:
 *  - Idle               — no recent pairing (default).
 *  - NeedsActivation    — fresh pairing, admin NOT active. UI prompts.
 *  - Dismissed(skipped) — user explicitly deferred. Sticky.
 */
class DeviceAdminPromptCoordinator(
    private val adminAlreadyActive: Boolean = false
) {
    private val _state = MutableStateFlow<DeviceAdminPromptState>(DeviceAdminPromptState.Idle)
    val state: StateFlow<DeviceAdminPromptState> = _state.asStateFlow()

    fun recordFreshPairing() {
        if (adminAlreadyActive) return
        if (_state.value is DeviceAdminPromptState.Dismissed) return
        _state.value = DeviceAdminPromptState.NeedsActivation
    }

    fun markAdminActive() {
        _state.value = DeviceAdminPromptState.Idle
    }

    fun markSkipped() {
        _state.value = DeviceAdminPromptState.Dismissed(skipUsed = true)
    }
}

sealed class DeviceAdminPromptState {
    data object Idle : DeviceAdminPromptState()
    data object NeedsActivation : DeviceAdminPromptState()
    data class Dismissed(val skipUsed: Boolean) : DeviceAdminPromptState()
}
