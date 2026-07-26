package com.tudominio.parentalcontrol.enforcement

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.domain.*
import com.tudominio.parentalcontrol.time.FakeTimeProvider
import com.tudominio.parentalcontrol.time.TimeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class EnforcementControllerTest {

    private lateinit var context: Context
    private lateinit var database: ParentalDatabase
    private lateinit var timeProvider: TimeProvider

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(
            context,
            ParentalDatabase::class.java
        ).allowMainThreadQueries().build()
        timeProvider = FakeTimeProvider()
    }

    @Test
    fun testControllerCanBeCreated() {
        val controller = EnforcementController(context, database, timeProvider)
        assertNotNull(controller)
    }

    @Test
    fun testControllerGetStatus() {
        val controller = EnforcementController(context, database, timeProvider)
        val status = controller.getStatus()
        
        assertNotNull(status)
        assertFalse(status.isBlocked)
    }

    @Test
    fun testMockPolicyManagerCreatesValidPolicies() {
        val policy = MockPolicyManager.createSimplePolicy()
        
        assertEquals("simple-device", policy.device_id)
        assertEquals(DeviceState.ACTIVE, policy.device_state)
        assertEquals(60, policy.daily_screen_time_minutes)
    }

    @Test
    fun testBlockedAppPolicy() {
        val policy = MockPolicyManager.createSimplePolicy()
        val blockedApp = policy.app_policies.find { it.package_name == "com.test.blocked" }
        
        assertNotNull(blockedApp)
        assertEquals(AppPolicyState.BLOCKED, blockedApp?.state)
    }

    @Test
    fun testAllowedAppPolicy() {
        val policy = MockPolicyManager.createSimplePolicy()
        val allowedApp = policy.app_policies.find { it.package_name == "com.test.allowed" }
        
        assertNotNull(allowedApp)
        assertEquals(AppPolicyState.ALLOWED, allowedApp?.state)
    }

    @Test
    fun testDowntimePolicy() {
        val policy = MockPolicyManager.createDowntimePolicy()
        assertEquals(DeviceState.DOWNTIME, policy.device_state)
    }

    @Test
    fun testLockedPolicy() {
        val policy = MockPolicyManager.createLockedPolicy()
        assertEquals(DeviceState.LOCKED, policy.device_state)
    }

    @Test
    fun testGrantPolicy() {
        val policy = MockPolicyManager.createGrantPolicy()
        assertEquals(1, policy.grants.size)
        assertEquals("active-grant", policy.grants.first().id)
    }

    @Test
    fun testFullTestPolicyHasAllScenarios() {
        val policy = MockPolicyManager.createFullTestPolicy()
        
        // Verificar que tiene apps de diferentes tipos
        assertTrue(policy.app_policies.any { it.state == AppPolicyState.BLOCKED })
        assertTrue(policy.app_policies.any { it.state == AppPolicyState.LIMITED })
        assertTrue(policy.app_policies.any { it.allowed_windows.isNotEmpty() })
        assertTrue(policy.schedules.isNotEmpty())
        assertTrue(policy.category_limits.isNotEmpty())
        assertTrue(policy.grants.isNotEmpty())
    }

    @Test
    fun testEnforcementDecisionAllowed() {
        val decision = EnforcementDecision.Allowed("com.example.app")
        
        assertEquals("com.example.app", decision.packageName)
    }

    @Test
    fun testEnforcementDecisionBlocked() {
        val decision = EnforcementDecision.Blocked("com.example.app", "Test reason")
        
        assertEquals("com.example.app", decision.packageName)
        assertEquals("Test reason", decision.reason)
    }

    @Test
    fun testEnforcementStatus() {
        val status = EnforcementStatus(
            isBlocked = true,
            currentPackage = "com.test.app",
            hasPolicy = true,
            isAdminActive = false
        )
        
        assertTrue(status.isBlocked)
        assertEquals("com.test.app", status.currentPackage)
        assertTrue(status.hasPolicy)
        assertFalse(status.isAdminActive)
    }
}
