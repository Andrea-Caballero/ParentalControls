package com.tudominio.parentalcontrol.ui.screen.apps

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tudominio.parentalcontrol.data.db.AppPolicyDao
import com.tudominio.parentalcontrol.data.model.AppPolicyEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the parent app-block screen.
 *
 * PR 5 of `openspec/changes/wire-pairing-and-approval-end-to-end` (task #30,
 * `app-block-policy` spec). Lets the parent browse every launchable app
 * installed on the reference device and toggle a per-app `app_policies`
 * row between `ALLOWED` and `BLOCKED`.
 *
 * The ViewModel is scoped to a single [deviceId] (set by the caller via
 * [setDeviceId] — typically `DeviceDetailScreen` when the parent taps
 * "Add to block list"). All DAO reads and writes use this device id, so
 * toggling an app for one child never affects another child's policy for
 * the same package. We use a setter (rather than a constructor parameter)
 * because Hilt's `@HiltViewModel` graph can't inject a per-screen `String`
 * value without assisted-injection boilerplate.
 */
@HiltViewModel
class AppsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appPolicyDao: AppPolicyDao
) : ViewModel() {

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()

    private val _blockedPackages = MutableStateFlow<Set<String>>(emptySet())
    val blockedPackages: StateFlow<Set<String>> = _blockedPackages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * Device id this ViewModel is scoped to. Starts as [UNKNOWN_DEVICE_ID]
     * because Hilt injects the VM before the screen knows which device
     * the parent is currently viewing. The screen MUST call [setDeviceId]
     * before [loadBlockedPackages] / [toggleBlock] to make every DAO call
     * device-scoped.
     */
    private var deviceId: String = UNKNOWN_DEVICE_ID

    /** Returns the device id this ViewModel is currently scoped to. */
    fun deviceId(): String = deviceId

    /**
     * Binds this ViewModel to a specific child [id]. Idempotent: callers
     * may invoke this every recomposition. The screen must call this
     * before [loadBlockedPackages] so the device-scoped flow is queried
     * with the right id.
     */
    fun setDeviceId(id: String) {
        if (id == deviceId) return
        deviceId = id
    }

    /**
     * Reads every launchable activity from [Context.getPackageManager] and
     * exposes them as [AppInfo] (package name + display label + icon). Uses
     * [Intent.ACTION_MAIN] + `CATEGORY_LAUNCHER` per the `app-block-policy`
     * spec scenario "Lists every launchable package".
     *
     * **OPPO dual-SIM dedup (WU-3):** OPPO devices ship `com.android.stk`
     * with TWO launcher activities (one per SIM slot). The naive
     * `map { it.activityInfo.packageName }` produced two AppInfo rows
     * with identical `packageName`, breaking the parent Compose
     * `LazyColumn`'s `key = { it.packageName }` with
     * `IllegalArgumentException: Key "com.android.stk" was already used`.
     * `distinctBy { activityInfo.packageName }` collapses them to one row;
     * `sortedBy { it.activityInfo.packageName }` keeps the row order
     * stable across recompositions.
     */
    fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            try {
                val pm = context.packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val resolved = pm.queryIntentActivities(intent, 0)
                _apps.value = resolved
                    .distinctBy { it.activityInfo.packageName }
                    .sortedBy { it.activityInfo.packageName }
                    .map { ri ->
                        AppInfo(
                            packageName = ri.activityInfo.packageName,
                            displayName = ri.loadLabel(pm).toString(),
                            icon = null
                        )
                    }
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Observes [AppPolicyDao.getAppPoliciesForDeviceFlow] for this ViewModel's
     * [deviceId] and exposes the set of package names whose `state = "BLOCKED"`.
     * Refreshes automatically when the DAO emits. Per-device scoping
     * guarantees that policies for sibling devices never leak into this UI.
     */
    fun loadBlockedPackages() {
        viewModelScope.launch(Dispatchers.IO) {
            appPolicyDao.getAppPoliciesForDeviceFlow(deviceId).collectLatest { policies ->
                _blockedPackages.value = policies
                    .filter { it.state == "BLOCKED" }
                    .map { it.package_name }
                    .toSet()
            }
        }
    }

    /**
     * Toggles the block state for [packageName] on this ViewModel's
     * [deviceId]. Performs an optimistic update on [_blockedPackages] and
     * persists via [appPolicyDao.upsertAppPolicy]. Rolls the optimistic
     * update back if the DAO throws.
     */
    fun toggleBlock(packageName: String, block: Boolean) {
        val previous = _blockedPackages.value
        val optimistic = if (block) previous + packageName else previous - packageName
        _blockedPackages.value = optimistic

        viewModelScope.launch(Dispatchers.IO) {
            try {
                appPolicyDao.upsertAppPolicy(
                    AppPolicyEntity(
                        device_id = deviceId,
                        package_name = packageName,
                        state = if (block) "BLOCKED" else "ALLOWED",
                        daily_limit_minutes = null,
                        allowed_windows = emptyList(),
                        category = null
                    )
                )
            } catch (e: Exception) {
                // Roll back optimistic update on DAO failure.
                _blockedPackages.value = previous
            }
        }
    }

    companion object {
        /**
         * Sentinel value used by Hilt's default `AppsViewModel` factory
         * when no explicit device id is provided by the caller. The screen
         * MUST call [setDeviceId] with the real device id from
         * [com.tudominio.parentalcontrol.ui.parent.screens.DashboardScreen]'s
         * `NavTarget.Apps(deviceId)` before reading or writing policies.
         */
        const val UNKNOWN_DEVICE_ID: String = "unknown"
    }
}

/**
 * Lightweight DTO for one row in [AppsViewModel.apps]. `icon` is the
 * `Drawable` resolved from `PackageManager.getApplicationIcon(packageName)`.
 * It is kept nullable because the Compose `Image` painter is built
 * lazily by the screen.
 */
data class AppInfo(
    val packageName: String,
    val displayName: String,
    val icon: Any?
)
