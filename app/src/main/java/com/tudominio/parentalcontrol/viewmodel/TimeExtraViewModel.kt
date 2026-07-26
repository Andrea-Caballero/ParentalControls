package com.tudominio.parentalcontrol.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tudominio.parentalcontrol.data.model.GrantEntity
import com.tudominio.parentalcontrol.data.model.TimeRequestEntity
import com.tudominio.parentalcontrol.data.repository.GrantResult
import com.tudominio.parentalcontrol.data.repository.TimeExtraRepository
import com.tudominio.parentalcontrol.data.repository.TimeRequestResult
import com.tudominio.parentalcontrol.time.DefaultTimeProvider
import com.tudominio.parentalcontrol.time.TimeProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * ViewModel para el flujo de tiempo extra (T28).
 * 
 * §0.4 paso 6: El grant levanta límites pero no desbloquea blocked/allow_only.
 * §0.9: Offline-tolerante con throttle.
 */
class TimeExtraViewModel(
    private val context: Context
) : ViewModel() {

    private val repository = TimeExtraRepository.getInstance(context)
    private val timeProvider: TimeProvider = DefaultTimeProvider(context)

    // Solicitudes del niño
    private val _myRequests = MutableStateFlow<List<TimeRequestUi>>(emptyList())
    val myRequests: StateFlow<List<TimeRequestUi>> = _myRequests.asStateFlow()

    // Grants activos
    private val _activeGrants = MutableStateFlow<List<GrantUi>>(emptyList())
    val activeGrants: StateFlow<List<GrantUi>> = _activeGrants.asStateFlow()

    // Tiempo extra disponible total (en minutos)
    private val _extraTimeAvailable = MutableStateFlow(0L)
    val extraTimeAvailable: StateFlow<Long> = _extraTimeAvailable.asStateFlow()

    // Countdown del grant más próximo a expirar
    private val _nextExpireCountdown = MutableStateFlow<Long?>(null)
    val nextExpireCountdown: StateFlow<Long?> = _nextExpireCountdown.asStateFlow()

    // Estado de carga
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Error
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Resultado de enviar solicitud
    private val _requestSent = MutableStateFlow<ExtraTimeRequestState?>(null)
    val requestSent: StateFlow<ExtraTimeRequestState?> = _requestSent.asStateFlow()

    // Job para countdown
    private var countdownJob: Job? = null

    init {
        // Observe extra_time grants reactively so the home screen updates
        // the moment a new grant is created (e.g., by the post-boot
        // pullApprovedRequests after a parent approve, which used to
        // require restarting the app for the UI to refresh).
        viewModelScope.launch {
            repository.observeExtraTimeGrants().collect { grants ->
                val now = timeProvider.wallInstant().toString()
                val active = grants.filter { it.expires_at > now }
                _activeGrants.value = active.map { it.toUi() }
                _extraTimeAvailable.value =
                    active.sumOf { it.minutes }.toLong()
            }
        }
        refreshData()
        startCountdown()
    }

    /**
     * Refresca datos desde el repositorio.
     */
    fun refreshData() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Cargar grants activos
                val grants = repository.getActiveExtraTimeGrant()
                if (grants != null) {
                    _activeGrants.value = listOf(grants.toUi())
                    _extraTimeAvailable.value = calculateRemainingMinutes(grants)
                } else {
                    _activeGrants.value = emptyList()
                    _extraTimeAvailable.value = 0
                }
                
            } catch (e: Exception) {
                _error.value = "Error sincronizando: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Envía solicitud de tiempo extra.
     */
    fun sendRequest(minutes: Int, reason: String?) {
        viewModelScope.launch {
            _isLoading.value = true
            _requestSent.value = null
            
            try {
                val result = repository.createTimeRequest(
                    deviceId = "", // Se llena desde el contexto
                    minutes = minutes,
                    reason = reason
                )
                
                when (result) {
                    is TimeRequestResult.Success -> {
                        _requestSent.value = ExtraTimeRequestState.Success(result.requestId)
                        refreshData()
                    }
                    is TimeRequestResult.Throttled -> {
                        _requestSent.value = ExtraTimeRequestState.Throttled(result.waitMinutes)
                    }
                    is TimeRequestResult.InvalidMinutes -> {
                        _requestSent.value = ExtraTimeRequestState.InvalidMinutes
                    }
                    is TimeRequestResult.Error -> {
                        _requestSent.value = ExtraTimeRequestState.Error(result.message)
                    }
                }
                
            } catch (e: Exception) {
                _error.value = "Error enviando solicitud: ${e.message}"
                _requestSent.value = ExtraTimeRequestState.Error(e.message ?: "Error desconocido")
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Procesa respuesta de aprobación (desde FCM o Realtime).
     */
    fun processApproval(requestId: String, approvedMinutes: Int) {
        viewModelScope.launch {
            try {
                val result = repository.processApproval(requestId, approvedMinutes)
                
                when (result) {
                    is GrantResult.Success -> {
                        refreshData()
                    }
                    is GrantResult.Error -> {
                        _error.value = result.message
                    }
                }
            } catch (e: Exception) {
                _error.value = "Error procesando aprobación: ${e.message}"
            }
        }
    }

    /**
     * Procesa denegación (desde FCM o Realtime).
     */
    fun processDenial(requestId: String) {
        viewModelScope.launch {
            repository.processDenial(requestId)
        }
    }

    /**
     * Verifica si hay grant activo.
     */
    fun hasActiveGrant(): Boolean = _extraTimeAvailable.value > 0

    /**
     * Verifica si puede usar una app específica.
     */
    fun canUseApp(): UseAppResult {
        val available = _extraTimeAvailable.value
        return when {
            available > 0 -> UseAppResult.CanUse(available)
            else -> UseAppResult.NoTime
        }
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            while (isActive) {
                delay(1000) // Actualizar cada segundo
                updateCountdown()
            }
        }
    }

    private fun updateCountdown() {
        val grant = _activeGrants.value.firstOrNull() ?: return
        
        try {
            val expiresAt = Instant.parse(grant.expiresAt)
            val now = Instant.now()
            val seconds = ChronoUnit.SECONDS.between(now, expiresAt)
            
            _nextExpireCountdown.value = if (seconds > 0) seconds else 0
            
            // Si expiró, refrescar
            if (seconds <= 0) {
                refreshData()
            }
        } catch (e: Exception) {
            _nextExpireCountdown.value = null
        }
    }

    private fun calculateRemainingMinutes(grant: GrantEntity): Long {
        return try {
            val expiresAt = Instant.parse(grant.expires_at)
            val now = Instant.now()
            val seconds = ChronoUnit.SECONDS.between(now, expiresAt)
            maxOf(0, seconds / 60)
        } catch (e: Exception) {
            0
        }
    }

    private fun GrantEntity.toUi(): GrantUi {
        return GrantUi(
            id = id,
            minutesTotal = minutes,
            minutesRemaining = calculateRemainingMinutes(this).toInt(),
            expiresAt = expires_at,
            isExpiringSoon = calculateRemainingMinutes(this) <= 5
        )
    }

    fun clearError() {
        _error.value = null
    }

    fun clearRequestSent() {
        _requestSent.value = null
    }

    override fun onCleared() {
        super.onCleared()
        countdownJob?.cancel()
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return TimeExtraViewModel(context) as T
                }
            }
        }
    }
}

/**
 * UI model para grant.
 */
data class GrantUi(
    val id: String,
    val minutesTotal: Int,
    val minutesRemaining: Int,
    val expiresAt: String,
    val isExpiringSoon: Boolean
)

/**
 * UI model para solicitud.
 */
data class TimeRequestUi(
    val requestId: String,
    val requestedMinutes: Int,
    val reason: String,
    val status: String,
    val createdAt: Long,
    val respondedAt: Long?
)

/**
 * Estados del resultado de solicitud.
 */
sealed class ExtraTimeRequestState {
    data class Success(val requestId: String) : ExtraTimeRequestState()
    data class Throttled(val waitMinutes: Long) : ExtraTimeRequestState()
    data object InvalidMinutes : ExtraTimeRequestState()
    data class Error(val message: String) : ExtraTimeRequestState()
}

/**
 * Resultado de verificar si puede usar app.
 */
sealed class UseAppResult {
    data class CanUse(val minutesAvailable: Long) : UseAppResult()
    data object NoTime : UseAppResult()
}
