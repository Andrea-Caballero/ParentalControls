package com.tudominio.parentalcontrol.consent

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tudominio.parentalcontrol.copy.CopyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel para gestión de consentimiento.
 */
class ConsentViewModel(
    private val context: Context
) : ViewModel() {

    private val consentManager = ConsentManager.getInstance(context)
    private val copyManager = CopyManager.getInstance(context)

    // Estado de la UI
    private val _uiState = MutableStateFlow<ConsentUiState>(ConsentUiState.Loading)
    val uiState: StateFlow<ConsentUiState> = _uiState.asStateFlow()

    // Indica si debe mostrar la divulgación
    private val _needsDisclosure = MutableStateFlow(true)
    val needsDisclosure: StateFlow<Boolean> = _needsDisclosure.asStateFlow()

    // Indica si debe mostrar onboarding
    private val _needsOnboarding = MutableStateFlow(false)
    val needsOnboarding: StateFlow<Boolean> = _needsOnboarding.asStateFlow()

    init {
        checkState()
    }

    /**
     * Verifica el estado actual y determina qué pantalla mostrar.
     */
    fun checkState() {
        viewModelScope.launch {
            _needsDisclosure.value = consentManager.needsDisclosure()
            _needsOnboarding.value = consentManager.needsOnboarding()
            
            _uiState.value = when {
                consentManager.needsDisclosure() -> ConsentUiState.NeedsDisclosure
                consentManager.needsOnboarding() -> ConsentUiState.NeedsOnboarding
                else -> ConsentUiState.SetupComplete
            }
        }
    }

    /**
     * Registra el consentimiento afirmativo.
     */
    fun giveConsent() {
        consentManager.giveDisclosureConsent()
        checkState()
    }

    /**
     * Declina el consentimiento.
     */
    fun declineConsent() {
        _uiState.value = ConsentUiState.ConsentDeclined
    }

    /**
     * Completa el onboarding.
     */
    fun completeOnboarding() {
        consentManager.completeOnboarding()
        checkState()
    }

    /**
     * Establece la banda de edad.
     */
    fun setAgeBand(ageBand: AgeBand) {
        consentManager.setAgeBand(ageBand)
    }

    /**
     * Obtiene la banda de edad actual.
     */
    fun getAgeBand(): AgeBand = consentManager.getAgeBand()

    /**
     * Resetea todo el consentimiento.
     */
    fun resetConsent() {
        consentManager.resetAll()
        checkState()
    }

    /**
     * Obtiene información de consentimiento.
     */
    fun getConsentInfo(): ConsentInfo = consentManager.getConsentInfo()

    /**
     * Verifica si se puede avanzar (requiere consentimiento).
     */
    fun canProceed(): Boolean = consentManager.isSetupComplete()

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ConsentViewModel(context) as T
                }
            }
        }
    }
}

/**
 * Estados de la UI de consentimiento.
 */
sealed class ConsentUiState {
    data object Loading : ConsentUiState()
    
    /** Necesita mostrar divulgación */
    data object NeedsDisclosure : ConsentUiState()
    
    /** Necesita mostrar onboarding */
    data object NeedsOnboarding : ConsentUiState()
    
    /** Setup completo, puede avanzar */
    data object SetupComplete : ConsentUiState()
    
    /** Consentimiento rechazado */
    data object ConsentDeclined : ConsentUiState()
}
