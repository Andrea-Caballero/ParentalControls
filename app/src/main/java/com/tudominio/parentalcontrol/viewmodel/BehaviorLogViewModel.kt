package com.tudominio.parentalcontrol.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.data.model.BehavioralEventEntity
import com.tudominio.parentalcontrol.data.repository.BehavioralEventsRepository
import com.tudominio.parentalcontrol.domain.model.Child
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel para [com.tudominio.parentalcontrol.ui.parent.screens.BehaviorLogScreen].
 *
 * PR B of `openspec/changes/2026-07-07-feat-parent-behavioral-event-log`.
 * Mirrors [ParentViewModel.selectedChildId] + [ParentViewModel.filteredDevices] for
 * the per-child filter chip: [filteredEvents] combines the events list with
 * the locally-selected child id. Hilt's `ViewModelValidationPlugin` forbids
 * `@HiltViewModel`-into-`@HiltViewModel` injection, so this VM owns its
 * own chip selection state. The dashboard's [ParentViewModel] and this
 * VM do not share selection state in V1; a future V2 could lift the
 * selection to a `@Singleton ChildSelectionHolder` if cross-screen
 * persistence is needed.
 *
 * The `children` flow is derived from `events.device_id` — one `Child`
 * per distinct device id. The screen feeds this into `ChildPickerChips`
 * and hides the row when the size is < 2 (mirrors `DashboardScaffold`).
 * The chip label is the `deviceId` in V1; V2 will join with
 * `ParentViewModel.devices` to render the child's firstName.
 *
 * The `init { refresh() }` block triggers the initial GET so the screen
 * never shows the empty state while a fresh snapshot is in-flight.
 * Loading + error StateFlows back the screen's `PullRefresh` + error
 * banner.
 */
@HiltViewModel
class BehaviorLogViewModel @Inject constructor(
    private val repository: BehavioralEventsRepository,
    private val authManager: DeviceAuthManager
) : ViewModel() {

    private val parentId: String = authManager.getParentId().orEmpty()

    /**
     * Raw events for [parentId], ordered newest-first by the DAO
     * (`BehavioralEventDao.flowByParent` `ORDER BY created_at DESC`).
     * `SharingStarted.Eagerly` keeps the flow warm from VM creation so
     * Compose's first `collectAsState()` read sees the actual cache, not
     * the `emptyList()` seed.
     */
    val events: StateFlow<List<BehavioralEventEntity>> =
        repository.observe(parentId)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = emptyList()
            )

    private val _selectedChildId = MutableStateFlow<String?>(null)
    val selectedChildId: StateFlow<String?> = _selectedChildId.asStateFlow()

    /**
     * Events narrowed by the selected child id. `null` selection = all
     * events. The DAO orders the source list newest-first so the filter
     * preserves the ordering — no re-sort needed.
     */
    val filteredEvents: StateFlow<List<BehavioralEventEntity>> =
        combine(events, selectedChildId) { list, id ->
            if (id == null) list else list.filter { it.device_id == id }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    /**
     * Distinct children derived from `events.device_id`. V1 uses the
     * `deviceId` as both the `Child.id` and the `firstName`; the
     * `ChildPickerChips` row will render the deviceId as the chip
     * label. A V2 follow-up can join with `ParentViewModel.devices` to
     * surface the child's firstName. The screen hides this row when
     * the size is < 2 (mirrors `DashboardScaffold.distinctChildren`).
     */
    val children: StateFlow<List<Child>> =
        events.map { list ->
            list.map { it.device_id }.distinct()
                .map { devId ->
                    Child(
                        id = devId,
                        parentId = "",
                        firstName = devId,
                        createdAt = "",
                        updatedAt = ""
                    )
                }
                .sortedBy { it.firstName }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        refresh()
    }

    /**
     * Issues [BehavioralEventsRepository.refresh] for the current parent id.
     * On success, clears the error. On failure, surfaces a localized error
     * message in [error] so the screen can render the error banner.
     */
    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            val result = repository.refresh(parentId)
            if (result.isFailure) {
                _error.value = "Error cargando eventos: " +
                    (result.exceptionOrNull()?.message ?: "Unknown error")
            }
            _isLoading.value = false
        }
    }

    /**
     * Updates the local chip selection. `null` clears the filter.
     */
    fun selectChild(id: String?) {
        _selectedChildId.value = id
    }

    fun clearError() {
        _error.value = null
    }
}
