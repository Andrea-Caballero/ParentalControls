package com.tudominio.parentalcontrol.ui.reinforced

import androidx.compose.animation.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Pantalla de oferta re-temporizada para el modo reforzado.
 * 
 * T31: Oferta re-temporizada
 * - En STANDARD no exige reset
 * - Ofrece "modo reforzado" como tarjeta honesta
 * - Guía el OOBE solo cuando hay un equipo nuevo/dedicado
 * 
 * §0.2: Device Owner = hard enforcement, STANDARD = soft enforcement
 */
@Composable
fun ReinforcedModeOfferScreen(
    currentLevel: String,
    isNewDevice: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Icon(
            Icons.Default.Star,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "¿Quieres mayor protección?",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Hay una forma de hacer que el control parental sea más difícil de desactivar.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Comparación de niveles
        ComparisonCard(
            currentLevel = currentLevel,
            reinforcedLevel = "Modo Reforzado (Device Owner)"
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Características del modo reforzado
        ReinforcedFeaturesCard()
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Warning sobre factory reset
        if (isNewDevice) {
            WarningCard(
                message = "Un dispositivo nuevo es el momento perfecto para configurar el modo reforzado."
            )
        } else {
            InfoCard(
                message = "El modo reforzado requiere configurar el teléfono desde cero. Considera usarlo en un dispositivo dedicado."
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Botones
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onDecline,
                modifier = Modifier.weight(1f)
            ) {
                Text("Quizás luego")
            }
            
            Button(
                onClick = onAccept,
                modifier = Modifier.weight(1f)
            ) {
                Text("Configurar")
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Nota de que no es obligatorio
        Text(
            text = "El modo estándar funciona bien para la mayoría de las familias. No necesitas configurar nada extra.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Tarjeta comparativa de niveles.
 */
@Composable
private fun ComparisonCard(
    currentLevel: String,
    reinforcedLevel: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Comparación",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Modo Actual",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = currentLevel,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                
                Icon(
                    Icons.Default.List,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = "Modo Reforzado",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Device Owner",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/**
 * Características del modo reforzado.
 */
@Composable
private fun ReinforcedFeaturesCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Modo Reforzado incluye:",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            FeatureItem(
                icon = Icons.Default.Warning,
                title = "Bloqueo de desinstalación",
                description = "La app no se puede desinstalar"
            )
            
            FeatureItem(
                icon = Icons.Default.Warning,
                title = "Suspensión de apps",
                description = "Apps bloqueadas no se pueden abrir"
            )
            
            FeatureItem(
                icon = Icons.Default.Warning,
                title = "Ocultar apps",
                description = "Apps inapropiadas no aparecen"
            )
            
            FeatureItem(
                icon = Icons.Default.Warning,
                title = "Protección de reset",
                description = "Requiere permiso para restaurar de fábrica"
            )
        }
    }
}

/**
 * Item de característica.
 */
@Composable
private fun FeatureItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Tarjeta de advertencia.
 */
@Composable
private fun WarningCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

/**
 * Tarjeta informativa.
 */
@Composable
private fun InfoCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
