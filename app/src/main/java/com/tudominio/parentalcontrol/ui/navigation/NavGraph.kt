package com.tudominio.parentalcontrol.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.tudominio.parentalcontrol.auth.MagicLinkDeepLinkHandler
import com.tudominio.parentalcontrol.auth.MagicLinkVerifier
import com.tudominio.parentalcontrol.auth.Role
import com.tudominio.parentalcontrol.copy.CopyManager
import com.tudominio.parentalcontrol.data.repository.TimeExtraRepository
import com.tudominio.parentalcontrol.pairing.PairingViewModel
import com.tudominio.parentalcontrol.pairing.ui.PairingScreen
import com.tudominio.parentalcontrol.ui.auth.MagicLinkSignInScreen
import com.tudominio.parentalcontrol.ui.auth.MagicLinkViewModel
import com.tudominio.parentalcontrol.ui.child.extra.ExtraTimeScreen
import com.tudominio.parentalcontrol.ui.child.status.ChildStatusScreen
import com.tudominio.parentalcontrol.ui.child.status.ChildStatusViewModel
import com.tudominio.parentalcontrol.ui.parent.screens.DashboardScreen
import com.tudominio.parentalcontrol.ui.screen.OnboardingScreen
import com.tudominio.parentalcontrol.ui.screen.apps.AppsViewModel
import com.tudominio.parentalcontrol.viewmodel.ParentViewModel

/**
 * Top-level routing composable for the ParentalControl app.
 *
 * PR 4 of `align-with-guia-fedora44` (`app-entry-routing` spec) hoists
 * the inline `when { ... }` block that previously lived in
 * `MainActivity.onCreate` so the activity only handles lifecycle +
 * deeplink plumbing.
 *
 * The initial route is decided by [resolveInitialRoute] and then the
 * composable manages its own sub-navigation between the screens that
 * share a top-level entry point:
 *
 *  - [NavRoute.Onboarding]      → unpaired device; advances to
 *                                 MagicLinkSignIn (parent path) or
 *                                 PairingFlow (child path) when the
 *                                 user picks a role.
 *  - [NavRoute.MagicLinkSignIn] → unpaired parent after picking "Soy
 *                                 el padre"; advances to Dashboard
 *                                 when the magic-link is verified
 *                                 (TODO follow-up: deep-link
 *                                 handler — not in scope of this PR).
 *                                 Currently returns to Onboarding if
 *                                 the user taps the back arrow.
 *  - [NavRoute.PairingFlow]     → child device during pairing;
 *                                 cancels back to Onboarding.
 *  - [NavRoute.Dashboard]       → paired parent (initial), or unpaired
 *                                 user who picked "parent" on
 *                                 Onboarding.
 *  - [NavRoute.ChildStatus]     → paired child (initial).
 *  - [NavRoute.ExtraTime]       → child requested extra time (T28 —
 *                                 also reached when `BlockOverlayService`
 *                                 fires the
 *                                 `parentalcontrol://request-extra-time`
 *                                 deeplink); returns to ChildStatus
 *                                 when the request is sent or cancelled.
 *
 * Every ViewModel and manager is passed as a parameter so `MainActivity`
 * resolves them via `hiltViewModel()` / Hilt singletons and tests can
 * inject mocks directly (no Hilt graph needed in the Robolectric tests).
 * The new [MagicLinkViewModel] is resolved via `hiltViewModel()`
 * inline (paralleling how `DashboardScreen` resolves
 * `BehaviorLogViewModel`) so the signature stays bounded.
 *
 * `prefilledPairingCode` and `pendingExtraTimePackage` are hoisted
 * state in `MainActivity` so the corresponding deeplinks survive both
 * `onCreate` and `onNewIntent`. `onPairingComplete` /
 * `onExtraTimeConsumed` are the activity-level callbacks
 * `PairingScreen` / `ExtraTimeScreen` invoke on terminal states; in
 * `MainActivity` they call `recreate()` or clear the pending state so
 * the next composition picks up the freshly persisted state.
 */
