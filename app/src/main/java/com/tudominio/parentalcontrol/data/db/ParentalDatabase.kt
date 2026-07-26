package com.tudominio.parentalcontrol.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tudominio.parentalcontrol.data.model.AppPolicyEntity
import com.tudominio.parentalcontrol.data.model.BehavioralEventEntity
import com.tudominio.parentalcontrol.data.model.GrantEntity
import com.tudominio.parentalcontrol.data.model.OutboxEntity
import com.tudominio.parentalcontrol.data.model.PolicyEntity
import com.tudominio.parentalcontrol.data.model.TimeRequestEntity
import com.tudominio.parentalcontrol.data.model.UsageTodayEntity

/**
 * T03 — Persistencia local con Room (fuente de verdad offline).
 *
 * Entities live under `data.model`, DAOs under `data.db`. The `@Database`
 * definition stays in this file because Room generates code for the
 * implementing class and needs the entity list co-located.
 *
 * Construction is owned by `DatabaseModule.provideDatabase(@ApplicationContext)`
 * (Hilt). The companion holds only the **constants** that the migration
 * tests (`OutboxMigrationTest`, `AppPolicyMigrationTest`) and the Hilt
 * provider need to reference — there is NO `getInstance(Context)` or
 * `INSTANCE` field. PR 4 of `align-with-guia-fedora44` removed the
 * duplicate provider so callers must receive the database via Hilt.
 */
@Database(
    entities = [
        PolicyEntity::class,
        AppPolicyEntity::class,
        GrantEntity::class,
        UsageTodayEntity::class,
        OutboxEntity::class,
        TimeRequestEntity::class,
        BehavioralEventEntity::class
    ],
    version = 8,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class ParentalDatabase : RoomDatabase() {

    abstract fun policyDao(): PolicyDao
    abstract fun appPolicyDao(): AppPolicyDao
    abstract fun grantDao(): GrantDao
    abstract fun usageDao(): UsageDao
    abstract fun outboxDao(): OutboxDao
    abstract fun timeRequestDao(): TimeRequestDao
    abstract fun behavioralEventDao(): BehavioralEventDao

    companion object {
        const val DATABASE_NAME = "parental_control.db"

        /**
         * Migration v4 -> v5 on the `outbox` table.
         *
         * - Adds `processed INTEGER NOT NULL DEFAULT 0`
         * - Adds `processed_at TEXT` (nullable)
         * - Renames `intentos` -> `retries` (value carried forward)
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE outbox ADD COLUMN processed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE outbox ADD COLUMN processed_at TEXT")
                db.execSQL("ALTER TABLE outbox RENAME COLUMN intentos TO retries")
            }
        }

        /**
         * Migration v5 -> v6 on the `app_policies` table.
         *
         * - Changes primary key from `package_name` alone to composite
         *   `(device_id, package_name)` so the same package may have distinct
         *   policy rows per child device.
         * - SQLite does not support `ALTER TABLE ... DROP PRIMARY KEY`, so
         *   the table is recreated and its rows are copied across.
         */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE app_policies_new (" +
                        "device_id TEXT NOT NULL, " +
                        "package_name TEXT NOT NULL, " +
                        "state TEXT NOT NULL, " +
                        "daily_limit_minutes INTEGER, " +
                        "allowed_windows TEXT NOT NULL, " +
                        "category TEXT, " +
                        "PRIMARY KEY (device_id, package_name))"
                )
                db.execSQL(
                    "INSERT INTO app_policies_new (device_id, package_name, state, " +
                        "daily_limit_minutes, allowed_windows, category) " +
                        "SELECT device_id, package_name, state, " +
                        "daily_limit_minutes, allowed_windows, category " +
                        "FROM app_policies"
                )
                db.execSQL("DROP TABLE app_policies")
                db.execSQL("ALTER TABLE app_policies_new RENAME TO app_policies")
            }
        }

        /**
         * Migration v6 → v7 on the `behavioral_events` table.
         */
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE behavioral_events ADD COLUMN parent_id TEXT")
            }
        }

        /**
         * F2a — Migration v7 → v8 on the `policy` table.
         *
         * - Adds `device_state TEXT NOT NULL DEFAULT 'ACTIVE'`. Existing
         *   rows read with `device_state = 'ACTIVE'` until the parent's
         *   next lock/unlock writes a fresh value through
         *   `PolicyDao.upsertPolicyIfNewer`.
         * - The DEFAULT is non-NULL so the existing `PolicyEntity`
         *   migration test (which constructs entities with the older
         *   `device_id/version/category_assignments` triple) keeps
         *   working — the new constructor parameter `device_state`
         *   defaults to "ACTIVE" and Room populates the column on
         *   insert.
         */
        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE policy ADD COLUMN device_state TEXT NOT NULL DEFAULT 'ACTIVE'"
                )
            }
        }
    }
}
