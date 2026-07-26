package com.tudominio.parentalcontrol.ui.child.status

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * P1 — bounded policy-poll helper. Owns the lifecycle-bound
 * coroutine that drives `pullPolicy` while the child UI is alive.
 *
 * The contract is enforced by the unit tests in
 * `PolicyPollTest`:
 *  - First tick fires IMMEDIATELY (no initial delay) so an
 *    already-open child UI observes the parent's `set-device-state
 *    → LOCKED` on the very next sync.
 *  - Subsequent ticks fire every [INTERVAL_MS] (8_000L → ≤8s
 *    worst-case bound for any state change to be observed).
 *  - No overlap: the next tick is only scheduled AFTER the
 *    previous one completes (sequential, not parallel).
 *  - Pull errors are caught and logged; the loop continues.
 *  - Cancelling the returned [Job] stops future pulls — no global
 *    infinite leak.
 *
 * The helper takes a `CoroutineScope` directly so the unit test
 * can use a `TestScope` and virtual time, sidestepping the
 * AndroidX `viewModelScope` ↔ `StandardTestDispatcher` incompat.
 */
object PolicyPoll {
    private const val TAG = "PolicyPoll"

    /**
     * P1 — bounded pullPolicy interval. Targets <=8s so a parent
     * `lockDevice` reaches an already-open child UI within the
     * documented bounded interval.
     */
    const val INTERVAL_MS: Long = 8_000L

    /**
     * Launch a bounded [pullPolicy] loop on [scope]. Returns the
     * [Job] so the caller can cancel explicitly (e.g. in the
     * ViewModel's `onCleared` — `viewModelScope` cancellation
     * alone is redundant but explicit per the OPPO contract).
     */
    fun start(
        scope: CoroutineScope,
        pullPolicy: suspend () -> Unit,
    ): Job = scope.launch {
        // First tick fires immediately. The OPPO task spec
        // requires the child's first observation within the
        // bounded interval — with an initial delay, the bound
        // would be 2×INTERVAL_MS. Immediate-first keeps the
        // bound at INTERVAL_MS.
        while (true) {
            runCatching { pullPolicy() }
                .onFailure { Log.w(TAG, "policy poll failed: ${it.message}") }
            delay(INTERVAL_MS)
        }
    }
}
