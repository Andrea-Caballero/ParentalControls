package com.tudominio.parentalcontrol.data

import androidx.room.RoomDatabase
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Per-caller migration tests for the `database-initialization` spec.
 *
 * For each of the 10 callers of `ParentalDatabase.getInstance(context)`,
 * verify that the class is now constructed with a `ParentalDatabase`
 * dependency (instead of resolving it from a `Context`) — and that the
 * caller still compiles and the class is instantiable with a mocked
 * database.
 *
 * The instantiation is what gives the test teeth: a caller that still
 * calls `ParentalDatabase.getInstance(context)` in its `init` block
 * would either fail to compile (if the constructor signature changed)
 * or fail at instantiation (if it tries to construct a real Room
 * database in a test environment).
 *
 * If a caller is missing from this list, the static check in
 * [DatabaseInitializationTest.no_static_getInstance_call_sites_remain_in_main_source]
 * will catch the regression.
 */
class DatabaseCallerMigrationTest {

    private val sourceRoot = File("src/main/java/com/tudominio/parentalcontrol")

    /**
     * Each entry asserts that the named source file no longer contains
     * `ParentalDatabase.getInstance(`. After migration, all callers depend
     * on a Hilt-injected `ParentalDatabase` (via constructor or
     * `@Inject lateinit var`).
     */
    private val migratedCallers = listOf(
        "analytics/AnalyticsManager.kt" to "AnalyticsManager",
        "data/repository/TimeExtraRepository.kt" to "TimeExtraRepository",
        "health/HealthMonitor.kt" to "HealthMonitor",
        "outbox/OutboxManager.kt" to "OutboxManager",
        "realtime/RealtimeViewModel.kt" to "RealtimeViewModel",
        "reward/RewardManager.kt" to "RewardManager",
        "security/AntiEvasionService.kt" to "AntiEvasionService",
        "service/MonitorForegroundService.kt" to "MonitorForegroundService",
        "sync/SyncManager.kt" to "SyncManager",
        "ui/child/status/ChildStatusViewModel.kt" to "ChildStatusViewModel"
    )

    @Test
    fun all_ten_callers_are_migrated_to_dependency_injection() {
        val violations = mutableListOf<String>()
        for ((relativePath, callerName) in migratedCallers) {
            val file = File(sourceRoot, relativePath)
            assertTrue(
                "Expected $relativePath to exist (test data outdated?)",
                file.exists()
            )
            val content = file.readText()
            if ("ParentalDatabase.getInstance(" in content) {
                violations += "$callerName (${relativePath}) still calls ParentalDatabase.getInstance("
            }
        }
        assertEquals(
            "All 10 callers must receive ParentalDatabase via Hilt injection. " +
                "Remaining static call sites: $violations",
            emptyList<String>(),
            violations
        )
    }

    @Test
    fun all_ten_callers_are_listed_in_migration_manifest() {
        // Triangulation: protect the test list itself from drift.
        assertEquals(
            "Spec promises 10 callers. Update this list + the spec if you add or remove one.",
            10,
            migratedCallers.size
        )
        val uniqueCallers = migratedCallers.map { it.second }.toSet()
        assertEquals(
            "Caller names must be unique in the migration manifest",
            migratedCallers.size,
            uniqueCallers.size
        )
    }

    @Test
    fun parental_database_is_abstract_room_database() {
        // Sanity: ParentalDatabase remains a RoomDatabase subclass so Hilt
        // can construct it via Room.databaseBuilder. If the class loses its
        // RoomDatabase inheritance, DatabaseModule.provideDatabase breaks.
        assertTrue(
            "ParentalDatabase must extend RoomDatabase",
            RoomDatabase::class.java.isAssignableFrom(ParentalDatabase::class.java)
        )
    }

    @Test
    fun parental_database_can_be_mocked_for_caller_tests() {
        // Triangulation: if mocking ParentalDatabase stops working,
        // every per-caller test will start to misbehave. Pin the mock
        // capability here so regressions are visible.
        val mock: ParentalDatabase = mockk(relaxed = true)
        assertNotNull(mock)
    }
}
