package com.tudominio.parentalcontrol.disclosure

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tudominio.parentalcontrol.copy.CopyManager

/**
 * Pantalla de transparencia: qué se monitorea y qué no.
 * 
 * §0.6: Siempre accesible para el menor.
 * No hay modo oculto.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransparencyScreen(
    copyManager: CopyManager,
    onBack: () -> Unit
) {
    val transparency = copyManager.getTransparency()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(transparency.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Subtítulo
            Text(
                text = transparency.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Sección: Qué se monitorea
            TransparencySection(
                title = transparency.monitoredTitle,
                icon = "📊"
            ) {
                TransparencyItem(
                    text = transparency.appsUsed,
                    isMonitored = true
                )
                TransparencyItem(
                    text = transparency.screenTime,
                    isMonitored = true
                )
                TransparencyItem(
                    text = transparency.blockedAttempts,
                    isMonitored = true
                )
                TransparencyItem(
                    text = transparency.requests,
                    isMonitored = true
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Sección: Qué NO se monitorea
            TransparencySection(
                title = transparency.notMonitoredTitle,
                icon = "🔒"
            ) {
                TransparencyItem(
                    text = transparency.notMessages,
                    isMonitored = false
                )
                TransparencyItem(
                    text = transparency.notCalls,
                    isMonitored = false
                )
                TransparencyItem(
                    text = transparency.notBrowsing,
                    isMonitored = false
                )
                TransparencyItem(
                    text = transparency.notLocation,
                    isMonitored = false
                )
                TransparencyItem(
                    text = transparency.notCamera,
                    isMonitored = false
                )
                TransparencyItem(
                    text = transparency.notMicrophone,
                    isMonitored = false
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Sección: Privacidad
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🔐",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = transparency.privacyTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = transparency.privacyDesc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Sección: Cómo acceder
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "📱",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = transparency.accessTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = transparency.accessDesc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }
    }
}

/**
 * Sección de transparencia.
 */
@Composable
private fun TransparencySection(
    title: String,
    icon: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = icon,
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            content()
        }
    }
}

/**
 * Item de transparencia (qué se monitorea o no).
 */
@Composable
private fun TransparencyItem(
    text: String,
    isMonitored: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (isMonitored) "✓" else "✗",
            color = if (isMonitored) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.secondary
            },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
