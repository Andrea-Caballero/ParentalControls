package com.tudominio.parentalcontrol.ui.child.status

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tudominio.parentalcontrol.admin.DeviceAdminPromptCoordinator
import com.tudominio.parentalcontrol.admin.DeviceAdminPromptState
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.health.DegradationAlertManager
import com.tudominio.parentalcontrol.reward.RewardManager
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Direct ChildStatusViewModel coverage for syncDeviceAdminState. The
// coordinator is in-memory and restarts Idle after process death; these
// tests pin the four branches (Idle/Dismissed × active/inactive) that
// the banner relies on at every cold start.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChildStatusViewModelAdminBannerSyncTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var context: Context
    private lateinit var database: ParentalDatabase
    private lateinit var rewardManager: RewardManager
    private lateinit var degradationAlertManager: DegradationAlertManager

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, ParentalDatabase::class.java)
            .allowMainThreadQueries().build()
        rewardManager = mockk(relaxed = true)
        degradationAlertManager = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        database.close()
    }

    private fun newVm(coordinator: DeviceAdminPromptCoordinator) = ChildStatusViewModel(
        context = context,
        database = database,
        rewardManager = rewardManager,
        degradationAlertManager = degradationAlertManager,
        adminCoordinator = coordinator
    )

    @Test fun idle_inactive_promotes_to_NeedsActivation_and_banner_true() = runTest {
        val coordinator = DeviceAdminPromptCoordinator()
        val vm = newVm(coordinator)
        try {
            vm.syncDeviceAdminState(active = false)
            assertEquals(DeviceAdminPromptState.NeedsActivation, coordinator.state.value)
            assertTrue(vm.deviceAdminBanner.value)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun dismissed_inactive_preserves_Dismissed_and_banner_true() = runTest {
        val coordinator = DeviceAdminPromptCoordinator().apply {
            recordFreshPairing(); markSkipped()
        }
        val vm = newVm(coordinator)
        try {
            vm.syncDeviceAdminState(active = false)
            val state = coordinator.state.value
            assertTrue(state is DeviceAdminPromptState.Dismissed)
            assertTrue((state as DeviceAdminPromptState.Dismissed).skipUsed)
            assertTrue(vm.deviceAdminBanner.value)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun needsActivation_active_clears_to_Idle_and_banner_false() = runTest {
        val coordinator = DeviceAdminPromptCoordinator().apply { recordFreshPairing() }
        val vm = newVm(coordinator)
        try {
            vm.syncDeviceAdminState(active = true)
            assertEquals(DeviceAdminPromptState.Idle, coordinator.state.value)
            assertFalse(vm.deviceAdminBanner.value)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun dismissed_active_clears_to_Idle_and_banner_false() = runTest {
        val coordinator = DeviceAdminPromptCoordinator().apply {
            recordFreshPairing(); markSkipped()
        }
        val vm = newVm(coordinator)
        try {
            vm.syncDeviceAdminState(active = true)
            assertEquals(DeviceAdminPromptState.Idle, coordinator.state.value)
            assertFalse(vm.deviceAdminBanner.value)
        } finally { vm.viewModelScope.cancel() }
    }
}
