package com.tudominio.parentalcontrol.ui.child.components

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tudominio.parentalcontrol.copy.CopyManager
import com.tudominio.parentalcontrol.copy.RepairCopy
import com.tudominio.parentalcontrol.health.DegradationAlertManager
import com.tudominio.parentalcontrol.health.HealthChecker
import com.tudominio.parentalcontrol.health.Permission

@Composable
fun DegradedAlertDialog(
    causes: List<DegradationAlertManager.DegradationCause>,
    copy: RepairCopy,
    onRepair: (DegradationAlertManager.DegradationCause) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (causes.isEmpty()) return
    
    val context = LocalContext.current
    val healthChecker = remember { HealthChecker(context) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.errorContainer,
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = copy.alertTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = copy.alertSubtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                causes.forEach { cause ->
                    CauseCard(
                        cause = cause,
                        copy = copy,
                        onRepair = {
                            val intent = getIntentForPermission(healthChecker, cause.permission)
                            onRepair(cause)
                            context.startActivity(intent)
                        }
                    )
                    
                    if (cause != causes.last()) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}

@Composable
private fun CauseCard(
    cause: DegradationAlertManager.DegradationCause,
    copy: RepairCopy,
    onRepair: () -> Unit
) {
    val (title, description) = when (cause.permission) {
        Permission.ACCESSIBILITY_SERVICE -> copy.accessibilityCause to copy.accessibilityDesc
        Permission.OVERLAY_PERMISSION -> copy.overlayCause to copy.overlayDesc
        Permission.BATTERY_OPTIMIZATION -> copy.batteryCause to copy.batteryDesc
        Permission.USAGE_STATS -> copy.usageCause to copy.usageDesc
        Permission.DEVICE_ADMIN -> copy.adminCause to copy.adminDesc
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Button(
                onClick = onRepair,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    Icons.Default.Build,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(copy.repairButton)
            }
        }
    }
}

@Composable
fun RecoveryDialog(
    copy: RepairCopy,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = copy.recoveryTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        },
        text = {
            Text(
                text = copy.recoveryMessage,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        },
        dismissButton = {}
    )
}

private fun getIntentForPermission(
    healthChecker: HealthChecker,
    permission: Permission
): Intent {
    return when (permission) {
        Permission.ACCESSIBILITY_SERVICE -> healthChecker.getAccessibilitySettingsIntent()
        Permission.OVERLAY_PERMISSION -> healthChecker.getOverlaySettingsIntent()
        Permission.BATTERY_OPTIMIZATION -> healthChecker.getBatteryOptimizationIntent()
        Permission.USAGE_STATS -> healthChecker.getUsageStatsIntent()
        Permission.DEVICE_ADMIN -> healthChecker.getDeviceAdminIntent()
    }
}
