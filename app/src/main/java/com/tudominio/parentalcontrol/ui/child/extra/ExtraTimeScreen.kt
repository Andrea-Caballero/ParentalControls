package com.tudominio.parentalcontrol.ui.child.extra

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tudominio.parentalcontrol.copy.CopyManager
import com.tudominio.parentalcontrol.data.repository.TimeExtraRepository
import com.tudominio.parentalcontrol.data.repository.TimeRequestResult
import kotlinx.coroutines.launch

/**
 * Pantalla de solicitud de tiempo extra.
 * 
 * §0.4 paso 6: El grant levanta límites pero no desbloquea blocked/allow_only.
 * §0.9: Offline-tolerante con throttle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtraTimeScreen(
    copyManager: CopyManager,
    repository: TimeExtraRepository,
    deviceId: String,
    onBack: () -> Unit,
    onRequestSent: (String) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * T28 — Optional package the child was blocked on when they opened
     * this screen via the `parentalcontrol://request-extra-time?pkg=…`
     * deeplink. The grant is device-wide (see
     * [TimeExtraRepository.createTimeRequest]) so this is purely
     * informational — shown as a "Bloqueado en: X" badge to give the
     * child context for why they're asking. When `null` the badge is
     * hidden (the child opened the screen the normal way, from
     * `ChildStatusScreen`, with no specific app in mind).
     */
    prefilledPackage: String? = null
) {
    val scope = rememberCoroutineScope()
    val requestCopy = copyManager.getRequestTime()
    
    // Estado
    var selectedMinutes by remember { mutableIntStateOf(15) }
    var reason by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    // Opciones de minutos predefinidos
    val minuteOptions = listOf(5, 10, 15, 20, 30, 45, 60)
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(requestCopy.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            // T28: contexto de la app bloqueada cuando el chico llegó acá
            // desde el deeplink `parentalcontrol://request-extra-time?pkg=…`.
            // No se manda al backend (el grant es device-wide); sólo
            // informa al chico por qué está pidiendo.
            if (!prefilledPackage.isNullOrBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Block,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Bloqueado en",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = prefilledPackage,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Indicador de cuánto tiempo puedes pedir
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
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Puedes pedir entre 5 y 120 minutos",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Selector de minutos
            Text(
                text = requestCopy.howMuch,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Chips de minutos
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                minuteOptions.take(4).forEach { minutes ->
                    FilterChip(
                        selected = selectedMinutes == minutes,
                        onClick = { selectedMinutes = minutes },
                        label = { Text("$minutes") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                minuteOptions.drop(4).forEach { minutes ->
                    FilterChip(
                        selected = selectedMinutes == minutes,
                        onClick = { selectedMinutes = minutes },
                        label = { Text("$minutes") },
                        modifier = Modifier.weight(1f)
                    )
                }
                // Rellenar espacio restante
                Spacer(modifier = Modifier.weight(1f))
            }
            
            // Selector manual para más de 60
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedTextField(
                value = selectedMinutes.toString(),
                onValueChange = { value ->
                    value.toIntOrNull()?.let { mins ->
                        if (mins in 1..120) {
                            selectedMinutes = mins
                        }
                    }
                },
                label = { Text("Minutos exactos") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Motivo opcional
            Text(
                text = requestCopy.reason,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text(requestCopy.reasonPlaceholder) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                maxLines = 3
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Error
            errorMessage?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
            
            // Botón de enviar
            Button(
                onClick = {
                    scope.launch {
                        isLoading = true
                        errorMessage = null
                        
                        val result = repository.createTimeRequest(
                            deviceId = deviceId,
                            minutes = selectedMinutes,
                            reason = reason.takeIf { it.isNotBlank() }
                        )
                        
                        isLoading = false
                        
                        when (result) {
                            is TimeRequestResult.Success -> {
                                onRequestSent(result.requestId)
                            }
                            is TimeRequestResult.Throttled -> {
                                errorMessage = "Espera ${result.waitMinutes} minutos antes de pedir otra vez"
                            }
                            is TimeRequestResult.InvalidMinutes -> {
                                errorMessage = "Los minutos deben estar entre 5 y 120"
                            }
                            is TimeRequestResult.Error -> {
                                errorMessage = result.message
                                onError(result.message)
                            }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(Icons.Default.Send, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(requestCopy.send)
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Nota sobre offline
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Si no tienes internet, la solicitud se enviará cuando te conectes",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Pantalla de resultado de solicitud.
 */
@Composable
fun ExtraTimeResultScreen(
    requestId: String,
    repository: TimeExtraRepository,
    copyManager: CopyManager,
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val extraTimeCopy = copyManager.getExtraTime()
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Icono de éxito
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = extraTimeCopy.pending,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Enviamos tu solicitud a tus padres. Te avisaremos cuando la aprueben.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(48.dp))
        
        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Volver")
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        TextButton(onClick = onBack) {
            Text("Pedir más tiempo")
        }
    }
}
