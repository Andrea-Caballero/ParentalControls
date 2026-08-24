package com.tudominio.parentalcontrol.enforcement

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tudominio.parentalcontrol.admin.LockManager
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.data.model.PolicyEntity
import com.tudominio.parentalcontrol.domain.CategoryLimit
import com.tudominio.parentalcontrol.domain.DayOfWeek
import com.tudominio.parentalcontrol.domain.Schedule
import com.tudominio.parentalcontrol.domain.ScheduleAction
import com.tudominio.parentalcontrol.time.DefaultTimeProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

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
    private val directExecutor = Executor { it.run() }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(
            context, ParentalDatabase::class.java
        )
            .allowMainThreadQueries()
            .setQueryExecutor(directExecutor)
            .setTransactionExecutor(directExecutor)
            .build()
    }

    @After
    fun tearDown() {
        runCatching { database.close() }
        Dispatchers.resetMain()
    }

    @Test
    fun `live reconstruction reads persisted policy fields`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val deviceId = MutableStateFlow<String?>("dev-policy")
        database.policyDao().insertPolicy(
            PolicyEntity(
                device_id = "dev-policy",
                version = 9L,
                category_assignments = emptyMap(),
                daily_screen_time_minutes = 37,
                schedules = listOf(Schedule("sleep", listOf(DayOfWeek.FRIDAY), "22:00", "07:00", ScheduleAction.LOCK)),
                category_limits = listOf(CategoryLimit("social", 12)),
            ),
        )
        val auth = mockk<DeviceAuthManager>(relaxed = true)
        every { auth.deviceId } returns deviceId
        val controller = EnforcementController(
            context, database, DefaultTimeProvider(context), auth, mockk(relaxed = true),
        )
        testScheduler.advanceUntilIdle()

        val policy = controller.javaClass.getDeclaredField("currentPolicy").run {
            isAccessible = true
            get(controller) as com.tudominio.parentalcontrol.domain.Policy
        }
        assertEquals(37, policy.daily_screen_time_minutes)
        assertEquals("sleep", policy.schedules.single().id)
        assertEquals(com.tudominio.parentalcontrol.domain.CategoryLimit("social", 12), policy.category_limits.single())
    }

    @Test
    fun `lockNow fires when deviceState is LOCKED for the real device id`() = runTest {
        // Align Dispatchers.Main with runTest's testScheduler so that
        // `advanceUntilIdle()` drains the controllerScope launches on
        // Main. The previous field-level `UnconfinedTestDispatcher()`
        // created its own scheduler independent of runTest's, which
        // made the test fail in isolation (the controller's launches
        // stayed parked on a scheduler the test never advanced) and
        // pass only when prior tests in the same JVM had warmed up
        // the InvalidationTracker / executor state. Pinning the
        // dispatcher and giving Room direct executors removes the
        // order dependence.
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
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
        val mockLock = mockk<LockManager>(relaxed = true)
        val lockCallCount = AtomicInteger()
        every { mockLock.isAdminActive() } returns true
        every { mockLock.lockNow() } answers {
            lockCallCount.incrementAndGet()
            true
        }
        val controller = EnforcementController(
            context = context,
            database = database,
            timeProvider = DefaultTimeProvider(context),
            authManager = am,
            lockManager = mockLock,
        )
        deviceId.value = "dev-real"
        testScheduler.advanceUntilIdle()
        assertEquals(1, lockCallCount.get())
    }
}
