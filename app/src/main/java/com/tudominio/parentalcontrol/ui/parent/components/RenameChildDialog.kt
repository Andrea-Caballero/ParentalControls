package com.tudominio.parentalcontrol.ui.parent.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Material 3 full-screen dialog for renaming a child. The parent enters
 * the new name; the dialog validates client-side (non-empty after trim,
 * length <= 32), and submits via [onConfirm]. The parent VM owns the
 * state machine ([com.tudominio.parentalcontrol.viewmodel.RenameChildState]);
 * this composable is stateless except for the local text-field value.
 *
 * Surface contract (locked by `RenameChildDialogTest` — the 8 RED tests
 * that gate this slice):
 *
 *  - testTag `rename_child_dialog` — Dialog root (Material 3 surface).
 *  - testTag `rename_child_text_field` — the OutlinedTextField.
 *  - testTag `rename_child_save_button` — Guardar.
 *  - testTag `rename_child_cancel_button` — Cancelar.
 *  - testTag `rename_child_error_text` — inline validation / server error.
 *  - testTag `rename_child_loading_indicator` — spinner inside Guardar.
 *
 * Validation (`El nombre no puede estar vacío` / `Máximo 32 caracteres`)
 * matches the Supabase `CHECK length 1-32` on `children.first_name` per
 * the Q2 chain's `005_children_table.sql`. Error label appears for the
 * 5xx server failure via the [errorMessage] parameter (per Q4=p
 * pessimistic rename).
 *
 * Snake-case testTags per the project convention (`device_card`,
 * `pairing_qr`, `auth_missing_sign_in_cta`). Spanish UI copy per Q6.
 *
 * See `openspec/changes/2026-07-07-fix-rename-child-dialog/proposal.md`
 * §1 for the form-factor rationale: `DialogProperties(usePlatformDefaultWidth = false)`
 * follows the Material 3 modal recommendation from design §B.6 of the
 * archived Q2 chain.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RenameChildDialog(
    initialName: String,
    isLoading: Boolean,
    errorMessage: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    val focusRequester = remember { FocusRequester() }

    // Pre-populate the text field when the dialog opens with a new
    // initial name (covers the Editing -> Saving -> Failed re-render
    // path that re-derives from the latest device cache).
    LaunchedEffect(initialName) {
        name = initialName
        focusRequester.requestFocus()
    }

    val validationError by remember {
        derivedStateOf { validateName(name) }
    }
    // Display priority: validation message (most local) first, then any
    // server error passed in. Both paths reuse the same `rename_child_error_text`
    // node so the RED tests #3 / #4 / #6 share one assertion surface.
    val visibleError: String? = validationError ?: errorMessage

    Dialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 4.dp,
            modifier = Modifier
                .width(360.dp)
                .testTag("rename_child_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Renombrar niño",
                    style = MaterialTheme.typography.titleLarge
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre del niño") },
                    singleLine = true,
                    isError = visibleError != null,
                    enabled = !isLoading,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (validationError == null) onConfirm(name)
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .testTag("rename_child_text_field")
                )

                // Inline error visible for validation OR server errors.
                // Uses a stable testTag so RED tests #3 / #4 / #6 locate
                // it via a single selector regardless of cause.
                if (visibleError != null) {
                    Text(
                        text = visibleError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("rename_child_error_text")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !isLoading,
                        modifier = Modifier.testTag("rename_child_cancel_button")
                    ) {
                        Text("Cancelar")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onConfirm(name) },
                        enabled = !isLoading && validationError == null,
                        modifier = Modifier.testTag("rename_child_save_button")
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(18.dp)
                                    .testTag("rename_child_loading_indicator"),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Guardando…")
                        } else {
                            Text("Guardar")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Pure validation helper for [RenameChildDialog]. Returns a localized
 * error message when [name] fails the contract, `null` otherwise.
 *
 * The 1-32 length range mirrors the Supabase `CHECK (length(first_name) between 1 and 32)`
 * on `children.first_name` (per Q2 chain `005_children_table.sql`).
 *
 * Pulled out as a free function so the 8 RED tests cover the exact
 * validation rules without going through Compose (and so future test
 * extensions can call it directly).
 */
internal fun validateName(name: String): String? {
    val trimmed = name.trim()
    return when {
        trimmed.isEmpty() -> "El nombre no puede estar vacío"
        name.length > 32 -> "Máximo 32 caracteres"
        else -> null
    }
}
