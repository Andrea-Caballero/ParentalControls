package com.tudominio.parentalcontrol.pairing

import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.tudominio.parentalcontrol.admin.DeviceAdminPromptCoordinator
import com.tudominio.parentalcontrol.admin.DeviceAdminPromptState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RED→GREEN — production-backed coverage for the post-pairing
 * `DeviceAdminPromptCoordinator` wiring defect.
 *
 * Confirmed pre-fix defect: `RepositoryModule` provides
 * `DeviceAdminPromptCoordinator` as a Hilt `@Singleton` shared with
 * `ChildStatusViewModel`, but `PairingScreen.SuccessContent` was
 * creating its own throwaway instance via `remember { ... }`. When
 * the user tapped "Más tarde", the state was written to a different
 * object than the one `ChildStatusViewModel` observed, so the
 * activation banner never appeared after `onPairingComplete`
 * recreated the activity.
 *
 * Post-fix contract:
 *  - The constructor parameter is the SAME instance exposed via
 *    `PairingViewModel.adminCoordinator` (reference equality, no
 *    defensive copy).
 *  - The `adminCoordinator` property is a stable read-only
 *    reference (no setter, no `var`).
 *  - State transitions on the injected coordinator are visible
 *    through `PairingViewModel.adminCoordinator.state` — the shared
 *    instance contract that the banner test relies on.
 *  - The default constructor (used by `PairingViewModelFactory` and
 *    pre-existing unit tests) still produces a working, per-instance
 *    coordinator so the test seam is preserved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PairingViewModelAdminCoordinatorWiringTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `explicitly injected coordinator is the exact instance exposed by PairingViewModel`() {
        val coordinator = DeviceAdminPromptCoordinator()

        val vm = PairingViewModel(
            context = context,
            savedStateHandle = SavedStateHandle(),
            adminCoordinator = coordinator
        )

        // Reference equality — same JVM object, not a copy, not a
        // re-wrapped delegate. Pre-fix, the UI held a different
        // instance via `remember { DeviceAdminPromptCoordinator() }`
        // so `assertSame` would have failed.
        assertSame(
            "PairingViewModel.adminCoordinator must be the exact " +
                "instance supplied to the constructor so the Hilt " +
                "@Singleton reaches PairingScreen.SuccessContent " +
                "untouched (the ChildStatusViewModel banner relies " +
                "on observing this same object).",
            coordinator,
            vm.adminCoordinator
        )
    }

    @Test
    fun `adminCoordinator is a stable read-only reference across the ViewModel lifetime`() {
        val coordinator = DeviceAdminPromptCoordinator()
        val vm = PairingViewModel(
            context = context,
            savedStateHandle = SavedStateHandle(),
            adminCoordinator = coordinator
        )

        // Reading the property multiple times (e.g. from multiple
        // recompositions of PairingScreen.SuccessContent) must
        // return the same instance — no per-read allocation, no
        // implicit copy. The Compose layer can safely hoist the
        // reference into local variables without `remember { }`.
        val first = vm.adminCoordinator
        val second = vm.adminCoordinator
        assertSame(
            "PairingViewModel.adminCoordinator must be a stable " +
                "reference, not a freshly-allocated wrapper, so " +
                "SuccessContent's reads agree with ChildStatusViewModel's.",
            first,
            second
        )

        // Verify there is no public setter — the property is `val`
        // and the only writable surface is the constructor. The
        // Kotlin compiler enforces this statically, but assert it
        // explicitly to lock the contract against a future
        // accidental `var` swap.
        val setter = vm.javaClass.declaredFields
            .firstOrNull { it.name == "adminCoordinator" }
        assertNotNull(
            "PairingViewModel must declare an adminCoordinator backing field.",
            setter
        )
        assertFalse(
            "PairingViewModel.adminCoordinator must NOT be mutable " +
                "from outside the constructor — the singleton is the " +
                "single source of truth for the prompt state machine.",
            java.lang.reflect.Modifier.isStatic(setter!!.modifiers).not() &&
                setter.modifiers and java.lang.reflect.Modifier.FINAL == 0
        )
    }

    @Test
    fun `pairing side state transitions remain visible on the shared coordinator instance`() {
        val coordinator = DeviceAdminPromptCoordinator()
        val vm = PairingViewModel(
            context = context,
            savedStateHandle = SavedStateHandle(),
            adminCoordinator = coordinator
        )

        // Initial state — fresh Idle.
        assertEquals(
            "Shared coordinator must start in Idle before any " +
                "pairing interaction.",
            DeviceAdminPromptState.Idle,
            vm.adminCoordinator.state.value
        )

        // PairingScreen.SuccessContent's LaunchedEffect calls
        // `coordinator.recordFreshPairing()` exactly once when the
        // success screen mounts. The shared coordinator must move
        // to NeedsActivation so ChildStatusViewModel flips its
        // banner true.
        coordinator.recordFreshPairing()
        assertTrue(
            "recordFreshPairing on the injected coordinator must be " +
                "observable through PairingViewModel.adminCoordinator " +
                "so ChildStatusViewModel sees NeedsActivation and " +
                "renders the one-time banner.",
            vm.adminCoordinator.state.value is DeviceAdminPromptState.NeedsActivation
        )

        // "Más tarde" path — SuccessContent's onSkip calls
        // `coordinator.markSkipped()`. The shared coordinator must
        // hold the Dismissed(skipUsed=true) state so the banner
        // stays sticky across screen changes.
        coordinator.markSkipped()
        val afterSkip = vm.adminCoordinator.state.value
        assertTrue(
            "markSkipped on the injected coordinator must be " +
                "observable through PairingViewModel.adminCoordinator " +
                "so the banner stays sticky (skipUsed=true). Got: $afterSkip",
            afterSkip is DeviceAdminPromptState.Dismissed
        )
        assertTrue(
            "Dismissed state must carry skipUsed=true so the banner " +
                "rendering logic in ChildStatusViewModel treats the " +
                "user as 'chose to defer'. Got: $afterSkip",
            (afterSkip as DeviceAdminPromptState.Dismissed).skipUsed
        )

        // Activation path — SuccessContent's adminLauncher callback
        // calls `coordinator.markAdminActive()` once the user accepts
        // the system Device Admin prompt. The shared coordinator
        // must return to Idle so the banner clears.
        coordinator.markAdminActive()
        assertEquals(
            "markAdminActive on the injected coordinator must be " +
                "observable through PairingViewModel.adminCoordinator " +
                "so the banner clears once Device Admin is enabled.",
            DeviceAdminPromptState.Idle,
            vm.adminCoordinator.state.value
        )
    }

    @Test
    fun `default constructor preserves the test seam with a fresh per-instance coordinator`() {
        // Existing unit tests (`PairingViewModelAdminGateTest`,
        // `PairingScreenRecoveryTest`,
        // `PairingViewModelChildNameLengthTest`,
        // `PairingViewModelManualCodeFormatTest`) build
        // `PairingViewModel(context, savedStateHandle)` directly
        // without a coordinator argument. The default value must
        // produce a working coordinator so those tests keep passing
        // without modification.
        val vmA = PairingViewModel(context, SavedStateHandle())
        val vmB = PairingViewModel(context, SavedStateHandle())

        // Both VMs must have a usable coordinator (non-null state,
        // fresh Idle).
        assertNotNull(vmA.adminCoordinator)
        assertNotNull(vmB.adminCoordinator)
        assertEquals(
            "Default-coordinator seam must start in Idle.",
            DeviceAdminPromptState.Idle,
            vmA.adminCoordinator.state.value
        )

        // Each VM gets its OWN fresh coordinator — no accidental
        // cross-test bleed. This is the same shape as
        // `PairingViewModelFactory(context).create(...)` which is
        // also preserved by the default.
        assertNotSame(
            "Each default-constructed PairingViewModel must get its " +
                "own fresh coordinator so direct-construction tests " +
                "cannot bleed state into one another.",
            vmA.adminCoordinator,
            vmB.adminCoordinator
        )

        // Sanity: mutating vmA's coordinator must NOT leak into vmB's.
        vmA.adminCoordinator.recordFreshPairing()
        assertEquals(
            "Default-coordinator mutations must be isolated per VM " +
                "instance — production Hilt swaps this for the " +
                "@Singleton, where the leak is the intended contract.",
            DeviceAdminPromptState.Idle,
            vmB.adminCoordinator.state.value
        )
    }
}