package com.tudominio.parentalcontrol.pairing.ui

import android.Manifest
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.tudominio.parentalcontrol.pairing.PairingUiState
import com.tudominio.parentalcontrol.pairing.PairingViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Pantalla principal de emparejamiento.
 */
@Composable
fun PairingScreen(
    viewModel: PairingViewModel,
    onPairingComplete: () -> Unit,
    onCancel: () -> Unit,
    prefilledCode: String? = null
) {
    val uiState by viewModel.uiState.collectAsState()

    // Pre-fill the manual entry text field from a deeplink, and auto-advance
    // to the manual-entry state. Triggered once per `prefilledCode` value.
    LaunchedEffect(prefilledCode) {
        val code = prefilledCode?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        viewModel.updateManualCode(code)
        viewModel.startManualPairing()
    }

    LaunchedEffect(Unit) {
        viewModel.navigationEvents.collect { event ->
            when (event) {
                is com.tudominio.parentalcontrol.pairing.PairingNavigationEvent.NavigateToHome -> {
                    onPairingComplete()
                }
                is com.tudominio.parentalcontrol.pairing.PairingNavigationEvent.OpenParentPanel -> {
                    Log.d("PairingScreen", "Abrir panel parental")
                }
                is com.tudominio.parentalcontrol.pairing.PairingNavigationEvent.GoBack -> {
                    onCancel()
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (uiState) {
            is PairingUiState.Idle -> {
                IdleContent(
                    onQrClick = { viewModel.startQrPairing() },
                    onCodeClick = { viewModel.startManualPairing() },
                    onCancel = onCancel
                )
            }
            is PairingUiState.ScanningQr -> {
                QrScannerContent(
                    onQrScanned = { viewModel.processQrCode(it) },
                    onBack = { viewModel.cancel() }
                )
            }
            is PairingUiState.EnteringCode -> {
                ManualCodeContent(
                    viewModel = viewModel,
                    onBack = { viewModel.cancel() }
                )
            }
            is PairingUiState.Pairing -> {
                PairingContent()
            }
            is PairingUiState.Success -> {
                SuccessContent(viewModel = viewModel, onPairingComplete = onPairingComplete)
            }
            is PairingUiState.Error -> {
                val error = uiState as PairingUiState.Error
                ErrorContent(
                    message = error.message,
                    canRetry = error.canRetry,
                    canRequestNew = error.canRequestNew,
                    onRetry = { viewModel.retry() },
                    onRequestNew = { viewModel.requestNewCode() },
                    onBack = onCancel
                )
            }
        }
    }
}

@Composable
private fun IdleContent(
    onQrClick: () -> Unit,
    onCodeClick: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "🔗",
            style = MaterialTheme.typography.displayLarge
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Emparejar dispositivo",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Conecta este dispositivo con tu cuenta parental",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = onQrClick,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("📷 Escanear código QR")
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = onCodeClick,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("⌨️ Ingresar código manualmente")
        }

        Spacer(modifier = Modifier.height(32.dp))

        TextButton(onClick = onCancel) {
            Text("Cancelar")
        }
    }
}

@Composable
private fun QrScannerContent(
    onQrScanned: (String) -> Unit,
    onBack: () -> Unit
) {
    var hasCameraPermission by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
        if (permission == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            hasCameraPermission = true
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (hasCameraPermission) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()

                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also {
                                it.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                                    @androidx.camera.core.ExperimentalGetImage
                                    val mediaImage = imageProxy.image
                                    if (mediaImage != null) {
                                        val image = InputImage.fromMediaImage(
                                            mediaImage,
                                            imageProxy.imageInfo.rotationDegrees
                                        )
                                        val scanner = BarcodeScanning.getClient()
                                        scanner.process(image)
                                            .addOnSuccessListener { barcodes ->
                                                for (barcode in barcodes) {
                                                    barcode.rawValue?.let { value ->
                                                        onQrScanned(value)
                                                    }
                                                }
                                            }
                                            .addOnCompleteListener {
                                                imageProxy.close()
                                            }
                                    } else {
                                        imageProxy.close()
                                    }
                                }
                            }

                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageAnalysis
                            )
                        } catch (e: Exception) {
                            Log.e("QrScanner", "Error: ${e.message}")
                        }
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

            Box(
                modifier = Modifier
                    .size(250.dp)
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.2f))
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(32.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Apunta la cámara al código QR",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
            ) {
                Icon(Icons.Default.ArrowBack, "Volver", tint = Color.White)
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("📷", style = MaterialTheme.typography.displayLarge)
                Spacer(modifier = Modifier.height(16.dp))
                Text("Se necesita permiso de cámara", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Para escanear el código QR, permite el acceso", textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Permitir cámara")
                }
                TextButton(onClick = onBack) { Text("Volver") }
            }
        }
    }
}

