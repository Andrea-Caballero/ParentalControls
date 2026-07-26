package com.tudominio.parentalcontrol.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tudominio.parentalcontrol.BuildConfig
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.auth.MagicLinkSent
import com.tudominio.parentalcontrol.auth.ParentSession
import com.tudominio.parentalcontrol.util.EmailValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for [MagicLinkSignInScreen]. Drives the parent magic-link
 * sign-in flow (Q1=b magic-link path from
 * `openspec/changes/feat-cross-device-pairing-and-approval/tasks.md`).
 *
 * State machine:
 *
 *   Editing(email)  --submit() w/ invalid email--> (no-op; UI gates
 *                                                    this anyway)
 *   Editing(email)  --submit() w/ valid email--->  Sending(email)
 *   Sending(email)  --signInWithMagicLink success-> Sent(email)
 *   Sending(email)  --signInWithMagicLink failure-> Failed(email, err)
 *   Failed(email,_) --retry()---------------------> Editing(email)
 *
 * The initial state is `Editing("")`. `submit()` is a no-op when the
 * state is anything other than `Editing` — the UI also disables the
 * send button outside `Editing`, so a duplicate tap cannot re-fire.
 *
 * Why the [MagicLinkSender] interface (instead of injecting
 * [DeviceAuthManager] directly): Compose UI tests would otherwise need
 * to `mockk<DeviceAuthManager>` to verify the sign-in call, but this
 * dev machine has a project-wide MockK + JDK 21 incompatibility
 * (~80 pre-existing failures across the suite, see
 * `openspec/changes/feat-cross-device-pairing-and-apply-progress.md`
 * §Blockers). The interface is the test seam — tests construct the VM
 * with a hand-rolled `FakeMagicLinkSender` that captures the email and
 * returns a configurable `Result`. Production uses the Hilt-bound
 * [DeviceAuthManagerMagicLinkSender] wrapper without modifying
 * `DeviceAuthManager.kt` (out-of-scope per the apply contract).
 *
 * Mirror of [com.tudominio.parentalcontrol.viewmodel.RenameChildState]:
 * hoisted onto the VM so the Compose state machine is testable
 * without a Compose rule (Compose tests still work — they just don't
 * need to be the primary verification surface).
 *
 * @HiltViewModel so the screen can use `hiltViewModel()` from the
 * navigation graph (`NavGraph` calls the screen with an explicit VM
 * parameter, paralleling `DashboardScreen(viewModel: ParentViewModel,
 * ...)`).
 */
@HiltViewModel
class MagicLinkViewModel @Inject constructor(
    private val sender: MagicLinkSender
) : ViewModel() {

    private val _state = MutableStateFlow<MagicLinkUiState>(MagicLinkUiState.Editing(""))
    val state: StateFlow<MagicLinkUiState> = _state.asStateFlow()

    /**
     * Updates the in-flight email. Always called from the
     * `OutlinedTextField`'s `onValueChange` callback. Only the
     * `Editing` state owns the mutable field — `Sending` / `Sent` /
     * `Failed` carry the email at the moment of submission and are
     * NOT mutated by further user typing (the text field is hidden in
     * those branches).
     */
    fun onEmailChange(email: String) {
        val current = _state.value
        if (current is MagicLinkUiState.Editing) {
            _state.value = current.copy(email = email)
        }
    }

    /**
     * Submits the email. No-op when the state is not `Editing` (UI
     * gates this anyway; defensive double-check prevents duplicate
     * submissions on fast taps).
     *
     * On success the state moves to `Sent(email)` and the screen
     * shows "Revisa tu email". On failure the state moves to
     * `Failed(email, message)` and the screen shows the inline error
     * + a Reintentar button.
     */
    fun submit() {
        val current = _state.value as? MagicLinkUiState.Editing ?: return
        val email = current.email.trim()
        if (!EmailValidator.isValid(email)) return // UI gates this; defensive
        if (email != current.email) {
            _state.value = current.copy(email = email)
        }
        _state.value = MagicLinkUiState.Sending(email)
        viewModelScope.launch {
            // Slice B1 — when `USE_SHARED_MOCK=true`, call `/auth/v1/dev-login`
            // and emit `Authenticated`. When false, behavior is unchanged.
            val useSharedMock = BuildConfig.USE_SHARED_MOCK
            val result = if (useSharedMock) sender.devLogin(email) else sender.signInWithMagicLink(email)
            _state.value = if (result.isSuccess) {
                if (useSharedMock) MagicLinkUiState.Authenticated(email) else MagicLinkUiState.Sent(email)
            } else {
                val message = result.exceptionOrNull()?.message ?: "Error al enviar el magic-link"
                MagicLinkUiState.Failed(email = email, errorMessage = message)
            }
        }
    }

    /**
     * Transitions from `Failed` back to `Editing(email)` so the user
     * can correct the address (or just hit send again). No-op outside
     * `Failed`.
     */
    fun retry() {
        val current = _state.value as? MagicLinkUiState.Failed ?: return
        _state.value = MagicLinkUiState.Editing(email = current.email)
    }
}

/** Test seam for [MagicLinkViewModel] (avoids `mockk<DeviceAuthManager>`,
 *  see class docs). Slice B1 adds [devLogin] for the shared-mock bypass. */
interface MagicLinkSender {
    suspend fun signInWithMagicLink(email: String): Result<MagicLinkSent>
    suspend fun devLogin(email: String): Result<ParentSession>
}

/** Forwards to [DeviceAuthManager]. Keeps `DeviceAuthManager.kt` untouched. */
class DeviceAuthManagerMagicLinkSender @Inject constructor(
    private val authManager: DeviceAuthManager
) : MagicLinkSender {
    override suspend fun signInWithMagicLink(email: String): Result<MagicLinkSent> =
        authManager.signInWithMagicLink(email)

    override suspend fun devLogin(email: String): Result<ParentSession> =
        authManager.devLogin(email)
}

/**
 * Sealed UI state for [MagicLinkSignInScreen] / [MagicLinkViewModel].
 *
 *  - [Editing]: text field editable, send button enabled iff
 *    [EmailValidator.isValid] matches `email`.
 *  - [Sending]: text field + send button hidden, loading indicator
 *    shown, the API call is in flight.
 *  - [Sent]: terminal state — the magic-link email was dispatched.
 *    Text field + send button hidden; "Revisa tu email" copy rendered.
 *  - [Failed]: terminal-but-recoverable — error text + Reintentar
 *    button rendered. Tapping Reintentar calls
 *    [MagicLinkViewModel.retry] which moves back to [Editing].
 *
 * Carries `email` on every variant so the screen can show "Enviamos
 * un link a parent@example.com" in the Sent / Failed branches.
 */
sealed interface MagicLinkUiState {
    val email: String

    data class Editing(override val email: String) : MagicLinkUiState

    data class Sending(override val email: String) : MagicLinkUiState

    data class Sent(override val email: String) : MagicLinkUiState

    /** Slice B1 — terminal state emitted after a successful `devLogin`. */
    data class Authenticated(override val email: String) : MagicLinkUiState

    data class Failed(
        override val email: String,
        val errorMessage: String
    ) : MagicLinkUiState
}
