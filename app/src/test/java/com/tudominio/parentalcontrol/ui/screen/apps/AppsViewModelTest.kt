package com.tudominio.parentalcontrol.ui.screen.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import com.tudominio.parentalcontrol.data.db.AppPolicyDao
import com.tudominio.parentalcontrol.data.model.AppPolicyEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [AppsViewModel].
 *
 * PR 5 of `openspec/changes/wire-pairing-and-approval-end-to-end` (task #30).
 *
 * Verifies the parent app-block UI ViewModel:
 *   - `loadInstalledApps()` queries `PackageManager.queryIntentActivities` and
 *     exposes the result as `StateFlow<List<AppInfo>>`.
 *   - `loadBlockedPackages()` observes `appPolicyDao.getAllAppPoliciesFlow()`
 *     and exposes the BLOCKED packages as `StateFlow<Set<String>>`.
 *   - `toggleBlock(packageName, block)` upserts an `AppPolicyEntity` with
 *     `state = "BLOCKED"` or `"ALLOWED"` and updates the blocked set
 *     optimistically (with rollback on DAO failure).
 *
 * Uses [mockk] for the [Context] / [PackageManager] / [AppPolicyDao]
 * collaborators so the test stays a unit test (no Robolectric PackageManager
 * shadow dance, no Room — DAO round-trip is verified via the mock).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppsViewModelTest {

    private lateinit var context: Context
    private lateinit var packageManager: PackageManager
    private lateinit var appPolicyDao: AppPolicyDao
    private lateinit var viewModel: AppsViewModel
    private lateinit var policiesFlow: MutableStateFlow<List<AppPolicyEntity>>

    companion object {
        private const val TEST_DEVICE_ID = "dev-A"
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = mockk(relaxed = true)
        packageManager = mockk(relaxed = true)
        every { context.packageManager } returns packageManager

        appPolicyDao = mockk(relaxed = false)
        policiesFlow = MutableStateFlow(emptyList())
        every { appPolicyDao.getAppPoliciesForDeviceFlow(TEST_DEVICE_ID) } returns policiesFlow
        coEvery { appPolicyDao.upsertAppPolicy(any()) } returns Unit

        viewModel = AppsViewModel(context, appPolicyDao)
        viewModel.setDeviceId(TEST_DEVICE_ID)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Builds a [ResolveInfo] whose `loadLabel(pm)` returns [label].
     *
     * Robolectric's [ResolveInfo.loadLabel] shadow falls back to
     * `nonLocalizedLabel` when set, so we use that hook instead of trying
     * to mock the final `ResolveInfo` class (which collides with mockk's
     * inline mock-maker inside the Robolectric sandbox).
     */
    private fun fakeResolveInfo(packageName: String, label: String): ResolveInfo {
        return ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                this.name = "$packageName.MainActivity"
            }
            resolvePackageName = packageName
            nonLocalizedLabel = label
        }
    }

    @Test
    fun loadInstalledApps_populates_state_flow() = runBlocking {
        // Arrange: PackageManager returns 3 ResolveInfos, each with a label.
        val ri1 = fakeResolveInfo("com.example.app1", "App One")
        val ri2 = fakeResolveInfo("com.example.app2", "App Two")
        val ri3 = fakeResolveInfo("com.example.app3", "App Three")
        every {
            packageManager.queryIntentActivities(any<Intent>(), any<Int>())
        } returns listOf(ri1, ri2, ri3)

        // Act
        viewModel.loadInstalledApps()

        // viewModelScope.launch uses Dispatchers.IO which is unaffected by
        // Dispatchers.setMain. Poll the StateFlow until the IO coroutine
        // has populated it.
        withTimeout(2000) {
            while (viewModel.apps.value.isEmpty()) {
                delay(20)
            }
        }

        // Assert
        val apps = viewModel.apps.value
        assertEquals("Expected 3 launchable apps", 3, apps.size)
        val packageNames = apps.map { it.packageName }.toSet()
        assertTrue(
            "Expected packageNames=$packageNames to contain all three apps",
            packageNames.containsAll(
                setOf("com.example.app1", "com.example.app2", "com.example.app3")
            )
        )
        assertEquals(
            setOf("App One", "App Two", "App Three"),
            apps.map { it.displayName }.toSet()
        )
    }

    @Test
    fun loadBlockedPackages_observes_dao_and_filters_BLOCKED() = runBlocking {
        // Arrange: emit two app_policies rows — one BLOCKED and one ALLOWED.
        policiesFlow.value = listOf(
            AppPolicyEntity(
                device_id = "dev-A",
                package_name = "com.example.blocked",
                state = "BLOCKED",
                daily_limit_minutes = null,
                allowed_windows = emptyList(),
                category = null
            ),
            AppPolicyEntity(
                device_id = "dev-A",
                package_name = "com.example.allowed",
                state = "ALLOWED",
                daily_limit_minutes = null,
                allowed_windows = emptyList(),
                category = null
            )
        )

        // Act
        viewModel.loadBlockedPackages()

        // Wait for the flow collector to pick up the seed value.
        withTimeout(2000) {
            while (viewModel.blockedPackages.value.isEmpty()) {
                delay(20)
            }
        }

        // Assert
        assertEquals(setOf("com.example.blocked"), viewModel.blockedPackages.value)
    }

    @Test
    fun toggleBlock_inserts_app_policy_with_correct_state() = runBlocking {
        // Act: toggle ON (block).
        viewModel.toggleBlock("com.example.app1", block = true)

        // The optimistic update is synchronous; the DAO call runs on
        // Dispatchers.IO so we wait for it.
        withTimeout(2000) {
            while (viewModel.blockedPackages.value.isEmpty()) {
                delay(20)
            }
        }
        coVerify {
            appPolicyDao.upsertAppPolicy(
                match {
                    it.package_name == "com.example.app1" &&
                        it.state == "BLOCKED"
                }
            )
        }

        // Act: toggle OFF (unblock).
        viewModel.toggleBlock("com.example.app1", block = false)
        withTimeout(2000) {
            while (viewModel.blockedPackages.value.isNotEmpty()) {
                delay(20)
            }
        }
        coVerify {
            appPolicyDao.upsertAppPolicy(
                match {
                    it.package_name == "com.example.app1" &&
                        it.state == "ALLOWED"
                }
            )
        }
    }

    @Test
    fun toggleBlock_rolls_back_on_dao_failure() = runBlocking {
        // Arrange: DAO throws on upsert.
        val failingDao = mockk<AppPolicyDao>(relaxed = false)
        every { failingDao.getAppPoliciesForDeviceFlow(TEST_DEVICE_ID) } returns flowOf(emptyList())
        coEvery { failingDao.upsertAppPolicy(any()) } throws RuntimeException("DB down")
        val vm = AppsViewModel(context, failingDao)
        vm.setDeviceId(TEST_DEVICE_ID)

        // Act: toggle ON.
        vm.toggleBlock("com.example.app1", block = true)

        // Wait long enough for the IO coroutine to fail and roll back.
        withTimeout(2000) {
            while (vm.blockedPackages.value.contains("com.example.app1")) {
                delay(20)
            }
        }

        // Assert: optimistic update was rolled back.
        assertEquals(emptySet<String>(), vm.blockedPackages.value)
        coVerify(exactly = 1) { failingDao.upsertAppPolicy(any()) }
    }

    /**
     * Per-device isolation: a VM scoped to `dev-A` MUST persist every
     * `app_policies` upsert under `device_id = "dev-A"`, never under
     * `"default"` or any other id. Regression coverage for the
     * `app-block-policy` spec scenario "Sibling devices are unaffected".
     */
    @Test
    fun toggleBlock_with_device_id_writes_to_correct_device() = runBlocking {
        // Act 1: toggle ON for dev-A.
        viewModel.toggleBlock("com.example.app", block = true)
        withTimeout(2000) {
            while (viewModel.blockedPackages.value.isEmpty()) {
                delay(20)
            }
        }
        coVerify {
            appPolicyDao.upsertAppPolicy(
                match {
                    it.device_id == "dev-A" &&
                        it.package_name == "com.example.app" &&
                        it.state == "BLOCKED"
                }
            )
        }

        // Act 2: build a fresh VM scoped to dev-B and toggle the same
        // package. The DAO MUST be called with device_id = "dev-B" —
        // not "default", not "dev-A".
        val devBPoliciesFlow = MutableStateFlow<List<AppPolicyEntity>>(emptyList())
        val devBDao = mockk<AppPolicyDao>(relaxed = false)
        every { devBDao.getAppPoliciesForDeviceFlow("dev-B") } returns devBPoliciesFlow
        coEvery { devBDao.upsertAppPolicy(any()) } returns Unit
        val devBVm = AppsViewModel(context, devBDao)
        devBVm.setDeviceId("dev-B")

        devBVm.toggleBlock("com.example.app", block = true)
        withTimeout(2000) {
            while (devBVm.blockedPackages.value.isEmpty()) {
                delay(20)
            }
        }
        coVerify {
            devBDao.upsertAppPolicy(
                match {
                    it.device_id == "dev-B" &&
                        it.package_name == "com.example.app" &&
                        it.state == "BLOCKED"
                }
            )
        }
    }

    /**
     * The device-scoped flow MUST be queried (not `getAllAppPoliciesFlow`).
     * This guarantees that policies for sibling devices never leak into
     * the UI when the parent views apps for one specific child.
     */
    @Test
    fun loadBlockedPackages_filters_by_device_id() = runBlocking {
        // Arrange: emit a dev-A policy through the dev-A flow. The VM is
        // scoped to dev-A and must use this flow (not getAllAppPoliciesFlow).
        val perDeviceFlow = MutableStateFlow(
            listOf(
                AppPolicyEntity(
                    device_id = "dev-A",
                    package_name = "com.example.blocked",
                    state = "BLOCKED",
                    daily_limit_minutes = null,
                    allowed_windows = emptyList(),
                    category = null
                )
            )
        )
        val scopedDao = mockk<AppPolicyDao>(relaxed = false)
        every { scopedDao.getAppPoliciesForDeviceFlow("dev-A") } returns perDeviceFlow
        coEvery { scopedDao.upsertAppPolicy(any()) } returns Unit
        val scopedVm = AppsViewModel(context, scopedDao)
        scopedVm.setDeviceId("dev-A")

        scopedVm.loadBlockedPackages()
        withTimeout(2000) {
            while (scopedVm.blockedPackages.value.isEmpty()) {
                delay(20)
            }
        }

        // Assert 1: only dev-A's BLOCKED package is in the set.
        assertEquals(setOf("com.example.blocked"), scopedVm.blockedPackages.value)

        // Assert 2: the VM MUST have queried the device-scoped flow
        // with its bound deviceId, and MUST NOT have touched the
        // cross-device `getAllAppPoliciesFlow()`.
        io.mockk.verify(exactly = 1) {
            scopedDao.getAppPoliciesForDeviceFlow("dev-A")
        }
        io.mockk.verify(exactly = 0) {
            scopedDao.getAllAppPoliciesFlow()
        }
    }
}
