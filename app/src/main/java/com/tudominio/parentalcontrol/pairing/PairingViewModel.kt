package com.tudominio.parentalcontrol.pairing

import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tudominio.parentalcontrol.workers.WorkerInitializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel para el flujo de emparejamiento.
 */
@HiltViewModel
class PairingViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val pairingManager = PairingManager.getInstance(context)

    // Estado de la UI
    private val _uiState = MutableStateFlow<PairingUiState>(PairingUiState.Idle)
    val uiState: StateFlow<PairingUiState> = _uiState.asStateFlow()

    // Código ingresado manualmente
    private val _manualCode = MutableStateFlow("")
    val manualCode: StateFlow<String> = _manualCode.asStateFlow()

    // Eventos de navegación
    // extraBufferCapacity so a `suspend fun emit` from a non-coroutine
    // caller (e.g. test triggering confirmAdminDecisionAndNavigate
    // synchronously before a collector has subscribed) does not block
    // indefinitely. `replay = 0` keeps the existing contract: the
    // event is delivered to live collectors only.
    private val _navigationEvents = MutableSharedFlow<PairingNavigationEvent>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val navigationEvents: SharedFlow<PairingNavigationEvent> = _navigationEvents.asSharedFlow()

    // QR escaneado recientemente (para evitar procesamiento duplicado)
    private var lastScannedCode: String? = null
    private var lastScanTime: Long = 0

    init {
        Log.d(TAG, "PairingViewModel inicializado")
    }

    /**
     * Inicia el emparejamiento con código QR.
     */
    fun startQrPairing() {
        Log.d(TAG, "Iniciando emparejamiento por QR")
        _uiState.value = PairingUiState.ScanningQr
    }

    /**
     * Inicia el emparejamiento con código manual.
     */
    fun startManualPairing() {
        Log.d(TAG, "Iniciando emparejamiento manual")
        _uiState.value = PairingUiState.EnteringCode
    }

    /**
     * Actualiza el código manual.
     */
    fun updateManualCode(code: String) {
        _manualCode.value = code.uppercase().filter { it.isLetterOrDigit() }.take(8)
    }

    /**
     * Procesa el QR escaneado.
     */
    fun processQrCode(content: String) {
        val currentTime = System.currentTimeMillis()
        
        // Evitar procesamiento duplicado (mismo código en 2 segundos)
        if (content == lastScannedCode && currentTime - lastScanTime < 2000) {
            Log.d(TAG, "QR ignorado (duplicado)")
            return
        }
        
        lastScannedCode = content
        lastScanTime = currentTime

        Log.d(TAG, "Procesando QR: ${content.take(20)}...")
        _uiState.value = PairingUiState.Pairing

        viewModelScope.launch {
            val result = pairingManager.pairWithQr(content)
            handlePairingResult(result)
        }
    }

    private val applicationScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate
    )

    /**
     * WU-D — confirms the Device Admin decision and navigates home.
     * The PairingScreen.SuccessContent calls this once the user
     * activates admin OR chooses "Más tarde". Pre-fix, this
     * navigation fired synchronously from `handlePairingResult`
     * before the Device Admin prompt could mount.
     *
     * `suspend` so the caller awaits the emission without leaking
     * a fire-and-forget job into a global scope. The SharedFlow
     * has `replay = 0`, so the event is delivered exactly once to a
     * live collector (PairingScreen.LaunchedEffect).
     */
    suspend fun confirmAdminDecisionAndNavigate() {
        _navigationEvents.emit(PairingNavigationEvent.NavigateToHome)
    }

    /**
     * Test seam — injects a Success result without going through
     * the full QR / manual-code path. Synchronous so unit tests can
     * observe the post-handlePairingResult state without scheduling
     * into viewModelScope (which uses Dispatchers.Main, not the
     * test's StandardTestDispatcher).
     */
    internal fun simulateSuccess(deviceId: String) {
        // Skip the WorkerInitializer IO side-effect in tests; the
        // production flow runs that side-effect via the real
        // handlePairingResult -> pairWithManualCode path.
        _uiState.value = PairingUiState.Success(deviceId)
    }

    /**
     * Empareja con el código manual.
     */
    fun pairWithManualCode() {
        val code = _manualCode.value
        if (code.length < PairingManager.CODE_LENGTH) {
            _uiState.value = PairingUiState.Error(
                "El código debe tener ${PairingManager.CODE_LENGTH} caracteres"
            )
            return
        }
        
        Log.d(TAG, "Emparejando con código manual: ${code.take(4)}...")
        _uiState.value = PairingUiState.Pairing
        
        viewModelScope.launch {
            val result = pairingManager.pairWithCode(code)
            handlePairingResult(result)
        }
    }

    /**
     * Maneja el resultado del emparejamiento.
     */
    private suspend fun handlePairingResult(result: PairingResult) {
        when (result) {
            is PairingResult.Success -> {
                Log.d(TAG, "Emparejamiento exitoso")
                // PR verification 2026-07-01: schedule the post-pairing sync
                // (which now includes `pullApprovedRequests`) so the child
                // can pick up any grants approved while it was unpaired.
                // This was previously a dead code path: `reinitializeAfterPairing`
                // was defined but never called.
                withContext(Dispatchers.IO) {
                    WorkerInitializer.reinitializeAfterPairing(context)
                }
                _uiState.value = PairingUiState.Success(result.deviceId)
                // WU-D — DO NOT auto-emit NavigateToHome. The Device
                // Admin prompt in PairingScreen.SuccessContent gates
                // the navigation; SuccessContent calls
                // [confirmAdminDecisionAndNavigate] once the user
                // activates admin or chooses "Más tarde".
            }
            
            is PairingResult.Error -> {
                Log.w(TAG, "Emparejamiento falló: ${result.type}")
                _uiState.value = when (result.type) {
                    PairingErrorType.INVALID_CODE -> {
                        PairingUiState.Error(
                            message = "Código inválido. Verifica el código e intenta de nuevo.",
                            canRetry = true,
                            canRequestNew = true
                        )
                    }
                    PairingErrorType.EXPIRED_CODE -> {
                        PairingUiState.Error(
                            message = "El código ha expirado. Solicita uno nuevo desde el panel parental.",
                            canRetry = false,
                            canRequestNew = true
                        )
                    }
                    PairingErrorType.ALREADY_USED -> {
                        PairingUiState.Error(
                            message = "Este código ya fue utilizado. Solicita uno nuevo.",
                            canRetry = false,
                            canRequestNew = true
                        )
                    }
                    PairingErrorType.INVALID_QR -> {
                        PairingUiState.Error(
                            message = "El código QR no es reconocido. Asegúrate de escanear el QR correcto.",
                            canRetry = true,
                            canRequestNew = false
                        )
                    }
                    PairingErrorType.SESSION_ERROR,
                    PairingErrorType.NETWORK_ERROR -> {
                        PairingUiState.Error(
                            message = "Error de conexión. Verifica tu conexión a internet.",
                            canRetry = true,
                            canRequestNew = false
                        )
                    }
                    else -> {
                        PairingUiState.Error(
                            message = result.message,
                            canRetry = true,
                            canRequestNew = false
                        )
                    }
                }
            }
        }
    }

    /**
     * Reintenta el emparejamiento.
     */
    fun retry() {
        Log.d(TAG, "Reintentando emparejamiento")
        _manualCode.value = ""
        _uiState.value = PairingUiState.Idle
    }

    /**
     * Solicita un nuevo código (muestra instrucciones).
     */
    fun requestNewCode() {
        Log.d(TAG, "Solicitando nuevo código")
        viewModelScope.launch {
            _navigationEvents.emit(PairingNavigationEvent.OpenParentPanel)
        }
    }

    /**
     * Cancela el emparejamiento y vuelve.
     */
    fun cancel() {
        Log.d(TAG, "Cancelando emparejamiento")
        _manualCode.value = ""
        _uiState.value = PairingUiState.Idle
    }

    /**
     * Navega al escáner QR.
     */
    fun goToQrScanner() {
        _uiState.value = PairingUiState.ScanningQr
    }

    /**
     * Navega al ingreso manual de código.
     */
    fun goToManualEntry() {
        _uiState.value = PairingUiState.EnteringCode
    }

    companion object {
        private const val TAG = "PairingViewModel"
    }
}

/**
 * Factory para PairingViewModel.
 */
class PairingViewModelFactory(
    @ApplicationContext private val context: Context
) : ViewModelProvider.Factory {
    
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PairingViewModel::class.java)) {
            return PairingViewModel(context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

/**
 * Estados de la UI de emparejamiento.
 */
sealed class PairingUiState {
    data object Idle : PairingUiState()
    data object ScanningQr : PairingUiState()
    data object EnteringCode : PairingUiState()
    data object Pairing : PairingUiState()
    data class Success(val deviceId: String) : PairingUiState()
    data class Error(
        val message: String,
        val canRetry: Boolean = true,
        val canRequestNew: Boolean = false
    ) : PairingUiState()
}

/**
 * Eventos de navegación del emparejamiento.
 */
sealed class PairingNavigationEvent {
    data object NavigateToHome : PairingNavigationEvent()
    data object OpenParentPanel : PairingNavigationEvent()
    data object GoBack : PairingNavigationEvent()
}
