package com.tudominio.parentalcontrol.ui.child.status

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tudominio.parentalcontrol.admin.LockManager
import com.tudominio.parentalcontrol.copy.CopyManager
import com.tudominio.parentalcontrol.health.DegradationAlertManager
import com.tudominio.parentalcontrol.ui.child.components.DegradedAlertDialog
import com.tudominio.parentalcontrol.ui.child.components.RecoveryDialog
import com.tudominio.parentalcontrol.ui.child.components.RewardBanner
import com.tudominio.parentalcontrol.ui.child.components.RewardReceivedDialog
import com.tudominio.parentalcontrol.workers.WorkerInitializer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ChildStatusScreen(
    viewModel: ChildStatusViewModel,
    copyManager: CopyManager,
    onRequestExtraTime: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val warningLevel by viewModel.warningLevel.collectAsState()
    val pendingRequest by viewModel.pendingTimeRequest.collectAsState()
    val rewardBalance by viewModel.rewardBalance.collectAsState()
    val degradationCauses by viewModel.degradationCauses.collectAsState()
    val showRecovery by viewModel.showRecoveryDialog.collectAsState()
    // WU-D follow-up — collect the banner visibility from the shared
    // coordinator. The banner is gated on `deviceAdminBanner.value`
    // (true when NeedsActivation OR Dismissed(skipUsed=true)).
    val bannerVisible by viewModel.deviceAdminBanner.collectAsState()
    val context = LocalContext.current
    val lockManager = remember { LockManager(context) }
    val adminLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // The ACTION_ADD_DEVICE_ADMIN activity returns RESULT_OK on
        // activation and RESULT_CANCELED on dismiss. Re-check the
        // real admin state — never trust the result code alone.
        viewModel.syncDeviceAdminState(lockManager.isAdminActive())
    }
    // Process-death safe reconciliation. The coordinator is in-memory
    // and restarts Idle after the OS kills the process, so without this
    // hook the banner would stay hidden even when Device Admin is
    // inactive. Re-derive from the actual system state on every cold
    // start. Does NOT auto-launch the activation intent — the user must
    // tap "Activar" to engage the system prompt.
    LaunchedEffect(lockManager) {
        viewModel.syncDeviceAdminState(lockManager.isAdminActive())
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            WorkerInitializer.recoverAfterPairing(context.applicationContext)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    
    var showRewardDialog by remember { mutableStateOf(false) }
    var rewardDialogMinutes by remember { mutableIntStateOf(0) }
    var rewardDialogTotal by remember { mutableLongStateOf(0L) }
    
    var showDegradedDialog by remember { mutableStateOf(false) }
    var currentDegradationCauses by remember { mutableStateOf<List<DegradationAlertManager.DegradationCause>>(emptyList()) }
    
    val repairCopy = remember { copyManager.getRepair() }
    
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ChildStatusEvent.ShowWarning -> {
                    val message = when (event.level) {
                        WarningLevel.WARNING -> "⏰ ${viewModel.formatTimeRemaining(viewModel.timeRemaining.value)} - El tiempo se acaba pronto"
                        WarningLevel.CRITICAL -> "⚠️ ¡Solo ${viewModel.formatTimeRemaining(viewModel.timeRemaining.value)}! Solicita tiempo extra"
                        WarningLevel.BLOCKED -> "🔒 Sin tiempo restante"
                        WarningLevel.NONE -> return@collect
                    }
                    snackbarHostState.showSnackbar(message)
                }
                is ChildStatusEvent.TimeRequestSent -> {
                    snackbarHostState.showSnackbar("Solicitud enviada")
                }
                is ChildStatusEvent.Error -> {
                    snackbarHostState.showSnackbar(event.message)
                }
                is ChildStatusEvent.NewRewardReceived -> {
                    rewardDialogMinutes = event.minutes
                    rewardDialogTotal = event.totalBalance
                    showRewardDialog = true
                }
                is ChildStatusEvent.DegradationDetected -> {
                    currentDegradationCauses = event.causes
                    showDegradedDialog = true
                }
            }
        }
    }

    if (showRewardDialog) {
        RewardReceivedDialog(
            minutes = rewardDialogMinutes,
            totalBalance = rewardDialogTotal,
            onDismiss = { showRewardDialog = false }
        )
    }

    if (showDegradedDialog && currentDegradationCauses.isNotEmpty()) {
        DegradedAlertDialog(
            causes = currentDegradationCauses,
            copy = repairCopy,
            onRepair = { cause ->
                viewModel.onRepairTapped(cause)
                showDegradedDialog = false
            },
            onDismiss = { showDegradedDialog = false }
        )
    }

    if (showRecovery) {
        RecoveryDialog(
            copy = repairCopy,
            onDismiss = {
                viewModel.dismissRecoveryDialog()
            }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when (val state = uiState) {
            is ChildStatusUiState.Loading -> {
                LoadingContent()
            }
            
            is ChildStatusUiState.Content -> {
                StatusContent(
                    state = state,
                    warningLevel = warningLevel,
                    hasPendingRequest = pendingRequest != null,
                    rewardBalance = rewardBalance,
                    copyManager = copyManager,
                    onRequestExtraTime = {
                        if (!state.hasPendingRequest) {
                            onRequestExtraTime()
                        }
                    },
                    bannerVisible = bannerVisible,
                    onActivateAdmin = { adminLauncher.launch(lockManager.getEnableAdminIntent()) }
                )
            }
            
            is ChildStatusUiState.Error -> {
                ErrorContent(
                    message = state.message,
                    onRetry = { viewModel.refresh() }
                )
            }
        }
        
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun LoadingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
internal fun StatusContent(
    state: ChildStatusUiState.Content,
    warningLevel: WarningLevel,
    hasPendingRequest: Boolean,
    rewardBalance: Long,
    copyManager: CopyManager,
    onRequestExtraTime: () -> Unit,
    bannerVisible: Boolean = false,
    onActivateAdmin: () -> Unit = {}
) {
    val extraTimeCopy = copyManager.getExtraTime()
    val blockCopy = copyManager.getBlock()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedVisibility(
            visible = bannerVisible,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            DeviceAdminActivationBanner(onActivate = onActivateAdmin)
            Spacer(modifier = Modifier.height(16.dp))
        }

        TimeRemainingCard(
            timeRemaining = state.timeRemaining,
            warningLevel = warningLevel,
            timeUsedToday = state.timeUsedToday,
            dailyLimit = state.dailyLimit
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        if (rewardBalance > 0) {
            RewardBanner(balanceMinutes = rewardBalance)
            Spacer(modifier = Modifier.height(24.dp))
        }
        
        if (state.nextBlockTime != null) {
            NextBlockCard(
                nextBlockTime = state.nextBlockTime,
                warningLevel = warningLevel,
                copy = blockCopy
            )
            
            Spacer(modifier = Modifier.height(24.dp))
        }
        
        AnimatedVisibility(
            visible = warningLevel != WarningLevel.NONE,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            WarningBanner(
                warningLevel = warningLevel,
                timeRemaining = state.timeRemaining
            )
            
            Spacer(modifier = Modifier.height(24.dp))
        }
        
        if (state.allowedAppsNow.isNotEmpty()) {
            AllowedAppsCard(
                apps = state.allowedAppsNow.take(3),
                copyManager = copyManager
            )
            
            Spacer(modifier = Modifier.height(24.dp))
        }
        
        ExtraTimeButton(
            hasPendingRequest = hasPendingRequest,
            copy = extraTimeCopy,
            onClick = onRequestExtraTime
        )
    }
}

@Composable
private fun TimeRemainingCard(
    timeRemaining: Long,
    warningLevel: WarningLevel,
    timeUsedToday: Long,
    dailyLimit: Long
) {
    val backgroundColor = when (warningLevel) {
        WarningLevel.NONE -> MaterialTheme.colorScheme.primaryContainer
        WarningLevel.WARNING -> MaterialTheme.colorScheme.tertiaryContainer
        WarningLevel.CRITICAL -> MaterialTheme.colorScheme.errorContainer
        WarningLevel.BLOCKED -> MaterialTheme.colorScheme.errorContainer
    }
    
    val contentColor = when (warningLevel) {
        WarningLevel.NONE -> MaterialTheme.colorScheme.onPrimaryContainer
        WarningLevel.WARNING -> MaterialTheme.colorScheme.onTertiaryContainer
        WarningLevel.CRITICAL -> MaterialTheme.colorScheme.onErrorContainer
        WarningLevel.BLOCKED -> MaterialTheme.colorScheme.onErrorContainer
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val emoji = when (warningLevel) {
                WarningLevel.NONE -> "😊"
                WarningLevel.WARNING -> "⏰"
                WarningLevel.CRITICAL -> "⚠️"
                WarningLevel.BLOCKED -> "😴"
            }
            
            Text(
                text = emoji,
                fontSize = 48.sp
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = formatTime(timeRemaining),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
            
            Text(
                text = if (warningLevel == WarningLevel.BLOCKED) "de tiempo" else "minutos restantes",
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor.copy(alpha = 0.8f)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            LinearProgressIndicator(
                progress = { calculateProgress(timeUsedToday, dailyLimit) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = contentColor,
                trackColor = contentColor.copy(alpha = 0.2f),
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "$timeUsedToday / $dailyLimit min hoy",
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun NextBlockCard(
    nextBlockTime: Instant,
    warningLevel: WarningLevel,
    copy: com.tudominio.parentalcontrol.copy.BlockCopy
) {
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    val blockTimeStr = nextBlockTime.atZone(ZoneId.systemDefault())
        .format(timeFormatter)
    
    val message = when (warningLevel) {
        WarningLevel.NONE -> "Podrás usarlo hasta las $blockTimeStr"
        WarningLevel.WARNING -> "Bloqueo en ${blockTimeStr}"
        WarningLevel.CRITICAL -> "Bloqueo قريب! ($blockTimeStr)"
        WarningLevel.BLOCKED -> "Bloqueado hasta mañana"
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.DateRange,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column {
                Text(
                    text = copy.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun WarningBanner(
    warningLevel: WarningLevel,
    timeRemaining: Long
) {
    val (backgroundColor, text, icon) = when (warningLevel) {
        WarningLevel.WARNING -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            "⏰ Te quedan $timeRemaining minutos",
            Icons.Default.Warning
        )
        WarningLevel.CRITICAL -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            "⚠️ ¡$timeRemaining minutos! El tiempo se acaba",
            Icons.Default.Warning
        )
        WarningLevel.BLOCKED -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            "🔒 Sin tiempo restante hoy",
            Icons.Default.Lock
        )
        else -> return
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun AllowedAppsCard(
    apps: List<String>,
    copyManager: CopyManager
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "📱 Apps disponibles",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            apps.forEach { app ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = app,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            
            if (apps.size >= 3) {
                Text(
                    text = "+ más apps",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ExtraTimeButton(
    hasPendingRequest: Boolean,
    copy: com.tudominio.parentalcontrol.copy.ExtraTimeCopy,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        enabled = !hasPendingRequest,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (hasPendingRequest) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.secondary
            }
        )
    ) {
        Icon(
            Icons.Default.Add,
            contentDescription = null
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (hasPendingRequest) copy.pending else copy.request,
            style = MaterialTheme.typography.titleMedium
        )
    }
}

/**
 * WU-D follow-up — one-time informational banner rendered at the top
 * of the child status content when Device Admin is not yet active
 * (fresh pairing + "Más tarde" or never confirmed). Tap launches the
 * system activation flow via [LockManager.getEnableAdminIntent]; the
 * result is re-checked in ChildStatusScreen's launcher callback so a
 * cancellation leaves the banner in place.
 *
 * Exposed `internal` so the renderer test can mount it without
 * spinning up the full ChildStatusViewModel. Test tags are part of
 * the public surface — do not rename without updating
 * ChildStatusDeviceAdminBannerRendererTest.
 */
@Composable
internal fun DeviceAdminActivationBanner(
    onActivate: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("child_status_admin_banner"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.AdminPanelSettings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Activa el control parental",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    text = "Permite que el panel parental bloquee este dispositivo remotamente.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = onActivate,
                modifier = Modifier.testTag("child_status_admin_activate_button")
            ) {
                Text("Activar")
            }
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Info,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(onClick = onRetry) {
            Text("Reintentar")
        }
    }
}

private fun formatTime(minutes: Long): String {
    return when {
        minutes <= 0 -> "0"
        minutes < 60 -> minutes.toString()
        else -> {
            val hours = minutes / 60
            val mins = minutes % 60
            if (mins > 0) "${hours}h ${mins}m" else "${hours}h"
        }
    }
}

private fun calculateProgress(used: Long, limit: Long): Float {
    if (limit <= 0) return 0f
    return (used.toFloat() / limit.toFloat()).coerceIn(0f, 1f)
}
