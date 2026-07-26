package com.tudominio.parentalcontrol.onboarding

import android.app.Activity
import android.content.Intent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tudominio.parentalcontrol.copy.CopyManager
import kotlinx.coroutines.delay

/**
 * Pantalla principal del onboarding con progreso real.
 * 
 * §0.2: Barra de progreso basada en T12 (nunca inflado).
 * §0.9: Onboarding reanudable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    copyManager: CopyManager,
    onboardingManager: OnboardingStateManager,
    onComplete: () -> Unit,
    onExit: () -> Unit
) {
    val currentStep by onboardingManager.currentStep.collectAsState()
    val progress by onboardingManager.progress.collectAsState()
    
    // Launcher para volver de ajustes
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Verificar estado al volver
        onboardingManager.verifyAndAdvanceIfReady()
    }
    
    // Mostrar demo del overlay
    var showOverlayDemo by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Setup") },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Salir")
                    }
                },
                actions = {
                    // Barra de progreso
                    ProtectionProgressBar(
                        progress = progress,
                        modifier = Modifier.padding(end = 16.dp)
                    )
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Contenido del paso actual
            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    slideInHorizontally { width -> width } + fadeIn() togetherWith
                            slideOutHorizontally { width -> -width } + fadeOut()
                },
                label = "step_transition"
            ) { step ->
                when (step) {
                    OnboardingStep.PAIRING -> PairingStepContent(
                        onComplete = { onboardingManager.completeStep(step); onboardingManager.advanceToNextStep() }
                    )
                    
                    OnboardingStep.CONSENT -> ConsentStepContent(
                        onComplete = { onboardingManager.completeStep(step); onboardingManager.advanceToNextStep() }
                    )
                    
                    OnboardingStep.ACCESSIBILITY -> PermissionStep(
                        title = "Acceso de Accesibilidad",
                        description = "Necesitamos esto para saber qué apps usas y bloquear las que no están permitidas.",
                        icon = "👁️",
                        isRequired = true,
                        onRequestPermission = {
                            val intent = onboardingManager.getSettingsIntent(step)
                            settingsLauncher.launch(intent)
                        },
                        isCompleted = onboardingManager.isStepCompleted(step),
                        onComplete = { onboardingManager.completeStep(step); onboardingManager.advanceToNextStep() }
                    )
                    
                    OnboardingStep.FIRST_WIN -> FirstWinStep(
                        copyManager = copyManager,
                        onDemo = { showOverlayDemo = true },
                        onComplete = {
                            onboardingManager.completeStep(step)
                            AnalyticsEmitter.emit(OnboardingAnalyticsEvent.FirstWin)
                            onboardingManager.advanceToNextStep()
                        }
                    )
                    
                    OnboardingStep.OVERLAY -> PermissionStep(
                        title = "Mostrar sobre apps",
                        description = "Necesitamos esto para mostrarte mensajes cuando una app está bloqueada.",
                        icon = "🔲",
                        isRequired = true,
                        onRequestPermission = {
                            val intent = onboardingManager.getSettingsIntent(step)
                            settingsLauncher.launch(intent)
                        },
                        isCompleted = onboardingManager.isStepCompleted(step),
                        onComplete = { onboardingManager.completeStep(step); onboardingManager.advanceToNextStep() }
                    )
                    
                    OnboardingStep.BATTERY -> PermissionStep(
                        title = "Optimización de batería",
                        description = "Desactiva la optimización para que el control funcione sin interrupciones.",
                        icon = "🔋",
                        isRequired = false,
                        onRequestPermission = {
                            val intent = onboardingManager.getSettingsIntent(step)
                            settingsLauncher.launch(intent)
                        },
                        isCompleted = onboardingManager.isStepCompleted(step),
                        onComplete = { onboardingManager.completeStep(step); onboardingManager.advanceToNextStep() }
                    )
                    
                    OnboardingStep.NOTIFICATIONS -> PermissionStep(
                        title = "Notificaciones",
                        description = "Recibe alertas cuando tus padres respondan a tus solicitudes.",
                        icon = "🔔",
                        isRequired = false,
                        onRequestPermission = {
                            val intent = onboardingManager.getSettingsIntent(step)
                            settingsLauncher.launch(intent)
                        },
                        isCompleted = onboardingManager.isStepCompleted(step),
                        onComplete = { onboardingManager.completeStep(step); onboardingManager.advanceToNextStep() }
                    )
                    
                    OnboardingStep.DEVICE_ADMIN -> PermissionStep(
                        title = "Administrador del dispositivo",
                        description = "Permite bloquear el dispositivo en momentos de descanso.",
                        icon = "🔐",
                        isRequired = false,
                        onRequestPermission = {
                            val intent = onboardingManager.getSettingsIntent(step)
                            settingsLauncher.launch(intent)
                        },
                        isCompleted = onboardingManager.isStepCompleted(step),
                        onComplete = {
                            onboardingManager.completeStep(step)
                            onboardingManager.completeOnboarding()
                            onComplete()
                        }
                    )
                }
            }
        }
    }
}

/**
 * Barra de progreso "Protección N de M".
 * 
 * §0.2: Refleja el estado REAL de T12.
 */
