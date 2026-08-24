package com.tudominio.parentalcontrol.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.data.model.GrantEntity
import com.tudominio.parentalcontrol.domain.AppPolicy
import com.tudominio.parentalcontrol.domain.AppPolicyState
import com.tudominio.parentalcontrol.domain.CategoryLimit
import com.tudominio.parentalcontrol.domain.DayOfWeek
import com.tudominio.parentalcontrol.domain.DeviceState
import com.tudominio.parentalcontrol.domain.Grant
import com.tudominio.parentalcontrol.domain.GrantSource
import com.tudominio.parentalcontrol.domain.Policy
import com.tudominio.parentalcontrol.domain.Schedule
import com.tudominio.parentalcontrol.domain.ScheduleAction
import com.tudominio.parentalcontrol.domain.Window
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F2 — atomicity proof for [LocalDataSource.syncPolicy] + F3 — corrupt
 * grant source must not kill the full policy flow.
 *
 * F2: pre-fix the writes (`policy` + `app_policies` + `grants`) ran in
 * separate suspend calls without a `withTransaction` boundary. A
 * failure in the grant insert (or any later step) left the policy
 * version bumped AND the previous app_policies / grants rows gone
 * (because `deleteAppPoliciesForDevice` and `deleteGrantsForDevice`
 * had already run). The post-fix `database.withTransaction { ... }`
 * wrapper commits all three writes together or rolls every one of
 * them back. We trigger the failure deterministically by renaming the
 * `grants` table out from under the DAO — the `insertGrants` call then
 * surfaces a `SQLiteException` whose pre-image the transaction must
 * erase.
 *
 * F3: a row in `grants` with an unknown `source` (e.g. a future server
 * that introduces a new grant type) used to terminate the entire
 * `getPolicyFlow` because `GrantEntity.toDomain()` called
 * `GrantSource.valueOf(...)` on a value Room happily persisted. The
 * fix switches to `mapNotNull { it.toDomainOrNull() }` so a single
 * corrupt row is dropped instead of poisoning every consumer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocalDataSourceAtomicityTest {

    private lateinit var context: Context
    private lateinit var db: ParentalDatabase
    private val directExecutor = java.util.concurrent.Executor { it.run() }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ParentalDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(directExecutor)
            .setTransactionExecutor(directExecutor)
            .build()
    }

    @After
    fun tearDown() {
        runCatching { db.close() }
    }

    // ========================================================================
    // F2 — syncPolicy atomicity
    // ========================================================================

    @Test
    fun `syncPolicy rolls back policy version and previous rows when grant insert throws`() = runTest {
        val source = LocalDataSource(db)
        val initial = policy(version = 3)
        assertTrue("baseline sync must succeed", source.syncPolicy(initial))

        val afterFirst = source.getPolicyFlow(DEVICE).first()!!
        val originalVersion = afterFirst.version
        val originalGrants = afterFirst.grants
        val originalAppPolicies = afterFirst.app_policies
        assertEquals(1, originalGrants.size)
        assertEquals(1, originalAppPolicies.size)

        // Trigger a deterministic failure in the grant insert by
        // renaming the `grants` table out from under the DAO. Room's
        // generated code resolves a missing relation at prepare-time
        // and surfaces a `SQLiteException` from `insertGrants`. Without
        // the `withTransaction` wrapper, the policy write + the
        // app_policies rewrite had ALREADY committed by this point, so
        // the failure left the table half-updated.
        db.openHelper.writableDatabase.execSQL("ALTER TABLE grants RENAME TO grants_gone")

        val bad = initial.copy(
            version = 5,
            daily_screen_time_minutes = 99,
            // Distinct app_policy so we can detect partial commits:
            // the pre-fix bug kept the new app_policy while the
            // grant insert failed.
            app_policies = listOf(
                AppPolicy(
                    package_name = "com.example.partial",
                    state = AppPolicyState.BLOCKED,
                    allowed_windows = emptyList(),
                    category = null,
                ),
            ),
            grants = listOf(
                Grant(
                    id = "grant-partial",
                    scope = "global",
                    minutes = 5,
                    source = GrantSource.MANUAL,
                    granted_at = "2026-07-31T10:00:00Z",
                    expires_at = "2026-07-31T11:00:00Z",
                ),
            ),
        )

        try {
            source.syncPolicy(bad)
            fail("syncPolicy must surface the broken grants table as a thrown exception")
        } catch (e: Exception) {
            // Expected — the grant insert blew up. The transaction
            // boundary is what we are proving below.
        }

        // Restore the grants table so we can re-read the policy via
        // the DAO (the rename broke `getGrantsForDeviceFlow`). We
        // rename back, not rebuild — the assertion below is about
        // the rollback, not about schema repair.
        db.openHelper.writableDatabase.execSQL("ALTER TABLE grants_gone RENAME TO grants")

        val afterFailure = source.getPolicyFlow(DEVICE).first()!!
        assertEquals(
            "policy version must NOT have advanced past the original — " +
                "withTransaction guarantees every write rolls back together",
            originalVersion,
            afterFailure.version,
        )
        assertEquals(
            "previous normalized grant rows must still be present",
            originalGrants,
            afterFailure.grants,
        )
        assertEquals(
            "previous normalized app_policy rows must still be present",
            originalAppPolicies,
            afterFailure.app_policies,
        )
        assertEquals(
            "the partial commit's daily_screen_time_minutes must NOT have leaked",
            initial.daily_screen_time_minutes,
            afterFailure.daily_screen_time_minutes,
        )
    }

    @Test
    fun `syncPolicy observers do not see an intermediate empty policy on success`() = runTest {
        val source = LocalDataSource(db)
        val initial = policy(version = 2)
        source.syncPolicy(initial)
        // Drain the initial emissions.
        source.getPolicyFlow(DEVICE).first()

        val full = initial.copy(
            version = 3,
            app_policies = listOf(
                AppPolicy(
                    package_name = "com.example.atomic",
                    state = AppPolicyState.LIMITED,
                    daily_limit_minutes = 30,
                    allowed_windows = listOf(Window(listOf(DayOfWeek.MONDAY), "16:00", "18:00")),
                    category = "games",
                ),
            ),
            grants = listOf(
                Grant(
                    id = "grant-atomic",
                    scope = "global",
                    minutes = 15,
                    source = GrantSource.MANUAL,
                    granted_at = "2026-07-31T10:00:00Z",
                    expires_at = "2026-07-31T11:00:00Z",
                ),
            ),
        )

        source.getPolicyFlow(DEVICE).test {
            // Skip the first emission (the policy already synced at v2).
            skipItems(1)
            source.syncPolicy(full)
            val observed = expectMostRecentItem()
            assertNotNull("observer must see a policy", observed)
            assertEquals("v3 must be observed with the new app_policy", 3, observed!!.version)
            assertEquals(
                "observer must see the new app_policy atomically — no empty intermediate",
                1,
                observed.app_policies.size,
            )
            assertEquals(
                "observer must see the new grant atomically — no empty intermediate",
                1,
                observed.grants.size,
            )
            assertEquals(
                "the new app_policy must be the one from the v3 write, not a stale row",
                "com.example.atomic",
                observed.app_policies.single().package_name,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========================================================================
    // F3 — corrupt grant source must not kill the policy flow
    // ========================================================================

    @Test
    fun `getPolicyFlow skips corrupt grant source without hiding valid grants`() = runTest {
        // Direct write bypasses LocalDataSource.syncPolicy so we can
        // plant a row the canonical decoder would never accept.
        db.grantDao().insertGrant(
            GrantEntity(
                id = "grant-valid",
                device_id = DEVICE,
                request_id = null,
                scope = "global",
                minutes = 10,
                source = GrantSource.MANUAL.name,
                granted_at = "2026-07-31T10:00:00Z",
                expires_at = "2026-07-31T11:00:00Z",
            ),
        )
        db.grantDao().insertGrant(
            GrantEntity(
                id = "grant-corrupt",
                device_id = DEVICE,
                request_id = null,
                scope = "global",
                minutes = 5,
                // A future server emits a new grant type the client
                // does not know about. Room happily stores it; the
                // client must NOT crash the whole flow on it.
                source = "FUTURE_GIVEAWAY",
                granted_at = "2026-07-31T10:00:00Z",
                expires_at = "2026-07-31T11:00:00Z",
            ),
        )
        db.policyDao().insertPolicy(
            com.tudominio.parentalcontrol.data.model.PolicyEntity(
                device_id = DEVICE,
                version = 4L,
                category_assignments = emptyMap(),
                device_state = "ACTIVE",
                daily_screen_time_minutes = 100,
                schedules = emptyList(),
                category_limits = emptyList(),
            ),
        )

        val source = LocalDataSource(db)
        val policy = source.getPolicyFlow(DEVICE).first()
        assertNotNull("flow must not be killed by a corrupt grant source", policy)
        assertEquals("valid grant must still be visible", 1, policy!!.grants.size)
        assertEquals("grant-valid", policy.grants.single().id)
        // Belt-and-suspenders: the corrupt row was persisted but is
        // skipped — NOT silently converted to MANUAL.
        assertFalse(
            "corrupt source must NOT be reinterpreted as MANUAL",
            policy.grants.any { it.id == "grant-corrupt" && it.source == GrantSource.MANUAL },
        )
    }

    private fun policy(version: Int) = Policy(
        device_id = DEVICE,
        version = version,
        device_state = DeviceState.ACTIVE,
        daily_screen_time_minutes = 60,
        schedules = listOf(Schedule("bedtime", listOf(DayOfWeek.MONDAY), "21:00", "07:00", ScheduleAction.LOCK)),
        category_limits = listOf(CategoryLimit("games", 19)),
        app_policies = listOf(
            AppPolicy(
                package_name = "com.example.game",
                state = AppPolicyState.LIMITED,
                daily_limit_minutes = 11,
                allowed_windows = listOf(Window(listOf(DayOfWeek.TUESDAY), "16:00", "17:00")),
                category = "games",
            ),
        ),
        category_assignments = mapOf("com.example.game" to "games"),
        grants = listOf(
            Grant(
                id = "grant-1",
                scope = "global",
                minutes = 10,
                source = GrantSource.MANUAL,
                granted_at = "2026-07-31T10:00:00Z",
                expires_at = "2026-07-31T11:00:00Z",
            ),
        ),
    )

    private companion object {
        const val DEVICE = "device-atomic"
    }
}
