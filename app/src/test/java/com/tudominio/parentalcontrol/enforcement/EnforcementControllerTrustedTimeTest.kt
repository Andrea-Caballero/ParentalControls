package com.tudominio.parentalcontrol.enforcement

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tudominio.parentalcontrol.admin.LockManager
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.data.db.GrantDao
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.data.model.GrantEntity
import com.tudominio.parentalcontrol.domain.DeviceState
import com.tudominio.parentalcontrol.domain.Grant
import com.tudominio.parentalcontrol.domain.GrantSource
import com.tudominio.parentalcontrol.domain.Policy
import com.tudominio.parentalcontrol.time.FakeTimeProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EnforcementControllerTrustedTimeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `unavailable time fails closed and recovery queries with trusted time`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val deviceId = MutableStateFlow<String?>("device-1")
        val provider = FakeTimeProvider(fakeElapsed = 1_000L)
        val grants = MutableStateFlow(emptyList<GrantEntity>())
        val dao = mockk<GrantDao>()
        every { dao.getActiveGrantsFlow(any(), any()) } returns grants
        val database = mockk<ParentalDatabase>(relaxed = true)
        every { database.grantDao() } returns dao
        val auth = mockk<DeviceAuthManager>(relaxed = true)
        every { auth.deviceId } returns deviceId
        val lock = mockk<LockManager>(relaxed = true)

        EnforcementController(context, database, provider, auth, lock)
        testScheduler.advanceUntilIdle()
        verify(exactly = 0) { dao.getActiveGrantsFlow(any(), any()) }

        val trusted = Instant.parse("2026-07-29T12:00:00Z")
        provider.confirmTrustedTime(trusted)
        testScheduler.advanceUntilIdle()
        verify(atLeast = 1) {
            dao.getActiveGrantsFlow("device-1", trusted.plusMillis(0).toString())
        }

        provider.loseTrustedTime()
        provider.confirmTrustedTime(trusted.plusSeconds(1))
        testScheduler.advanceUntilIdle()
        verify(atLeast = 1) {
            dao.getActiveGrantsFlow("device-1", trusted.plusSeconds(1).toString())
        }

    }

    @Test
    fun `unavailable trust blocks controller evaluation without wall clock fallback`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val provider = FakeTimeProvider(fakeElapsed = 1_000L)
        val database = mockk<ParentalDatabase>(relaxed = true)
        val auth = mockk<DeviceAuthManager>(relaxed = true)
        every { auth.deviceId } returns MutableStateFlow("device-1")
        every { database.grantDao() } returns mockk(relaxed = true)
        val controller = EnforcementController(context, database, provider, auth, mockk(relaxed = true))

        controller.setPolicy(policy(grantExpiresAt = "2026-07-29T13:00:00Z"))
        controller.evaluateAndEnforce("com.example.child")

        assertTrue(controller.decisionFlow.first() is EnforcementDecision.Blocked)
    }

    @Test
    fun `trust loss clears controller grants before recovery can revive an expired grant`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val trusted = Instant.parse("2026-07-29T12:00:00Z")
        val provider = FakeTimeProvider(fakeElapsed = 1_000L)
        provider.confirmTrustedTime(trusted)
        val initial = MutableStateFlow(listOf(grantEntity("2026-07-29T12:30:00Z")))
        val recovery = MutableSharedFlow<List<GrantEntity>>()
        val dao = mockk<GrantDao>()
        every { dao.getActiveGrantsFlow(any(), any()) } answers {
            if (arg<String>(1) == trusted.toString()) initial else recovery
        }
        val database = mockk<ParentalDatabase>(relaxed = true)
        every { database.grantDao() } returns dao
        val auth = mockk<DeviceAuthManager>(relaxed = true)
        every { auth.deviceId } returns MutableStateFlow("device-1")
        val controller = EnforcementController(context, database, provider, auth, mockk(relaxed = true))

        controller.setPolicy(policy(grantExpiresAt = "2026-07-29T12:30:00Z"))
        controller.evaluateAndEnforce("com.example.child")
        val initialDecision = controller.decisionFlow.first()
        assertTrue(initialDecision is EnforcementDecision.Allowed)

        provider.loseTrustedTime()
        assertTrue(controllerGrants(controller).isEmpty())
        assertTrue(controllerPolicy(controller).grants.isEmpty())
        provider.confirmTrustedTime(Instant.parse("2026-07-29T13:00:00Z"))
        controller.evaluateAndEnforce("com.example.child.recovered")
        testScheduler.advanceUntilIdle()
        assertTrue(controllerGrants(controller).isEmpty())
        assertTrue(controllerPolicy(controller).grants.isEmpty())
        assertTrue(
            controller.decisionFlow.first { it.packageName == "com.example.child.recovered" } is
                EnforcementDecision.Blocked
        )
    }

    private fun policy(grantExpiresAt: String) = Policy(
        device_id = "device-1",
        version = 1,
        device_state = DeviceState.DOWNTIME,
        daily_screen_time_minutes = 120,
        schedules = emptyList(),
        category_limits = emptyList(),
        app_policies = emptyList(),
        category_assignments = emptyMap(),
        grants = listOf(
            Grant(
                id = "grant-1",
                scope = "device",
                minutes = 30,
                source = GrantSource.EXTRA_TIME,
                granted_at = "2026-07-29T11:00:00Z",
                expires_at = grantExpiresAt
            )
        )
    )

    private fun grantEntity(expiresAt: String) = GrantEntity(
        id = "grant-1",
        device_id = "device-1",
        request_id = null,
        scope = "device",
        minutes = 30,
        source = "extra_time",
        granted_at = "2026-07-29T11:00:00Z",
        expires_at = expiresAt
    )

    @Suppress("UNCHECKED_CAST")
    private fun controllerGrants(controller: EnforcementController): List<Grant> =
        EnforcementController::class.java.getDeclaredField("currentGrants").run {
            isAccessible = true
            get(controller) as List<Grant>
        }

    private fun controllerPolicy(controller: EnforcementController): Policy =
        EnforcementController::class.java.getDeclaredField("currentPolicy").run {
            isAccessible = true
            get(controller) as Policy
        }
}