@Composable
fun NavGraph(
    isPaired: Boolean,
    isChildDevice: Boolean,
    prefilledPairingCode: String?,
    pendingExtraTimePackage: String?,
    parentViewModel: ParentViewModel,
    appsViewModel: AppsViewModel,
    pairingViewModel: PairingViewModel,
    childStatusViewModel: ChildStatusViewModel,
    copyManager: CopyManager,
    timeExtraRepository: TimeExtraRepository,
    deviceId: String,
    onPairingComplete: () -> Unit,
    onExtraTimeConsumed: () -> Unit,
    pendingMagicLinkUrl: String? = null,
    magicLinkVerifier: MagicLinkVerifier? = null,
    onMagicLinkConsumed: () -> Unit = {},
    pendingAuthenticatedRoute: Long? = null,
    onAuthenticatedRouteConsumed: () -> Unit = {},
    // Slice B1 — fires after `devLogin` success; activity wires to a
    // hoisted nav-trigger (see MainActivity.pendingAuthenticatedRoute).
    onAuthenticated: () -> Unit = {}
) {
    // `remember(key)` resets the route when the pending deeplink arrives.
    // Without the key, the `var route by remember { ... }` would only
    // evaluate the initial branch once and subsequent deeplinks would
    // be silently ignored (the user would stay on whatever screen was
    // already rendered). The key is the package name itself because:
    //   - `null` is the resting state (don't re-route)
    //   - a fresh non-null value means "go to ExtraTime now"
    // After the screen finishes, the activity clears the state via
    // [onExtraTimeConsumed] (back to `null`), so the next composition
    // lands on the natural initial route.
    var route by remember(pendingExtraTimePackage) {
        mutableStateOf(
            resolveInitialRoute(isPaired, isChildDevice, pendingExtraTimePackage)
        )
    }

    // Continuation #2: close the magic-link round-trip. When the parent
    // taps `parentalcontrol://magic-link?token=…&email=…`, MainActivity
    // forwards the URL here; the pure MagicLinkDeepLinkHandler verifies the
    // OTP (which persists the ParentSession via DeviceAuthManager) and, on
    // success, we route to the Dashboard. On any failure the parent is sent
    // to the sign-in screen to retry. `onMagicLinkConsumed` clears the
    // pending URL so a recomposition/recreate does not replay the verify.
    // Keyed on the URL so a fresh deep-link re-triggers the effect.
    LaunchedEffect(pendingMagicLinkUrl) {
        val url = pendingMagicLinkUrl
        if (url != null && magicLinkVerifier != null) {
            val result = MagicLinkDeepLinkHandler(magicLinkVerifier).handle(url)
            route = if (result != null && result.isSuccess) {
                NavRoute.Dashboard
            } else {
                NavRoute.MagicLinkSignIn
            }
            onMagicLinkConsumed()
        }
    }

    // Slice B1 — fix-1: navigate to Dashboard on devLogin success
    // without recreate() (see MainActivity field kdoc + commit body).
    // Mirrors the pendingMagicLinkUrl pattern above.
    LaunchedEffect(pendingAuthenticatedRoute) {
        if (pendingAuthenticatedRoute != null) {
            route = NavRoute.Dashboard
            onAuthenticatedRouteConsumed()
        }
    }

    when (route) {
        NavRoute.Onboarding -> OnboardingScreen(
            onSelectParent = { route = NavRoute.MagicLinkSignIn },
            onSelectChild = { route = NavRoute.PairingFlow }
        )
        NavRoute.MagicLinkSignIn -> {
            // Hilt resolves the VM here (paralleling the
            // `hiltViewModel<BehaviorLogViewModel>()` call inside
            // `DashboardScreen` for the BehaviorLog nav target). The
            // NavGraph signature stays bounded — `MagicLinkViewModel`
            // does not pollute the constructor arg list.
            val magicLinkVm: MagicLinkViewModel = hiltViewModel()
            MagicLinkSignInScreen(
                viewModel = magicLinkVm,
                onBack = { route = NavRoute.Onboarding },
                onAuthenticated = onAuthenticated
            )
        }
        NavRoute.PairingFlow -> PairingScreen(
            viewModel = pairingViewModel,
            onPairingComplete = onPairingComplete,
            onCancel = { route = NavRoute.Onboarding },
            prefilledCode = prefilledPairingCode
        )
        NavRoute.Dashboard -> DashboardScreen(
            viewModel = parentViewModel,
            appsViewModel = appsViewModel,
            onNavigateToDevice = { },
            onNavigateToRequests = { }
        )
        NavRoute.ChildStatus -> ChildStatusScreen(
            viewModel = childStatusViewModel,
            copyManager = copyManager,
            onRequestExtraTime = { route = NavRoute.ExtraTime }
        )
        NavRoute.ExtraTime -> ExtraTimeScreen(
            copyManager = copyManager,
            repository = timeExtraRepository,
            deviceId = deviceId,
            prefilledPackage = pendingExtraTimePackage,
            onBack = {
                route = NavRoute.ChildStatus
                onExtraTimeConsumed()
            },
            onRequestSent = {
                route = NavRoute.ChildStatus
                onExtraTimeConsumed()
            },
            onError = { onExtraTimeConsumed() }
        )
    }
}

