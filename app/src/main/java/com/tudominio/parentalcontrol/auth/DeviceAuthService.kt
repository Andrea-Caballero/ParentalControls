package com.tudominio.parentalcontrol.auth

import android.content.Context
import com.tudominio.parentalcontrol.network.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Servicio que maneja el ciclo de vida de la autenticación del dispositivo.
 * Incluye refresh automático y detección de anomalías.
 */
class DeviceAuthService(private val context: Context) {

    companion object {
        private const val REFRESH_INTERVAL_MS = 4 * 60 * 1000L // 4 minutos
        private const val HEALTH_CHECK_INTERVAL_MS = 5 * 60 * 1000L // 5 minutos
        private const val MAX_RETRY_COUNT = 3
        
        @Volatile
        private var instance: DeviceAuthService? = null

        fun getInstance(context: Context): DeviceAuthService {
            return instance ?: synchronized(this) {
                instance ?: DeviceAuthService(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val authManager = DeviceAuthManager.getInstance(context)
    
    // Jobs
    private var refreshJob: Job? = null
    private var healthCheckJob: Job? = null
    
    // Estado
    private val _authState = MutableStateFlow<AuthServiceState>(AuthServiceState.IDLE)
    val authState: StateFlow<AuthServiceState> = _authState.asStateFlow()
    
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _retryCount = MutableStateFlow(0)
    val retryCount: StateFlow<Int> = _retryCount.asStateFlow()

    // Callbacks
    var onIntegrityFailure: (() -> Unit)? = null
    var onNeedsPairing: (() -> Unit)? = null
    var onConnectionLost: (() -> Unit)? = null
    var onSessionExpired: (() -> Unit)? = null
    var onAuthenticated: (() -> Unit)? = null

    /**
     * Inicia el servicio de autenticación.
     */
    fun start() {
        if (_authState.value != AuthServiceState.IDLE) return
        
        scope.launch {
            _authState.value = AuthServiceState.STARTING
            _connectionState.value = ConnectionState.CONNECTING
            
            // Inicializar y autenticar
            val result = authManager.authenticateOrCreate()
            
            when (result) {
                is AuthResult.Success -> {
                    _authState.value = AuthServiceState.AUTHENTICATED
                    _connectionState.value = ConnectionState.CONNECTED
                    _retryCount.value = 0
                    
                    // Notificar
                    onAuthenticated?.invoke()
                    
                    // Iniciar refresh periódico
                    startAutoRefresh()
                    
                    // Iniciar health checks
                    startHealthCheck()
                }
                is AuthResult.NeedsPairing -> {
                    _authState.value = AuthServiceState.NEEDS_PAIRING
                    _connectionState.value = ConnectionState.NEEDS_PAIRING
                    onNeedsPairing?.invoke()
                }
                is AuthResult.Error -> {
                    _authState.value = AuthServiceState.ERROR
                    _connectionState.value = ConnectionState.ERROR
                    scheduleRetry()
                }
            }
        }
    }

    /**
     * Detiene el servicio.
     */
    fun stop() {
        refreshJob?.cancel()
        healthCheckJob?.cancel()
        _authState.value = AuthServiceState.IDLE
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * Completa el emparejamiento.
     */
    suspend fun completePairing(code: String): Boolean {
        _authState.value = AuthServiceState.PAIRING
        _connectionState.value = ConnectionState.CONNECTING
        
        val result = authManager.completePairing(code)
        
        when (result) {
            is AuthResult.Success -> {
                _authState.value = AuthServiceState.AUTHENTICATED
                _connectionState.value = ConnectionState.CONNECTED
                _retryCount.value = 0
                onAuthenticated?.invoke()
                startAutoRefresh()
                startHealthCheck()
                return true
            }
            is AuthResult.NeedsPairing -> {
                _authState.value = AuthServiceState.NEEDS_PAIRING
                _connectionState.value = ConnectionState.NEEDS_PAIRING
                onNeedsPairing?.invoke()
                return false
            }
            is AuthResult.Error -> {
                _authState.value = AuthServiceState.ERROR
                _connectionState.value = ConnectionState.ERROR
                return false
            }
        }
    }

    /**
     * Maneja falla de integridad (T23).
     */
    suspend fun handleIntegrityFailure(): Boolean {
        _authState.value = AuthServiceState.HANDLING_ANOMALY
        
        val result = authManager.handleIntegrityFailure()
        
        when (result) {
            is AuthResult.NeedsPairing -> {
                _authState.value = AuthServiceState.INTEGRITY_ERROR
                _connectionState.value = ConnectionState.INTEGRITY_ERROR
                onIntegrityFailure?.invoke()
                return true
            }
            else -> {
                _authState.value = AuthServiceState.ERROR
                _connectionState.value = ConnectionState.ERROR
                return false
            }
        }
    }

    /**
     * Refresca la sesión manualmente.
     */
    suspend fun refreshNow(): Boolean {
        val result = authManager.refreshSession()
        
        when (result) {
            is AuthResult.Success -> {
                _connectionState.value = ConnectionState.CONNECTED
                return true
            }
            is AuthResult.NeedsPairing -> {
                _connectionState.value = ConnectionState.NEEDS_PAIRING
                onSessionExpired?.invoke()
                return false
            }
            is AuthResult.Error -> {
                _connectionState.value = ConnectionState.ERROR
                return false
            }
        }
    }

    private fun startAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            while (isActive) {
                delay(REFRESH_INTERVAL_MS)
                
                if (!isActive) break
                
                // Verificar si la sesión está por expirar
                if (!authManager.isSessionExpiringSoon()) {
                    continue
                }
                
                val result = authManager.refreshSession()
                
                when (result) {
                    is AuthResult.Success -> {
                        // Refresh exitoso
                    }
                    is AuthResult.NeedsPairing -> {
                        _connectionState.value = ConnectionState.NEEDS_PAIRING
                        onSessionExpired?.invoke()
                        break
                    }
                    is AuthResult.Error -> {
                        _connectionState.value = ConnectionState.ERROR
                        handleRefreshFailure()
                    }
                }
            }
        }
    }

    private fun startHealthCheck() {
        healthCheckJob?.cancel()
        healthCheckJob = scope.launch {
            while (isActive) {
                delay(HEALTH_CHECK_INTERVAL_MS)
                
                if (!isActive) break
                
                // Verificar integridad (T23)
                performHealthCheck()
            }
        }
    }

    private suspend fun performHealthCheck() {
        // Verificar estado de la sesión
        val state = authManager.sessionState.value
        
        when (state) {
            SessionState.EXPIRED, SessionState.INVALID -> {
                _connectionState.value = ConnectionState.NEEDS_PAIRING
                _authState.value = AuthServiceState.NEEDS_PAIRING
                onSessionExpired?.invoke()
                refreshJob?.cancel()
            }
            SessionState.NONE -> {
                _connectionState.value = ConnectionState.DISCONNECTED
                onConnectionLost?.invoke()
                scheduleRetry()
            }
            else -> { /* OK */ }
        }
    }

    private fun handleRefreshFailure() {
        scope.launch {
            val result = authManager.refreshSession()
            
            when (result) {
                is AuthResult.Success -> {
                    _retryCount.value = 0
                }
                is AuthResult.NeedsPairing -> {
                    _authState.value = AuthServiceState.NEEDS_PAIRING
                    _connectionState.value = ConnectionState.NEEDS_PAIRING
                    onNeedsPairing?.invoke()
                }
                is AuthResult.Error -> {
                    scheduleRetry()
                }
            }
        }
    }

    private fun scheduleRetry() {
        val currentRetry = _retryCount.value
        if (currentRetry >= MAX_RETRY_COUNT) {
            _authState.value = AuthServiceState.FAILED
            _connectionState.value = ConnectionState.ERROR
            onConnectionLost?.invoke()
            return
        }
        
        _retryCount.value = currentRetry + 1
        _authState.value = AuthServiceState.RETRYING
        
        scope.launch {
            delay((currentRetry + 1) * 1000L) // Exponential backoff
            start()
        }
    }

    /**
     * Fuerza re-autenticación.
     */
    suspend fun forceReauth() {
        refreshJob?.cancel()
        healthCheckJob?.cancel()
        
        authManager.forceReauth()
        _authState.value = AuthServiceState.REAUTHING
        
        start()
    }

    /**
     * Obtiene el token de acceso actual.
     */
    fun getAccessToken(): String? = authManager.getAccessToken()

    /**
     * Obtiene el device ID.
     */
    fun getDeviceId(): String? = authManager.deviceId.value

    /**
     * Verifica si está emparejado.
     */
    fun isPaired(): Boolean = authManager.isPaired()

    /**
     * Cierra la sesión.
     */
    fun logout() {
        authManager.clearSession()
        refreshJob?.cancel()
        healthCheckJob?.cancel()
        _authState.value = AuthServiceState.IDLE
        _connectionState.value = ConnectionState.DISCONNECTED
    }
}

enum class AuthServiceState {
    IDLE,
    STARTING,
    AUTHENTICATED,
    PAIRING,
    NEEDS_PAIRING,
    REAUTHING,
    RETRYING,
    HANDLING_ANOMALY,
    INTEGRITY_ERROR,
    ERROR,
    FAILED
}
