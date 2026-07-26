package com.tudominio.parentalcontrol.ui.parent.screens

import com.tudominio.parentalcontrol.data.repository.DeviceListError
import com.tudominio.parentalcontrol.viewmodel.DeviceListUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * State-machine tests for [EmptyRefreshScheduler] — the policy that
 * drives the parent Dashboard's bounded empty-state auto-refresh.
 *
 * These tests exercise the actual state sequence the production
 * LaunchedEffect drives (Empty → Loading → Empty → … → Success) so
 * the prior bug (Loading reset the attempt counter, defeating the
 * bounded budget and producing an infinite 10s storm) is regression-
 * pinned. Delay-math-only tests would not have caught it.
 */
class EmptyStateRefreshPolicyTest {

    private fun scheduler(maxAttempts: Int = 3, baseDelay: Long = 1_000L) =
        EmptyRefreshScheduler(maxAttempts = maxAttempts, baseDelayMs = baseDelay)

    @Test
    fun `Empty first entry returns base delay and advances attempt`() {
        val s = scheduler()
        assertEquals(1_000L, s.onState(DeviceListUiState.Empty))
    }

    @Test
    fun `Loading preserves the attempt counter across the Empty-Loading-Empty flicker`() {
        val s = scheduler()
        // Empty (attempt 1 → 2), Loading (no reset), Empty (attempt 2 → 3)
        assertEquals(1_000L, s.onState(DeviceListUiState.Empty))
        assertNull("Loading must NOT return a delay", s.onState(DeviceListUiState.Loading))
        // After Loading, the next Empty returns the SECOND delay
        // (2_000L), proving the counter survived the flicker.
        assertEquals(2_000L, s.onState(DeviceListUiState.Empty))
    }

    @Test
    fun `Success resets the counter for the next empty cycle`() {
        val s = scheduler()
        s.onState(DeviceListUiState.Empty) // attempt 1 → 2
        s.onState(DeviceListUiState.Empty) // attempt 2 → 3
        assertNull(
            s.onState(DeviceListUiState.Success(items = emptyList()))
        )
        // New empty cycle starts at attempt 1 again.
        assertEquals(1_000L, s.onState(DeviceListUiState.Empty))
    }

    @Test
    fun `Error resets the counter for the next empty cycle`() {
        val s = scheduler()
        s.onState(DeviceListUiState.Empty)
        assertNull(
            s.onState(DeviceListUiState.Error(reason = DeviceListError.Transient("x")))
        )
        assertEquals(1_000L, s.onState(DeviceListUiState.Empty))
    }

    @Test
    fun `budget exhaustion stops the loop and the static CTA takes over`() {
        val s = scheduler(maxAttempts = 2, baseDelay = 100L)
        // Two attempts then null.
        assertEquals(100L, s.onState(DeviceListUiState.Empty))
        assertEquals(200L, s.onState(DeviceListUiState.Empty))
        assertNull(
            "After exhausting the budget the scheduler must return null " +
                "so the LaunchedEffect stops polling",
            s.onState(DeviceListUiState.Empty)
        )
        // Subsequent Empty entries keep returning null (budget stays
        // exhausted for this empty cycle).
        assertNull(s.onState(DeviceListUiState.Empty))
    }

    @Test
    fun `Loading while budget exhausted stays null`() {
        val s = scheduler(maxAttempts = 1, baseDelay = 100L)
        s.onState(DeviceListUiState.Empty) // attempt 1 → 2 (exhausted)
        assertNull(s.onState(DeviceListUiState.Loading))
        assertNull(s.onState(DeviceListUiState.Empty))
    }
}