@Composable
fun ProtectionProgressBar(
    progress: OnboardingProgress,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "🛡️",
            style = MaterialTheme.typography.bodyMedium
        )
        
        LinearProgressIndicator(
            progress = { progress.completed.toFloat() / progress.total.toFloat() },
            modifier = Modifier
                .width(60.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        
        Text(
            text = "${progress.completed}/${progress.total}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Contenido del paso de emparejamiento.
 */
@Composable
private fun PairingStepContent(
    onComplete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "📱",
            style = MaterialTheme.typography.displayLarge
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "Empieza emparejando",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Escanea el código QR que tus padres te den para conectar este dispositivo.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(48.dp))
        
        // Placeholder para QR
        Card(
            modifier = Modifier.size(200.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("QR Scanner")
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = onComplete,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Ya estoy emparejado")
        }
    }
}

/**
 * Contenido del paso de consentimiento.
 */
@Composable
private fun ConsentStepContent(
    onComplete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "📋",
            style = MaterialTheme.typography.displayLarge
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "Entiende cómo funciona",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Esta app monitorea el uso de apps para ayudarte a mantener hábitos saludables. Tus padres pueden ver cuánto tiempo usas cada app.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Text(
                text = "🔒 Lo que NO hacemos:\n• Leer tus mensajes\n• Escuchar tus llamadas\n• Rastrear tu ubicación\n• Grabar con la cámara",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp)
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = onComplete,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Entiendo, continuar")
        }
    }
}

/**
 * Paso de permiso genérico.
 */
@Composable
private fun PermissionStep(
    title: String,
    description: String,
    icon: String,
    isRequired: Boolean,
    onRequestPermission: () -> Unit,
    isCompleted: Boolean,
    onComplete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = icon,
            style = MaterialTheme.typography.displayLarge
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        if (isCompleted) {
            // Ya completado
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("¡Listo!")
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Button(
                onClick = onComplete,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Continuar")
            }
        } else {
            Button(
                onClick = onRequestPermission,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isRequired) "Dar permiso" else "Configurar")
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            if (!isRequired) {
                TextButton(onClick = onComplete) {
                    Text("Omitir por ahora")
                }
            }
        }
    }
}

/**
 * Paso de "Primera victoria" - demo del overlay.
 */
@Composable
private fun FirstWinStep(
    copyManager: CopyManager,
    onDemo: () -> Unit,
    onComplete: () -> Unit
) {
    var showDemo by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        delay(500)
        showDemo = true
    }
    
    LaunchedEffect(showDemo) {
        if (showDemo) {
            delay(3000)
            showDemo = false
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Demo del overlay
        Box(
            modifier = Modifier
                .size(200.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            if (showDemo) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "🛡️",
                        style = MaterialTheme.typography.displayMedium
                    )
                    Text(
                        text = "¡Protegido!",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Text(
            text = "¡Tu protección funciona!",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Así es como te avisaremos cuando una app esté bloqueada. Ahora vamos a configurar los permisos finales para completar tu protección.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = { showDemo = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Ver demo")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Button(
            onClick = onComplete,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("¡Genial, continuar!")
        }
    }
}