/**
 * The set of top-level routes [NavGraph] can render.
 *
 * Exposed (internal) so unit tests can assert the routing decision in
 * [resolveInitialRoute] without spinning up Compose. The enum is internal
 * (not `private`) so [NavGraphTest] in the same module can reference it
 * for the pure-function tests.
 */
internal enum class NavRoute {
    Onboarding,
    MagicLinkSignIn,
    PairingFlow,
    Dashboard,
    ChildStatus,
    ExtraTime
}

/**
 * Pure-function decision for the entry-point route.
 *
 * Extracted from the composable so tests can pin the 4-way branch
 * with trivial assertions — no Compose, no Hilt, no Robolectric
 * needed for the routing contract itself. The Compose test in
 * [NavGraphTest] complements this by proving the chosen route
 * actually renders.
 *
 * T28: `pendingExtraTimePackage` overrides the natural initial route
 * so the `parentalcontrol://request-extra-time?pkg=…` deeplink
 * routes the child straight to `ExtraTimeScreen`. Unpaired devices
 * drop the deeplink (no parent link exists to grant against).
 *
 * **Discriminator contract (Slice B1 fix)**: `isChildDevice` MUST
 * come from [resolveIsChildDevice] (role-based), not from the legacy
 * `isPaired && parent_id != null` heuristic. Parent devices
 * legitimately carry a `parent_id` for parent-scoped Supabase
 * queries; the heuristic misroutes them to the CHILD UI after a
 * process relaunch.
 */
internal fun resolveInitialRoute(
    isPaired: Boolean,
    isChildDevice: Boolean,
    pendingExtraTimePackage: String? = null
): NavRoute = when {
    !isPaired -> NavRoute.Onboarding
    pendingExtraTimePackage != null -> NavRoute.ExtraTime
    isChildDevice -> NavRoute.ChildStatus
    else -> NavRoute.Dashboard
}

/**
 * Pure-function discriminator that decides whether the device should
 * be routed to the CHILD UI on cold start.
 *
 * Contract:
 *   - Unpaired                        → always `false`.
 *   - Paired + [Role.CHILD]           → `true`.
 *   - Paired + [Role.PARENT]          → `false`. (The OPPO parent
 *                                       relaunch bug.)
 *   - Paired + `role == null`         → `false`. (Defensive default;
 *                                       a stale pre-fix child install
 *                                       is migrated by
 *                                       `DeviceAuthManager.loadPersistedState`
 *                                       before this is read.)
 */
internal fun resolveIsChildDevice(
    isPaired: Boolean,
    role: Role?
): Boolean = isPaired && role == Role.CHILD
