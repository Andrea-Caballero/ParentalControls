package com.tudominio.parentalcontrol.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.auth.Role
import com.tudominio.parentalcontrol.data.repository.DeviceListError
import com.tudominio.parentalcontrol.data.repository.ParentRepository
import com.tudominio.parentalcontrol.domain.model.ApprovalResult
import com.tudominio.parentalcontrol.domain.model.ChildDevice
import com.tudominio.parentalcontrol.domain.model.PolicyTemplate
import com.tudominio.parentalcontrol.domain.model.TimeRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * ViewModel para la UI del padre.
 *
 * T6 of `hotfix-parent-auth-session` adds:
 *  - [DeviceAuthManager] injection (Hilt-resolved from `RepositoryModule`).
 *  - [authenticateAsParent] — delegates to
 *    `authManager.authenticateOrCreate(Role.PARENT)` and returns the
 *    same [Result]. Called from `OnboardingScreen` before navigating to
 *    `Dashboard` so the parent always has a session.
 *  - Typed [DeviceListUiState.Error] carrying a [DeviceListError] instead
 *    of a raw `String`. The `DashboardScreen` pattern-matches on the
 *    variant to swap the CTA between "Iniciar sesión como padre" (auth
 *    missing) and "Reintentar" + "Volver" (transient).
 */
