package com.tudominio.parentalcontrol.workers

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
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
 * Pins the post-pairing scheduling containment contract.
 *
 * Pre-fix: WorkerInitializer.reinitializeAfterPairing rethrew
 * WorkManager exceptions, crashing the viewModelScope coroutine before
 * PairingViewModel could set PairingUiState.Success. Pairing is
 * irreversible so the UI was stranded in Pairing.
 *
 * Post-fix: reinitializeAfterPairing returns SCHEDULED/FAILED and
 * NEVER rethrows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WorkerInitializerTest {

    private val context
        get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `reinitializeAfterPairing returns SCHEDULED and enqueues exactly once on the happy path`() {
        mockkObject(WorkScheduler)
        every { WorkScheduler.scheduleSyncAfterPairing(any()) } returns Unit

        val outcome = WorkerInitializer.reinitializeAfterPairing(context)

        assertEquals(PostPairingSchedulingOutcome.SCHEDULED, outcome)
        verify(exactly = 1) { WorkScheduler.scheduleSyncAfterPairing(context) }
    }

    @Test
    fun `reinitializeAfterPairing returns FAILED and never rethrows when WorkScheduler throws`() {
        mockkObject(WorkScheduler)
        val cause = IllegalStateException("WorkManager down")
        every { WorkScheduler.scheduleSyncAfterPairing(any()) } throws cause

        val outcome = try {
            WorkerInitializer.reinitializeAfterPairing(context)
        } catch (t: Throwable) {
            throw AssertionError(
                "reinitializeAfterPairing MUST NOT rethrow WorkManager exceptions; got ${t::class.qualifiedName}: ${t.message}",
                t
            )
        }

        assertEquals(PostPairingSchedulingOutcome.FAILED, outcome)
        verify(exactly = 1) { WorkScheduler.scheduleSyncAfterPairing(context) }

        val warnings = ShadowLog.getLogs()
            .filter { it.tag == "WorkerInitializer" && it.type == Log.WARN }
        assertTrue(
            "Expected a Log.w from WorkerInitializer naming the post-pairing failure; got: $warnings",
            warnings.any { it.msg.contains("post-emparejamiento") }
        )
    }

    @Test
    fun `recovery returns SCHEDULED and enqueues exactly once on the happy path`() {
        mockkObject(WorkScheduler)
        every { WorkScheduler.scheduleSyncAfterPairingRecovery(any()) } returns Unit

        val outcome = WorkerInitializer.recoverAfterPairing(context)

        assertEquals(PostPairingSchedulingOutcome.SCHEDULED, outcome)
        verify(exactly = 1) { WorkScheduler.scheduleSyncAfterPairingRecovery(context) }
    }

    @Test
    fun `recovery returns FAILED logs and never rethrows when WorkScheduler throws`() {
        mockkObject(WorkScheduler)
        val cause = IllegalStateException("WorkManager still down")
        every { WorkScheduler.scheduleSyncAfterPairingRecovery(any()) } throws cause

        val outcome = try {
            WorkerInitializer.recoverAfterPairing(context)
        } catch (t: Throwable) {
            throw AssertionError("Recovery MUST NOT rethrow WorkManager exceptions", t)
        }

        assertEquals(PostPairingSchedulingOutcome.FAILED, outcome)
        verify(exactly = 1) { WorkScheduler.scheduleSyncAfterPairingRecovery(context) }
        val warnings = ShadowLog.getLogs()
            .filter { it.tag == "WorkerInitializer" && it.type == Log.WARN }
        assertTrue(warnings.any { it.msg.contains("recuperación sync post-emparejamiento") })
    }
}
