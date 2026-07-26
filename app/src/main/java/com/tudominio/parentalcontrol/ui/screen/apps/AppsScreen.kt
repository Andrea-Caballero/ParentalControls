package com.tudominio.parentalcontrol.ui.screen.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmapOrNull

/**
 * Parent-facing screen that lists every launchable app installed on the
 * reference device and exposes a per-app switch to toggle its block state.
 *
 * PR 5 of `openspec/changes/wire-pairing-and-approval-end-to-end` (task #31,
 * `app-block-policy` spec). The list of apps comes from
 * [AppsViewModel.loadInstalledApps]; per-app toggling is handled by
 * [AppsViewModel.toggleBlock]. The screen binds [viewModel] to [deviceId]
 * so every DAO read/write targets the right child device — see the spec
 * scenario "AppsScreen honors the incoming device id".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(
    deviceId: String,
    viewModel: AppsViewModel,
    onBack: () -> Unit
) {
    val apps by viewModel.apps.collectAsState()
    val blocked by viewModel.blockedPackages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    // Bind the device id first so the device-scoped flow is queried with
    // the right id when loadBlockedPackages() runs.
    LaunchedEffect(deviceId) {
        viewModel.setDeviceId(deviceId)
    }
    LaunchedEffect(Unit) {
        viewModel.loadInstalledApps()
        viewModel.loadBlockedPackages()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Apps") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading && apps.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                apps.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "No apps found",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "Install an app to see it here",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(apps, key = { it.packageName }) { app ->
                            AppRow(
                                app = app,
                                isBlocked = blocked.contains(app.packageName),
                                onToggle = { newBlocked ->
                                    viewModel.toggleBlock(app.packageName, newBlocked)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: AppInfo,
    isBlocked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val painter: Painter? = remember(app.packageName) {
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(app.packageName)
            val bitmap = drawable.toBitmapOrNull() ?: return@runCatching null
            BitmapPainter(bitmap.asImageBitmap())
        }.getOrNull()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (painter != null) {
            Icon(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.size(40.dp)
            )
        } else {
            Box(modifier = Modifier.size(40.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.displayName,
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Switch(
            checked = isBlocked,
            onCheckedChange = onToggle,
            modifier = Modifier.testTag("apps_switch_${app.packageName}")
        )
    }
}

/**
 * Smaller text the test uses to identify a row's switch. We expose it via
 * the switch's `contentDescription` ("Block" / "Unblock …") so a Compose UI
 * test can `performClick` on it.
 */
@Suppress("unused")
private const val BLOCK_LABEL = "Block"
