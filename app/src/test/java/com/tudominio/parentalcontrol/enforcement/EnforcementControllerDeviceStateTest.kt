package com.tudominio.parentalcontrol.enforcement

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tudominio.parentalcontrol.admin.LockManager
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.data.model.PolicyEntity
import com.tudominio.parentalcontrol.time.DefaultTimeProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Smoke test: the `EnforcementController` MUST observe the row for
 * the REAL device id (not the legacy `"default"` literal). After this
 * fix, the child app picks up the parent's `set-device-state → LOCKED`
 * and `LockManager.lockNow()` fires.
 *
 * Pre-fix this test would have failed: the controller queried
 * `getPolicyFlow("default")` and the LOCKED row for `"dev-real"`
 * would not have been observed, so `lockNow()` was never called.
 *
 * Additional cases (ACTIVE no-op, lastAppliedDeviceState dedup
 * transitions, default-vs-real row precedence) are intentionally
 * deferred to a follow-up work unit that adds a deterministic Room
 * InvalidationTracker helper — the current Robolectric + virtual
 * time combination has a known race with the InvalidationTracker's
 * `background executor` that the existing test scope cannot await
 * deterministically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EnforcementControllerDeviceStateTest {

    private lateinit var database: ParentalDatabase
    private lateinit var context: Context
    private lateinit var lockManager: LockManager

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(
            context, ParentalDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        runCatching { database.close() }
        Dispatchers.resetMain()
    }

    @Test
    fun `lockNow fires when deviceState is LOCKED for the real device id`() = runTest {
        val deviceId = MutableStateFlow<String?>(null)
        // Seed the LOCKED row for the REAL device id BEFORE the
        // controller starts observing.
        database.policyDao().insertPolicy(
            PolicyEntity(
                device_id = "dev-real",
                version = 6L,
                category_assignments = emptyMap(),
                device_state = "LOCKED"
            )
        )
        val am = mockk<DeviceAuthManager>(relaxed = true)
        every { am.deviceId } returns deviceId
        every { am.isPaired() } returns (deviceId.value != null)
        lockManager = mockk<LockManager>(relaxed = true)
        every { lockManager.isAdminActive() } returns true
        val controller = EnforcementController(
            context = context,
            database = database,
            timeProvider = DefaultTimeProvider(context),
            authManager = am,
            lockManager = lockManager,
        )
        deviceId.value = "dev-real"
        testScheduler.advanceUntilIdle()
        verify(atLeast = 1) { lockManager.lockNow() }
    }
}
