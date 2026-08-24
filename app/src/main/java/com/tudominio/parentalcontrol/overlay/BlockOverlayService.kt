package com.tudominio.parentalcontrol.overlay

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import androidx.compose.runtime.Composable
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
import com.tudominio.parentalcontrol.R
import com.tudominio.parentalcontrol.ui.theme.ParentalControlTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException

private const val MAX_RECONCILIATION_ATTEMPTS = 3
private const val RECONCILIATION_RETRY_DELAY_MS = 100L

class BlockOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {
    companion object {
        const val CHANNEL_ID = "block_overlay_channel"
        const val NOTIFICATION_ID = 2
        const val ACTION_RECONCILE = "com.tudominio.parentalcontrol.overlay.RECONCILE"
    }

    private lateinit var coordinator: OverlayCoordinator
    private lateinit var windowManager: WindowManager
    private lateinit var scope: CoroutineScope
    private var serviceActorValue: ActorId? = null
    private val serviceActor: ActorId get() = serviceActorValue ?: error("overlay service actor is not initialized")
    private val renderMutex = Mutex()
    private var reconcileJob: Job? = null
    private var reconcileRequested = false
    private var explicitReconcileRequested = false
    private var composeView: ComposeView? = null
    private var rendered: DesiredOverlayState.Shown? = null
    private var destroyed = false
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        coordinator = OverlayCoordinator.get(this)
        serviceActorValue = coordinator.registerService()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        createNotificationChannel()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        scope.launch {
            coordinator.desired.collectLatest { requestReconcile() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RECONCILE) {
            explicitReconcileRequested = true
            // Reconciliation can be cleanup-only. A service launched with
            // startForegroundService must enter the foreground before doing any
            // work, then reconcile() stops it when no overlay remains.
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundNotification()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                val handle = coordinator.owner()?.takeIf { coordinator.ownerActor(it) == serviceActor }?.handle
                    ?: (coordinator.desired.value as? DesiredOverlayState.Shown)?.handle
                handle?.let {
                    coordinator.publish(
                        ObservedOverlayState.Failed(
                            it,
                            failure.message ?: "Foreground startup failed",
                            actor = serviceActor,
                        ),
                    )
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            requestReconcile()
        }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (!destroyed) {
            destroyed = true
            // onDestroy is delivered on the main thread, the same dispatcher used by reconcile.
            // Remove and publish before cancelling the scope so destruction cannot cancel cleanup.
            val owned = coordinator.owner()?.takeIf { coordinator.ownerActor(it) == serviceActor }
            val handle = owned?.handle
            removeOwnedView(onlyThisService = true)
            if (handle != null) {
                coordinator.publish(
                    ObservedOverlayState.Failed(
                        handle,
                        "View removal requires reconciliation",
                        actor = serviceActor,
                        owner = owned?.owner,
                    ),
                )
            }
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            scope.cancel()
        }
        super.onDestroy()
    }

    private suspend fun reconcile(desired: DesiredOverlayState, explicitCleanup: Boolean) = renderMutex.withLock {
        if (desired is DesiredOverlayState.Shown) {
            serviceActorValue = coordinator.rebindService(desired.handle) ?: serviceActor
        }
        val foreignOwner = coordinator.owner()?.takeUnless { coordinator.ownerActor(it) == serviceActor }
        if (explicitCleanup && foreignOwner != null) {
            var removed = false
            repeat(MAX_RECONCILIATION_ATTEMPTS) { attempt ->
                removed = removeExactOwner(foreignOwner)
                if (!removed && attempt + 1 < MAX_RECONCILIATION_ATTEMPTS) {
                    kotlinx.coroutines.delay(RECONCILIATION_RETRY_DELAY_MS * (attempt + 1))
                }
            }
            coordinator.publish(
                if (removed) ObservedOverlayState.Lost(foreignOwner.handle, serviceActor, foreignOwner.owner)
                else ObservedOverlayState.Failed(
                    foreignOwner.handle,
                    "Cross-generation view removal failed",
                    actor = serviceActor,
                    owner = foreignOwner.owner,
                ),
            )
            return@withLock
        }
        when (desired) {
            DesiredOverlayState.Hidden -> {
                val owner = coordinator.owner()
                if (owner != null && coordinator.ownerActor(owner) != serviceActor) {
                     coordinator.publish(
                        ObservedOverlayState.Failed(
                            owner.handle,
                            "Foreign-generation owner requires explicit reconciliation",
                            actor = serviceActor,
                            owner = owner.owner,
                        ),
                    )
                    return@withLock
                }
                val handle = owner?.handle
                    ?: rendered?.handle
                    ?: (coordinator.observed.value as? ObservedOverlayState.Shown)?.handle
                    ?: (coordinator.observed.value as? ObservedOverlayState.Failed)?.handle
                if (removeOwnedView()) {
                    val reconciliationProof = owner?.owner
                    handle?.let {
                         coordinator.publish(
                            ObservedOverlayState.Lost(
                                it,
                            actor = serviceActor,
                            owner = reconciliationProof,
                            ),
                        )
                    }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else if (owner == null && rendered == null && composeView == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else if (handle != null) {
                    val ownerGeneration = coordinator.owner()?.owner
                     coordinator.publish(
                        ObservedOverlayState.Failed(
                            handle,
                            "View removal failed",
                            actor = serviceActor,
                            owner = ownerGeneration,
                        ),
                    )
                }
            }
            is DesiredOverlayState.Shown -> render(desired)
        }
    }

    private fun requestReconcile() {
        reconcileRequested = true
        if (reconcileJob?.isActive == true) return
        reconcileJob = scope.launch {
            do {
                reconcileRequested = false
                val explicit = explicitReconcileRequested
                explicitReconcileRequested = false
                reconcile(coordinator.desired.value, explicit)
            } while (reconcileRequested)
        }
    }

    private suspend fun render(desired: DesiredOverlayState.Shown) {
        val owner = coordinator.owner()
        if (owner != null && coordinator.ownerActor(owner) != serviceActor) {
             coordinator.publish(
                ObservedOverlayState.Failed(
                    desired.handle,
                    "Foreign-generation owner requires explicit reconciliation",
                    actor = serviceActor,
                    owner = owner.owner,
                ),
            )
            return
        }
        if (owner?.handle == desired.handle && coordinator.ownerActor(owner) == serviceActor &&
            owner.attachment == Attachment.ATTACHED && composeView?.isAttachedToWindow == true
        ) {
                coordinator.publish(ObservedOverlayState.Shown(desired.handle, serviceActor, owner.owner))
            return
        }
        if (owner != null) {
            val previousHandle = owner.handle
            if (!removeOwnedView()) {
                 coordinator.publish(
                    ObservedOverlayState.Failed(
                        previousHandle,
                        "Previous view removal failed",
                        actor = serviceActor,
                        owner = owner.owner,
                    ),
                )
                return
            }
            if (previousHandle != desired.handle) {
                         coordinator.publish(
                            ObservedOverlayState.Lost(
                        previousHandle,
                        actor = serviceActor,
                        owner = owner.owner,
                    ),
                )
            }
        }
        if (!Settings.canDrawOverlays(this)) {
             coordinator.publish(
                ObservedOverlayState.Failed(
                    desired.handle,
                    "Overlay permission is missing",
                    actor = serviceActor,
                ),
            )
            return
        }
        var attemptView: ComposeView? = null
        try {
            startForegroundNotification()
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            val view = ComposeView(this).apply {
                setViewTreeLifecycleOwner(this@BlockOverlayService)
                setViewTreeSavedStateRegistryOwner(this@BlockOverlayService)
                 setContent { ParentalControlTheme { BlockOverlayContent(desired.reason) } }
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.CENTER }
            attemptView = view
            if (coordinator.claim(serviceActor, desired.handle, view) !is ClaimOutcome.Claimed) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                 coordinator.publish(
                    ObservedOverlayState.Failed(
                        desired.handle,
                        "Prior physical owner is unresolved",
                        actor = serviceActor,
                    ),
                )
                return
            }
            windowManager.addView(view, params)
            val claimed = coordinator.owner() ?: error("overlay claim did not publish an owner")
            check(coordinator.markAttached(serviceActor, desired.handle, claimed) is CoreOutcome.Accepted)
            composeView = view
            rendered = desired
            if (coordinator.desired.value == desired) {
                coordinator.publish(ObservedOverlayState.Shown(desired.handle, serviceActor, claimed.owner))
            }
            else {
                val removed = removeOwnedView()
                 coordinator.publish(
                     if (removed) ObservedOverlayState.Lost(desired.handle, serviceActor, claimed.owner)
                     else ObservedOverlayState.Failed(
                        desired.handle,
                        "Stale view removal failed",
                        actor = serviceActor,
                        owner = claimed.owner,
                    ),
                )
            }
        } catch (cancellation: CancellationException) {
            val removed = cleanupFailedAttempt(attemptView, desired.handle)
            if (attemptView != null && removed) {
                 coordinator.publish(
                    ObservedOverlayState.Failed(
                        desired.handle,
                        "Overlay show was cancelled",
                        actor = serviceActor,
                        owner = coordinator.owner()?.owner,
                    ),
                )
            } else if (attemptView != null) {
                 coordinator.publish(
                    ObservedOverlayState.Failed(
                        desired.handle,
                        "Overlay cancellation cleanup is unresolved",
                        actor = serviceActor,
                        owner = coordinator.owner()?.owner,
                    ),
                )
            } else {
                 coordinator.publish(
                    ObservedOverlayState.Failed(
                        desired.handle,
                        "Overlay show was cancelled before attachment proof",
                        actor = serviceActor,
                    ),
                )
            }
            throw cancellation
        } catch (failure: Throwable) {
            val removed = cleanupFailedAttempt(attemptView, desired.handle)
                coordinator.publish(
                ObservedOverlayState.Failed(
                    desired.handle,
                    failure.message ?: if (removed) "Overlay view creation failed" else "Overlay view cleanup is unresolved",
                        actor = serviceActor,
                        owner = coordinator.owner()?.owner,
                ),
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun removeOwnedView(onlyThisService: Boolean = false): Boolean {
        val owner = coordinator.owner() ?: run {
            return false
        }
        if (coordinator.ownerActor(owner) != serviceActor) return false
        val view = owner.view
        val removalOwner = owner.takeIf { it.attachment == Attachment.DETACH_UNRESOLVED } ?: owner.copy(attachment = Attachment.DETACH_UNRESOLVED).takeIf { coordinator.markDetachUnresolved(serviceActor, owner) is CoreOutcome.Accepted } ?: return false
        return try {
            windowManager.removeView(view)
            if (coordinator.markRemovalUncertain(serviceActor, removalOwner) !is CoreOutcome.Accepted) return false
            coordinator.confirmRemoval(serviceActor, removalOwner).also {
                if (it is CoreOutcome.Accepted && composeView === view && rendered?.handle == owner.handle) {
                    composeView = null
                    rendered = null
                }
            } is CoreOutcome.Accepted
        } catch (_: Throwable) {
            coordinator.markDetachUnresolved(serviceActor, owner)
            false
        }
    }

    private fun removeExactOwner(expected: ExactOwnerIdentity): Boolean {
        val current = coordinator.owner()
        if (current == null || current.handle != expected.handle ||
            current.owner != expected.owner || current.view !== expected.view
        ) return false
        val removalOwner = expected.takeIf { it.attachment == Attachment.DETACH_UNRESOLVED } ?: expected.copy(attachment = Attachment.DETACH_UNRESOLVED).takeIf { coordinator.markDetachUnresolved(serviceActor, expected) is CoreOutcome.Accepted } ?: return false
        return try {
            windowManager.removeView(expected.view)
            if (coordinator.markRemovalUncertain(serviceActor, removalOwner) !is CoreOutcome.Accepted) return false
            coordinator.confirmRemoval(serviceActor, removalOwner).also {
                if (it is CoreOutcome.Accepted && composeView === expected.view && rendered?.handle == expected.handle) {
                    composeView = null
                    rendered = null
                }
            } is CoreOutcome.Accepted
        } catch (_: Throwable) {
            coordinator.markDetachUnresolved(serviceActor, expected)
            false
        }
    }

    private fun cleanupFailedAttempt(view: ComposeView?, handle: OverlayHandle): Boolean {
        if (view == null) return false
        val owner = coordinator.owner()?.takeIf { it.handle == handle && it.view === view }
            ?: return false
        val removalOwner = owner.takeIf { it.attachment == Attachment.DETACH_UNRESOLVED } ?: owner.copy(attachment = Attachment.DETACH_UNRESOLVED).takeIf { coordinator.markDetachUnresolved(serviceActor, owner) is CoreOutcome.Accepted } ?: return false
        return try {
            windowManager.removeView(view)
            if (coordinator.markRemovalUncertain(serviceActor, removalOwner) !is CoreOutcome.Accepted) return false
            coordinator.confirmRemoval(serviceActor, removalOwner).also {
                if (it is CoreOutcome.Accepted && composeView === view && rendered?.handle == owner.handle) {
                    composeView = null
                    rendered = null
                }
            } is CoreOutcome.Accepted
        } catch (_: Throwable) {
            coordinator.markDetachUnresolved(serviceActor, owner)
            false
        }
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Block overlay", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun startForegroundNotification() {
        startForeground(NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Parental control").setContentText("Blocking app").setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW).build())
    }

    fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(this)
    fun requestOverlayPermission(activity: android.app.Activity) {
        activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
    }
}

@Composable
fun BlockOverlayContent(reason: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F5F5)),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .padding(32.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(8.dp),
        ) {
            Column(
                modifier = Modifier
                    .padding(32.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Text("🔒", style = MaterialTheme.typography.displayLarge)
                Text("App blocked", style = MaterialTheme.typography.headlineMedium, color = Color(0xFF333333))
                Text(reason, style = MaterialTheme.typography.bodyLarge, color = Color(0xFF666666), textAlign = TextAlign.Center)
                Text(
                    "An adult must approve your request",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF999999),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
