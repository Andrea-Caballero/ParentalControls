package com.tudominio.parentalcontrol.ui.child.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import com.tudominio.parentalcontrol.reward.RewardGrantUi
import com.tudominio.parentalcontrol.reward.RewardHistoryItem
import java.time.Instant

/**
 * Banner de recompensa que muestra el saldo de tiempo ganado.
 */
@Composable
fun RewardBanner(
    balanceMinutes: Long,
    isNewReward: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (balanceMinutes <= 0) return
    
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Icono de trofeo
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "🎁 Tiempo ganado",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        
                        if (isNewReward) {
                            Badge(
                                containerColor = MaterialTheme.colorScheme.primary
                            ) {
                                Text("¡Nuevo!")
                            }
                        }
                    }
                    
                    Text(
                        text = "$balanceMinutes minutos disponibles",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            
            Icon(
                Icons.Default.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * Tarjeta de recompensa individual.
 */
@Composable
fun RewardCard(
    reward: RewardGrantUi,
    modifier: Modifier = Modifier
) {
    val backgroundColor = when {
        reward.isExpired -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        reward.isExpiringSoon -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
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
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = null,
                    tint = if (reward.isExpired) {
                        MaterialTheme.colorScheme.outline
                    } else {
                        MaterialTheme.colorScheme.secondary
                    }
                )
                
                Column {
                    Text(
                        text = if (reward.isExpired) {
                            "Recompensa usada"
                        } else {
                            "+${reward.minutesRemaining} min"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (reward.isExpired) {
                            MaterialTheme.colorScheme.outline
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        }
                    )
                    
                    if (!reward.isExpired && reward.expiresAt != null) {
                        Text(
                            text = "Expira en ${formatExpiryTime(reward.expiresAt!!)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (reward.isExpiringSoon) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                            }
                        )
                    }
                }
            }
            
            if (reward.isExpiringSoon && !reward.isExpired) {
                AssistChip(
                    onClick = { },
                    label = { Text("Expira pronto") },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                )
            }
        }
    }
}

/**
 * Confirmación de recompensa recibida.
 */
@Composable
fun RewardReceivedDialog(
    minutes: Int,
    totalBalance: Long,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(48.dp)
            )
        },
        title = {
            Text(
                "🎉 ¡Recompensa recibida!",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "+$minutes minutos",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    "Ahora tienes $totalBalance minutos de tiempo ganado.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("¡Genial!")
            }
        }
    )
}

/**
 * Historial de recompensas.
 */
@Composable
fun RewardHistorySection(
    history: List<RewardHistoryItem>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = "Historial de recompensas",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        if (history.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = "Aún no tienes recompensas. ¡Sigue así!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            history.take(5).forEach { item ->
                RewardHistoryItem(
                    item = item,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}

/**
 * Item individual del historial.
 */
@Composable
private fun RewardHistoryItem(
    item: RewardHistoryItem,
    modifier: Modifier = Modifier
) {
    val backgroundColor = when {
        item.isExpired -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        item.isActive -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
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
                    if (item.isExpired) Icons.Default.CheckCircle else Icons.Default.Star,
                    contentDescription = null,
                    tint = if (item.isExpired) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.secondary
                    }
                )
                
                Text(
                    text = "+${item.minutes} min",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
            
            Text(
                text = when {
                    item.isActive -> "Activa"
                    item.isExpired -> "Usada"
                    else -> "Expirada"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ============ Utilidades ============

/**
 * Formatea el tiempo hasta expiración.
 */
private fun formatExpiryTime(expiresAt: Instant): String {
    val now = Instant.now()
    val seconds = java.time.Duration.between(now, expiresAt).seconds
    
    return when {
        seconds <= 0 -> "expirado"
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}min"
        seconds < 86400 -> "${seconds / 3600}h"
        else -> "${seconds / 86400}d"
    }
}
