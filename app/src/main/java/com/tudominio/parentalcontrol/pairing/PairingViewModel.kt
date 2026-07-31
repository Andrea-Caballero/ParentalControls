package com.tudominio.parentalcontrol.pairing

import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tudominio.parentalcontrol.admin.DeviceAdminPromptCoordinator
import com.tudominio.parentalcontrol.workers.PostPairingSchedulingOutcome
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
    @ApplicationContext private val context: Context,
    private val savedStateHandle: SavedStateHandle,
    // WU-D follow-up — Device Admin prompt coordinator. Hilt supplies
    // the @Singleton from RepositoryModule in production so the same
    // state machine drives the pairing screen gating AND the child
    // status banner. Pre-fix, `PairingScreen.SuccessContent` built a
    // throwaway `DeviceAdminPromptCoordinator()` via `remember { ... }`
    // and the state changes from "Más tarde" never reached the
    // Hilt-singleton observed by `ChildStatusViewModel`.
    //
    // The default value preserves the manual `PairingViewModelFactory`
    // and the existing unit tests (`PairingViewModelAdminGateTest`,
    // `PairingScreenRecoveryTest`, `PairingViewModelChildNameLengthTest`,
    // `PairingViewModelManualCodeFormatTest`) that construct
    // `PairingViewModel(context, savedStateHandle)` directly. Hilt
    // calls the same constructor with all three parameters supplied
    // from the graph, so production gets the singleton and tests
    // get a fresh per-instance coordinator without changing the
    // call sites.
    //
    // `internal val` (read-only) so `PairingScreen.SuccessContent`
    // can use the exact injected instance — no defensive copy, no
    // mutable replacement, no leak of the coordinator outside the
    // app module.
    internal val adminCoordinator: DeviceAdminPromptCoordinator = DeviceAdminPromptCoordinator()
) : ViewModel() {

    private val pairingManager = PairingManager.getInstance(context)

    // Estado de la UI
    private val _uiState = MutableStateFlow<PairingUiState>(PairingUiState.Idle)
    val uiState: StateFlow<PairingUiState> = _uiState.asStateFlow()

    // Nombre del niño asociado al dispositivo.
    // Backed directly by SavedStateHandle so it survives ViewModel
    // recreation AND process death (the SavedStateRegistry is
    // rehydrated by the Activity / NavBackStackEntry on cold start).
    // The exposed StateFlow contract is unchanged: collectors see
    // the current value and any updates.
    val childFirstName: StateFlow<String> = savedStateHandle.getStateFlow(
        KEY_CHILD_FIRST_NAME, ""
    )

    // Código ingresado manualmente
    private val _manualCode = savedStateHandle.getStateFlow(KEY_MANUAL_CODE, "")
    val manualCode: StateFlow<String> = _manualCode

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
        // Restored-state bypass guard: a pre-fix value persisted when
        // MAX_CHILD_FIRST_NAME_LENGTH was still 80 can be rehydrated
        // from SavedStateHandle on cold start. PairingManager
        // .childFirstNameProvider only trims the value it sees, so a
        // raw 33..80-char restored name would bypass the new 32-char
        // cap and reach the wire as a server HTTP 400
        // ("child_first_name es requerido (1..32 caracteres)").
        //
        // Normalize the restored value through the SAME
        // sanitizeChildFirstName pipeline used by updateChildFirstName
        // and persist the normalized value back into SavedStateHandle.
        // The exposed childFirstName StateFlow is backed by the same
        // handle entry, so UI state, provider state, and persisted
        // state all observe the same normalized value after init.
        val restored = savedStateHandle.get<String>(KEY_CHILD_FIRST_NAME)
        if (restored != null) {
            val sanitized = sanitizeChildFirstName(restored)
            if (sanitized != restored) {
                savedStateHandle[KEY_CHILD_FIRST_NAME] = sanitized
            }
        }

        pairingManager.childFirstNameProvider = {
            childFirstName.value.trim().takeIf { it.isNotEmpty() }
        }
        Log.d(TAG, "PairingViewModel inicializado")
    }

    /**
     * Pure helper: trim → safe-char filter → whitespace collapse →
     * cap at [MAX_CHILD_FIRST_NAME_LENGTH]. Shared by
     * [updateChildFirstName] (live UI input) and the init-block
     * restored-state normalization so both paths apply the exact same
     * sanitization rules. Keeping it as a single source of truth
     * prevents the pre-fix 33..80-char restored-state bypass from
     * re-introducing the wire-side HTTP 400.
     */
    private fun sanitizeChildFirstName(input: String): String =
        input
            .trim()
            .filter { it.isLetter() || it == ' ' || it == '-' || it == '\'' }
            .replace(Regex("\\s+"), " ")
            .take(MAX_CHILD_FIRST_NAME_LENGTH)

    /**
     * Actualiza el nombre del niño con caracteres seguros para el backend.
     */
    fun updateChildFirstName(name: String) {
        savedStateHandle[KEY_CHILD_FIRST_NAME] = sanitizeChildFirstName(name)
    }

    /**
     * Inicia el emparejamiento con código QR.
     */
    fun startQrPairing() {
        if (!hasChildFirstName()) return
        Log.d(TAG, "Iniciando emparejamiento por QR")
        _uiState.value = PairingUiState.ScanningQr
    }

    /**
     * Inicia el emparejamiento con código manual.
     */
    fun startManualPairing() {
        if (!hasChildFirstName()) return
        Log.d(TAG, "Iniciando emparejamiento manual")
        _uiState.value = PairingUiState.EnteringCode
    }

    private fun hasChildFirstName(): Boolean = childFirstName.value.isNotBlank()

    /**
     * Actualiza el código manual.
     */
    fun updateManualCode(code: String) {
        savedStateHandle[KEY_MANUAL_CODE] = code.uppercase().filter { it.isLetterOrDigit() }.take(8)
    }

    /**
     * Procesa el QR escaneado.
     */
    fun processQrCode(content: String) {
        if (!hasChildFirstName()) return
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
     * Pre-fix bug: `pairWithManualCode` only checked length before
     * invoking the network. A typed `ABCD1234` (contains `1`) reached
     * the wire and failed server-side with HTTP 400 `INVALID_CODE_FORMAT`,
     * leaking the raw server token to the user.
     *
     * Post-fix: validate the format against the same regex the server
     * uses (`^[A-HJ-NP-Z2-9]{8}$`) via [PairingManager.isValidManualCode]
     * and reject with a truthful Spanish copy that names I/O/0/1 BEFORE
     * any network call. The same helper also gates the UI "Pair" button
     * so the contract is enforced at both entry points.
     *
     * @return null when the code is valid; a `PairingUiState.Error` with
     *         a format-specific message otherwise.
     */
    private fun rejectInvalidFormat(code: String): PairingUiState.Error? {
        if (PairingManager.isValidManualCode(code)) return null
        return PairingUiState.Error(
            message = "Código no válido. Solo letras A-H, J-N, P-Z y números 2-9 (sin I, O, 0, 1).",
            canRetry = true,
            canRequestNew = false,
        )
    }

    /**
     * Empareja con el código manual.
     */
    fun pairWithManualCode() {
        if (!hasChildFirstName()) return
        val code = manualCode.value
        if (code.length < PairingManager.CODE_LENGTH) {
            _uiState.value = PairingUiState.Error(
                "El código debe tener ${PairingManager.CODE_LENGTH} caracteres"
            )
            return
        }
        // Format validation: a full-length code that does not match the
        // server regex (excludes I/O/0/1) would fail server-side with
        // HTTP 400 INVALID_CODE_FORMAT. Reject here with a truthful
        // Spanish copy so the user sees the actual constraint and no
        // network call is made. Also fires when IME/programmatic input
        // bypasses the UI button gate (e.g. deeplink with an invalid
        // code, restored state, or a test invoking pairWithManualCode
        // directly).
        rejectInvalidFormat(code)?.let {
            _uiState.value = it
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
    internal suspend fun handlePairingResult(result: PairingResult) {
        when (result) {
            is PairingResult.Success -> {
                Log.d(TAG, "Emparejamiento exitoso")
                // PR verification 2026-07-01: schedule the post-pairing sync
                // (which now includes `pullApprovedRequests`) so the child
                // can pick up any grants approved while it was unpaired.
                // This was previously a dead code path: `reinitializeAfterPairing`
                // was defined but never called.
                //
                // Post-fix: `reinitializeAfterPairing` is exception-safe and
                // returns [PostPairingSchedulingOutcome]. Pairing is
                // irreversible so any scheduling failure MUST NOT block the
                // transition to Success — log a contextual warning and
                // continue.
                withContext(Dispatchers.IO) {
                    val outcome = WorkerInitializer.reinitializeAfterPairing(context)
                    if (outcome == PostPairingSchedulingOutcome.FAILED) {
                        Log.w(
                            TAG,
                            "Programación post-emparejamiento falló; avanzando a Success de todos modos. deviceId=${result.deviceId}"
                        )
                    }
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
        savedStateHandle[KEY_MANUAL_CODE] = ""
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
        savedStateHandle[KEY_MANUAL_CODE] = ""
        _uiState.value = PairingUiState.Idle
    }

    override fun onCleared() {
        pairingManager.childFirstNameProvider = { null }
        super.onCleared()
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
        // Aligned to supabase/functions/pairing/index.ts validation
        // (1..32 chars after trim). Pre-fix, this constant was 80, so names
        // 33..80 chars passed Android UI sanitization and then failed the
        // server-side check with HTTP 400 "child_first_name es requerido
        // (1..32 caracteres)".
        private const val MAX_CHILD_FIRST_NAME_LENGTH = 32

        /** SavedStateHandle key for the persisted child first name. */
        const val KEY_CHILD_FIRST_NAME = "child_first_name"

        /** SavedStateHandle key for the persisted manual pairing code. */
        const val KEY_MANUAL_CODE = "manual_code"
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
            return PairingViewModel(context, SavedStateHandle()) as T
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
