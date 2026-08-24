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
import com.tudominio.parentalcontrol.domain.UsageContext
import com.tudominio.parentalcontrol.time.FakeTimeProvider
import app.cash.turbine.test
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
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
        val dao = mockk<GrantDao>()
        val grants = MutableStateFlow(emptyList<GrantEntity>())
        every { dao.getActiveGrantsFlow(any(), any()) } returns grants
        val database = mockk<ParentalDatabase>(relaxed = true)
        every { database.grantDao() } returns dao
        val auth = mockk<DeviceAuthManager>(relaxed = true)
        every { auth.deviceId } returns deviceId
        val lock = mockk<LockManager>(relaxed = true)

        val controller = EnforcementController(context, database, provider, auth, lock)
        testScheduler.runCurrent()
        verify(exactly = 0) { dao.getActiveGrantsFlow(any(), any()) }

        val trusted = Instant.parse("2026-07-29T12:00:00Z")
        provider.confirmTrustedTime(trusted)
        testScheduler.runCurrent()
        verify(atLeast = 1) {
            dao.getActiveGrantsFlow("device-1", trusted.plusMillis(0).toString())
        }

        provider.loseTrustedTime()
        provider.confirmTrustedTime(trusted.plusSeconds(1))
        testScheduler.runCurrent()
        verify(atLeast = 1) {
            dao.getActiveGrantsFlow("device-1", trusted.plusSeconds(1).toString())
        }
        controller.closeForTrustedTimeTest()

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
        controller.closeForTrustedTimeTest()
    }

    @Test
    fun `queued allowed decision is blocked when current grant expires before execution`() = runTest {
        val trusted = Instant.parse("2026-07-29T12:00:00Z")
        val provider = FakeTimeProvider(fakeElapsed = 1_000L)
        provider.confirmTrustedTime(trusted)
        val database = mockk<ParentalDatabase>(relaxed = true)
        val auth = mockk<DeviceAuthManager>(relaxed = true)
        every { auth.deviceId } returns MutableStateFlow(null)
        val controller = EnforcementController(
            context, database, provider, auth, mockk(relaxed = true),
            usageContextFlowProvider = { _, _ -> flowOf(UsageContext.empty()) },
            usageDateFlowProvider = { flowOf("2026-07-29") },
            controllerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        )

        controller.setPolicy(policy(grantExpiresAt = "2026-07-29T12:01:00Z"))
        controller.evaluateAndEnforce("com.example.child")
        provider.advanceTime(61_000L)
        testScheduler.runCurrent()

        assertTrue(controller.decisionFlow.replayCache.last() is EnforcementDecision.Blocked)
        controller.closeForTrustedTimeTest()
    }

    @Test
    fun `queued allowed work cannot publish Allowed after trust is lost`() = runTest {
        val provider = FakeTimeProvider(fakeElapsed = 1_000L)
        provider.confirmTrustedTime(Instant.parse("2026-07-29T12:00:00Z"))
        val controller = EnforcementController(
            context, mockk(relaxed = true), provider,
            mockk(relaxed = true) { every { deviceId } returns MutableStateFlow(null) },
            mockk(relaxed = true),
            usageContextFlowProvider = { _, _ -> flowOf(UsageContext.empty()) },
            usageDateFlowProvider = { flowOf("2026-07-29") },
            controllerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        )
        val events = mutableListOf<EnforcementDecision>()
        val collector = launch { controller.decisionFlow.collect { events += it } }
        controller.setPolicy(policy("2026-07-29T13:00:00Z"))
        controller.evaluateAndEnforce("com.example.child")
        provider.loseTrustedTime()
        testScheduler.runCurrent()
        assertTrue(events.any { it is EnforcementDecision.Blocked })
        assertTrue(events.none { it is EnforcementDecision.Allowed })
        collector.cancel()
        controller.closeForTrustedTimeTest()
    }

    @Test
    fun `queued critical non temporal Allow survives trusted time loss`() = runTest {
        val provider = FakeTimeProvider(fakeElapsed = 1_000L)
        provider.confirmTrustedTime(Instant.parse("2026-07-29T12:00:00Z"))
        val controller = EnforcementController(
            context, mockk(relaxed = true), provider,
            mockk(relaxed = true) { every { deviceId } returns MutableStateFlow(null) },
            mockk(relaxed = true),
            usageContextFlowProvider = { _, _ -> flowOf(UsageContext.empty()) },
            usageDateFlowProvider = { flowOf("2026-07-29") },
            controllerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job()),
        )
        val events = mutableListOf<EnforcementDecision>()
        val collector = launch { controller.decisionFlow.collect { events += it } }

        controller.setPolicy(policy("2026-07-29T13:00:00Z"))
        controller.evaluateAndEnforce("com.android.dialer")
        provider.loseTrustedTime()
        testScheduler.runCurrent()

        assertTrue(events.any { it == EnforcementDecision.Allowed("com.android.dialer") })
        collector.cancel()
        controller.closeForTrustedTimeTest()
    }

    @Test
    fun `recovery publishes a new decision after the pre-recovery event boundary`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val provider = FakeTimeProvider(fakeElapsed = 1_000L)
        val controller = EnforcementController(
            context, mockk(relaxed = true), provider,
            mockk(relaxed = true) { every { deviceId } returns MutableStateFlow("device-1") },
            mockk(relaxed = true)
        )
        val events = mutableListOf<EnforcementDecision>()
        val collector = launch { controller.decisionFlow.collect { events += it } }
        controller.setPolicy(policy("2026-07-29T13:00:00Z"))
        controller.evaluateAndEnforce("com.example.child")
        testScheduler.runCurrent()
        val boundary = events.size
        provider.confirmTrustedTime(Instant.parse("2026-07-29T12:00:00Z"))
        testScheduler.runCurrent()
        assertTrue(events.size > boundary)
        assertTrue(events.drop(boundary).any { it.packageName == "com.example.child" })
        collector.cancel()
        controller.closeForTrustedTimeTest()
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
        testScheduler.runCurrent()
        assertTrue(controllerGrants(controller).isEmpty())
        assertTrue(controllerPolicy(controller).grants.isEmpty())
        assertTrue(
            controller.decisionFlow.first { it.packageName == "com.example.child.recovered" } is
                EnforcementDecision.Blocked
        )
        controller.closeForTrustedTimeTest()
    }

    @Test
    fun `trust loss immediately blocks the currently effective foreground package`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val provider = FakeTimeProvider(fakeElapsed = 1_000L)
        provider.confirmTrustedTime(Instant.parse("2026-07-29T12:00:00Z"))
        val controller = EnforcementController(
            context,
            mockk(relaxed = true),
            provider,
            mockk(relaxed = true) { every { deviceId } returns MutableStateFlow(null) },
            mockk(relaxed = true),
        )
        controller.setPolicy(policy("2026-07-29T13:00:00Z"))
        controller.evaluateAndEnforce("com.example.child")
        assertTrue(controller.decisionFlow.first() is EnforcementDecision.Allowed)

        provider.loseTrustedTime()
        testScheduler.runCurrent()

        assertEquals(
            EnforcementDecision.Blocked("com.example.child", "Trusted time unavailable"),
            controller.decisionFlow.replayCache.last(),
        )
        assertTrue(controller.getStatus().isBlocked)
        controller.closeForTrustedTimeTest()
    }

    @Test
    fun `duplicate trust loss emits one immediate block`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val provider = FakeTimeProvider(fakeElapsed = 1_000L)
        provider.confirmTrustedTime(Instant.parse("2026-07-29T12:00:00Z"))
        val controller = EnforcementController(
            context,
            mockk(relaxed = true),
            provider,
            mockk(relaxed = true) { every { deviceId } returns MutableStateFlow(null) },
            mockk(relaxed = true),
        )
        val events = mutableListOf<EnforcementDecision>()
        val collector = launch { controller.decisionFlow.collect { events += it } }
        controller.setPolicy(policy("2026-07-29T13:00:00Z"))
        controller.evaluateAndEnforce("com.example.child")
        testScheduler.runCurrent()
        val beforeLoss = events.size

        provider.loseTrustedTime()
        testScheduler.runCurrent()
        val afterFirstLoss = events.size
        provider.loseTrustedTime()
        testScheduler.runCurrent()

        assertEquals(
            afterFirstLoss,
            events.size,
        )
        assertTrue(events.drop(beforeLoss).any {
            it == EnforcementDecision.Blocked("com.example.child", "Trusted time unavailable")
        })
        collector.cancel()
        controller.closeForTrustedTimeTest()
    }

    @Test
    fun `trusted time loss executes one blocking side effect`() = runTest {
        val provider = FakeTimeProvider(fakeElapsed = 1_000L)
        provider.confirmTrustedTime(Instant.parse("2026-07-29T12:00:00Z"))
        val blockingContext = mockk<Context>(relaxed = true)
        val grants = MutableStateFlow(listOf(grantEntity("2026-07-29T13:00:00Z")))
        val dao = mockk<GrantDao>()
        every { dao.getActiveGrantsFlow(any(), any()) } returns grants
        val database = mockk<ParentalDatabase>(relaxed = true)
        every { database.grantDao() } returns dao
        val controller = EnforcementController(blockingContext, database, provider,
            mockk(relaxed = true) { every { deviceId } returns MutableStateFlow("device-1") },
            mockk(relaxed = true), controllerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job()))
        controller.setPolicy(policy("2026-07-29T13:00:00Z"))
        controller.evaluateAndEnforce("com.example.child")
        provider.loseTrustedTime()
        testScheduler.runCurrent()
        verify(exactly = 1) { blockingContext.startActivity(any()) }
        controller.closeForTrustedTimeTest()
    }

    @Test
    fun `non temporal blocked policy still evaluates while trusted time is unavailable`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val provider = FakeTimeProvider(fakeElapsed = 1_000L)
        val controller = EnforcementController(
            context,
            mockk(relaxed = true),
            provider,
            mockk(relaxed = true) { every { deviceId } returns MutableStateFlow(null) },
            mockk(relaxed = true),
        )
        controller.setPolicy(
            policy("2026-07-29T13:00:00Z").copy(
                grants = emptyList(),
                app_policies = listOf(
                    com.tudominio.parentalcontrol.domain.AppPolicy(
                        package_name = "com.example.child",
                        state = com.tudominio.parentalcontrol.domain.AppPolicyState.BLOCKED,
                    ),
                ),
            ),
        )
        controller.evaluateAndEnforce("com.example.child")

        assertEquals(
            EnforcementDecision.Blocked("com.example.child", "Esta app está bloqueada."),
            controller.decisionFlow.first(),
        )
        controller.closeForTrustedTimeTest()
    }

    @Test
    fun `non temporal allowed policy still allows while trusted time is unavailable`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val provider = FakeTimeProvider(fakeElapsed = 1_000L)
        val controller = EnforcementController(
            context,
            mockk(relaxed = true),
            provider,
            mockk(relaxed = true) { every { deviceId } returns MutableStateFlow(null) },
            mockk(relaxed = true),
        )

        controller.setPolicy(policy("2026-07-29T13:00:00Z"))
        controller.evaluateAndEnforce("com.android.dialer")

        assertEquals(
            EnforcementDecision.Allowed("com.android.dialer"),
            controller.decisionFlow.first(),
        )
        controller.closeForTrustedTimeTest()
    }

    @Test
    fun `trust loss without a tracked foreground package only invalidates state`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val provider = FakeTimeProvider(fakeElapsed = 1_000L)
        provider.confirmTrustedTime(Instant.parse("2026-07-29T12:00:00Z"))
        val controller = EnforcementController(
            context,
            mockk(relaxed = true),
            provider,
            mockk(relaxed = true) { every { deviceId } returns MutableStateFlow(null) },
            mockk(relaxed = true),
        )
        val events = mutableListOf<EnforcementDecision>()
        val collector = launch { controller.decisionFlow.collect { events += it } }

        provider.loseTrustedTime()
        testScheduler.runCurrent()

        assertTrue(events.isEmpty())
        collector.cancel()
        controller.closeForTrustedTimeTest()
    }

    @Test
    fun `room emission that is expired at trusted time is discarded before publication`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val trusted = Instant.parse("2026-07-29T12:00:00Z")
        val provider = FakeTimeProvider(fakeElapsed = 1_000L)
        provider.confirmTrustedTime(trusted)
        val emissions = MutableStateFlow(listOf(grantEntity("2026-07-29T11:00:00Z")))
        val dao = mockk<GrantDao>()
        every { dao.getActiveGrantsFlow(any(), any()) } returns emissions
        val database = mockk<ParentalDatabase>(relaxed = true)
        every { database.grantDao() } returns dao
        val auth = mockk<DeviceAuthManager>(relaxed = true)
        every { auth.deviceId } returns MutableStateFlow("device-1")
        val controller = EnforcementController(context, database, provider, auth, mockk(relaxed = true))

        testScheduler.runCurrent()

        assertTrue(controllerGrants(controller).isEmpty())
        controller.closeForTrustedTimeTest()
    }

    @Test
    fun `trusted time recovery reevaluates the last package without another foreground event`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val provider = FakeTimeProvider(fakeElapsed = 1_000L)
        val database = mockk<ParentalDatabase>(relaxed = true)
        val auth = mockk<DeviceAuthManager>(relaxed = true)
        every { auth.deviceId } returns MutableStateFlow("device-1")
        every { database.grantDao() } returns mockk(relaxed = true)
        val controller = EnforcementController(context, database, provider, auth, mockk(relaxed = true))
        controller.decisionFlow.test {
            controller.setPolicy(policy(grantExpiresAt = "2026-07-29T11:30:00Z"))
            controller.evaluateAndEnforce("com.example.child")
            assertTrue(awaitItem() is EnforcementDecision.Blocked)

            provider.confirmTrustedTime(Instant.parse("2026-07-29T12:00:00Z"))
            testScheduler.runCurrent()

            assertTrue(awaitItem() is EnforcementDecision.Blocked)
            expectNoEvents()
        }
        controller.closeForTrustedTimeTest()
    }

    @Test
    fun `room recovery initial emission and recovery callback produce one reevaluation`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val provider = FakeTimeProvider(fakeElapsed = 1_000L)
        val grants = MutableStateFlow(emptyList<GrantEntity>())
        val dao = mockk<GrantDao>()
        every { dao.getActiveGrantsFlow(any(), any()) } returns grants
        val database = mockk<ParentalDatabase>(relaxed = true)
        every { database.grantDao() } returns dao
        val auth = mockk<DeviceAuthManager>(relaxed = true)
        every { auth.deviceId } returns MutableStateFlow("device-1")
        val controller = EnforcementController(
            context,
            database,
            provider,
            auth,
            mockk(relaxed = true),
            controllerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job()),
        )
        val events = mutableListOf<EnforcementDecision>()
        val collector = launch { controller.decisionFlow.collect { events += it } }
        try {
            controller.setPolicy(policy(grantExpiresAt = "2026-07-29T11:30:00Z"))
            controller.evaluateAndEnforce("com.example.child")
            testScheduler.runCurrent()
            val beforeRecovery = events.size

            provider.confirmTrustedTime(Instant.parse("2026-07-29T12:00:00Z"))
            testScheduler.runCurrent()

            assertEquals(1, events.size - beforeRecovery)
            assertTrue(events[beforeRecovery] is EnforcementDecision.Blocked)
            assertEquals("com.example.child", events[beforeRecovery].packageName)
        } finally {
            collector.cancel()
            controller.closeForTrustedTimeTest()
        }
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

private fun EnforcementController.closeForTrustedTimeTest() {
    javaClass.getDeclaredField("controllerScope").apply {
        isAccessible = true
        (get(this@closeForTrustedTimeTest) as CoroutineScope).coroutineContext.cancel()
    }
}
