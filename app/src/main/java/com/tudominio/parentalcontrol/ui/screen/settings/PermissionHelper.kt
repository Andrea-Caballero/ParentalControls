package com.tudominio.parentalcontrol.ui.screen.settings

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

// 1. Estadísticas de uso de apps
fun requestUsageStatsPermission(context: Context) {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = appOps.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        android.os.Process.myUid(),
        context.packageName
    )
    if (mode != AppOpsManager.MODE_ALLOWED) {
        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }
}

// 2. Superposición sobre otras apps
fun requestOverlayPermission(context: Context) {
    if (!Settings.canDrawOverlays(context)) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        context.startActivity(intent)
    }
}

// 3. Administrador de dispositivo
fun requestDeviceAdmin(context: Context, adminComponent: ComponentName) {
    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
        putExtra(
            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "Necesario para aplicar restricciones de tiempo de pantalla."
        )
    }
    context.startActivity(intent)
}

// 4. Servicio de accesibilidad
fun openAccessibilitySettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
}
