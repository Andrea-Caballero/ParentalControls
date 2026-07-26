package com.tudominio.parentalcontrol.ui.parent.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tudominio.parentalcontrol.domain.model.*
import com.tudominio.parentalcontrol.viewmodel.ParentViewModel

/**
 * Pantalla de detalle de dispositivo.
 * Muestra stats, salud y permite configurar política.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailScreen(
    deviceId: String,
    viewModel: ParentViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToApps: (String) -> Unit = {}
) {
    val devices by viewModel.devices.collectAsState()
    val templates by viewModel.templates.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    // Mock data para demo
    var selectedTab by remember { mutableIntStateOf(0) }
    var showTemplateDialog by remember { mutableStateOf(false) }

    // WU-4 — refresh the device list while the detail is visible so
    // a parent tapping "lock" sees the new state when they navigate back.
    LaunchedEffect(Unit) {
        viewModel.loadTemplates()
        viewModel.loadDevices()
    }

    // F3 — flip `hasLoadedDevices` to true on the first emission where
    // `isLoading` is false (one-shot). Pre-fix the screen relied on
    // `isLoading` alone, which reverts to false after load — could not
    // distinguish "load still in flight" from "load returned empty
    // list". The cold empty cache stays in LOADING; only a CONFIRMED
    // empty list after a successful load surfaces NOT-FOUND.
    var hasLoadedDevices by remember { mutableStateOf(false) }
    LaunchedEffect(isLoading) { if (!isLoading) hasLoadedDevices = true }

    // WU-4 — real device lookup via `viewModel.devices`. Pre-fix the
    // screen hardcoded a fake "Galaxy S21 de Juan" record and ignored
    // `viewModel.devices` entirely.
    val device: ChildDevice? = resolveSelectedDevice(devices, deviceId)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(device?.name ?: "Sin dispositivo") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { device?.id?.let(viewModel::lockDevice) },
                        enabled = device != null
                    ) {
                        Icon(Icons.Default.Lock, "Bloquear")
                    }
                }
            )
        }
    ) { padding ->
        when (resolveDetailLoadState(devices, isLoading, hasLoadedDevices, deviceId)) {
            DetailLoadState.Loading -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) { CircularProgressIndicator() }
                return@Scaffold
            }
            DetailLoadState.NotFound -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Spacer(Modifier.height(32.dp))
                    Icon(Icons.Default.Info, null, modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.outline)
                    Text("Dispositivo no encontrado", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "El dispositivo \"$deviceId\" no está en la lista del padre. " +
                            "Vuelve al panel y vuelve a intentarlo.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                return@Scaffold
            }
            DetailLoadState.Found -> Unit
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Estado rápido
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = when (device!!.state) {
                        DeviceState.LOCKED -> MaterialTheme.colorScheme.errorContainer
                        DeviceState.DOWNTIME -> MaterialTheme.colorScheme.tertiaryContainer
                        DeviceState.ACTIVE -> MaterialTheme.colorScheme.primaryContainer
                    }
                )
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
                        Icon(
                            when (device!!.state) {
                                DeviceState.LOCKED -> Icons.Default.Lock
                                DeviceState.DOWNTIME -> Icons.Default.Star
                                DeviceState.ACTIVE -> Icons.Default.Check
                            },
                            null,
                            modifier = Modifier.size(32.dp)
                        )
                        Column {
                            Text(
                                when (device!!.state) {
                                    DeviceState.LOCKED -> "Dispositivo bloqueado"
                                    DeviceState.DOWNTIME -> "Hora de dormir"
                                    DeviceState.ACTIVE -> "Activo"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "v${device!!.policyVersion} • ${device!!.appVersion}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    if (device!!.isOnline) {
                        AssistChip(
                            onClick = { },
                            label = { Text("Online") },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Notifications,
                                    null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }
            }

            // Tabs
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Uso") },
                    icon = { Icon(Icons.Default.List, null) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Salud") },
                    icon = { Icon(Icons.Default.Info, null) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Política") },
                    icon = { Icon(Icons.Default.Settings, null) }
                )
            }

            when (selectedTab) {
                0 -> UsageTab()
                1 -> HealthTab()
                2 -> PolicyTab(
                    device = device!!,
                    templates = templates,
                    onApplyTemplate = { showTemplateDialog = true },
                    onReward = { viewModel.grantReward(deviceId, 15, "Recompensa") },
                    onAddToBlockList = { onNavigateToApps(deviceId) }
                )
            }
        }
    }

    // Diálogo de selección de plantilla
    if (showTemplateDialog) {
        TemplateSelectionDialog(
            templates = templates,
            onSelect = { template ->
                viewModel.updateDevicePolicy(deviceId, template.id)
                showTemplateDialog = false
            },
            onDismiss = { showTemplateDialog = false }
        )
    }
}

@Composable
private fun UsageTab() {
    // R5 — honest empty-state: the synthetic Instagram/WhatsApp/Juego
    // XYZ stats are not derivable from any real endpoint. The Uso
    // tab is reserved for a future `get-usage-stats` edge function.
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Info, null, modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.outline)
        Text("Estadísticas de uso no disponibles",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp))
        Text("El endpoint de uso detallado no está implementado en este build.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
private fun HealthTab() {
    // R5 — honest empty-state: the synthetic enforcementLevel=STANDARD /
    // suspicionLevel=NONE / batteryLevel=85 stats are not derivable
    // from any real endpoint. The Salud tab is reserved for a future
    // `get-device-health` edge function.
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Warning, null, modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.outline)
        Text("Salud del dispositivo no disponible",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp))
        Text("El endpoint de salud detallada no está implementado en este build.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
private fun HealthCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color
) {
    // F3 — HealthCard is no longer referenced after the honest
    // empty-state refactor; kept here as a no-op to avoid
    // re-importing Card/Color if a future iteration needs it.
    @Suppress("UNUSED_PARAMETER")
    Card(modifier = Modifier.fillMaxWidth()) {
        Text("$title: $value", style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun PolicyTab(
    device: ChildDevice,
    templates: List<PolicyTemplate>,
    onApplyTemplate: () -> Unit,
    onReward: () -> Unit,
    onAddToBlockList: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Versión actual
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Versión de política")
                Text(
                    "v${device.policyVersion}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Botón de recompensa
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Recompensa",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Dar tiempo extra como recompensa",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onReward,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Star, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Dar +15 minutos")
                }
            }
        }

        // Cambiar plantilla
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Plantilla de política",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Aplica una plantilla predefinida o personaliza manualmente",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onApplyTemplate,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Settings, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Cambiar plantilla")
                }
            }
        }

        // PR 5 task #32: "Add to block list" affordance wired to
        // onNavigateToApps(deviceId). Opens AppsScreen for this child device.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "App block list",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Curate which apps this child can launch",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onAddToBlockList,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_to_block_list_button")
                ) {
                    Icon(Icons.Default.Add, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add to block list")
                }
            }
        }
    }
}

@Composable
private fun TemplateSelectionDialog(
    templates: List<PolicyTemplate>,
    onSelect: (PolicyTemplate) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Seleccionar plantilla") },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(templates) { template ->
                    Card(
                        onClick = { onSelect(template) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (template.isDefault)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        template.name,
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    if (template.isDefault) {
                                        AssistChip(
                                            onClick = { },
                                            label = { Text("Por defecto") },
                                            modifier = Modifier.height(24.dp)
                                        )
                                    }
                                }
                                Text(
                                    template.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            Icon(
                                Icons.Default.Info,
                                null,
                                tint = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

// WU-4 — resolve the selected [ChildDevice] from the parent VM
// `devices` StateFlow. Returns null when the id is absent so the
// caller can branch into an explicit "not found" state.
internal fun resolveSelectedDevice(
    devices: List<ChildDevice>, deviceId: String
): ChildDevice? = devices.firstOrNull { it.id == deviceId }

internal fun resolveDisplayChildName(child: Child?): String =
    child?.firstName ?: "Sin asignar"

enum class DetailLoadState { Loading, Found, NotFound }

internal fun resolveDetailLoadState(
    devices: List<ChildDevice>, isLoading: Boolean,
    hasLoadedDevices: Boolean, deviceId: String
): DetailLoadState = when {
    resolveSelectedDevice(devices, deviceId) != null -> DetailLoadState.Found
    isLoading -> DetailLoadState.Loading
    !hasLoadedDevices -> DetailLoadState.Loading
    else -> DetailLoadState.NotFound
}
