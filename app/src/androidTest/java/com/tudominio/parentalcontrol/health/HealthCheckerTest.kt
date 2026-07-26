package com.tudominio.parentalcontrol.health

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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

@RunWith(AndroidJUnit4::class)
class HealthCheckerTest {

    private lateinit var context: Context
    private lateinit var healthChecker: HealthChecker

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        healthChecker = HealthChecker(context)
    }

    @Test
    fun testHealthCheckerCreated() {
        assertNotNull(healthChecker)
    }

    @Test
    fun testCheckHealthReturnsResult() {
        val result = healthChecker.checkHealth()
        
        assertNotNull(result)
        assertNotNull(result.enforcementLevel)
    }

    @Test
    fun testEnforcementLevelIsEnum() {
        val result = healthChecker.checkHealth()
        
        assertTrue(result.enforcementLevel is EnforcementLevel)
    }

    @Test
    fun testMissingPermissionsIsList() {
        val result = healthChecker.checkHealth()
        
        assertNotNull(result.missingPermissions)
        assertTrue(result.missingPermissions is List)
    }

    @Test
    fun testRecommendationsIsList() {
        val result = healthChecker.checkHealth()
        
        assertNotNull(result.recommendations)
        assertTrue(result.recommendations is List)
    }

    @Test
    fun testIsFullyOperationalReflectsLevel() {
        val result = healthChecker.checkHealth()
        
        // Si está degradado, no está completamente operativo
        if (result.isDegraded) {
            assertFalse(result.isFullyOperational)
        }
    }

    @Test
    fun testPermissionsAreDefined() {
        val permissions = Permission.values()
        
        assertTrue(permissions.isNotEmpty())
        assertTrue(permissions.contains(Permission.ACCESSIBILITY_SERVICE))
        assertTrue(permissions.contains(Permission.OVERLAY_PERMISSION))
        assertTrue(permissions.contains(Permission.BATTERY_OPTIMIZATION))
        assertTrue(permissions.contains(Permission.USAGE_STATS))
        assertTrue(permissions.contains(Permission.DEVICE_ADMIN))
    }

    @Test
    fun testRecommendationsAreDefined() {
        val recommendations = Recommendation.values()
        
        assertTrue(recommendations.isNotEmpty())
        assertTrue(recommendations.any { it.message.isNotEmpty() })
    }

    @Test
    fun testIntentsAreCreated() {
        // Verificar que los intents se crean sin error
        val accessibilityIntent = healthChecker.getAccessibilitySettingsIntent()
        assertNotNull(accessibilityIntent)
        
        val overlayIntent = healthChecker.getOverlaySettingsIntent()
        assertNotNull(overlayIntent)
        
        val batteryIntent = healthChecker.getBatteryOptimizationIntent()
        assertNotNull(batteryIntent)
        
        val usageIntent = healthChecker.getUsageStatsIntent()
        assertNotNull(usageIntent)
        
        val adminIntent = healthChecker.getDeviceAdminIntent()
        assertNotNull(adminIntent)
    }

    @Test
    fun testHealthCheckResultHasAllFields() {
        val result = healthChecker.checkHealth()
        
        // Verificar todos los campos booleanos
        assertNotNull(result.isAccessibilityServiceEnabled)
        assertNotNull(result.isOverlayPermissionGranted)
        assertNotNull(result.isBatteryOptimizationIgnored)
        assertNotNull(result.isUsageStatsPermissionGranted)
        assertNotNull(result.isDeviceAdminActive)
        assertNotNull(result.isDeviceOwner)
        assertNotNull(result.isDegraded)
        assertNotNull(result.isFullyOperational)
    }
}