@HiltViewModel
class ParentViewModel @Inject constructor(
    private val repository: ParentRepository,
    private val authManager: DeviceAuthManager
) : ViewModel() {

    // Estado de dispositivos — sealed UI state for the four render branches
    // (Loading / Success / Empty / Error). PR 2 of
    // openspec/changes/wire-pairing-and-approval-end-to-end replaces the
    // simple `List<ChildDevice>` flow with a typed UI state so the
    // DashboardScreen can render loading, error, empty, and success states.
    private val _deviceListState = MutableStateFlow<DeviceListUiState>(DeviceListUiState.Loading)
    val deviceListState: StateFlow<DeviceListUiState> = _deviceListState.asStateFlow()

    // Legacy alias — pre-PR 2 callers used `devices`. Kept so existing tests
    // and any UI that hasn't migrated still compile.
    private val _devices = MutableStateFlow<List<ChildDevice>>(emptyList())
    val devices: StateFlow<List<ChildDevice>> = _devices.asStateFlow()

    // B.1 of `feat-multi-child-picker` (Change B). The selected child
    // scopes BOTH the Devices tab and the Solicitudes tab. In-memory only
    // per decision R2-V1 — cold start always begins at null (= "Todos").
    // The stale-selection reset below keeps a chip from outliving the
    // child it was scoped to after a fresh `loadDevices()`.
    private val _selectedChildId = MutableStateFlow<String?>(null)
    val selectedChildId: StateFlow<String?> = _selectedChildId.asStateFlow()

    // Derived from `_devices` + `_selectedChildId` via `combine`. `null`
    // selection returns the unfiltered list. The collector pattern matches
    // the established `pendingRequestsFlow` mirror in `init {}`.
    //
    // `SharingStarted.Eagerly` (instead of `WhileSubscribed`) keeps the
    // derived StateFlow's value warm from the moment the VM exists, so
    // Compose's first `collectAsState()` read sees the actual filter
    // result, not the `emptyList()` initial value. Critical for the
    // pre-`WhileSubscribed`-timeout Robolectric test path.
    val filteredDevices: StateFlow<List<ChildDevice>> =
        combine(_devices, _selectedChildId) { list, id ->
            if (id == null) list else list.filter { it.child?.id == id }
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            emptyList()
        )

    // Solicitudes pendientes
    private val _pendingRequests = MutableStateFlow<List<TimeRequest>>(emptyList())
    val pendingRequests: StateFlow<List<TimeRequest>> = _pendingRequests.asStateFlow()

    // Plantillas disponibles
    private val _templates = MutableStateFlow<List<PolicyTemplate>>(emptyList())
    val templates: StateFlow<List<PolicyTemplate>> = _templates.asStateFlow()

    // Estado de carga
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Error
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Result of the most recent approval call. PR 4 of
    // openspec/changes/wire-pairing-and-approval-end-to-end exposes the
    // ApprovalResult (grant_id, minutes, expires_at) returned by the
    // approve-request edge function so the UI can show a confirmation
    // banner with the granted minutes.
    private val _approvalResult = MutableStateFlow<ApprovalResult?>(null)
    val approvalResult: StateFlow<ApprovalResult?> = _approvalResult.asStateFlow()

    // Código de emparejamiento generado
    private val _pairingCode = MutableStateFlow<PairingCodeResult?>(null)
    val pairingCode: StateFlow<PairingCodeResult?> = _pairingCode.asStateFlow()

    // RenameChildState — hoisted StateFlow per Q2=h (engram #294). The
    // dashboard pattern-matches on the sealed UI state to render the
    // rename dialog at the right time. The dialog composable itself
    // stays stateless — only the text field holds local mutable state;
    // transitions live here so the dialog surface contract is testable
    // without a Compose rule.
    //
    // Transitions:
    //   requestRename(...)               Hidden   -> Editing
    //   confirmRename(newName) on success Editing -> Saving -> Saved -> Hidden (auto-dismiss)
    //   confirmRename(newName) on failure Editing -> Saving -> Failed
    //   dismissRename()                  any      -> Hidden (short-circuit)
    private val _renameChildState =
        MutableStateFlow<RenameChildState>(RenameChildState.Hidden)
    val renameChildState: StateFlow<RenameChildState> = _renameChildState.asStateFlow()

    /**
     * Opens the rename dialog for [childId], seeding it with the child's
     * current [currentName]. The dialog pre-populates its text field
     * with [currentName] so the parent only edits what changes.
     * Mirrors the established `requestRename` request-style naming of
     * the dialog's intent surface.
     */
    fun requestRename(childId: String, currentName: String) {
        _renameChildState.value = RenameChildState.Editing(childId, currentName)
    }

    /**
     * Submits the rename. Validates that we're in [RenameChildState.Editing],
     * moves into [RenameChildState.Saving], awaits the PATCH, then lands
     * in [RenameChildState.Saved] (auto-dismisses to Hidden after 1.5s)
     * on success or [RenameChildState.Failed] on error. Per Q4=p the call
     * is pessimistic — no optimistic UI update before the await.
     *
     * The post-success branch calls [loadDevices] so the dashboard
     * devices list (and its chip row) see the new first name in the
     * next frame; then schedules a 1.5s auto-dismiss that guards on
     * the saved snapshot still being current — a fresh `requestRename`
     * during the window replaces the snapshot and the old dismissal
     * no-ops.
     */
    fun confirmRename(newName: String) {
        val editing = _renameChildState.value as? RenameChildState.Editing ?: return
        _renameChildState.value = RenameChildState.Saving(editing.childId)
        viewModelScope.launch {
            val result = repository.renameChild(editing.childId, newName.trim())
            _renameChildState.value = if (result.isSuccess) {
                loadDevices()
                val saved = RenameChildState.Saved(editing.childId)
                viewModelScope.launch {
                    delay(RENAME_AUTO_DISMISS_MS)
                    if (_renameChildState.value == saved) {
                        _renameChildState.value = RenameChildState.Hidden
                    }
                }
                saved
            } else {
                RenameChildState.Failed(
                    childId = editing.childId,
                    error = result.exceptionOrNull()?.message ?: "Error al renombrar"
                )
            }
        }
    }

    /**
     * Closes the dialog from any state. The Saved state's auto-dismiss
     * guard (see [confirmRename]) ensures a manual dismiss during the
     * 1.5s confirmation window is honored — the inner `delay` only
     * dismisses when the snapshot still matches.
     */
    fun dismissRename() {
        _renameChildState.value = RenameChildState.Hidden
    }

    init {
        loadDevices()
        loadPendingRequests()
        // D2 of `fix-parent-solicitudes-auto-poll` — mirror the
        // singleton-level `ParentRepository.pendingRequestsFlow` (written
        // by `SolicitudesPollingWorker` and by `loadPendingRequests()`
        // itself) into `_pendingRequests` so the UI sees fresh rows even
        // when no UI event fires a fetch.
        viewModelScope.launch {
            repository.pendingRequestsFlow.collect { list ->
                _pendingRequests.value = list
            }
        }
    }

    /**
     * Issues a synthetic anonymous parent session via
     * [DeviceAuthManager.authenticateOrCreate] with [Role.PARENT]. The
     * hotfix path: no real Supabase call, no sign-up form. Called from
     * `OnboardingScreen` before navigating to `Dashboard` so the parent
     * always has an access token by the time the device list tries to
     * load.
     *
     * On success, chains [loadDevices] so the dashboard transitions out
     * of `Error(AuthMissing)` once auth completes (T2.1 of
     * `hotfix-parent-auth-cta-reload`). The failure path leaves
     * `_deviceListState` untouched (design Decision 3): the existing
     * error banner stays so the user can retry or surface the auth
     * failure via `clearError()`.
     *
     * Returns the same [Result] the auth manager returns — success means
     * a token is now persisted, `getAccessToken()` is non-null, and the
     * device list has been re-fetched.
     */
    suspend fun authenticateAsParent(): Result<Unit> =
        authManager.authenticateOrCreate(Role.PARENT).onSuccess { loadDevices() }

    fun loadDevices() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            _deviceListState.value = DeviceListUiState.Loading
            try {
                val result = repository.getDevices()
                val list = result.getOrNull()
                if (list != null) {
                    _devices.value = list
                    // Stale-selection reset per parent-device-list spec:
                    // after every successful fetch, if the cached
                    // `_selectedChildId` no longer matches any device's
                    // `child.id`, the picker must default back to "Todos".
                    val validIds = list.mapNotNull { it.child?.id }.toSet()
                    if (_selectedChildId.value !in validIds) {
                        _selectedChildId.value = null
                    }
                    _deviceListState.value = if (list.isEmpty()) {
                        DeviceListUiState.Empty
                    } else {
                        DeviceListUiState.Success(list)
                    }
                } else {
                    val errorReason = mapToDeviceListError(result.exceptionOrNull())
                    _error.value = "Error cargando dispositivos: ${describe(errorReason)}"
                    _deviceListState.value = DeviceListUiState.Error(errorReason)
                }
            } catch (e: Exception) {
                val errorReason = mapToDeviceListError(e)
                _error.value = "Error cargando dispositivos: ${describe(errorReason)}"
                _deviceListState.value = DeviceListUiState.Error(errorReason)
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun mapToDeviceListError(throwable: Throwable?): DeviceListError {
        if (throwable is DeviceListError) return throwable
        val message = throwable?.message
        if (message != null && message.contains("not authenticated")) {
            return DeviceListError.AuthMissing
        }
        return DeviceListError.Transient(message ?: "Unknown error")
    }

    fun loadPendingRequests() {
        // D5 of `fix-parent-solicitudes-auto-poll` — dedup rapid tab taps
        // by gating on the existing `_isLoading` flag. The first thing the
        // dashboard's `LaunchedEffect(selectedTab)` does on tab 1 is call
        // here; if a previous fetch is still in flight, we return without
        // issuing a parallel call. Mirrors `loadDevices()`'s
        // `_isLoading` contract.
        if (_isLoading.value) return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // PR 4 of openspec/changes/wire-pairing-and-approval-end-to-end
                // wires the repository to a real REST query that returns
                // Result<List<TimeRequest>>; map success/failure into the
                // existing StateFlows so the RequestCard UI keeps rendering
                // unchanged.
                //
                // V2 thread-through (`fix-v2-server-side-solicitudes-filter`):
                // when a child is selected the repository translates the
                // id to device ids and asks Postgrest for the matching
                // subset (small payload + lower JSON parse cost); when
                // `_selectedChildId` is null (Todos) the URL stays
                // parameter-less and RLS alone scopes the rows.
                val result = repository.getPendingRequests(
                    selectedChildId = _selectedChildId.value
                )
                val list = result.getOrNull()
                if (list != null) {
                    _pendingRequests.value = list
                    // D2 — mirror the same list into the singleton flow
                    // so a second VM (or a future consumer) sees the
                    // freshly-fetched rows.
                    repository.publishPendingRequests(list)
                } else {
                    _error.value = "Error cargando solicitudes: " +
                        (result.exceptionOrNull()?.message ?: "Unknown error")
                }
            } catch (e: Exception) {
                _error.value = "Error cargando solicitudes: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadTemplates() {
        viewModelScope.launch {
            try {
                _templates.value = repository.getTemplates()
            } catch (e: Exception) {
                _error.value = "Error cargando plantillas: ${e.message}"
            }
        }
    }

    fun approveRequest(requestId: String, minutes: Int, response: String? = null) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // PR 4 of openspec/changes/wire-pairing-and-approval-end-to-end
                // handles the Result<ApprovalResult> returned by the new
                // edge-function call. On success we surface the ApprovalResult
                // (grant_id, minutes, expires_at) via _approvalResult so the
                // parent UI can show a confirmation banner.
                val result = repository.approveRequest(requestId, minutes, response)
                val approval = result.getOrNull()
                if (approval != null) {
                    _approvalResult.value = approval
                    loadPendingRequests()
                    loadDevices() // Refresh para ver versión actualizada
                } else {
                    _error.value = "Error aprobando solicitud: " +
                        (result.exceptionOrNull()?.message ?: "Unknown error")
                }
            } catch (e: Exception) {
                _error.value = "Error aprobando solicitud: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun denyRequest(requestId: String, reason: String? = null) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // PR 4 of openspec/changes/wire-pairing-and-approval-end-to-end
                // handles the Result<Boolean> returned by the new
                // edge-function call. On success we reload the pending list
                // so the RequestCard disappears.
                val result = repository.denyRequest(requestId, reason)
                if (result.isSuccess) {
                    loadPendingRequests()
                } else {
                    _error.value = "Error denegando solicitud: " +
                        (result.exceptionOrNull()?.message ?: "Unknown error")
                }
            } catch (e: Exception) {
                _error.value = "Error denegando solicitud: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun createPairingCode(deviceName: String, ageBand: String, ttlMinutes: Int = 10) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = repository.createPairingCode(deviceName, ageBand, ttlMinutes)
                _pairingCode.value = result.getOrNull()
                if (result.isFailure) {
                    _error.value = "Error creando código: ${result.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                _error.value = "Error creando código: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun grantReward(deviceId: String, minutes: Int, reason: String? = null) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.grantReward(deviceId, minutes, reason)
                loadDevices()
            } catch (e: Exception) {
                _error.value = "Error concediendo recompensa: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateDevicePolicy(deviceId: String, templateId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.applyTemplate(deviceId, templateId)
                loadDevices()
            } catch (e: Exception) {
                _error.value = "Error actualizando política: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun lockDevice(deviceId: String) {
        viewModelScope.launch {
            // WU-2 — surface failure as a typed snackbar message so the
            // parent sees the click failed rather than a silent reload
            // (which was the OPPO bug: the pre-fix repo always returned
            // true; the card simply reloaded the unchanged ACTIVE state).
            val ok = repository.lockDevice(deviceId)
            if (ok) {
                loadDevices()
            } else {
                _error.value = "Error bloqueando dispositivo: la solicitud falló"
            }
        }
    }

    fun unlockDevice(deviceId: String) {
        viewModelScope.launch {
            val ok = repository.unlockDevice(deviceId)
            if (ok) {
                loadDevices()
            } else {
                _error.value = "Error desbloqueando dispositivo: la solicitud falló"
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    /**
     * Updates [selectedChildId]. Passing `null` switches back to "Todos".
     * The chip row + Devices/Solicitudes tabs observe the StateFlow and
     * re-filter automatically. V1 (in-memory only) — selection resets
     * across cold start per decision R2-V1.
     */
    fun setSelectedChild(id: String?) {
        _selectedChildId.value = id
    }

    fun clearPairingCode() {
        _pairingCode.value = null
    }

    private fun describe(error: DeviceListError): String = when (error) {
        DeviceListError.AuthMissing -> "not authenticated"
        is DeviceListError.Transient -> error.reason
    }
}

data class PairingCodeResult(
    val code: String,
    val expiresAt: String,
    val deeplink: String
)

/**
 * Confirmation-window duration for [RenameChildState.Saved] before the
 * dialog auto-dismisses back to [RenameChildState.Hidden]. Tuned so
 * the chip-row's freshly-updated first name is briefly visible inside
 * the still-open dialog before the dialog closes — gives the parent
 * feedback that the rename landed without requiring a second tap.
 * 1.5s is the proposal's open-question default; tweakable in one
 * place if the manual smoke test finds it too long.
 */
private const val RENAME_AUTO_DISMISS_MS = 1_500L

/**
 * Sealed UI state for the parent-side rename flow
 * (`openspec/changes/2026-07-07-fix-rename-child-dialog/`). The
 * dashboard pattern-matches on this state to decide whether to render
 * the dialog, what [RenameChildDialog.initialName] to pass, and which
 * of the three intents ([requestRename] / [confirmRename] /
 * [dismissRename]) the dialog should invoke.
 *
 *  - [Hidden]: no dialog rendered.
 *  - [Editing]: dialog open at rest, awaiting Guardar / Cancelar.
 *  - [Saving]: dialog open with spinner; the PATCH is in-flight.
 *  - [Saved]: dialog open with confirmation copy; auto-dismisses
 *    via [ParentViewModel.confirmRename]'s inner `delay`.
 *  - [Failed]: dialog open with inline server error.
 *
 * Per Q2=h (engram #294) this state is hoisted onto the VM so
 * renaming is testable without a Compose rule.
 */
sealed interface RenameChildState {
    data object Hidden : RenameChildState
    data class Editing(val childId: String, val currentName: String) : RenameChildState
    data class Saving(val childId: String) : RenameChildState
    data class Saved(val childId: String) : RenameChildState
    data class Failed(val childId: String, val error: String) : RenameChildState
}

/**
 * Sealed UI state for the parent dashboard's device list (PR 2 of
 * `openspec/changes/wire-pairing-and-approval-end-to-end`). The four states
 * correspond to the four render branches in `DashboardScreen`:
 * - [Loading]: the network call is in flight; show a centered spinner.
 * - [Success]: the call returned a non-empty list; render `DeviceCard`s.
 * - [Empty]: the call returned an empty list; show the "Pair a new device"
 *   CTA (per `parent-device-list/spec.md`).
 * - [Error]: the call failed; show the error banner. The [Error.reason] is
 *   a typed [DeviceListError] — `DashboardScreen` pattern-matches on it
 *   to choose the CTA (auth-missing → "Iniciar sesión como padre";
 *   transient → retry/back).
 */
sealed interface DeviceListUiState {
    data object Loading : DeviceListUiState
    data class Success(val items: List<ChildDevice>) : DeviceListUiState
    data object Empty : DeviceListUiState
    data class Error(val reason: DeviceListError) : DeviceListUiState
}
