package com.tudominio.parentalcontrol.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PolicyMigrationTest {
    @Test
    fun migration_9_to_10_preserves_existing_columns_and_defaults_new_fields() {
        val helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ParentalDatabase::class.java,
        )
        helper.createDatabase(DB, 9).apply {
            execSQL(
                "INSERT INTO policy (device_id, version, category_assignments, device_state) " +
                    "VALUES ('dev-old', 4, '{\"pkg\":\"games\"}', 'LOCKED')",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 10, true, ParentalDatabase.MIGRATION_9_10)
        db.query("SELECT * FROM policy WHERE device_id = 'dev-old'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(4L, cursor.getLong(cursor.getColumnIndexOrThrow("version")))
            assertEquals("{\"pkg\":\"games\"}", cursor.getString(cursor.getColumnIndexOrThrow("category_assignments")))
            assertEquals("LOCKED", cursor.getString(cursor.getColumnIndexOrThrow("device_state")))
            assertEquals(120, cursor.getInt(cursor.getColumnIndexOrThrow("daily_screen_time_minutes")))
            assertEquals("[]", cursor.getString(cursor.getColumnIndexOrThrow("schedules")))
            assertEquals("[]", cursor.getString(cursor.getColumnIndexOrThrow("category_limits")))
        }
        db.close()
    }

    private companion object {
        const val DB = "policy-migration-test"
    }
}
