package com.tudominio.parentalcontrol.ui.screen.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import com.tudominio.parentalcontrol.data.db.AppPolicyDao
import com.tudominio.parentalcontrol.data.model.AppPolicyEntity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WU-3 RED→GREEN tests for [AppsViewModel.loadInstalledApps]
 * deduplicating OPPO dual-SIM launcher activities.
 *
 * # Bug
 *
 * OPPO dual-SIM devices ship `com.android.stk` with TWO launcher
 * activities (one per SIM slot). Pre-fix, `loadInstalledApps` mapped
 * both into [AppInfo] with `packageName = "com.android.stk"` and the
 * Compose `LazyColumn` crashed with
 * `IllegalArgumentException: Key "com.android.stk" was already used`.
 *
 * # Fix shape
 *
 * Deduplicate at the data source: `distinctBy { activityInfo.packageName }`
 * before mapping to [AppInfo]. The contract is "one row per package
 * regardless of how many launcher activities the OS exposes".
 *
 * Uses a mocked `Context` / `PackageManager` (same pattern as
 * `AppsScreenTest`) so the test doesn't depend on Robolectric's
 * `PackageManager` shadow — the dedup happens after the round-trip
 * to PackageManager, in our pure code path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppsViewModelLauncherDeduplicationTest {

    private lateinit var context: Context
    private lateinit var packageManager: PackageManager
    private lateinit var appPolicyDao: AppPolicyDao
    private lateinit var viewModel: AppsViewModel
    private lateinit var policiesFlow: MutableStateFlow<List<AppPolicyEntity>>

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = mockk(relaxed = true)
        packageManager = mockk(relaxed = true)
        every { context.packageManager } returns packageManager

        appPolicyDao = mockk(relaxed = false)
        policiesFlow = MutableStateFlow(emptyList())
        every { appPolicyDao.getAppPoliciesForDeviceFlow(any()) } returns policiesFlow
        coEvery { appPolicyDao.upsertAppPolicy(any()) } returns Unit

        viewModel = AppsViewModel(context, appPolicyDao)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

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

    private fun stubPackageManager(apps: List<ResolveInfo>) {
        every { packageManager.queryIntentActivities(any<Intent>(), any<Int>()) } returns apps
    }

    /**
     * RED on master: two launcher activities for the same package
     * must collapse to one AppInfo. Pre-fix [AppsViewModel.loadInstalledApps]
     * produced two rows with identical `packageName`, breaking Compose's
     * `key = { it.packageName }`.
     *
     * The test polls the StateFlow for the deduped list (the
     * `LaunchedEffect` runs `loadInstalledApps` async) so the assertion
     * observes the production code path rather than mocking the
     * result.
     */
    @Test
    fun twoLauncherActivitiesForStk_collapseToOneAppInfo() = runBlocking {
        stubPackageManager(
            listOf(
                fakeResolveInfo("com.android.stk", "SIM Toolkit"),
                fakeResolveInfo("com.android.stk", "SIM Toolkit"),
                fakeResolveInfo("com.whatsapp", "WhatsApp")
            )
        )

        viewModel.loadInstalledApps()

        // Wait for the IO coroutine to populate the StateFlow.
        withTimeout(2000) {
            while (viewModel.apps.value.isEmpty()) {
                delay(20)
            }
        }

        val apps = viewModel.apps.value
        assertEquals(
            "Two launcher activities for com.android.stk must collapse " +
                "to a single AppInfo row (otherwise Compose's " +
                "`key = { it.packageName }` throws " +
                "`IllegalArgumentException: Key was already used`). " +
                "Got: ${apps.size} rows, packageNames=${apps.map { it.packageName }}",
            2,
            apps.size
        )
        val packageNames = apps.map { it.packageName }.toSet()
        assertEquals(
            "Dedup must keep com.android.stk AND com.whatsapp " +
                "(the third distinct package). Got: $packageNames",
            setOf("com.android.stk", "com.whatsapp"),
            packageNames
        )
    }

    /**
     * Deterministic display-name selection: when two launcher
     * activities expose the same package, the first match wins (so
     * the display name is stable across recompositions). The OPPO
     * bug report notes the dual-SIM launcher exposes two activities
     * with the same package name; the first one's display name must
     * be the one shown in the parent UI.
     */
    @Test
    fun duplicateLauncherActivities_firstLabelWins() = runBlocking {
        stubPackageManager(
            listOf(
                fakeResolveInfo("com.android.stk", "SIM Toolkit"),
                fakeResolveInfo("com.android.stk", "STK backup label"),
            )
        )

        viewModel.loadInstalledApps()

        withTimeout(2000) {
            while (viewModel.apps.value.isEmpty()) {
                delay(20)
            }
        }

        val stk = viewModel.apps.value.firstOrNull { it.packageName == "com.android.stk" }
        assertEquals(
            "First match's label must win for determinism. Got: " +
                "${stk?.displayName}",
            "SIM Toolkit",
            stk?.displayName
        )
    }
}
