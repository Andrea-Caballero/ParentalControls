package com.tudominio.parentalcontrol.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@AndroidEntryPoint
class AppMonitorService : AccessibilityService() {

    companion object {
        private val _appInForeground = MutableStateFlow<String?>(null)
        val appInForeground = _appInForeground.asStateFlow()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString()
            if (packageName != null && !isNoise(packageName)) {
                _appInForeground.value = packageName
            }
        }
    }

    override fun onInterrupt() {
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT
            notificationTimeout = 100
        }
    }

    private fun isNoise(packageName: String): Boolean {
        if (packageName.isEmpty()) return true

        val launcherPrefixes = listOf(
            "com.android.launcher",
            "com.google.android.apps.nexuslauncher",
            "com.google.android.apps.launcher",
            "com.samsung.android.app.launcher",
            "com.sec.android.app.launcher",
            "com.lge.launcher",
            "com.miui.home",
            "com.miui.launcher",
            "com.oppo.launcher",
            "com.huawei.android.launcher",
            "com.lenovo.launcher",
            "com.gionee.launcher",
            "com.htc.launcher",
            "com.sony.launcher",
            "com.amazon.firelauncher",
            "com.zte.launcher"
        )

        val imePrefixes = listOf(
            "com.google.android.inputmethod",
            "com.baidu.input_method",
            "com.sohu.inputmethod",
            "com.qq.input.method",
            "com.iflytek.inputmethod",
            "com.swype.android.inputmethod",
            "com.htc.inputmethod",
            "com.miui.securityinputmethod"
        )

        return launcherPrefixes.any { packageName.startsWith(it) } ||
               imePrefixes.any { packageName.startsWith(it) }
    }
}
