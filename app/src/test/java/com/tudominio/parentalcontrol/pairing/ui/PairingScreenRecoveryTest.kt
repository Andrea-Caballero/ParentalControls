package com.tudominio.parentalcontrol.pairing.ui

import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.tudominio.parentalcontrol.pairing.PairingNavigationEvent
import com.tudominio.parentalcontrol.pairing.PairingUiState
import com.tudominio.parentalcontrol.pairing.PairingViewModel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PairingScreenRecoveryTest {

    @Test
    fun `open parent panel event returns to code entry flow and preserves child name`() {
        val viewModel = PairingViewModel(
            ApplicationProvider.getApplicationContext(),
            SavedStateHandle()
        )
        viewModel.updateChildFirstName("Lucía")
        viewModel.startManualPairing()
        assertEquals(PairingUiState.EnteringCode, viewModel.uiState.value)

        handlePairingNavigationEvent(
            event = PairingNavigationEvent.OpenParentPanel,
            viewModel = viewModel,
            onPairingComplete = {},
            onCancel = {}
        )

        assertEquals(PairingUiState.Idle, viewModel.uiState.value)
        assertEquals("Lucía", viewModel.childFirstName.value)
    }
}
