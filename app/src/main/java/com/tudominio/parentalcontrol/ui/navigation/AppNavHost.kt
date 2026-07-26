package com.tudominio.parentalcontrol.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.auth.DeviceAuthManagerMagicLinkVerifier
import com.tudominio.parentalcontrol.copy.CopyManager
import com.tudominio.parentalcontrol.data.repository.TimeExtraRepositoryEntryPoint
import com.tudominio.parentalcontrol.pairing.PairingViewModel
import com.tudominio.parentalcontrol.ui.child.status.ChildStatusViewModel
import com.tudominio.parentalcontrol.ui.screen.apps.AppsViewModel
import com.tudominio.parentalcontrol.viewmodel.ParentViewModel
import dagger.hilt.android.EntryPointAccessors

/**
 * Compose-side wiring for [NavGraph].
 *
 * Lives in `ui/navigation/` (not next to `MainActivity`) so the activity
 * stays focused on lifecycle + deeplink plumbing. The activity reads its
 * own `MutableState<String?>` for [prefilledPairingCode] and
 * [pendingExtraTimePackage] and forwards the current value here; this
 * composable then resolves the auth state, the Hilt-provided singletons
 * (via [EntryPointAccessors] / `hiltViewModel`), and the device id, and
 * feeds everything to [NavGraph] as parameters.
 *
 * The wiring is hoisted out of `MainActivity.onCreate` because:
 *  - `hiltViewModel<>()` requires a Compose context, and `onCreate` is
 *    a plain `Activity` method.
 *  - `LocalContext.current` requires a composable scope.
 *  - `remember { … }` needs a recomposition-aware scope so the auth
 *    manager / copy manager / outbox entry-point aren't re-fetched on
 *    every recomposition.
 *
 * [onPairingComplete] is invoked by [NavGraph] when `PairingScreen`
 * reports success. The activity wires this to `recreate()` so the next
 * composition picks up the freshly persisted pairing state.
 *
 * [onExtraTimeConsumed] is invoked by [NavGraph] when `ExtraTimeScreen`
 * finishes (back, request sent, or error) so the activity clears the
 * pending-extra-time state — otherwise the next composition would
 * re-route to ExtraTime on every recreation. Without this, an activity
 * `recreate()` (e.g. after pairing) would replay the deeplink forever.
 */
@Composable
fun AppNavHost(
    prefilledPairingCode: String?,
    pendingExtraTimePackage: String?,
    pendingMagicLinkUrl: String? = null,
    pendingAuthenticatedRoute: Long? = null,
    onPairingComplete: () -> Unit,
    onExtraTimeConsumed: () -> Unit,
    onMagicLinkConsumed: () -> Unit = {},
    onAuthenticatedRouteConsumed: () -> Unit = {},
    // Slice B1 — forwarded to MagicLinkSignInScreen; activity wires to a
    // nav trigger (no more recreate, see MainActivity.pendingAuthenticatedRoute).
    onAuthenticated: () -> Unit = {}
) {
    val context = LocalContext.current
    val authManager = remember { DeviceAuthManager.getInstance(context) }
    val copyManager = remember { CopyManager.getInstance(context) }
    val timeExtraRepository = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            TimeExtraRepositoryEntryPoint::class.java
        ).timeExtraRepository()
    }
    // Continuation #2: the magic-link deep-link handler is a pure class;
    // wrap the `DeviceAuthManager` singleton in the verifier adapter so
    // the handler stays Context-free and NavGraph can run it off the
    // pending URL without a Hilt dependency.
    val magicLinkVerifier = remember { DeviceAuthManagerMagicLinkVerifier(authManager) }
    val isPaired = authManager.isPaired()
    // Slice B1 — fix-1 (discriminator): use the explicit Role flag
    // instead of the previous `isPaired && parent_id != null` heuristic.
    // Parent devices LEGITIMATELY carry a parent_id (parent-scoped
    // Supabase queries filter by parent_id server-side), so the
    // previous check misrouted the OPPO parent to the CHILD UI after
    // a process relaunch. See [resolveIsChildDevice] for the full
    // contract and regression test in NavGraphTest.
    val role = authManager.getRole()
    val isChildDevice = resolveIsChildDevice(isPaired = isPaired, role = role)
    val deviceId = authManager.deviceId.value.orEmpty()

    NavGraph(
        isPaired = isPaired,
        isChildDevice = isChildDevice,
        prefilledPairingCode = prefilledPairingCode,
        pendingExtraTimePackage = pendingExtraTimePackage,
        pendingMagicLinkUrl = pendingMagicLinkUrl,
        pendingAuthenticatedRoute = pendingAuthenticatedRoute,
        parentViewModel = hiltViewModel<ParentViewModel>(),
        appsViewModel = hiltViewModel<AppsViewModel>(),
        pairingViewModel = hiltViewModel<PairingViewModel>(),
        childStatusViewModel = hiltViewModel<ChildStatusViewModel>(),
        copyManager = copyManager,
        timeExtraRepository = timeExtraRepository,
        deviceId = deviceId,
        magicLinkVerifier = magicLinkVerifier,
        onPairingComplete = onPairingComplete,
        onExtraTimeConsumed = onExtraTimeConsumed,
        onMagicLinkConsumed = onMagicLinkConsumed,
        onAuthenticatedRouteConsumed = onAuthenticatedRouteConsumed,
        onAuthenticated = onAuthenticated
    )
}
