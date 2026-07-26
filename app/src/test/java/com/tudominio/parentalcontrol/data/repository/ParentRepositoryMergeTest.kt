package com.tudominio.parentalcontrol.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.data.local.PendingRequestsCache
import com.tudominio.parentalcontrol.domain.model.RequestStatus
import com.tudominio.parentalcontrol.domain.model.TimeRequest
import com.tudominio.parentalcontrol.network.SupabaseClientProvider
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RED coverage for the merge-on-publish logic of [ParentRepository].
 *
 * Companion to [ParentRepositoryColdStartTest] (which covers the cold-start
 * hydration invariant). The two GREEN tasks that the apply phase has to ship:
 *  - `publishPendingRequests` merges the incoming list with whatever the
 *    flow currently holds, key-based on `TimeRequest.id`, **newer
 *    `status` / `respondedAt` wins**.
 *  - The merge preserves optimistic local updates that have not been synced
 *    upstream yet — e.g., a parent who just tapped "Approve" on a request
 *    whose APPROVED row hasn't reached `approve-request` yet, but a stale
 *    PENDING row already returned from the next polling tick.
 *
 * Today (pre-fix):
 *  - `publishPendingRequests` is `_pendingRequestsFlow.value = list`
 *    (`ParentRepository.kt:81-83`) — last write wins, no merge.
 *  - Both tests below RED today because a stale PENDING server fetch will
 *    CLOBBER the locally-tracked APPROVED row.
 *
 * Bug context:
 *  - engram #244 (`sdd/fix-parent-log-events-cleared-on-reopen/explore`)
 *    root-cause analysis.
 *  - engram #245 (`sdd/fix-parent-log-events-cleared-on-reopen/decisions`)
 *    (m) merge decision.
 *
 * The merge is in-memory and synchronous (the disk cache only matters for
 * cold-start hydration, which [ParentRepositoryColdStartTest] pins). So no
 * `awaitHydration` helper is needed in these tests — the `_pendingRequestsFlow`
 * value is updated synchronously inside the `fun publishPendingRequests`
 * call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ParentRepositoryMergeTest {

    private lateinit var context: Context
    private lateinit var authManager: DeviceAuthManager
    private lateinit var clientProvider: SupabaseClientProvider

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // The merge tests do not exercise the disk layer directly, but
        // `publishPendingRequests` writes through to the DataStore cache,
        // so wipe it for hygiene — keeps a previous test's payload from
        // mutating the in-memory flow via hydration on construction.
        PendingRequestsCache.clearForTest(context)

        authManager = mockk(relaxed = true)
        clientProvider = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        PendingRequestsCache.clearForTest(context)
    }

    private fun newRepository(): ParentRepository =
        ParentRepository(context, authManager, clientProvider)

    /**
     * T1.4 — RED today.
     *
     * Scenario (per tasks.md §1.4):
     *  1. Warm session sees `tr-001` PENDING (the canonical server-side
     *     state). [ParentRepository.publishPendingRequests] seeds the
     *     in-memory flow with it.
     *  2. Local optimistic update: parent taps Approve → flow now holds
     *     `tr-001_APPROVED_local` (status APPROVED, with `respondedAt`
     *     and `parentResponse`). This has not synced upstream yet.
     *  3. Stale server fetch returns `tr-001` (status PENDING, no
     *     `respondedAt`) — the upstream `status=eq.PENDING` filter at
     *     `ParentRepository.kt:159-160` may briefly surface an
     *     already-resolved row between the UPDATE and the SELECT.
     *
     * Expected behavior AFTER fix: the local APPROVED row wins the merge
     * (it has a newer `status` + `respondedAt`), so the flow still
     * surfaces the parent's optimistic decision.
     *
     * Expected behavior TODAY: the third `publishPendingRequests` call is
     * `_pendingRequestsFlow.value = listOf(PENDING)` — the local APPROVED
     * is CLOBBERED, RED.
     */
    @Test
    fun `publishPendingRequests merges with cache preserving local optimistic updates`() = runTest {
        val repository = newRepository()

        val tr001Pending = TimeRequest(
            id = "tr-001",
            deviceId = "dev-001",
            deviceName = "Galaxy Tab S6 Lite",
            minutesRequested = 30,
            reason = "homework",
            status = RequestStatus.PENDING,
            createdAt = "2026-07-03T11:00:00Z"
        )
        repository.publishPendingRequests(listOf(tr001Pending))

        val tr001ApprovedLocal = tr001Pending.copy(
            status = RequestStatus.APPROVED,
            respondedAt = "2026-07-03T12:00:00Z",
            parentResponse = "ok, +30 min"
        )
        repository.publishPendingRequests(listOf(tr001ApprovedLocal))
        assertEquals(
            "sanity: warm flow must carry local APPROVED before stale PENDING server fetch",
            listOf(tr001ApprovedLocal),
            repository.pendingRequestsFlow.value
        )

        // Stale server-side filter surfaces the same row as PENDING (no
        // `respondedAt`, no `parentResponse`). The merge MUST preserve the
        // local APPROVED — it carries a strictly newer `status` +
        // `respondedAt`.
        val tr001StalePendingFromServer = tr001Pending
        repository.publishPendingRequests(listOf(tr001StalePendingFromServer))

        assertEquals(
            "merge: local APPROVED row must survive a stale PENDING server fetch " +
                "(newer status + respondedAt wins)",
            listOf(tr001ApprovedLocal),
            repository.pendingRequestsFlow.value
        )
    }

    /**
     * T1.5 — RED today.
     *
     * Scenario (per tasks.md §1.5):
     *  1. Cache + flow hold `tr-001` APPROVED with
     *     `respondedAt = "2026-07-03T10:00:00Z"` — parent already decided.
     *  2. A stale server fetch (the same upstream-filter window) returns
     *     `tr-001` as PENDING.
     *
     * Expected behavior AFTER fix: merge sees that the local row has
     * `respondedAt` while the incoming doesn't, so the local row wins on
     * "newer status" tie-break → flow stays APPROVED.
     *
     * Expected behavior TODAY: `_pendingRequestsFlow.value = listOf(PENDING)`
     * clobbers the APPROVED row → RED.
     */
    @Test
    fun `publishPendingRequests local newer status wins over stale cache`() = runTest {
        val tr001Approved = TimeRequest(
            id = "tr-001",
            deviceId = "dev-001",
            deviceName = "Galaxy Tab S6 Lite",
            minutesRequested = 30,
            reason = "homework",
            status = RequestStatus.APPROVED,
            createdAt = "2026-07-03T11:00:00Z",
            respondedAt = "2026-07-03T10:00:00Z",
            parentResponse = "ok, +30 min"
        )
        val repository = newRepository()
        repository.publishPendingRequests(listOf(tr001Approved))

        val tr001StalePendingFromServer = TimeRequest(
            id = "tr-001",
            deviceId = "dev-001",
            deviceName = "Galaxy Tab S6 Lite",
            minutesRequested = 30,
            reason = "homework",
            status = RequestStatus.PENDING,
            createdAt = "2026-07-03T11:00:00Z"
        )
        repository.publishPendingRequests(listOf(tr001StalePendingFromServer))

        assertEquals(
            "merge: local APPROVED row with respondedAt must beat the stale " +
                "PENDING server fetch (newer status wins)",
            listOf(tr001Approved),
            repository.pendingRequestsFlow.value
        )
    }
}