@Composable
private fun ManualCodeContent(
    viewModel: PairingViewModel,
    onBack: () -> Unit
) {
    val manualCode by viewModel.manualCode.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.Start)) {
            Icon(Icons.Default.ArrowBack, "Volver")
        }

        Spacer(modifier = Modifier.weight(0.5f))

        Text("⌨️", style = MaterialTheme.typography.displayMedium)

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Código de emparejamiento",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Ingresa el código de 8 caracteres del panel parental",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = manualCode,
            onValueChange = { viewModel.updateManualCode(it) },
            label = { Text("Código") },
            placeholder = { Text("ABCD1234") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { viewModel.pairWithManualCode() }
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "${manualCode.length}/8 caracteres",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { viewModel.pairWithManualCode() },
            enabled = manualCode.length >= 8,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("✅ Emparejar")
        }

        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PairingContent() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(64.dp))
        Spacer(modifier = Modifier.height(24.dp))
        Text(text = "Emparejando...", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Conectando con el servidor",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SuccessContent(
    viewModel: PairingViewModel,
    onPairingComplete: () -> Unit
) {
    val context = LocalContext.current
    val lockManager = remember { com.tudominio.parentalcontrol.admin.LockManager(context) }
    // WU-D — gating coordinator shared with ChildStatusViewModel so
    // the same state machine drives the pairing screen gating and
    // the child status banner. Default to a fresh coordinator in the
    // test seam; Hilt-injected in production.
    val coordinator = remember { com.tudominio.parentalcontrol.admin.DeviceAdminPromptCoordinator() }

    var adminChecked by remember { mutableStateOf(false) }
    var adminActive by remember { mutableStateOf(lockManager.isAdminActive()) }
    var showPrompt by remember { mutableStateOf(!adminActive) }
    var navigating by remember { mutableStateOf(false) }

    val adminLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // Re-check admin status. The `ACTION_ADD_DEVICE_ADMIN` activity
        // returns RESULT_OK on activation, RESULT_CANCELED on dismiss.
        // Both cases land here — we treat the user as "needs to
        // re-verify admin status" either way.
        adminActive = lockManager.isAdminActive()
        if (adminActive) {
            coordinator.markAdminActive()
            showPrompt = false
            if (!navigating) {
                navigating = true
                onAdminConfirmed(viewModel)
            }
        }
    }

    // Check admin on first mount.
    LaunchedEffect(Unit) {
        if (!adminChecked) {
            adminActive = lockManager.isAdminActive()
            adminChecked = true
            if (adminActive) {
                // Admin already active — record a fresh pairing but
                // the coordinator stays in Idle, and we proceed.
                coordinator.recordFreshPairing()
                if (!navigating) {
                    navigating = true
                    onAdminConfirmed(viewModel)
                }
            } else {
                coordinator.recordFreshPairing()
            }
        }
    }

    if (showPrompt && !adminActive) {
        AdminActivationDialog(
            onActivate = {
                adminLauncher.launch(lockManager.getEnableAdminIntent())
            },
            onSkip = {
                coordinator.markSkipped()
                showPrompt = false
                if (!navigating) {
                    navigating = true
                    onAdminConfirmed(viewModel)
                }
            }
        )
    } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("✅", style = MaterialTheme.typography.displayLarge)
            Spacer(Modifier.height(24.dp))
            Text(
                text = "¡Emparejado!",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Este dispositivo está conectado a tu cuenta parental",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * WU-D — ask the view model to emit the navigation event. The actual
 * `onPairingComplete()` callback runs from [PairingScreen]'s
 * `navigationEvents` collector in the outer LaunchedEffect, so we
 * keep a single source of truth for navigation.
 */
private fun onAdminConfirmed(viewModel: PairingViewModel) {
    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
        viewModel.confirmAdminDecisionAndNavigate()
    }
}

@Composable
private fun AdminActivationDialog(
    onActivate: () -> Unit,
    onSkip: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { /* require explicit choice — no auto-dismiss */ },
        title = { Text("Activar control parental") },
        text = {
            Text(
                "Para que el panel parental pueda bloquear este " +
                    "dispositivo remotamente, se necesita activar el " +
                    "permiso de administrador del dispositivo."
            )
        },
        confirmButton = {
            TextButton(onClick = onActivate) {
                Text("Activar control parental")
            }
        },
        dismissButton = {
            TextButton(onClick = onSkip) {
                Text("Más tarde")
            }
        }
    )
}

@Composable
private fun ErrorContent(
    message: String,
    canRetry: Boolean,
    canRequestNew: Boolean,
    onRetry: () -> Unit,
    onRequestNew: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("❌", style = MaterialTheme.typography.displayLarge)
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Error de emparejamiento",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Text(
                text = message,
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center
            )
        }
        Spacer(modifier = Modifier.height(32.dp))

        if (canRetry) {
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Refresh, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Reintentar")
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (canRequestNew) {
            OutlinedButton(onClick = onRequestNew, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Refresh, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Solicitar nuevo código")
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        TextButton(onClick = onBack) { Text("Cancelar") }
    }
}
