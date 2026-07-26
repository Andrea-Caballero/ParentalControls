package com.tudominio.parentalcontrol.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "behavioral_events")
data class BehavioralEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val event_type: String,
    val event_version: Int = 1,
    val device_id: String,
    val client_ts: String,
    val props: String,
    val synced: Boolean = false,
    val created_at: String,
    /**
     * Owning parent's UUID (Supabase `auth.uid()`). NULL on rows written
     * BEFORE the v6→v7 migration (the migration adds the column nullable;
     * the lazy-backfill strategy from `proposal.md` open question #3
     * resolves existing rows at read time, not via SQL).
     *
     * The column is added by [com.tudominio.parentalcontrol.data.db.ParentalDatabase.MIGRATION_6_7]
     * (change A of `feat-parent-behavioral-event-log`).
     */
    @ColumnInfo(name = "parent_id") val parentId: String? = null
)
