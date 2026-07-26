package com.tudominio.parentalcontrol.ui.parent.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tudominio.parentalcontrol.data.model.BehavioralEventEntity
import com.tudominio.parentalcontrol.ui.parent.components.ChildPickerChips
import com.tudominio.parentalcontrol.viewmodel.BehaviorLogViewModel

/**
 * Pantalla del registro de eventos conductuales.
 *
 * PR B of `openspec/changes/2026-07-07-feat-parent-behavioral-event-log`
 * (Change B, Phase 3.2). Material 3 `Scaffold` + `LazyColumn` + `PullRefresh`.
 * Each event renders as a **terminal** Material 3 `Card` (per Q3=c — no tap
 * action, no detail navigation): icon + `event_type` label + formatted
 * `created_at` timestamp + child first name. Empty / loading / error
 * branches are mutually exclusive.
 *
 * Test tags (locked in `BehaviorLogScreenTest.kt` Phase 0):
 *   - `behavior_log_lazy_column`     — the wrapping `LazyColumn`
 *   - `behavior_log_event_card_<event_type>` — one per event (keyed by type)
 *   - `behavior_log_event_timestamp` — formatted `created_at` text
 *   - `behavior_log_event_child_name` — child first name (V1 = deviceId)
 *   - `behavior_log_pull_refresh`    — `PullRefreshIndicator` container
 *   - `behavior_log_empty_state`     — empty-state text node
 *   - `behavior_log_error_banner`    — error banner text node
 *
 * Spanish UI copy per project convention.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BehaviorLogScreen(
    viewModel: BehaviorLogViewModel,
    onNavigateBack: () -> Unit
) {
    val events by viewModel.filteredEvents.collectAsState()
    val children by viewModel.children.collectAsState()
    val selectedChildId by viewModel.selectedChildId.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    val pullRefreshState = rememberPullToRefreshState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Registro de eventos") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Child picker row — hidden when ≤ 1 distinct child. Mirrors
            // `DashboardScaffold.distinctChildren` at DashboardScreen.kt:290.
            if (children.size >= 2) {
                ChildPickerChips(
                    children = children,
                    selected = selectedChildId,
                    onSelect = { id -> viewModel.selectChild(id) }
                )
            }

            PullToRefreshBox(
                state = pullRefreshState,
                isRefreshing = isLoading,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("behavior_log_pull_refresh")
            ) {
                when {
                    error != null -> ErrorBanner(
                        message = error!!,
                        onRetry = { viewModel.refresh() },
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("behavior_log_error_banner")
                    )
                    events.isEmpty() -> EmptyState(
                        icon = Icons.Default.History,
                        title = "Sin eventos",
                        subtitle = "Los eventos aparecerán aquí cuando los dispositivos los reporten",
                        modifier = Modifier.testTag("behavior_log_empty_state")
                    )
                    else -> LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("behavior_log_lazy_column"),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(events, key = { it.id }) { event ->
                            EventCard(event = event)
                        }
                    }
                }
            }
        }
    }
}

/**
 * One event rendered as a Material 3 `Card` with icon + event_type label +
 * timestamp + child name. Card-as-terminal per Q3=c — no `onClick` handler.
 */
@Composable
private fun EventCard(event: BehavioralEventEntity) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("behavior_log_event_card_${event.event_type}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = iconForEventType(event.event_type),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = event.event_type.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatTimestamp(event.created_at),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("behavior_log_event_timestamp")
                    )
                    Text(
                        text = event.device_id,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("behavior_log_event_child_name")
                    )
                }
            }
        }
    }
}

private fun iconForEventType(eventType: String): ImageVector = when (eventType) {
    "limit_reached", "time_warning_shown" -> Icons.Default.Warning
    "block_overlay_shown" -> Icons.Default.Warning
    "app_open" -> Icons.Default.History
    else -> Icons.Default.Check
}

private fun formatTimestamp(iso: String): String {
    // V1 format: take the HH:mm slice from the ISO-8601 string. V2 can
    // upgrade to a locale-aware formatter once the parent device's locale
    // is available in the screen.
    val tIdx = iso.indexOf('T')
    if (tIdx < 0 || iso.length < tIdx + 6) return iso
    return iso.substring(tIdx + 1, tIdx + 6)
}

@Composable
private fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline
            )
            Text(
                title,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun ErrorBanner(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Error cargando eventos",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Text(message, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onRetry) {
                    Text("Reintentar")
                }
            }
        }
    }
}
