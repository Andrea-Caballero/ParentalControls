package com.tudominio.parentalcontrol.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * F2a — `device_state` (LOCKED / ACTIVE) persisted here so the
 * [com.tudominio.parentalcontrol.enforcement.EnforcementController]
 * observer picks up the parent's lock on the next emit. The column
 * defaults to "ACTIVE" for installs that predate the v7 → v8
 * migration (the [com.tudominio.parentalcontrol.data.db.ParentalDatabase.MIGRATION_7_8]
 * backfills the value via `DEFAULT 'ACTIVE'`).
 */
@Entity(tableName = "policy")
data class PolicyEntity(
    @PrimaryKey val device_id: String,
    val version: Long,
    val category_assignments: Map<String, String>,
    val device_state: String = "ACTIVE"
)
