package com.tudominio.parentalcontrol.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.tudominio.parentalcontrol.service.MonitorForegroundService
import com.tudominio.parentalcontrol.workers.WorkScheduler

/** Re-arms foreground monitoring and delegates network recovery to WorkManager. */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
        const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
        const val ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Recibido broadcast: ${intent.action}")
        when (intent.action) {
            ACTION_BOOT_COMPLETED, ACTION_MY_PACKAGE_REPLACED -> onBootCompleted(context)
        }
    }

    private fun onBootCompleted(context: Context) {
        if (!isUsageServiceRunning(context)) startMonitorForegroundService(context)

        // Authentication may require network and refresh backoff. Never hold the
        // receiver's approximately ten-second lifecycle while doing that work.
        WorkScheduler.scheduleAuthRestore(context)
        Log.d(TAG, "Boot recovery delegated to WorkManager")
    }

    private fun isUsageServiceRunning(context: Context): Boolean {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            activityManager.getRunningServices(Integer.MAX_VALUE).any {
                it.service.className == MonitorForegroundService::class.java.name
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error verificando servicio: ${e.message}")
            false
        }
    }

    private fun startMonitorForegroundService(context: Context) {
        val serviceIntent = Intent(context, MonitorForegroundService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando servicio: ${e.message}")
        }
    }
}
