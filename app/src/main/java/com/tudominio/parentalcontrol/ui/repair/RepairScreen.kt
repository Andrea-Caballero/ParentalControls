package com.tudominio.parentalcontrol.ui.repair

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Pantalla de reparar: detecta permisos degradados y guía al usuario.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepairScreen(
    viewModel: RepairViewModel,
    onBack: () -> Unit,
    onFullyRepaired: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val issues by viewModel.issues.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val isFullyRepaired by viewModel.isFullyRepaired.collectAsState()
    
    // Launcher para deep links
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Re-check when returning from settings
        viewModel.checkHealth()
    }
    
    // Handle fully repaired
    LaunchedEffect(isFullyRepaired) {
        if (isFullyRepaired) {
            // Small delay to show success state
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reparar") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver")
                    }
                },
                actions = {
                    // Refresh button
                    IconButton(onClick = { viewModel.checkHealth() }) {
                        Icon(Icons.Default.Refresh, "Actualizar")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (val state = uiState) {
                is RepairUiState.Loading -> {
                    LoadingContent()
                }
                
                is RepairUiState.HasIssues -> {
                    IssuesContent(
                        issues = issues,
                        progress = progress,
                        onFixIssue = { issue ->
                            settingsLauncher.launch(issue.deepLinkIntent)
                        },
                        onFixAllCritical = {
                            val critical = issues.firstOrNull { it.severity == IssueSeverity.CRITICAL }
                            critical?.let { settingsLauncher.launch(it.deepLinkIntent) }
                        }
                    )
                }
                
                is RepairUiState.Repaired -> {
                    RepairedContent(
                        progress = progress,
                        onDone = onFullyRepaired
                    )
                }
                
                is RepairUiState.Error -> {
                    ErrorContent(
                        message = state.message,
                        onRetry = { viewModel.checkHealth() }
                    )
                }
            }
        }
    }
}

/**
 * Contenido de carga.
 */
@Composable
private fun LoadingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text("Verificando estado...")
        }
    }
}

/**
 * Contenido cuando hay problemas.
 */
@Composable
private fun IssuesContent(
    issues: List<RepairIssue>,
    progress: RepairProgress,
    onFixIssue: (RepairIssue) -> Unit,
    onFixAllCritical: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Header con progreso
        ProgressHeader(progress = progress)
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Issues críticos primero
        val criticalIssues = issues.filter { it.severity == IssueSeverity.CRITICAL }
        val warningIssues = issues.filter { it.severity == IssueSeverity.WARNING }
        
        if (criticalIssues.isNotEmpty()) {
            Text(
                text = "⚠️ Problemas que bloquean el control",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            criticalIssues.forEach { issue ->
                IssueCard(
                    issue = issue,
                    onFix = { onFixIssue(issue) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Button(
                onClick = onFixAllCritical,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.Build, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Reparar todos")
            }
            
            Spacer(modifier = Modifier.height(24.dp))
        }
        
        if (warningIssues.isNotEmpty()) {
            Text(
                text = "💡 Mejoras recomendadas",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.tertiary
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            warningIssues.forEach { issue ->
                IssueCard(
                    issue = issue,
                    onFix = { onFixIssue(issue) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

/**
 * Header con barra de progreso.
 */
@Composable
private fun ProgressHeader(progress: RepairProgress) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Estado de protección",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${progress.passed}/${progress.total}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            LinearProgressIndicator(
                progress = { progress.percentage / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = when {
                    progress.percentage >= 80 -> MaterialTheme.colorScheme.primary
                    progress.percentage >= 50 -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.error
                },
                trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = when {
                    progress.percentage >= 80 -> "Protección casi completa"
                    progress.percentage >= 50 -> "Protección parcial"
                    else -> "Protección limitada"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Tarjeta de issue individual.
 */
@Composable
private fun IssueCard(
    issue: RepairIssue,
    onFix: () -> Unit
) {
    val (icon, iconColor, backgroundColor) = when (issue.severity) {
        IssueSeverity.CRITICAL -> Triple(
            Icons.Default.Warning,
            MaterialTheme.colorScheme.error,
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
        IssueSeverity.WARNING -> Triple(
            Icons.Default.Info,
            MaterialTheme.colorScheme.tertiary,
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
        )
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(iconColor.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(24.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // Content
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = issue.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = issue.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.width(8.dp))
            
            // Action button
            FilledTonalButton(
                onClick = onFix,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text("Arreglar")
            }
        }
    }
}

/**
 * Contenido cuando está completamente reparado.
 */
@Composable
private fun RepairedContent(
    progress: RepairProgress,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Success icon
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Text(
            text = "¡Todo funciona!",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Tu control parental está completamente configurado y funcionando.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Progress summary
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "${progress.passed}/${progress.total} protecciones activas",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        
        Spacer(modifier = Modifier.height(48.dp))
        
        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Continuar")
        }
    }
}

/**
 * Contenido de error.
 */
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
            text = "Error verificando estado",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(onClick = onRetry) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Reintentar")
        }
    }
}
