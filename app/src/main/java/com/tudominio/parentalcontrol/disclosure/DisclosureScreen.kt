package com.tudominio.parentalcontrol.disclosure

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tudominio.parentalcontrol.consent.ConsentManager
import com.tudominio.parentalcontrol.copy.CopyManager

/**
 * Pantalla de divulgación prominente.
 * 
 * §0.6: Divulgación prominente que cumple los requisitos:
 * - Dentro de la app
 * - Durante uso normal
 * - Describe datos de accesibilidad y su uso/compartición
 * - Acción afirmativa para consentir
 * - Separada de la política de privacidad
 */
@Composable
fun DisclosureScreen(
    copyManager: CopyManager,
    consentManager: ConsentManager,
    onConsentGiven: () -> Unit,
    onDecline: () -> Unit
) {
    val disclosure = copyManager.getDisclosure()
    var showDeclineDialog by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        // Header
        Text(
            text = disclosure.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = disclosure.subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Punto 1: Monitoreo de apps
        DisclosurePoint(
            emoji = "📱",
            title = disclosure.point1Title,
            description = disclosure.point1Desc
        )
        
        // Punto 2: Acceso de accesibilidad
        DisclosurePoint(
            emoji = "👁️",
            title = disclosure.point2Title,
            description = disclosure.point2Desc
        )
        
        // Punto 3: Compartir información
        DisclosurePoint(
            emoji = "📤",
            title = disclosure.point3Title,
            description = disclosure.point3Desc
        )
        
        // Punto 4: Siempre visible
        DisclosurePoint(
            emoji = "🔓",
            title = disclosure.point4Title,
            description = disclosure.point4Desc
        )
        
        // Punto 5: Tú decides
        DisclosurePoint(
            emoji = "✅",
            title = disclosure.point5Title,
            description = disclosure.point5Desc
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Pregunta de consentimiento
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Text(
                    text = disclosure.consentTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = disclosure.consentQuestion,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Botones de consentimiento
        Button(
            onClick = {
                consentManager.giveDisclosureConsent()
                onConsentGiven()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(
                text = disclosure.consentYes,
                style = MaterialTheme.typography.titleMedium
            )
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        OutlinedButton(
            onClick = { showDeclineDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = disclosure.consentNo)
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = disclosure.consentNote,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
    
    // Diálogo de declined
    if (showDeclineDialog) {
        AlertDialog(
            onDismissRequest = { showDeclineDialog = false },
            title = { Text("¿Seguramente quieres salir?") },
            text = {
                Text("Si no aceptas, la app no podrá funcionar y tendrás que configurarla de nuevo más tarde.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeclineDialog = false
                        onDecline()
                    }
                ) {
                    Text("Salir")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeclineDialog = false }) {
                    Text("Quedarme")
                }
            }
        )
    }
}

/**
 * Punto individual de la divulgación.
 */
@Composable
private fun DisclosurePoint(
    emoji: String,
    title: String,
    description: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = emoji,
                style = MaterialTheme.typography.headlineMedium
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
