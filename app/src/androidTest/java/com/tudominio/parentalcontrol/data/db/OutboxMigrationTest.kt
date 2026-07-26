package com.tudominio.parentalcontrol.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the Room v4 -> v5 migration on the `outbox` table.
 *
 * Pre-migration schema (v4):
 *   - `intentos INTEGER NOT NULL`
 *   - no `processed`, no `processed_at`
 *
 * Post-migration schema (v5):
 *   - `retries INTEGER NOT NULL` (renamed from `intentos`, value carried forward)
 *   - `processed INTEGER NOT NULL DEFAULT 0`
 *   - `processed_at TEXT` (nullable)
 */
@RunWith(AndroidJUnit4::class)
class OutboxMigrationTest {

    private val dbName = ParentalDatabase.DATABASE_NAME

    @Test
    fun migration_4_to_5_renames_intentos_and_adds_processed_columns() {
        val helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ParentalDatabase::class.java
        )

        helper.createDatabase(dbName, 4).apply {
            // Insert a v4 row with intentos=3
            execSQL(
                "INSERT INTO outbox (id, tipo, payload_json, dedup_key, intentos, created_at, server_date) " +
                    "VALUES ('00000000-0000-0000-0000-000000000abc', 'TIME_REQUEST', '{}', " +
                    "'dedup-1', 3, '2026-06-04T12:00:00Z', '2026-06-04')"
            )
            close()
        }

        // Open the database with the v5 schema, running the migration
        val db = helper.runMigrationsAndValidate(dbName, 5, true, ParentalDatabase.MIGRATION_4_5)

        // Verify the migration outcome
        val cursor = db.query(
            "SELECT retries, processed, processed_at FROM outbox WHERE id = ?",
            arrayOf<Any>("00000000-0000-0000-0000-000000000abc")
        )
        try {
            assertTrue("Expected the migrated row to exist", cursor.moveToFirst())
            val retriesIdx = cursor.getColumnIndexOrThrow("retries")
            val processedIdx = cursor.getColumnIndexOrThrow("processed")
            val processedAtIdx = cursor.getColumnIndexOrThrow("processed_at")
            assertNotNull("retries column must be present after migration", retriesIdx >= 0)
            assertNotNull("processed column must be present after migration", processedIdx >= 0)
            assertNotNull("processed_at column must be present after migration", processedAtIdx >= 0)
            // The value carried from `intentos` must be present in `retries`.
            assertEquals(3, cursor.getInt(retriesIdx))
            // `processed` defaults to 0 (FALSE in Kotlin terms).
            assertEquals(0, cursor.getInt(processedIdx))
            // `processed_at` defaults to NULL.
            assertNull(cursor.getString(processedAtIdx))
        } finally {
            cursor.close()
        }

        db.close()
    }
}
