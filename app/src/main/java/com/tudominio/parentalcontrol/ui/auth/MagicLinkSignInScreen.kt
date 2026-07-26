package com.tudominio.parentalcontrol.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tudominio.parentalcontrol.util.EmailValidator

/**
 * Compose screen for the parent magic-link sign-in flow.
 *
 * Lands here when the parent taps "Soy el padre" on [com.tudominio.parentalcontrol.ui.screen.OnboardingScreen].
 * Collects [MagicLinkUiState] from [MagicLinkViewModel] and pattern-
 * matches on it to render the four branches:
 *
 *  - [MagicLinkUiState.Editing] — `OutlinedTextField` + send button.
 *  - [MagicLinkUiState.Sending] — centered [CircularProgressIndicator].
 *  - [MagicLinkUiState.Sent] — "Revisa tu email" copy.
 *  - [MagicLinkUiState.Failed] — error text + Reintentar button.
 *
 * testTags (per Compose test contract at
 * `app/src/test/.../ui/auth/MagicLinkSignInScreenTest.kt`):
 *
 *  - `magic_link_email_field` — the [OutlinedTextField] in Editing.
 *  - `magic_link_send_button` — the primary action button in Editing.
 *  - `magic_link_loading` — the [CircularProgressIndicator] in Sending.
 *  - `magic_link_error_text` — the inline error label in Failed.
 *  - `magic_link_retry_button` — the Reintentar button in Failed.
 *
 * Receives [onBack] so the [com.tudominio.parentalcontrol.ui.navigation.NavGraph]
 * can route to NavRoute.Onboarding again if the parent wants to pick
 * the child path instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MagicLinkSignInScreen(
    viewModel: MagicLinkViewModel,
    onBack: () -> Unit = {},
    // Slice B1 — fired on `Authenticated`; activity wires to `restartActivity`.
    onAuthenticated: (() -> Unit)? = null
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Iniciar sesión como padre") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver")
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
            when (val s = state) {
                is MagicLinkUiState.Editing -> EditingContent(
                    email = s.email,
                    onEmailChange = viewModel::onEmailChange,
                    onSubmit = viewModel::submit
                )
                is MagicLinkUiState.Sending -> SendingContent()
                is MagicLinkUiState.Sent -> SentContent(email = s.email)
                // Slice B1 — `devLogin` persisted the session; fire `onAuthenticated`.
                is MagicLinkUiState.Authenticated -> {
                    LaunchedEffect(Unit) { onAuthenticated?.invoke() }
                }
                is MagicLinkUiState.Failed -> FailedContent(
                    errorMessage = s.errorMessage,
                    onRetry = viewModel::retry
                )
            }
        }
    }
}

/**
 * `Editing` branch — text field + send button. The send button is
 * enabled only when [EmailValidator.isValid] matches the trimmed
 * email (the same predicate [MagicLinkViewModel.submit] checks before
 * launching the coroutine).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditingContent(
    email: String,
    onEmailChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "👨\u200d👩\u200d👧\u200d👦",
            style = MaterialTheme.typography.displayLarge
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Iniciar sesión como padre",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Te enviaremos un magic-link a tu email para que puedas iniciar sesión.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        // `OutlinedTextField` with a `value`/`onValueChange` pair is the
        // canonical hoisted-state pattern for Compose text fields.
        // `KeyboardOptions(keyboardType = KeyboardType.Email)` puts the
        // soft keyboard in email-input mode (shows "@" prominently).
        OutlinedTextField(
            value = email,
            onValueChange = onEmailChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("magic_link_email_field"),
            singleLine = true,
            label = { Text("Email") },
            placeholder = { Text("parent@example.com") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("magic_link_send_button"),
            enabled = EmailValidator.isValid(email.trim())
        ) {
            Text("Enviar magic-link")
        }
    }
}

/**
 * `Sending` branch — a centered spinner. Backed by the
 * `magic_link_loading` testTag (Compose test pins the indicator's
 * existence while `DeviceAuthManager.signInWithMagicLink` is in
 * flight, see
 * `magic_link_on_submit_with_sending_state_shows_loading_indicator`).
 */
@Composable
private fun SendingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier
                .size(48.dp)
                .testTag("magic_link_loading")
        )
    }
}

/**
 * `Sent` branch — terminal state. Renders the success copy and the
 * email the link was sent to. No retry CTA (the email is in flight;
 * the user must tap the magic-link they receive).
 */
@Composable
private fun SentContent(email: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "✉️",
            style = MaterialTheme.typography.displayLarge
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Revisa tu email",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Enviamos un link mágico a $email. Ábrelo desde tu celular para continuar.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * `Failed` branch — surfaces the error text + Reintentar button. Tapping
 * Reintentar calls [MagicLinkViewModel.retry] which transitions back
 * to Editing (the email is preserved so the user can correct and
 * resubmit without re-typing).
 */
@Composable
private fun FailedContent(
    errorMessage: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "⚠️",
            style = MaterialTheme.typography.displayLarge
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "No se pudo enviar el magic-link",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = errorMessage,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("magic_link_error_text"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("magic_link_retry_button")
        ) {
            Text("Reintentar")
        }
    }
}
