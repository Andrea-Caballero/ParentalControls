package com.tudominio.parentalcontrol.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.service.MonitorForegroundService
import com.tudominio.parentalcontrol.workers.SyncWorker
import com.tudominio.parentalcontrol.workers.WorkScheduler
import com.tudominio.parentalcontrol.workers.WorkerInitializer
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Receiver para re-armar los servicios tras boot o actualización.
 *
 * Responsabilidades:
 * 1. Reiniciar el MonitorForegroundService (T06)
 * 2. Reconciliar uso con UsageStats (T07)
 * 3. Encolar sincronización inicial (T18/T20)
 * 4. PR 3: agendar el [com.tudominio.parentalcontrol.workers.OutboxDrainer]
 *    periódico para que el drain de la outbox sobreviva al reinicio.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
        const val ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Recibido broadcast: ${intent.action}")

        when (intent.action) {
            ACTION_BOOT_COMPLETED -> {
                onBootCompleted(context)
            }
            ACTION_MY_PACKAGE_REPLACED -> {
                onPackageReplaced(context)
            }
        }
    }

    private fun onBootCompleted(context: Context) {
        Log.d(TAG, "Boot completado, inicializando servicios")

        // 1. Iniciar MonitorForegroundService si no está corriendo
        if (!isUsageServiceRunning(context)) {
            startMonitorForegroundService(context)
        }

        // 2. Gate every WorkManager work scheduled at boot on a successfully
        //    restored session. With a session, re-arm the periodic
        //    OutboxDrainer so the drain survives the reboot and kick off
        //    the after-boot sync chain. Without a session, cancel ONLY
        //    the post-boot sync chain (`${SyncWorker.WORK_NAME}_after_boot`)
        //    that the boot path itself enqueued — DO NOT cancel the
        //    periodic OutboxDrainer / ReconciliationWorker schedules
        //    (they are configured with `ExistingPeriodicWorkPolicy.KEEP`
        //    and must survive across reboots per spec scenarios 5 and 6
        //    of `boot-worker-lifecycle/spec.md`) and DO NOT cancel the
        //    after-pairing schedule (`${SyncWorker.WORK_NAME}_after_pairing`,
        //    a distinct unique-work name).
        //    KEEP + a unique work name ensures we do not replace an
        //    already-scheduled OutboxDrainer instance.
        GlobalScope.launch {
            val authManager = DeviceAuthManager.getInstance(context)
            val session = authManager.restoreSession()
            if (session != null) {
                // PR verification 2026-07-01: refresh the access token.
                // `loadPersistedState` only restores `deviceId` + `isPaired`
                // from SharedPrefs, not the in-memory `currentAccessToken`,
                // so any authenticated request (sync, pull, drain) returns
                // Offline until the user pairs again. Calling
                // `authenticateOrCreate` here rehydrates the token from the
                // persisted session and lets the post-boot sync chain
                // succeed without requiring the user to re-pair.
                authManager.authenticateOrCreate()

                WorkScheduler.scheduleOutboxDrainer(context)
                WorkerInitializer.initialize(context, isAfterBoot = true)
            } else {
                WorkScheduler.cancelWork(context, "${SyncWorker.WORK_NAME}_after_boot")
                Log.w(TAG, "no stored session, skipping sync chain")
            }
        }

        Log.d(TAG, "Inicialización post-boot completada")
    }

    private fun onPackageReplaced(context: Context) {
        Log.d(TAG, "Package reemplazado, reinicializando")

        // Similar a boot pero también fuerza sync
        onBootCompleted(context)
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
        Log.d(TAG, "Iniciando MonitorForegroundService")

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
