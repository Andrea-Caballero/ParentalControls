package com.tudominio.parentalcontrol.ui.screen.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.tudominio.parentalcontrol.data.db.AppPolicyDao
import com.tudominio.parentalcontrol.data.model.AppPolicyEntity
import com.tudominio.parentalcontrol.ui.theme.ParentalControlTheme
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI tests for [AppsScreen].
 *
 * PR 5 of `openspec/changes/wire-pairing-and-approval-end-to-end` (task #31).
 *
 * Verifies the parent app-block list screen:
 *   - Renders every [AppInfo] from `viewModel.apps` as a row.
 *   - Tapping the per-row switch invokes `viewModel.toggleBlock(packageName, true)`.
 *
 * The [AppsViewModel] uses a fully-mocked [Context] / [PackageManager] /
 * [AppPolicyDao] so the test doesn't depend on Robolectric's PackageManager
 * shadow or a real Room database. Per-row switches are tagged
 * `apps_switch_<packageName>` so the test can target them without depending
 * on Material3's internal Switch content description.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var packageManager: PackageManager
    private lateinit var appPolicyDao: AppPolicyDao
    private lateinit var policiesFlow: MutableStateFlow<List<AppPolicyEntity>>
    private lateinit var viewModel: AppsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = mockk(relaxed = true)
        packageManager = mockk(relaxed = true)
        every { context.packageManager } returns packageManager

        appPolicyDao = mockk(relaxed = false)
        policiesFlow = MutableStateFlow(emptyList())
        every { appPolicyDao.getAppPoliciesForDeviceFlow("dev-A") } returns policiesFlow
        coEvery { appPolicyDao.upsertAppPolicy(any()) } returns Unit

        viewModel = AppsViewModel(context, appPolicyDao)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Builds a [ResolveInfo] whose `loadLabel(pm)` returns [label].
     * Uses `nonLocalizedLabel` because Robolectric's [ResolveInfo.loadLabel]
     * shadow falls back to it, and the field is settable on a real
     * instance (mocking the final [ResolveInfo] collides with mockk's
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

    /**
     * Sets up [packageManager] to return [apps] from `queryIntentActivities`.
     */
    private fun stubPackageManager(apps: List<ResolveInfo>) {
        every { packageManager.queryIntentActivities(any<Intent>(), any<Int>()) } returns apps
    }

    /**
     * `AppsScreen` MUST bind the ViewModel to the supplied `deviceId` so
     * that the device-scoped DAO query and every `toggleBlock` write
     * target the right child device. Regression coverage for the
     * `app-block-policy` spec scenario "AppsScreen honors the incoming
     * device id".
     */
    @Test
    fun apps_screen_binds_device_id_to_viewmodel() = runBlocking {
        val customDeviceId = "child-tablet-9"
        val customFlow = MutableStateFlow<List<AppPolicyEntity>>(emptyList())
        val customDao = mockk<AppPolicyDao>(relaxed = false)
        every { customDao.getAppPoliciesForDeviceFlow(customDeviceId) } returns customFlow
        coEvery { customDao.upsertAppPolicy(any()) } returns Unit
        val screenVm = AppsViewModel(context, customDao)

        composeTestRule.setContent {
            ParentalControlTheme {
                AppsScreen(
                    deviceId = customDeviceId,
                    viewModel = screenVm,
                    onBack = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        assertEquals(
            "AppsScreen must bind the supplied deviceId onto its ViewModel",
            customDeviceId,
            screenVm.deviceId()
        )
    }

    @Test
    fun apps_screen_shows_list_of_apps() {
        runBlocking {
            stubPackageManager(
                listOf(
                    fakeResolveInfo("com.example.app1", "App One"),
                    fakeResolveInfo("com.example.app2", "App Two"),
                    fakeResolveInfo("com.example.app3", "App Three")
                )
            )

            composeTestRule.setContent {
                ParentalControlTheme {
                    AppsScreen(
                        deviceId = "dev-A",
                        viewModel = viewModel,
                        onBack = {}
                    )
                }
            }

            // The LaunchedEffect calls loadInstalledApps() which kicks off
            // a Dispatchers.IO coroutine. Poll until the StateFlow is
            // populated.
            withTimeout(2000) {
                while (viewModel.apps.value.isEmpty()) {
                    delay(20)
                }
            }
            composeTestRule.waitForIdle()

            // All three switches (one per app) exist.
            composeTestRule.onNodeWithTag("apps_switch_com.example.app1").assertExists()
            composeTestRule.onNodeWithTag("apps_switch_com.example.app2").assertExists()
            composeTestRule.onNodeWithTag("apps_switch_com.example.app3").assertExists()
        }
    }

    @Test
    fun apps_screen_toggle_block_calls_viewmodel() {
        runBlocking {
            stubPackageManager(
                listOf(fakeResolveInfo("com.example.app1", "App One"))
            )

            composeTestRule.setContent {
                ParentalControlTheme {
                    AppsScreen(
                        deviceId = "dev-A",
                        viewModel = viewModel,
                        onBack = {}
                    )
                }
            }

            withTimeout(2000) {
                while (viewModel.apps.value.isEmpty()) {
                    delay(20)
                }
            }
            composeTestRule.waitForIdle()

            // Tap the switch on the first app row.
            composeTestRule
                .onNodeWithTag("apps_switch_com.example.app1")
                .assertExists()
                .performClick()

            // After click, the optimistic update should add the package
            // to the blocked set. Poll until that propagates.
            withTimeout(2000) {
                while (!viewModel.blockedPackages.value.contains("com.example.app1")) {
                    delay(20)
                }
            }
            composeTestRule.waitForIdle()
        }
    }
}
