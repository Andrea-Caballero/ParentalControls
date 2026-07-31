package com.tudominio.parentalcontrol.pairing

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Boundary contract for [PairingViewModel.updateChildFirstName] vs.
 * the Supabase pairing edge function (`supabase/functions/pairing/index.ts`),
 * which validates `child_first_name` as 1..32 characters after trim.
 *
 * Pre-fix bug: Android's [PairingViewModel] truncated at 80 chars. Names
 * 33..80 chars passed the UI sanitization, were stored in SavedStateHandle,
 * and were sent over the wire — only to fail server-side with HTTP 400
 * "child_first_name es requerido (1..32 caracteres)".
 *
 * Post-fix contract verified here:
 *  - a 32-character valid name is preserved verbatim by `updateChildFirstName`,
 *  - a 33+-character valid name is capped at 32 chars,
 *  - [PairingManager.childFirstNameProvider] exposes the same capped value
 *    (i.e. what is persisted == what is sent over the wire),
 *  - a pre-fix 33..80 char value rehydrated from SavedStateHandle is
 *    normalized during ViewModel construction so the restored state
 *    cannot bypass the cap and re-introduce the wire-side HTTP 400.
 *
 * Existing trim + safe-character (`isLetter`, space, '-', `'`) behavior
 * is preserved — these tests use only letters, so the filter is a no-op.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PairingViewModelChildNameLengthTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * Defensive isolation: [PairingViewModel]'s `init {}` writes
     * `childFirstNameProvider` onto the process-wide [PairingManager]
     * singleton. Without teardown, a leak from this class could
     * contaminate [PairingManagerTest] (which shares the same singleton)
     * regardless of test-class ordering or parallel execution. Reset
     * both the provider AND the singleton `instance` field before AND
     * after every test so each test starts from (and ends at) a clean
     * slate. Mirrors the reset strategy used in
     * [PairingManagerTest.setUp].
     */
    @Before
    fun setUp() {
        resetPairingManagerSingleton()
    }

    @After
    fun tearDown() {
        resetPairingManagerSingleton()
    }

    private fun resetPairingManagerSingleton() {
        // Clear the provider FIRST so any subsequent getInstance() call
        // from another test class sees a clean provider, then reset the
        // instance field so the next getInstance() rebuilds the manager.
        PairingManager.getInstance(context).childFirstNameProvider = { null }
        val instanceField = PairingManager::class.java.getDeclaredField("instance")
        instanceField.isAccessible = true
        instanceField.set(null, null)
    }

    private fun newVm(
        savedState: SavedStateHandle = SavedStateHandle()
    ): PairingViewModel = PairingViewModel(
        context = context,
        savedStateHandle = savedState
    )

    @Test
    fun `32 character valid name is preserved verbatim`() {
        val vm = newVm()
        // All letters — passes the isLetter() filter unchanged.
        val name = "A".repeat(32)

        vm.updateChildFirstName(name)

        assertEquals(
            "A name at exactly the 32-char server boundary must not " +
                "be truncated by the ViewModel — otherwise the wire and " +
                "SavedStateHandle would diverge from the server contract.",
            name,
            vm.childFirstName.value
        )
        assertEquals(32, vm.childFirstName.value.length)
    }

    @Test
    fun `33 character valid name is capped at 32 chars`() {
        val vm = newVm()
        // 33 chars exceeds the server contract; UI must not let it through.
        val name = "A".repeat(33)

        vm.updateChildFirstName(name)

        assertEquals(
            "A name above the 32-char server boundary must be capped " +
                "by the ViewModel so the wire payload stays inside the " +
                "supabase/functions/pairing validation window.",
            "A".repeat(32),
            vm.childFirstName.value
        )
        assertEquals(32, vm.childFirstName.value.length)
    }

    @Test
    fun `longer valid name is capped at 32 chars regardless of length`() {
        val vm = newVm()
        // 80 chars was the pre-fix MAX_CHILD_FIRST_NAME_LENGTH — regression
        // check that a length in the previous bypass window (33..80) is
        // still capped at 32 after the fix.
        val name = "A".repeat(80)

        vm.updateChildFirstName(name)

        assertEquals(32, vm.childFirstName.value.length)
        assertEquals("A".repeat(32), vm.childFirstName.value)
    }

    @Test
    fun `childFirstNameProvider returns the same capped value`() {
        val manager = PairingManager.getInstance(context)
        val vm = newVm()
        // 33 chars — proves the provider (init { } wired in the ViewModel)
        // exposes the post-cap value, not the raw 33-char input the user
        // typed. Pre-fix, the provider would surface the 80-char raw value
        // because there was no cap at the ViewModel seam.
        vm.updateChildFirstName("A".repeat(33))

        val providerValue = manager.childFirstNameProvider()
        assertNotNull(
            "childFirstNameProvider must not return null after a valid " +
                "name was set — the wire contract requires the name.",
            providerValue
        )
        assertEquals(
            "childFirstNameProvider must surface the same 32-char capped " +
                "value the ViewModel persisted, so the wire payload " +
                "matches the server contract.",
            vm.childFirstName.value,
            providerValue
        )
        assertEquals(32, providerValue!!.length)
    }

    /**
     * Regression for the restored-state bypass. A pre-fix long name
     * (33..80 chars, written when MAX_CHILD_FIRST_NAME_LENGTH was 80)
     * can be rehydrated from SavedStateHandle on cold start. Pre-fix,
     * `PairingManager.childFirstNameProvider` only trimmed the restored
     * value, so the cap never applied to the restored value and the
     * wire payload was still 33..80 chars → server HTTP 400.
     *
     * Post-fix: ViewModel construction normalizes the restored value
     * through the same sanitizeChildFirstName pipeline used by
     * updateChildFirstName, writes the normalized value back into
     * SavedStateHandle, and the provider exposes the same normalized
     * value that UI sees. Proves all three seams (UI flow, provider,
     * persisted handle) agree on the same 32-char value.
     */
    @Test
    fun `preloaded 80 char name is normalized during construction and provider returns the normalized value`() {
        val restoredHandle = SavedStateHandle(
            mapOf(PairingViewModel.KEY_CHILD_FIRST_NAME to "A".repeat(80))
        )

        val vm = newVm(savedState = restoredHandle)

        // (1) UI sees the normalized value via the StateFlow backed by
        //     the same SavedStateHandle entry the ViewModel wrote back.
        assertEquals(
            "childFirstName must surface the 32-char normalized value, " +
                "not the 80-char raw restored value, so the wire payload " +
                "stays inside the 1..32 server contract.",
            "A".repeat(32),
            vm.childFirstName.value
        )
        assertEquals(32, vm.childFirstName.value.length)

        // (2) Wire side (PairingManager provider) sees the same
        //     normalized value — otherwise the pre-fix bypass is still
        //     open and the 400 will re-appear on the first pairing
        //     action after process death.
        val manager = PairingManager.getInstance(context)
        val providerValue = manager.childFirstNameProvider()
        assertNotNull(
            "childFirstNameProvider must not return null after a valid " +
                "name was restored — the wire contract requires the name.",
            providerValue
        )
        assertEquals(
            "childFirstNameProvider must surface the normalized value " +
                "the ViewModel wrote back, not the raw 80-char restored " +
                "value — otherwise the pre-fix 33..80-char restored-state " +
                "bypass is still open.",
            "A".repeat(32),
            providerValue
        )

        // (3) Persisted state is normalized too, so a future
        //     process-death cycle does not re-introduce the bypass.
        val afterConstruction = restoredHandle.get<String>(
            PairingViewModel.KEY_CHILD_FIRST_NAME
        )
        assertNotNull(
            "SavedStateHandle must retain the value after construction.",
            afterConstruction
        )
        assertEquals(
            "After construction, SavedStateHandle must hold the 32-char " +
                "normalized value (not the raw 80-char restored value), " +
                "so subsequent process-death cycles do not re-introduce " +
                "the bypass.",
            "A".repeat(32),
            afterConstruction
        )
        // Sanity: the ViewModel flow and the handle agree.
        assertEquals(
            "childFirstName flow and SavedStateHandle entry must agree.",
            afterConstruction,
            vm.childFirstName.value
        )
    }

    /**
     * Boundary counterpart to the 80-char test: a 33-char pre-fix
     * restored value (the lower edge of the bypass window, just one
     * char above the 32-char cap) must also be normalized. Same
     * expected behavior — the helper is length-agnostic, so proving
     * the 33-char edge keeps the contract honest at the boundary.
     */
    @Test
    fun `preloaded 33 char name is normalized during construction`() {
        val restoredHandle = SavedStateHandle(
            mapOf(PairingViewModel.KEY_CHILD_FIRST_NAME to "A".repeat(33))
        )

        val vm = newVm(savedState = restoredHandle)

        assertEquals(
            "A 33-char restored value must be normalized to 32 chars " +
                "during construction.",
            "A".repeat(32),
            vm.childFirstName.value
        )
        assertEquals(32, vm.childFirstName.value.length)
    }
}
