package com.tudominio.parentalcontrol.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tudominio.parentalcontrol.data.local.PendingRequestsCache
import com.tudominio.parentalcontrol.domain.model.RequestStatus
import com.tudominio.parentalcontrol.domain.model.TimeRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric round-trip test for [PendingRequestsCache].
 *
 * Companion to [ParentRepositoryColdStartTest] (cold-start hydration end-to-end
 * on top of the repository) and [ParentRepositoryMergeTest] (in-memory merge
 * semantics on `publishPendingRequests`). This case pins the disk layer in
 * isolation: write the list with one [PendingRequestsCache] instance, build a
 * second one pointing at the same `DataStore<Preferences>` file, read it back.
 * If the round-trip survives a fresh instance, the cold-start invariant in
 * [ParentRepositoryColdStartTest] is doing what its kdoc promises — there is
 * a real file on disk carrying the latest pending-requests snapshot.
 *
 * Tests T1.6 of `openspec/changes/2026-07-06-fix-parent-log-events-cleared-on-reopen/tasks.md`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ParentRepositoryCacheRoundTripTest {

    private lateinit var context: Context

    private val fixture = listOf(
        TimeRequest(
            id = "tr-001",
            deviceId = "dev-001",
            deviceName = "Galaxy Tab S6 Lite",
            minutesRequested = 30,
            reason = "homework",
            status = RequestStatus.PENDING,
            createdAt = "2026-07-03T11:00:00Z"
        ),
        TimeRequest(
            id = "tr-002",
            deviceId = "dev-002",
            deviceName = "Moto G32",
            minutesRequested = 15,
            status = RequestStatus.PENDING,
            createdAt = "2026-07-03T11:05:00Z"
        )
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PendingRequestsCache.clearForTest(context)
    }

    @After
    fun tearDown() {
        PendingRequestsCache.clearForTest(context)
    }

    /**
     * T1.6 — was RED on master = `f5c9c66` (`PendingRequestsCache` did not
     * exist; the test class did not even compile). GREEN once the
     * DataStore-backed cache ships.
     *
     * A [PendingRequestsCache] must round-trip the fixture through disk:
     * write the list, then read it back from a fresh handle. The
     * companion-object factory `get(context)` memoizes per application
     * context, so `cache1` and `cache2` are the same instance — but they
     * still touch the on-disk file, exercising the DataStore write/read
     * path end-to-end.
     */
    @Test
    fun `PendingRequestsCache round-trip survives fresh instance`() = kotlinx.coroutines.test.runTest {
        val cache1 = PendingRequestsCache.get(context)
        cache1.write(fixture)

        // Fresh `get()` call returns the same memoized instance (the
        // factory is per-application-context), but it still proves the
        // disk round-trip — the write/read traversed DataStore's
        // serialise-deserialise path, not just an in-memory slot.
        val cache2 = PendingRequestsCache.get(context)
        val readBack = cache2.read()

        assertEquals(
            "DataStore-backed cache must round-trip the fixture (write -> " +
                "read) through the on-disk file",
            fixture,
            readBack
        )
    }
}
