package com.tudominio.parentalcontrol.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppMonitorServiceManifestTest {

    @Test
    fun app_monitor_service_is_registered_as_an_accessibility_service() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(AccessibilityService.SERVICE_INTERFACE).setPackage(context.packageName)

        val serviceInfo = context.packageManager.queryIntentServices(intent, 0)
            .map { it.serviceInfo }
            .singleOrNull { it.name.endsWith(".accessibility.AppMonitorService") }

        assertNotNull("AppMonitorService must resolve from the accessibility service action", serviceInfo)
        assertEquals(
            "android.permission.BIND_ACCESSIBILITY_SERVICE",
            serviceInfo?.permission
        )
        assertTrue(serviceInfo?.metaData?.containsKey("android.accessibilityservice") == true)
        assertTrue(serviceInfo?.metaData?.getInt("android.accessibilityservice", 0) != 0)
        assertEquals(false, serviceInfo?.exported)
        assertEquals(true, serviceInfo?.enabled)
    }

    @Test
    fun block_overlay_declares_special_use_subtype_and_stays_not_exported() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val service = manifest.substringAfter("android:name=\".overlay.BlockOverlayService\"")
            .substringBefore("</service>")
        assertTrue(service.contains("android:exported=\"false\""))
        assertTrue(service.contains("android:foregroundServiceType=\"specialUse\""))
        assertTrue(service.contains("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"))
        assertTrue(service.contains("overlay_enforcement"))
    }
}
