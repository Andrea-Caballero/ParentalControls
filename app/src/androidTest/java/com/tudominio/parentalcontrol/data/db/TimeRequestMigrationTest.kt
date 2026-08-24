package com.tudominio.parentalcontrol.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimeRequestMigrationTest {

    @Test
    fun migration_10_to_11_repairs_server_id_default_and_preserves_rows() {
        val helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ParentalDatabase::class.java
        )

        helper.createDatabase(ParentalDatabase.DATABASE_NAME, 10).apply {
            // Reproduce the shipped faulty v10 variant: server_id has no default.
            execSQL("ALTER TABLE time_requests RENAME TO time_requests_old")
            execSQL(
                "CREATE TABLE time_requests (" +
                    "request_id TEXT NOT NULL, device_id TEXT NOT NULL, package_name TEXT, " +
                    "minutes_requested INTEGER NOT NULL, reason TEXT, status TEXT NOT NULL, " +
                    "created_at TEXT NOT NULL, responded_at TEXT, parent_response TEXT, " +
                    "server_id TEXT, PRIMARY KEY(request_id))"
            )
            execSQL(
                "INSERT INTO time_requests SELECT request_id, device_id, package_name, " +
                    "minutes_requested, reason, status, created_at, responded_at, " +
                    "parent_response, server_id FROM time_requests_old"
            )
            execSQL("DROP TABLE time_requests_old")
            execSQL(
                "INSERT INTO time_requests " +
                    "(request_id, device_id, package_name, minutes_requested, reason, status, " +
                    "created_at, responded_at, parent_response, server_id) VALUES " +
                    "('request-a', 'device-a', 'com.example.a', 15, 'first', 'PENDING', " +
                    "'2026-08-12T10:00:00Z', NULL, NULL, NULL)"
            )
            execSQL(
                "INSERT INTO time_requests " +
                    "(request_id, device_id, package_name, minutes_requested, reason, status, " +
                    "created_at, responded_at, parent_response, server_id) VALUES " +
                    "('request-b', 'device-b', NULL, 30, NULL, 'APPROVED', " +
                    "'2026-08-12T11:00:00Z', '2026-08-12T11:05:00Z', 'approved', 'server-b')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            ParentalDatabase.DATABASE_NAME,
            11,
            true,
            ParentalDatabase.MIGRATION_10_11
        )
        try {
            val defaultCursor = db.query("PRAGMA table_info(time_requests)")
            var serverIdDefault: String? = null
            try {
                while (defaultCursor.moveToNext()) {
                    if (defaultCursor.getString(defaultCursor.getColumnIndexOrThrow("name")) == "server_id") {
                        serverIdDefault = defaultCursor.getString(
                            defaultCursor.getColumnIndexOrThrow("dflt_value")
                        )
                    }
                }
            } finally {
                defaultCursor.close()
            }
            assertEquals("NULL", serverIdDefault)

            val rows = db.query(
                "SELECT request_id, device_id, server_id FROM time_requests ORDER BY request_id"
            )
            try {
                assertTrue(rows.moveToFirst())
                assertEquals("request-a", rows.getString(0))
                assertEquals("device-a", rows.getString(1))
                assertTrue(rows.isNull(2))
                assertTrue(rows.moveToNext())
                assertEquals("request-b", rows.getString(0))
                assertEquals("device-b", rows.getString(1))
                assertEquals("server-b", rows.getString(2))
                assertTrue(!rows.moveToNext())
            } finally {
                rows.close()
            }
        } finally {
            db.close()
        }
    }
}
