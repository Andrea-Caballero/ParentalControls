package com.tudominio.parentalcontrol.ui.child.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tudominio.parentalcontrol.viewmodel.GrantUi

/**
 * Banner que muestra tiempo extra disponible.
 */
@Composable
fun ExtraTimeBanner(
    minutesAvailable: Long,
    countdownSeconds: Long?,
    grants: List<GrantUi>,
    modifier: Modifier = Modifier
) {
    if (minutesAvailable <= 0 && grants.isEmpty()) {
        return
    }

    val isExpiringSoon = grants.any { it.isExpiringSoon }
    val backgroundColor = when {
        isExpiringSoon -> MaterialTheme.colorScheme.tertiaryContainer
        minutesAvailable > 30 -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.AddCircle,
                    contentDescription = null,
                    tint = if (isExpiringSoon)
                        MaterialTheme.colorScheme.tertiary
                    else
                        MaterialTheme.colorScheme.secondary
                )
                Column {
                    Text(
                        "+${minutesAvailable} min extra",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    if (grants.size == 1) {
                        Text(
                            "1 grant activo",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (grants.isNotEmpty()) {
                        Text(
                            "${grants.size} grants activos",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            countdownSeconds?.let { seconds ->
                ExtraTimeCountdown(seconds = seconds)
            }
        }
    }
}

/**
 * Muestra countdown de expiración.
 */
@Composable
fun ExtraTimeCountdown(
    seconds: Long,
    modifier: Modifier = Modifier
) {
    val minutes = seconds / 60
    val secs = seconds % 60
    
    val text = when {
        minutes > 0 -> "${minutes}m ${secs}s"
        else -> "${secs}s"
    }

    val color = when {
        seconds < 60 -> MaterialTheme.colorScheme.error
        seconds < 300 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.1f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = color
            )
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = color,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Tarjeta de grant de tiempo extra.
 */
@Composable
fun GrantCard(
    grant: GrantUi,
    modifier: Modifier = Modifier
) {
    val backgroundColor = when {
        grant.isExpiringSoon -> MaterialTheme.colorScheme.errorContainer
        grant.minutesRemaining < 10 -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        if (grant.isExpiringSoon) Icons.Default.Warning else Icons.Default.AddCircle,
                        contentDescription = null,
                        tint = if (grant.isExpiringSoon)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.secondary
                    )
                    Column {
                        Text(
                            "+${grant.minutesRemaining} min",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Tiempo extra",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (grant.isExpiringSoon) {
                    AssistChip(
                        onClick = { },
                        label = { Text("Expira pronto") },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            val progress = grant.minutesRemaining.toFloat() / grant.minutesTotal.toFloat()
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = if (grant.isExpiringSoon)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.secondary,
                trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
            )

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "${grant.minutesRemaining} min de ${grant.minutesTotal}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Diálogo para confirmar uso de tiempo extra.
 */
@Composable
fun UseExtraTimeDialog(
    minutesAvailable: Long,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedMinutes by remember { mutableIntStateOf(minOf(5, minutesAvailable.toInt())) }
    val options = remember(minutesAvailable) {
        listOf(5, 10, 15, 30).filter { it <= minutesAvailable }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.AddCircle, null, tint = MaterialTheme.colorScheme.secondary)
                Text("Usar tiempo extra")
            }
        },
        text = {
            Column {
                Text(
                    "Tienes $minutesAvailable minutos disponibles.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))

                Text("¿Cuántos minutos quieres usar?", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { mins ->
                        FilterChip(
                            selected = selectedMinutes == mins,
                            onClick = { selectedMinutes = mins },
                            label = { Text("$mins") }
                        )
                    }
                }

                if (minutesAvailable - selectedMinutes > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Quedarán ${minutesAvailable - selectedMinutes} min",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selectedMinutes) }) {
                Text("Usar $selectedMinutes min")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

/**
 * Notificación de aprobación.
 */
@Composable
fun ApprovalNotification(
    message: String,
    onDismiss: () -> Unit
) {
    Snackbar(
        modifier = Modifier.padding(16.dp),
        action = {
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        },
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Check, null, modifier = Modifier.size(20.dp))
            Text(message)
        }
    }
}
