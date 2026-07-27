package com.tudominio.parentalcontrol.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tudominio.parentalcontrol.data.model.GrantEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests proving that every grants read in [GrantDao] is scoped to
 * a single [deviceId]. This is the Android-backend device-isolation
 * remediation slice — the cross-device grant-mixing bug that used to
 * leak device A's `extra_time` / `reward` grants into device B's
 * balance / countdown / active-list views.
 *
 * Each test seeds grants for two distinct devices and asserts the
 * deviceId-scoped queries return ONLY the rows that belong to the
 * requested device, regardless of how many rows the other device
 * happens to have. The unscoped `getGrantsForScope(scope)` overload
 * that used to live on the DAO is no longer exposed — it was the
 * root cause of the bug and removing it from the surface forces every
 * caller to make device isolation explicit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GrantDaoIsolationTest {

    private lateinit var context: Context
    private lateinit var db: ParentalDatabase
    private lateinit var grantDao: GrantDao

    private val farFuture = "2999-01-01T00:00:00Z"
    private val farPast = "2000-01-01T00:00:00Z"
    private val midFuture = "2999-06-01T00:00:00Z"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ParentalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        grantDao = db.grantDao()
    }

    @After
    fun tearDown() {
        runCatching { db.close() }
    }

    private fun grant(
        id: String,
        deviceId: String,
        scope: String,
        minutes: Int,
        expiresAt: String = farFuture
    ): GrantEntity = GrantEntity(
        id = id,
        device_id = deviceId,
        request_id = null,
        scope = scope,
        minutes = minutes,
        source = scope,
        granted_at = "2026-07-27T10:00:00Z",
        expires_at = expiresAt
    )

    private fun seedDevice(
        deviceId: String,
        scope: String,
        vararg grants: GrantEntity
    ) {
        // default: one extra_time + one reward grant with the given id
        val defaults = listOf(
            grant(id = "${deviceId}_${scope}_a", deviceId = deviceId, scope = scope, minutes = 15),
            grant(id = "${deviceId}_${scope}_b", deviceId = deviceId, scope = scope, minutes = 25)
        )
        runBlocking {
            grantDao.insertGrants(defaults + grants.toList())
        }
    }

    // ===== Goal 1: device isolation for scope-filtered reads =====

    /**
     * `getGrantsForScopeFlow(deviceId, scope)` MUST return only the
     * rows for the requested device. The pre-fix DAO exposed an
     * unscoped `getGrantsForScope(scope)` overload that returned every
     * paired device's rows — that path leaked device A's grants into
     * device B's reward balance / extra-time countdown.
     */
    @Test
    fun getGrantsForScopeFlow_isolates_rows_by_device_id() = runBlocking {
        seedDevice("dev-A", "extra_time")
        seedDevice("dev-B", "extra_time")
        seedDevice("dev-A", "reward")
        seedDevice("dev-B", "reward")

        val aExtra = grantDao.getGrantsForScopeFlow("dev-A", "extra_time").first()
        val bExtra = grantDao.getGrantsForScopeFlow("dev-B", "extra_time").first()
        val aReward = grantDao.getGrantsForScopeFlow("dev-A", "reward").first()
        val bReward = grantDao.getGrantsForScopeFlow("dev-B", "reward").first()

        assertEquals("dev-A extra_time must contain only dev-A rows", 2, aExtra.size)
        assertEquals("dev-B extra_time must contain only dev-B rows", 2, bExtra.size)
        assertEquals("dev-A reward must contain only dev-A rows", 2, aReward.size)
        assertEquals("dev-B reward must contain only dev-B rows", 2, bReward.size)

        assertTrue(
            "dev-A extra_time must not leak dev-B rows",
            aExtra.all { it.device_id == "dev-A" }
        )
        assertTrue(
            "dev-B extra_time must not leak dev-A rows",
            bExtra.all { it.device_id == "dev-B" }
        )
        assertTrue(
            "dev-A reward must not leak dev-B rows",
            aReward.all { it.device_id == "dev-A" }
        )
        assertTrue(
            "dev-B reward must not leak dev-A rows",
            bReward.all { it.device_id == "dev-B" }
        )

        // Sanity: the rows are actually different per device.
        // 2 grants for dev-A + 2 grants for dev-B = 4 unique ids.
        // If the isolation failed and A and B shared ids, this would
        // collapse to 2 instead of 4.
        assertEquals(4, (aExtra + bExtra).toSet().size)
        assertEquals(4, (aReward + bReward).toSet().size)
    }

    /**
     * Same contract for the unfiltered (active + expired) deviceId
     * query.
     */
    @Test
    fun getGrantsForDeviceFlow_isolates_rows_by_device_id() = runBlocking {
        seedDevice("dev-A", "extra_time")
        seedDevice("dev-B", "extra_time")

        val a = grantDao.getGrantsForDeviceFlow("dev-A").first()
        val b = grantDao.getGrantsForDeviceFlow("dev-B").first()

        assertEquals("dev-A must see only its own grants", 2, a.size)
        assertEquals("dev-B must see only its own grants", 2, b.size)
        assertTrue(a.all { it.device_id == "dev-A" })
        assertTrue(b.all { it.device_id == "dev-B" })
    }

    // ===== Goal 1: device isolation for active-filtered reads =====

    /**
     * `getActiveGrantsForScopeOnce(deviceId, scope, now)` MUST push the
     * deviceId filter into SQL — used by the reward balance and
     * extra-time totals. The pre-fix code used `getGrantsForScope` +
     * an in-memory `filter { it.expires_at > now }`, which preserved
     * the cross-device leak while only fixing the active/expiry half.
     */
    @Test
    fun getActiveGrantsForScopeOnce_isolates_by_device_id_and_expiry() = runBlocking {
        // dev-A: 1 active extra_time, 1 expired extra_time
        grantDao.insertGrant(
            grant("a_active", "dev-A", "extra_time", 10, expiresAt = farFuture)
        )
        grantDao.insertGrant(
            grant("a_expired", "dev-A", "extra_time", 99, expiresAt = farPast)
        )
        // dev-B: 1 active extra_time that MUST NOT leak into dev-A's balance
        grantDao.insertGrant(
            grant("b_active", "dev-B", "extra_time", 50, expiresAt = midFuture)
        )

        val now = "2026-07-27T10:00:00Z"
        val aActive = grantDao.getActiveGrantsForScopeOnce("dev-A", "extra_time", now)
        val bActive = grantDao.getActiveGrantsForScopeOnce("dev-B", "extra_time", now)

        // dev-A: only its own active grant survives
        assertEquals("dev-A must see exactly one active extra_time grant", 1, aActive.size)
        assertEquals("dev-A's active grant must be the one for dev-A", "a_active", aActive[0].id)
        assertEquals("dev-A's active grant must not leak dev-B's minutes", 10, aActive[0].minutes)

        // dev-B: only its own active grant survives
        assertEquals("dev-B must see exactly one active extra_time grant", 1, bActive.size)
        assertEquals("dev-B's active grant must be the one for dev-B", "b_active", bActive[0].id)
    }

    /**
     * The Flow variant of the active-grants query must keep emitting
     * the deviceId-scoped set on every table change — proving the SQL
     * filter is stable across the reactive lifecycle (no in-memory
     * post-filter that could re-introduce cross-device leakage).
     */
    @Test
    fun getActiveGrantsForScopeFlow_emits_device_scoped_rows_after_insert() = runBlocking {
        // Pre-populate dev-B so any leak would show up immediately.
        grantDao.insertGrant(
            grant("b_seed", "dev-B", "extra_time", 50, expiresAt = midFuture)
        )

        val now = "2026-07-27T10:00:00Z"
        val flow = grantDao.getActiveGrantsForScopeFlow("dev-A", "extra_time", now)

        // First emission is empty for dev-A (nothing seeded yet).
        val initial = flow.first()
        assertTrue("pre-condition: dev-A has no grants yet", initial.isEmpty())

        // Now seed an active grant for dev-A; the Flow must emit a
        // list that contains ONLY dev-A's row, never dev-B's.
        grantDao.insertGrant(
            grant("a_only", "dev-A", "extra_time", 15, expiresAt = midFuture)
        )
        val afterA = flow.first()
        assertEquals("dev-A's Flow must surface exactly one row", 1, afterA.size)
        assertEquals("dev-A's Flow must surface the dev-A row", "a_only", afterA[0].id)

        // Seed another row for dev-B after-the-fact; the dev-A Flow
        // must NOT pick it up (regression guard for the historical
        // bug where new writes to the table could bleed into other
        // devices' reactive streams via a missing WHERE clause).
        grantDao.insertGrant(
            grant("b_after", "dev-B", "extra_time", 99, expiresAt = midFuture)
        )
        val afterB = flow.first()
        assertEquals(
            "dev-A's Flow must NOT include dev-B rows inserted after subscription",
            1,
            afterB.size
        )
        assertEquals("a_only", afterB[0].id)
    }

    /**
     * `getActiveGrantsFlow(deviceId, now)` (no scope) must also isolate
     * by device_id — covers the child-home-screen grants-sum path
     * observed by `ChildStatusViewModel`.
     */
    @Test
    fun getActiveGrantsFlow_isolates_by_device_id() = runBlocking {
        grantDao.insertGrant(grant("a", "dev-A", "extra_time", 15, expiresAt = midFuture))
        grantDao.insertGrant(grant("b_extra", "dev-B", "extra_time", 30, expiresAt = midFuture))
        grantDao.insertGrant(grant("b_reward", "dev-B", "reward", 40, expiresAt = midFuture))

        val now = "2026-07-27T10:00:00Z"
        val aActive = grantDao.getActiveGrantsFlow("dev-A", now).first()
        val bActive = grantDao.getActiveGrantsFlow("dev-B", now).first()

        assertEquals("dev-A must see exactly one active grant across all scopes", 1, aActive.size)
        assertEquals("a", aActive[0].id)
        assertEquals("dev-B must see exactly two active grants across all scopes", 2, bActive.size)
        assertTrue("dev-B active set must not contain dev-A rows", bActive.all { it.device_id == "dev-B" })
    }

    // ===== Goal 1: device-isolated deletion =====

    /**
     * `deleteGrantsForDevice(deviceId)` must remove ONLY that device's
     * grants. A pre-fix regression that filtered on `scope` instead of
     * `device_id` could wipe the entire grant table when called from
     * the un-pair path; the SQL below is the contract that prevents
     * that.
     */
    @Test
    fun deleteGrantsForDevice_does_not_touch_other_devices() = runBlocking {
        seedDevice("dev-A", "extra_time")
        seedDevice("dev-B", "extra_time")

        grantDao.deleteGrantsForDevice("dev-A")

        val a = grantDao.getGrantsForDeviceFlow("dev-A").first()
        val b = grantDao.getGrantsForDeviceFlow("dev-B").first()
        assertTrue("dev-A grants must be empty after delete", a.isEmpty())
        assertEquals("dev-B grants must be untouched by dev-A delete", 2, b.size)
        assertTrue("dev-B grants must still belong to dev-B", b.all { it.device_id == "dev-B" })
    }

    // ===== Goal 1: cross-scope isolation =====

    /**
     * Scope filtering must remain independent of deviceId filtering —
     * a grant with `scope = 'reward'` must NOT appear in an
     * `extra_time` query, even for the same device. Without this guard
     * the old `getGrantsForScope` + post-filter pattern in
     * `RewardManager` and `TimeExtraRepository` would mix reward
     * grants into the extra-time totals (the original bug, just on a
     * single device).
     */
    @Test
    fun getGrantsForScopeFlow_isolates_by_scope_within_a_device() = runBlocking {
        seedDevice("dev-A", "extra_time")
        seedDevice("dev-A", "reward")

        val aExtra = grantDao.getGrantsForScopeFlow("dev-A", "extra_time").first()
        val aReward = grantDao.getGrantsForScopeFlow("dev-A", "reward").first()

        assertEquals("dev-A extra_time must contain 2 extra_time rows", 2, aExtra.size)
        assertTrue(aExtra.all { it.scope == "extra_time" })
        assertEquals("dev-A reward must contain 2 reward rows", 2, aReward.size)
        assertTrue(aReward.all { it.scope == "reward" })
    }
}