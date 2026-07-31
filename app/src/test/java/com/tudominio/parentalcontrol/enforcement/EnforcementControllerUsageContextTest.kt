package com.tudominio.parentalcontrol.enforcement

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tudominio.parentalcontrol.admin.LockManager
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.data.db.AppPolicyDao
import com.tudominio.parentalcontrol.data.db.GrantDao
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.data.db.PolicyDao
import com.tudominio.parentalcontrol.domain.AppPolicy
import com.tudominio.parentalcontrol.domain.AppPolicyState
import com.tudominio.parentalcontrol.domain.CategoryLimit
import com.tudominio.parentalcontrol.domain.Decision
import com.tudominio.parentalcontrol.domain.DeviceState
import com.tudominio.parentalcontrol.domain.Policy
import com.tudominio.parentalcontrol.domain.UsageContext
import com.tudominio.parentalcontrol.domain.evaluar
import com.tudominio.parentalcontrol.time.FakeTimeProvider
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EnforcementControllerUsageContextTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `evaluation before first usage emission uses empty then first emission becomes authoritative`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val usage = MutableStateFlow(UsageContext.empty())
        val controller = controller(flowProvider = { _, _ -> usage })
        controller.setPolicy(policy(appLimit = 10))
        controller.evaluateAndEnforce(APP)
        assertTrue(controller.decisionFlow.replayCache.single() is EnforcementDecision.Allowed)

        usage.value = UsageContext(usagePorApp = mapOf(APP to 10))
        advanceUntilIdle()
        assertEquals(10, controller.currentUsageForTest().usagePorApp[APP])
        assertEquals(10, controller.policyForTest().app_policies.single().daily_limit_minutes)
        assertTrue(controller.decisionForTest() is Decision.Bloquear)
    }

    @Test
    fun `app category and global persisted minutes are applied to decisions`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val usage = UsageContext(
            usagePorApp = mapOf(APP to 10),
            usagePorCategoria = mapOf(CATEGORY to 20),
            tiempoGlobal = 30,
        )

        val appController = controller(flowProvider = { _, _ -> flowOf(usage) })
        appController.setPolicy(policy(appLimit = 10))
        appController.evaluateAndEnforce(APP)
        assertTrue(appController.decisionForTest() is Decision.Bloquear)

        val categoryController = controller(flowProvider = { _, _ -> flowOf(usage.copy(usagePorApp = emptyMap())) })
        categoryController.setPolicy(policy(categoryLimit = 20))
        categoryController.evaluateAndEnforce(APP)
        assertTrue(categoryController.decisionForTest() is Decision.Bloquear)

        val globalController = controller(flowProvider = { _, _ -> flowOf(usage.copy(usagePorApp = emptyMap(), usagePorCategoria = emptyMap())) })
        globalController.setPolicy(policy(globalLimit = 30))
        globalController.evaluateAndEnforce(APP)
        assertTrue(globalController.decisionForTest() is Decision.Bloquear)
    }

    @Test
    fun `blank identity suppresses provider and authenticated selection uses current date`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val identity = MutableStateFlow<String?>(null)
        val calls = mutableListOf<Pair<String, String>>()
        val provider: (String, String) -> Flow<UsageContext> = { id, date ->
            calls += id to date
            flowOf(UsageContext.empty())
        }
        val controller = controller(identity = identity, flowProvider = provider)
        advanceUntilIdle()
        assertTrue(calls.isEmpty())

        identity.value = DEVICE
        advanceUntilIdle()
        assertEquals(listOf(DEVICE to LocalDate.of(2026, 7, 30).toString()), calls)
        controller.closeForTest()
    }

    @Test
    fun `identity change restarts usage collection and cancels the previous identity`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val identity = MutableStateFlow<String?>("device-one")
        val first = MutableSharedFlow<UsageContext>(extraBufferCapacity = 1)
        val second = MutableSharedFlow<UsageContext>(extraBufferCapacity = 1)
        val calls = AtomicInteger()
        val controller = controller(identity) { id, _ ->
            calls.incrementAndGet()
            if (id == "device-one") first else second
        }
        advanceUntilIdle()
        identity.value = "device-two"
        advanceUntilIdle()
        assertEquals(2, calls.get())
        controller.closeForTest()
    }

    @Test
    fun `duplicate usage snapshots do not trigger duplicate reevaluation`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val usage = MutableStateFlow(UsageContext.empty())
        val controller = controller(flowProvider = { _, _ -> usage })
        controller.setPolicy(policy(appLimit = 10))
        controller.evaluateAndEnforce(APP)
        advanceUntilIdle()
        val snapshot = UsageContext(usagePorApp = mapOf(APP to 10))
        usage.value = snapshot
        advanceUntilIdle()
        assertTrue(controller.decisionForTest() is Decision.Bloquear)
        usage.value = snapshot
        advanceUntilIdle()
        assertEquals(snapshot, controller.currentUsageForTest())
    }

    @Test
    fun `date rollover resubscribes to provider with the new date key`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val dates = MutableStateFlow("2026-07-30")
        val calls = mutableListOf<Pair<String, String>>()
        val byDate = mapOf(
            "2026-07-30" to UsageContext(usagePorApp = mapOf(APP to 5)),
            "2026-07-31" to UsageContext(usagePorApp = mapOf(APP to 50)),
        )
        val provider: (String, String) -> Flow<UsageContext> = { id, date ->
            calls += id to date
            flowOf(byDate[date] ?: UsageContext.empty())
        }
        val controller = controller(
            flowProvider = provider,
            dateFlow = { dates },
        )
        controller.setPolicy(policy(appLimit = 10))
        advanceUntilIdle()
        assertEquals(listOf(DEVICE to "2026-07-30"), calls)

        // Simulate midnight: date flow advances to the new day. The controller must
        // re-subscribe to the provider with the new date key so post-midnight usage
        // is observed instead of yesterday's.
        dates.value = "2026-07-31"
        advanceUntilIdle()
        assertEquals(
            listOf(DEVICE to "2026-07-30", DEVICE to "2026-07-31"),
            calls,
        )
        // The new-day usage (50 minutes) exceeds the 10-minute app limit, so the
        // forced reevaluation must observe it and produce a Bloquear decision.
        assertTrue(controller.decisionForTest() is Decision.Bloquear)
        controller.closeForTest()
    }

    @Test
    fun `identity change does not synchronously clear usage cache before new provider emits`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val identity = MutableStateFlow<String?>("device-one")
        val firstUsage = MutableStateFlow(UsageContext(usagePorApp = mapOf(APP to 100)))
        val secondUsage = MutableSharedFlow<UsageContext>(extraBufferCapacity = 1)
        val provider: (String, String) -> Flow<UsageContext> = { id, _ ->
            if (id == "device-one") firstUsage else secondUsage
        }
        val controller = controller(identity = identity, flowProvider = provider)
        advanceUntilIdle()
        assertEquals(100, controller.currentUsageForTest().usagePorApp[APP])

        // Switch to a new identity whose provider is a SharedFlow that we never emit from.
        // The previous identity's authoritative cache must be retained until the new
        // provider actually emits — clearing it synchronously would expose a fail-open
        // window where enforcement reevaluates against an empty context.
        identity.value = "device-two"
        advanceUntilIdle()
        assertEquals(100, controller.currentUsageForTest().usagePorApp[APP])
        controller.closeForTest()
    }

    private fun controller(
        identity: MutableStateFlow<String?> = MutableStateFlow(DEVICE),
        dateFlow: () -> Flow<String> = { MutableStateFlow(LocalDate.of(2026, 7, 30).toString()) },
        flowProvider: (String, String) -> Flow<UsageContext>,
    ): EnforcementController {
        val auth = mockk<DeviceAuthManager>(relaxed = true)
        every { auth.deviceId } returns identity
        val database = mockk<ParentalDatabase>(relaxed = true)
        val policyDao = mockk<PolicyDao>(relaxed = true)
        val appPolicyDao = mockk<AppPolicyDao>(relaxed = true)
        val grantDao = mockk<GrantDao>(relaxed = true)
        every { database.policyDao() } returns policyDao
        every { database.appPolicyDao() } returns appPolicyDao
        every { database.grantDao() } returns grantDao
        every { policyDao.getPolicyFlow(any()) } returns emptyFlow()
        every { appPolicyDao.getAppPoliciesForDeviceFlow(any()) } returns emptyFlow()
        every { grantDao.getActiveGrantsFlow(any(), any()) } returns emptyFlow()
        val timeProvider = FakeTimeProvider(fakeServerDate = LocalDate.of(2026, 7, 30))
        timeProvider.confirmTrustedTime(java.time.Instant.parse("2026-07-30T12:00:00Z"))
        return EnforcementController(
            context = context,
            database = database,
            timeProvider = timeProvider,
            authManager = auth,
            lockManager = mockk<LockManager>(relaxed = true),
            usageContextFlowProvider = flowProvider,
            usageDateFlowProvider = dateFlow,
            controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }

    private fun policy(
        appLimit: Int? = null,
        categoryLimit: Int? = null,
        globalLimit: Int = 120,
    ) = Policy(
        device_id = DEVICE,
        version = 1,
        device_state = DeviceState.ACTIVE,
        daily_screen_time_minutes = globalLimit,
        schedules = emptyList(),
        category_limits = if (categoryLimit == null) emptyList() else listOf(CategoryLimit(CATEGORY, categoryLimit)),
        app_policies = listOf(AppPolicy(APP, AppPolicyState.LIMITED, appLimit ?: 1, category = CATEGORY)),
        category_assignments = mapOf(APP to CATEGORY),
        grants = emptyList(),
    )

    private companion object {
        const val APP = "com.example.child"
        const val CATEGORY = "games"
        const val DEVICE = "device-1"
    }
}

private fun EnforcementController.closeForTest() {
    javaClass.getDeclaredField("controllerScope").apply {
        isAccessible = true
        (get(this@closeForTest) as CoroutineScope).coroutineContext.cancel()
    }
}

private fun EnforcementController.currentUsageForTest(): UsageContext =
    javaClass.getDeclaredField("currentUsage").run {
        isAccessible = true
        get(this@currentUsageForTest) as UsageContext
    }

private fun EnforcementController.policyForTest(): com.tudominio.parentalcontrol.domain.Policy =
    javaClass.getDeclaredField("currentPolicy").run {
        isAccessible = true
        get(this@policyForTest) as com.tudominio.parentalcontrol.domain.Policy
    }

private fun EnforcementController.decisionForTest(): Decision = evaluar(
    policy = policyForTest(),
    packageName = "com.example.child",
    usage = currentUsageForTest(),
    now = LocalDateTime.of(2026, 7, 30, 12, 0),
    zonaHoraria = ZoneId.of("UTC"),
)
