package com.tudominio.parentalcontrol.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tudominio.parentalcontrol.data.model.AppPolicyEntity
import com.tudominio.parentalcontrol.data.model.GrantEntity
import com.tudominio.parentalcontrol.data.model.OutboxEntity
import com.tudominio.parentalcontrol.data.model.PolicyEntity
import com.tudominio.parentalcontrol.data.model.UsageTodayEntity
import com.tudominio.parentalcontrol.data.model.WindowEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ParentalDatabaseTest {

    private lateinit var db: ParentalDatabase
    private lateinit var policyDao: PolicyDao
    private lateinit var appPolicyDao: AppPolicyDao
    private lateinit var grantDao: GrantDao
    private lateinit var usageDao: UsageDao
    private lateinit var outboxDao: OutboxDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, ParentalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        policyDao = db.policyDao()
        appPolicyDao = db.appPolicyDao()
        grantDao = db.grantDao()
        usageDao = db.usageDao()
        outboxDao = db.outboxDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    // =============================================================================
    // T03.3 — Guard de versión atómico
    // =============================================================================
    @Test
    fun versionGuardNewerVersionOverwrites() = runBlocking {
        val policyV1 = PolicyEntity("device-1", 1, mapOf("pkg1" to "games"))
        val policyV2 = PolicyEntity("device-1", 2, mapOf("pkg1" to "social"))

        policyDao.insertPolicy(policyV1)
        val result = policyDao.upsertPolicyIfNewer(policyV2)

        assertTrue(result)
        val retrieved = policyDao.getPolicyFlow("device-1").first()
        assertEquals(2L, retrieved?.version)
    }

    @Test
    fun versionGuardSameVersionDoesNotOverwrite() = runBlocking {
        val policyV1 = PolicyEntity("device-1", 1, mapOf("pkg1" to "games"))
        val policySame = PolicyEntity("device-1", 1, mapOf("pkg1" to "social"))

        policyDao.insertPolicy(policyV1)
        val result = policyDao.upsertPolicyIfNewer(policySame)

        assertFalse(result)
        val retrieved = policyDao.getPolicyFlow("device-1").first()
        assertEquals("games", retrieved?.category_assignments?.get("pkg1"))
    }

    @Test
    fun versionGuardDowngradeDoesNotOverwrite() = runBlocking {
        val policyV2 = PolicyEntity("device-1", 2, mapOf("pkg1" to "social"))
        val policyV1 = PolicyEntity("device-1", 1, mapOf("pkg1" to "games"))

        policyDao.insertPolicy(policyV2)
        val result = policyDao.upsertPolicyIfNewer(policyV1)

        assertFalse(result)
        val retrieved = policyDao.getPolicyFlow("device-1").first()
        assertEquals("social", retrieved?.category_assignments?.get("pkg1"))
    }

    // =============================================================================
    // T03.1 — Persistir y leer política
    // =============================================================================
    @Test
    fun persistAndReadPolicy() = runBlocking {
        val policy = PolicyEntity("device-1", 5, mapOf("com.example.app" to "games"))
        policyDao.insertPolicy(policy)

        val retrieved = policyDao.getPolicyFlow("device-1").first()
        assertNotNull(retrieved)
        assertEquals(5L, retrieved?.version)
        assertEquals("games", retrieved?.category_assignments?.get("com.example.app"))
    }

    @Test
    fun persistAndReadAppPolicies() = runBlocking {
        val appPolicy = AppPolicyEntity(
            package_name = "com.example.app",
            device_id = "device-1",
            state = "LIMITED",
            daily_limit_minutes = 30,
            allowed_windows = listOf(WindowEntity(listOf("MONDAY"), "16:00", "18:00")),
            category = "games"
        )
        appPolicyDao.upsertAppPolicy(appPolicy)

        val retrieved = appPolicyDao.getAppPolicy("com.example.app")
        assertNotNull(retrieved)
        assertEquals("LIMITED", retrieved?.state)
        assertEquals(30, retrieved?.daily_limit_minutes)
    }

    @Test
    fun persistAndReadGrants() = runBlocking {
        val grant = GrantEntity(
            id = "grant-1",
            device_id = "device-1",
            request_id = null,
            scope = "device",
            minutes = 30,
            source = "EXTRA_TIME",
            granted_at = "2026-06-15T08:00:00Z",
            expires_at = "2026-06-15T23:00:00Z"
        )
        grantDao.insertGrant(grant)

        val retrieved = grantDao.getActiveGrantsFlow("device-1", "2026-06-15T10:00:00Z").first()
        assertEquals(1, retrieved.size)
        assertEquals("device", retrieved[0].scope)
    }

    // =============================================================================
    // T03.5 — usage_today con acumulación
    // =============================================================================
    @Test
    fun accumulateUsageToday() = runBlocking {
        val serverDate = "2026-06-15"

        usageDao.incrementUsage("com.example.app", serverDate, 10)
        val usage1 = usageDao.getUsage("com.example.app", serverDate)
        assertEquals(10, usage1?.usage_minutes)

        usageDao.incrementUsage("com.example.app", serverDate, 5)
        val usage2 = usageDao.getUsage("com.example.app", serverDate)
        assertEquals(15, usage2?.usage_minutes)
    }

    @Test
    fun usageTodayWithServerDateRollover() = runBlocking {
        val yesterday = "2026-06-14"
        val today = "2026-06-15"

        usageDao.incrementUsage("com.example.app", yesterday, 30)
        usageDao.incrementUsage("com.example.app", today, 10)

        val yesterdayUsage = usageDao.getUsage("com.example.app", yesterday)
        val todayUsage = usageDao.getUsage("com.example.app", today)

        assertEquals(30, yesterdayUsage?.usage_minutes)
        assertEquals(10, todayUsage?.usage_minutes)
    }

    @Test
    fun getUsageForDateAsFlow() = runBlocking {
        val serverDate = "2026-06-15"
        usageDao.upsertUsage(UsageTodayEntity("com.app1", serverDate, 10))
        usageDao.upsertUsage(UsageTodayEntity("com.app2", serverDate, 20))

        val usages = usageDao.getUsageForDateFlow(serverDate).first()
        assertEquals(2, usages.size)
        assertEquals(30, usages.sumOf { it.usage_minutes })
    }

    // =============================================================================
    // T03.2 — Outbox encola y drena
    // =============================================================================
    @Test
    fun outboxEnqueueAndDrain() = runBlocking {
        val serverDate = "2026-06-15"
        val item = OutboxEntity(
            tipo = "usage_log",
            payload_json = """{"pkg":"com.example.app","minutes":10}""",
            dedup_key = "usage-2026-06-15-com.example.app",
            created_at = "2026-06-15T10:00:00Z",
            server_date = serverDate
        )
        outboxDao.insertOutboxItem(item)

        val pending = outboxDao.getPendingItems(3, 50)
        assertEquals(1, pending.size)
        assertEquals("usage_log", pending[0].tipo)
    }

    @Test
    fun outboxDedupPreventsDuplicate() = runBlocking {
        val serverDate = "2026-06-15"
        val item1 = OutboxEntity(
            tipo = "usage_log",
            payload_json = """{"pkg":"com.example.app"}""",
            dedup_key = "dedup-key-1",
            created_at = "2026-06-15T10:00:00Z",
            server_date = serverDate
        )
        val item2 = OutboxEntity(
            tipo = "usage_log",
            payload_json = """{"pkg":"com.example.app"}""",
            dedup_key = "dedup-key-1",
            created_at = "2026-06-15T10:01:00Z",
            server_date = serverDate
        )
        outboxDao.insertOutboxItem(item1)
        outboxDao.insertOutboxItem(item2)

        val pending = outboxDao.getPendingItems(3, 50)
        assertEquals(1, pending.size)
    }

    @Test
    fun outboxIncrementAttempts() = runBlocking {
        val serverDate = "2026-06-15"
        val item = OutboxEntity(
            tipo = "usage_log",
            payload_json = """{"pkg":"com.example.app"}""",
            dedup_key = null,
            created_at = "2026-06-15T10:00:00Z",
            server_date = serverDate
        )
        outboxDao.insertOutboxItem(item)
        val inserted = outboxDao.getPendingItems(3, 50).first()

        outboxDao.incrementRetries(inserted.id)
        val afterIncrement = outboxDao.getPendingItems(3, 50).first()
        assertEquals(1, afterIncrement.retries)
    }

    @Test
    fun outboxDeleteFailedItems() = runBlocking {
        val serverDate = "2026-06-15"
        val item = OutboxEntity(
            tipo = "usage_log",
            payload_json = """{"pkg":"com.example.app"}""",
            dedup_key = null,
            retries = 3,
            created_at = "2026-06-15T10:00:00Z",
            server_date = serverDate
        )
        outboxDao.insertOutboxItem(item)

        outboxDao.deleteFailedItems(3)
        val pending = outboxDao.getPendingItems(3, 50)
        assertEquals(0, pending.size)
    }

    // =============================================================================
    // T03.4 — Agregados por categoría y global
    // =============================================================================
    @Test
    fun globalUsageAggregation() = runBlocking {
        val serverDate = "2026-06-15"
        usageDao.upsertUsage(UsageTodayEntity("com.app1", serverDate, 10))
        usageDao.upsertUsage(UsageTodayEntity("com.app2", serverDate, 20))
        usageDao.upsertUsage(UsageTodayEntity("com.app3", serverDate, 30))

        val globalFlow = usageDao.getGlobalUsageFlow(serverDate).first()
        assertEquals(60, globalFlow)
    }

    @Test
    fun categoryUsageAggregation() = runBlocking {
        val serverDate = "2026-06-15"
        val deviceId = "device-1"

        appPolicyDao.upsertAppPolicy(
            AppPolicyEntity(deviceId, "com.game1", "LIMITED", 60, emptyList(), "games")
        )
        appPolicyDao.upsertAppPolicy(
            AppPolicyEntity(deviceId, "com.game2", "LIMITED", 60, emptyList(), "games")
        )
        appPolicyDao.upsertAppPolicy(
            AppPolicyEntity(deviceId, "com.social", "ALLOWED", null, emptyList(), "social")
        )

        usageDao.upsertUsage(UsageTodayEntity("com.game1", serverDate, 20))
        usageDao.upsertUsage(UsageTodayEntity("com.game2", serverDate, 30))
        usageDao.upsertUsage(UsageTodayEntity("com.social", serverDate, 15))

        val gamesUsage = usageDao.getCategoryUsageFlow(serverDate, "games").first()
        assertEquals(50, gamesUsage)
    }

    @Test
    fun categoryUsageExcludesAlwaysAllowed() = runBlocking {
        val serverDate = "2026-06-15"
        val deviceId = "device-1"

        appPolicyDao.upsertAppPolicy(
            AppPolicyEntity(deviceId, "com.game1", "ALWAYS_ALLOWED", null, emptyList(), "games")
        )
        appPolicyDao.upsertAppPolicy(
            AppPolicyEntity(deviceId, "com.game2", "LIMITED", 60, emptyList(), "games")
        )

        usageDao.upsertUsage(UsageTodayEntity("com.game1", serverDate, 100))
        usageDao.upsertUsage(UsageTodayEntity("com.game2", serverDate, 20))

        val gamesUsage = usageDao.getCategoryUsageFlow(serverDate, "games").first()
        assertEquals(20, gamesUsage)
    }
}
