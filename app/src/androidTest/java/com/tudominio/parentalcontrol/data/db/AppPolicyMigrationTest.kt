package com.tudominio.parentalcontrol.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the Room v5 -> v6 migration on the `app_policies` table.
 *
 * Pre-migration schema (v5):
 *   - `package_name TEXT NOT NULL PRIMARY KEY`
 *   - `device_id TEXT NOT NULL`
 *   - `state`, `daily_limit_minutes`, `allowed_windows`, `category` as before
 *
 * Post-migration schema (v6):
 *   - `device_id TEXT NOT NULL`
 *   - `package_name TEXT NOT NULL`
 *   - composite PRIMARY KEY (`device_id`, `package_name`)
 *
 * Critical regression coverage: the spec requires per-device isolation. The
 * pre-migration PK (package_name alone) allowed blocking an app for child A
 * to overwrite child B's row for the same package. After this migration the
 * same package may have distinct rows per device.
 */
@RunWith(AndroidJUnit4::class)
class AppPolicyMigrationTest {

    private val dbName = ParentalDatabase.DATABASE_NAME

    @Test
    fun migration_5_to_6_creates_composite_pk_and_preserves_data() {
        val helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ParentalDatabase::class.java
        )

        // Seed v5 with two rows for the same package across two devices.
        // If the v5 PK were preserved post-migration, only one row would
        // survive the INSERT OR REPLACE on (package_name). The fix
        // guarantees both rows survive.
        helper.createDatabase(dbName, 5).apply {
            execSQL(
                "INSERT INTO app_policies (package_name, device_id, state, " +
                    "daily_limit_minutes, allowed_windows, category) " +
                    "VALUES ('com.x', 'dev-A', 'BLOCKED', NULL, '[]', NULL)"
            )
            execSQL(
                "INSERT INTO app_policies (package_name, device_id, state, " +
                    "daily_limit_minutes, allowed_windows, category) " +
                    "VALUES ('com.x', 'dev-B', 'ALLOWED', NULL, '[]', NULL)"
            )
            close()
        }

        // Run the migration to v6.
        val db = helper.runMigrationsAndValidate(dbName, 6, true, ParentalDatabase.MIGRATION_5_6)

        try {
            // Both rows must survive the migration.
            val cursor = db.query(
                "SELECT device_id, package_name, state FROM app_policies ORDER BY device_id",
                arrayOf<Any>()
            )
            val rows = mutableListOf<Triple<String, String, String>>()
            try {
                while (cursor.moveToNext()) {
                    rows.add(
                        Triple(
                            cursor.getString(cursor.getColumnIndexOrThrow("device_id")),
                            cursor.getString(cursor.getColumnIndexOrThrow("package_name")),
                            cursor.getString(cursor.getColumnIndexOrThrow("state"))
                        )
                    )
                }
            } finally {
                cursor.close()
            }

            assertEquals(
                "Expected 2 rows after migration; composite PK must preserve both devices",
                2,
                rows.size
            )
            assertTrue(
                "Expected dev-A's row to survive migration",
                rows.any { it.first == "dev-A" && it.second == "com.x" && it.third == "BLOCKED" }
            )
            assertTrue(
                "Expected dev-B's row to survive migration",
                rows.any { it.first == "dev-B" && it.second == "com.x" && it.third == "ALLOWED" }
            )

            // Composite-PK regression check: inserting a new (dev-A, com.x)
            // row MUST NOT replace dev-B's row, because the PK is now
            // (device_id, package_name) and these are different rows.
            db.execSQL(
                "INSERT OR REPLACE INTO app_policies (device_id, package_name, " +
                    "state, daily_limit_minutes, allowed_windows, category) " +
                    "VALUES ('dev-A', 'com.x', 'LIMITED', 30, '[]', 'games')"
            )

            val verifyCursor = db.query(
                "SELECT device_id, state FROM app_policies WHERE package_name = 'com.x' ORDER BY device_id",
                arrayOf<Any>()
            )
            val states = mutableListOf<Pair<String, String>>()
            try {
                while (verifyCursor.moveToNext()) {
                    states.add(
                        verifyCursor.getString(verifyCursor.getColumnIndexOrThrow("device_id")) to
                            verifyCursor.getString(verifyCursor.getColumnIndexOrThrow("state"))
                    )
                }
            } finally {
                verifyCursor.close()
            }

            assertEquals(
                "Composite PK must keep dev-A and dev-B rows independent",
                2,
                states.size
            )
            assertTrue(
                "dev-A row must reflect the new upsert (LIMITED)",
                states.any { it.first == "dev-A" && it.second == "LIMITED" }
            )
            assertTrue(
                "dev-B row must remain untouched (ALLOWED) after dev-A upsert",
                states.any { it.first == "dev-B" && it.second == "ALLOWED" }
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun migration_5_to_6_preserves_daily_limit_and_category_columns() {
        val helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ParentalDatabase::class.java
        )

        helper.createDatabase(dbName, 5).apply {
            execSQL(
                "INSERT INTO app_policies (package_name, device_id, state, " +
                    "daily_limit_minutes, allowed_windows, category) " +
                    "VALUES ('com.y', 'dev-A', 'LIMITED', 45, " +
                    "'[{\"days\":[\"MONDAY\"],\"from\":\"16:00\",\"to\":\"18:00\"}]', 'games')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 6, true, ParentalDatabase.MIGRATION_5_6)
        try {
            val cursor = db.query(
                "SELECT state, daily_limit_minutes, category FROM app_policies WHERE package_name = 'com.y'",
                arrayOf<Any>()
            )
            try {
                assertTrue("Expected migrated row to exist", cursor.moveToFirst())
                val state = cursor.getString(cursor.getColumnIndexOrThrow("state"))
                val limit = cursor.getInt(cursor.getColumnIndexOrThrow("daily_limit_minutes"))
                val category = cursor.getString(cursor.getColumnIndexOrThrow("category"))
                assertNotNull(state)
                assertEquals("LIMITED", state)
                assertEquals(45, limit)
                assertEquals("games", category)
            } finally {
                cursor.close()
            }
        } finally {
            db.close()
        }
    }
}
