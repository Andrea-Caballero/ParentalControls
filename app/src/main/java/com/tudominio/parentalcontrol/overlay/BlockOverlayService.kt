package com.tudominio.parentalcontrol.overlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.tudominio.parentalcontrol.MainActivity
import com.tudominio.parentalcontrol.R
import com.tudominio.parentalcontrol.ui.theme.ParentalControlTheme

class BlockOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    companion object {
        const val CHANNEL_ID = "block_overlay_channel"
        const val NOTIFICATION_ID = 2
        const val ACTION_SHOW = "com.tudominio.parentalcontrol.action.SHOW_BLOCK_OVERLAY"
        const val ACTION_HIDE = "com.tudominio.parentalcontrol.action.HIDE_BLOCK_OVERLAY"
        const val EXTRA_REASON = "reason"
        const val EXTRA_CALLBACK_INTENT = "callback_intent"

        private var instance: BlockOverlayService? = null

        fun isShowing(): Boolean = instance?.isShowing == true

        fun show(context: Context, reason: String, onRequestPermission: () -> Unit) {
            val intent = Intent(context, BlockOverlayService::class.java).apply {
                action = ACTION_SHOW
                putExtra(EXTRA_REASON, reason)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            instance?.setPermissionCallback(onRequestPermission)
        }

        fun hide(context: Context) {
            val intent = Intent(context, BlockOverlayService::class.java).apply {
                action = ACTION_HIDE
            }
            context.startService(intent)
        }
    }

    private lateinit var windowManager: WindowManager
    private var composeView: ComposeView? = null
    private var isShowing: Boolean = false
    private var permissionCallback: (() -> Unit)? = null
    private var currentReason: String = ""

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                currentReason = intent.getStringExtra(EXTRA_REASON) ?: "Acceso restringido"
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
                showOverlay()
            }
            ACTION_HIDE -> {
                hideOverlay()
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        hideOverlay()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        instance = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Bloqueo de apps",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notificación del servicio de bloqueo"
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Control parental")
            .setContentText("Bloqueando app")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    @SuppressLint("InflateParams", "WrongConstant")
    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            return
        }

        if (isShowing) return

        startForegroundNotification()

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@BlockOverlayService)
            setViewTreeSavedStateRegistryOwner(this@BlockOverlayService)

            setContent {
                ParentalControlTheme {
                    BlockOverlayContent(
                        reason = currentReason,
                        onRequestPermission = {
                            permissionCallback?.invoke()
                            // Permitir que el menoresolicite tiempo extra
                        }
                    )
                }
            }
        }

        windowManager.addView(composeView, layoutParams)
        isShowing = true
    }

    private fun hideOverlay() {
        composeView?.let {
            windowManager.removeView(it)
            composeView = null
        }
        isShowing = false
    }

    private fun setPermissionCallback(callback: () -> Unit) {
        permissionCallback = callback
    }

    fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    fun requestOverlayPermission(activity: android.app.Activity) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:$packageName")
        )
        activity.startActivity(intent)
    }
}

@Composable
fun BlockOverlayContent(
    reason: String,
    onRequestPermission: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F5F5)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .padding(32.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.White
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(32.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Icono de bloqueo
                Text(
                    text = "🔒",
                    style = MaterialTheme.typography.displayLarge
                )

                // Título
                Text(
                    text = "App bloqueada",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color(0xFF333333)
                )

                // Motivo
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFF666666),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                // CTA - Pedir permiso
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2196F3)
                    )
                ) {
                    Text(
                        text = "Pedir permiso",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                // Nota
                Text(
                    text = "Un adulto deberá aprobar tu solicitud",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF999999),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
