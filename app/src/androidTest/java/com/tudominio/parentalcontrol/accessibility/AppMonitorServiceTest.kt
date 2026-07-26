package com.tudominio.parentalcontrol.accessibility

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppMonitorServiceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun testForegroundAppEmission() = runBlocking {
        // Simulate an accessibility event
        val service = AppMonitorService()
        val mockEvent = android.view.accessibility.AccessibilityEvent.obtain(
            android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ).apply {
            packageName = "com.example.app"
        }

        // Trigger event handling
        service.onAccessibilityEvent(mockEvent)

        // Verify that the package name is emitted
        val emittedPackage = AppMonitorService.appInForeground.first()
        assertEquals("com.example.app", emittedPackage)
    }

    @Test
    fun testNoiseFiltering() = runBlocking {
        // Simulate an event from a launcher app
        val service = AppMonitorService()
        val mockEvent = android.view.accessibility.AccessibilityEvent.obtain(
            android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ).apply {
            packageName = "com.android.launcher"
        }

        // Trigger event handling
        service.onAccessibilityEvent(mockEvent)

        // Verify that no noisy package name is emitted
        val emittedPackage = AppMonitorService.appInForeground.first()
        assertEquals(null, emittedPackage)
    }
}
