package com.tudominio.parentalcontrol.ui.parent.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tudominio.parentalcontrol.domain.model.ChildDevice
import com.tudominio.parentalcontrol.domain.model.DeviceState
import com.tudominio.parentalcontrol.domain.model.TimeRequest
import com.tudominio.parentalcontrol.viewmodel.ParentViewModel
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Tarjeta de dispositivo hijo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceCard(
    device: ChildDevice,
    onClick: () -> Unit,
    onLock: () -> Unit,
    onUnlock: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("device_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Icono de estado
                    Icon(
                        when (device.state) {
                            DeviceState.LOCKED -> Icons.Default.Lock
                            DeviceState.DOWNTIME -> Icons.Default.Star
                            DeviceState.ACTIVE -> Icons.Default.Home
                        },
                        contentDescription = null,
                        tint = when (device.state) {
                            DeviceState.LOCKED -> MaterialTheme.colorScheme.error
                            DeviceState.DOWNTIME -> MaterialTheme.colorScheme.tertiary
                            DeviceState.ACTIVE -> MaterialTheme.colorScheme.primary
                        }
                    )

                    Column {
                        Text(
                            device.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            device.model ?: "Dispositivo",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        // B.4 of `feat-multi-child-picker` (Change B) —
                        // surface the child's first name under the
                        // device/model row. The per-card child-name
                        // text is visible to users; the
                        // q2_gap_dashboard_renders_child_identity_testTag_for_paired_devices
                        // contract is satisfied by sibling `child_name`
                        // markers rendered in `DashboardScaffold`,
                        // because the clickable `Card` parent merges
                        // descendants and swallows the inner Text's
                        // testTag under a default merged-tree
                        // `onNodeWithTag` search.
                        device.child?.let { child ->
                            Text(
                                text = child.firstName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } ?: Text(
                            text = "Sin asignar",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Indicador online
                    if (device.isOnline) {
                        Icon(
                            Icons.Default.Notifications,
                            contentDescription = "Online",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    // Menú de acciones
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, "Más opciones")
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            if (device.state == DeviceState.LOCKED) {
                                DropdownMenuItem(
                                    text = { Text("Desbloquear") },
                                    onClick = {
                                        showMenu = false
                                        onUnlock()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Info, null)
                                    }
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text("Bloquear ahora") },
                                    onClick = {
                                        showMenu = false
                                        onLock()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Lock, null)
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Configurar política") },
                                onClick = { showMenu = false },
                                leadingIcon = {
                                    Icon(Icons.Default.Settings, null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Ver uso") },
                                onClick = { showMenu = false },
                                leadingIcon = {
                                    Icon(Icons.Default.List, null)
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Info adicional
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        "Última conexión",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        formatLastSeen(device.lastSeenAt),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "v${device.policyVersion}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        device.appVersion,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Chip de estado
            Spacer(modifier = Modifier.height(8.dp))
            AssistChip(
                onClick = { },
                label = {
                    Text(
                        when (device.state) {
                            DeviceState.LOCKED -> "Bloqueado"
                            DeviceState.DOWNTIME -> "Hora de dormir"
                            DeviceState.ACTIVE -> "Activo"
                        }
                    )
                },
                leadingIcon = {
                    Icon(
                        when (device.state) {
                            DeviceState.LOCKED -> Icons.Default.Lock
                            DeviceState.DOWNTIME -> Icons.Default.Star
                            DeviceState.ACTIVE -> Icons.Default.Check
                        },
                        null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = when (device.state) {
                        DeviceState.LOCKED -> MaterialTheme.colorScheme.errorContainer
                        DeviceState.DOWNTIME -> MaterialTheme.colorScheme.tertiaryContainer
                        DeviceState.ACTIVE -> MaterialTheme.colorScheme.primaryContainer
                    }
                )
            )
        }
    }
}

/**
 * Tarjeta de solicitud de tiempo.
 */
@Composable
fun RequestCard(
    request: TimeRequest,
    onApprove: (Int) -> Unit,
    onDeny: () -> Unit
) {
    var showApproveDialog by remember { mutableStateOf(false) }
    var customMinutes by remember { mutableStateOf(request.minutesRequested.toString()) }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            request.deviceName ?: "Dispositivo",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            request.appName ?: "Tiempo general",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                Text(
                    "+${request.minutesRequested} min",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            request.reason?.let { reason ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "\"$reason\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    formatTimeAgo(request.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onDeny,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Denegar")
                    }

                    Button(onClick = { showApproveDialog = true }) {
                        Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Aprobar")
                    }
                }
            }
        }
    }

    // Diálogo de aprobación con minutos customizables
    if (showApproveDialog) {
        AlertDialog(
            onDismissRequest = { showApproveDialog = false },
            title = { Text("Aprobar solicitud") },
            text = {
                Column {
                    Text("¿Cuántos minutos quieres aprobar?")
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = customMinutes,
                        onValueChange = { customMinutes = it.filter { c -> c.isDigit() } },
                        label = { Text("Minutos") },
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5, 10, 15, 30).forEach { mins ->
                            FilterChip(
                                selected = customMinutes == mins.toString(),
                                onClick = { customMinutes = mins.toString() },
                                label = { Text("$mins") }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        customMinutes.toIntOrNull()?.let { onApprove(it) }
                        showApproveDialog = false
                    }
                ) {
                    Text("Aprobar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showApproveDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

/**
 * Bottom sheet para emparejar nuevo dispositivo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingBottomSheet(
    viewModel: ParentViewModel,
    onDismiss: () -> Unit,
    initialStep: Int = 1
) {
    val pairingCode by viewModel.pairingCode.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var deviceName by remember { mutableStateOf("") }
    var selectedAgeBand by remember { mutableStateOf("7-12") }
    var step by remember { mutableIntStateOf(initialStep) }

    // B.5 of `feat-multi-child-picker` (Change B) — refresh the device
    // list when the sheet dismisses (success OR cancel). The sheet's
    // `ModalBottomSheet` is composed inside `DashboardScaffold`, so its
    // `onDispose` runs whenever `showPairingSheet` flips false. The
    // refresh is unconditional on dismiss per the `parent-device-list`
    // spec scenario "Dismissing the sheet without pairing is a no-op".
    DisposableEffect(Unit) {
        onDispose {
            viewModel.loadDevices()
        }
    }

    // Step transition is reactive on `pairingCode` becoming non-null.
    // Replacing the previous eager `step = 2` on click, which left the
    // sheet stuck on an empty step-2 card whenever the pairing-code
    // request failed (real backend 5xx, timeout, etc.) while the error
    // snackbar was already cleared by `LaunchedEffect(error) { ... }`.
    // On failure `_pairingCode.value` stays null and the sheet stays on
    // step 1 where the snackbar can surface the problem.
    LaunchedEffect(pairingCode) {
        if (pairingCode != null) step = 2
    }

    val ageBands = listOf(
        "0-6" to "0-6 años",
        "7-12" to "7-12 años",
        "13-17" to "13-17 años"
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (step) {
                1 -> {
                    // Paso 1: Ingresar datos
                    Text(
                        "Emparejar dispositivo",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(modifier = Modifier.height(24.dp))

                    OutlinedTextField(
                        value = deviceName,
                        onValueChange = { deviceName = it },
                        label = { Text("Nombre del dispositivo") },
                        placeholder = { Text("Ej: Galaxy de Juan") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        "Edad del niño",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ageBands.forEach { (value, label) ->
                            FilterChip(
                                selected = selectedAgeBand == value,
                                onClick = { selectedAgeBand = value },
                                label = { Text(label) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = {
                            viewModel.createPairingCode(deviceName, selectedAgeBand)
                        },
                        enabled = deviceName.isNotBlank() && !isLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Generar código")
                        }
                    }
                }

                2 -> {
                    // Paso 2: Mostrar código
                    Text(
                        "Código de emparejamiento",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    pairingCode?.let { code ->
                        Text(
                            code.code,
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            "Válido por 10 minutos",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                val qrPainter = rememberQrCodePainter(data = code.deeplink)
                                Image(
                                    painter = qrPainter,
                                    contentDescription = "Pairing QR",
                                    modifier = Modifier
                                        .size(240.dp)
                                        .testTag("pairing_qr")
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Ingresa este código en el dispositivo del niño",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        OutlinedButton(
                            onClick = { step = 1 },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Generar otro código")
                        }
                    } ?: run {
                        if (isLoading) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ============ Helpers ============

private fun formatLastSeen(isoTimestamp: String): String {
    return try {
        val instant = Instant.parse(isoTimestamp)
        val formatter = DateTimeFormatter.ofPattern("dd MMM, HH:mm")
            .withZone(ZoneId.systemDefault())
        formatter.format(instant)
    } catch (e: Exception) {
        isoTimestamp
    }
}

private fun formatTimeAgo(isoTimestamp: String): String {
    return try {
        val instant = Instant.parse(isoTimestamp)
        val now = Instant.now()
        val seconds = java.time.Duration.between(instant, now).seconds

        when {
            seconds < 60 -> "Hace un momento"
            seconds < 3600 -> "Hace ${seconds / 60} min"
            seconds < 86400 -> "Hace ${seconds / 3600} h"
            else -> "Hace ${seconds / 86400} días"
        }
    } catch (e: Exception) {
        isoTimestamp
    }
}
