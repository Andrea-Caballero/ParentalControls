package com.tudominio.parentalcontrol.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Onboarding role-selection screen. Parent sign-in is currently disabled;
 * the child card routes to pairing via `onSelectChild`.
 *
 * The parent card remains visible for role context but is explicitly
 * disabled until a supported parent-auth flow is shipped.
 *
 * The role cards use [Card] with [Modifier.clickable] (not
 * `OutlinedCard(onClick = ...)`) because the Material3 `onClick`
 * overload is experimental and Compose's stability inference can
 * optimize away a direct `onClick = onSelectChild` parameter
 * reference, causing the click to silently no-op in tests.
 * `Modifier.clickable` is the standard, test-friendly equivalent.
 */
@Composable
fun OnboardingScreen(
    onSelectChild: () -> Unit = {}
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
            text = "Control Parental",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Configura este dispositivo",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = "¿Quién va a usar este dispositivo?",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Padre
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("onboarding_parent_card")
                .semantics {
                    disabled()
                },
            colors = CardDefaults.outlinedCardColors()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "👨",
                    style = MaterialTheme.typography.displayMedium
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = "Soy el padre",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Gestionar dispositivos de mis hijos",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Inicio de sesión no disponible",
                        modifier = Modifier.testTag("onboarding_parent_unavailable"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Hijo
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("onboarding_child_card")
                .clickable {
                    onSelectChild()
                }
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "👦",
                style = MaterialTheme.typography.displayMedium
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = "Soy el hijo",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Emparejar con la cuenta de mis padres",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
