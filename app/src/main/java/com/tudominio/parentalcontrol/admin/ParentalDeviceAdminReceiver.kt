package com.tudominio.parentalcontrol.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Device Admin Receiver para control parental.
 * Provee funcionalidad de bloqueo de dispositivo (lockNow).
 */
class ParentalDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        const val TAG = "ParentalDeviceAdminReceiver"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(
            context,
            "Control parental activado",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Toast.makeText(
            context,
            "Control parental desactivado",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "Al desactivar el control parental, se perderá la capacidad de bloquear el dispositivo remotamente."
    }

    override fun onLockTaskModeEntering(
        context: Context,
        intent: Intent,
        pkg: String
    ) {
        super.onLockTaskModeEntering(context, intent, pkg)
    }

    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        super.onLockTaskModeExiting(context, intent)
    }
}
