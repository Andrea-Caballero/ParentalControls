package com.tudominio.parentalcontrol

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import com.tudominio.parentalcontrol.ui.navigation.AppNavHost
import com.tudominio.parentalcontrol.ui.theme.ParentalControlTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity host for the ParentalControl app.
 *
 * Per the `app-entry-routing` spec (PR 4 of `align-with-guia-fedora44`),
 * this activity only owns lifecycle + deeplink plumbing. The actual
 * route selection lives in `ui.navigation.NavGraph`, invoked via
 * [AppNavHost]. Two deeplink payloads are hoisted here so they survive
 * both `onCreate` (cold start) and `onNewIntent` (warm start) without
 * an activity restart:
 *
 *   - [prefilledPairingCode]  ← `parentalcontrol://pair?code=…`
 *   - [pendingExtraTimePackage] ← `parentalcontrol://request-extra-time?pkg=…`
 *     (T28 — fired by `BlockOverlayService` when the child taps
 *     "Pedir permiso"; the package name is informational context for
 *     `ExtraTimeScreen` because the actual grant is device-wide).
 *
 * `MainActivity.setContent { ... }` is capped at 5 lines per the spec
 * (see `MainActivityRoutingTest`). The wiring is therefore delegated
 * to [ParentalControlApp], a private @Composable helper that closes
 * over the activity's hoisted state and the lifecycle callbacks.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val prefilledPairingCode = mutableStateOf<String?>(null)
    private val pendingExtraTimePackage = mutableStateOf<String?>(null)
    private val pendingMagicLinkUrl = mutableStateOf<String?>(null)
    // Slice B1 — fix-1: nav trigger for the devLogin success path;
    // NavGraph's LaunchedEffect routes to Dashboard without calling
    // recreate() (see field kdoc in commit body for the root cause).
    private val pendingAuthenticatedRoute = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleDeeplink(intent)
        setContent {
            ParentalControlTheme {
                ParentalControlApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeeplink(intent)
    }

    /**
     * Dispatches to the matching deeplink handler based on the URI host.
     * Originally named `handlePairingDeeplink` (PR 4 of `align-with-guia-fedora44`);
     * renamed to `handleDeeplink` when T28 of `overlay-to-extratime` added
     * a second deeplink (`request-extra-time`). The test
     * `MainActivityRoutingTest#main_activity_retains_handle_pairing_deeplink_helper`
     * is updated to assert on the new name.
     *
     * Adding a new deeplink means one more branch here + one more intent-filter
     * in `AndroidManifest.xml` + one more state field above.
     */
    private fun handleDeeplink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val data = intent.data ?: return
        if (data.scheme != "parentalcontrol") return
        when (data.host) {
            "pair" -> prefilledPairingCode.value = data.getQueryParameter("code")
            "request-extra-time" ->
                pendingExtraTimePackage.value = data.getQueryParameter("pkg") ?: ""
            // Continuation #2: forward the full magic-link URL to the
            // NavGraph, which runs MagicLinkDeepLinkHandler off it. We pass
            // the whole URI string (not the parsed params) so the pure,
            // Context-free handler owns all parsing/validation in one place.
            "magic-link" -> pendingMagicLinkUrl.value = data.toString()
        }
    }

    /**
     * Triggers an activity `recreate()` so the next composition picks up
     * the freshly persisted pairing state (parent vs child device).
     * Invoked by `NavGraph` when `PairingScreen` reports success via
     * its `onPairingComplete` callback.
     */
    private fun restartActivity() {
        recreate()
    }

    /**
     * @Composable wiring helper. Kept inside `MainActivity` (not in
     * another file) so the activity owns the close-over of its hoisted
     * state and lifecycle callbacks. Extracted from `setContent { ... }`
     * so the `setContent` body stays at the spec's ≤ 5-line cap.
     */
    @Composable
    private fun ParentalControlApp() {
        AppNavHost(
            prefilledPairingCode = prefilledPairingCode.value,
            pendingExtraTimePackage = pendingExtraTimePackage.value,
            pendingMagicLinkUrl = pendingMagicLinkUrl.value,
            pendingAuthenticatedRoute = pendingAuthenticatedRoute.value,
            onPairingComplete = ::restartActivity,
            onExtraTimeConsumed = { pendingExtraTimePackage.value = null },
            onMagicLinkConsumed = { pendingMagicLinkUrl.value = null },
            onAuthenticatedRouteConsumed = { pendingAuthenticatedRoute.value = null },
            // Slice B1 — devLogin success → set nav trigger (replaces `::restartActivity`).
            onAuthenticated = { pendingAuthenticatedRoute.value = System.currentTimeMillis() }
        )
    }
}
