package com.tudominio.parentalcontrol.pairing

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.tudominio.parentalcontrol.workers.PostPairingSchedulingOutcome
import com.tudominio.parentalcontrol.workers.WorkerInitializer
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Pins the post-pairing UI containment contract on PairingViewModel.
 *
 * Pre-fix: handlePairingResult ran reinitializeAfterPairing before
 * setting PairingUiState.Success. A WorkManager exception escaped
 * viewModelScope and stranded the UI in Pairing despite an
 * irreversible successful pairing.
 *
 * Post-fix: a FAILED outcome is logged with a contextual warning AND
 * the UI still transitions to Success(deviceId).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PairingViewModelPairingResultTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun newVm(): PairingViewModel =
        PairingViewModel(ApplicationProvider.getApplicationContext(), SavedStateHandle())

    @Test
    fun `success with failed scheduling still transitions uiState to Success and emits a warning`() = runTest {
        mockkObject(WorkerInitializer)
        every { WorkerInitializer.reinitializeAfterPairing(any()) } returns
            PostPairingSchedulingOutcome.FAILED

        val vm = newVm()
        vm.handlePairingResult(PairingResult.Success(deviceId = "dev-1", parentId = null))

        assertEquals(
            "uiState MUST advance to Success even when scheduling failed — pairing is irreversible.",
            PairingUiState.Success("dev-1"),
            vm.uiState.value
        )
        verify(exactly = 1) { WorkerInitializer.reinitializeAfterPairing(any()) }

        val warnings = ShadowLog.getLogs()
            .filter { it.tag == "PairingViewModel" && it.type == Log.WARN }
        assertTrue(
            "Expected a contextual Log.w naming the post-pairing scheduling failure; got: $warnings",
            warnings.any { it.msg.contains("post-emparejamiento") }
        )
    }

    @Test
    fun `success with scheduled outcome transitions uiState to Success without a warning`() = runTest {
        mockkObject(WorkerInitializer)
        every { WorkerInitializer.reinitializeAfterPairing(any()) } returns
            PostPairingSchedulingOutcome.SCHEDULED

        val vm = newVm()
        vm.handlePairingResult(PairingResult.Success(deviceId = "dev-2", parentId = null))

        assertEquals(PairingUiState.Success("dev-2"), vm.uiState.value)
        verify(exactly = 1) { WorkerInitializer.reinitializeAfterPairing(any()) }

        val warnings = ShadowLog.getLogs()
            .filter { it.tag == "PairingViewModel" && it.type == Log.WARN }
        assertTrue(
            "Happy path must NOT emit a Log.w from PairingViewModel; got: $warnings",
            warnings.isEmpty()
        )
    }
}
