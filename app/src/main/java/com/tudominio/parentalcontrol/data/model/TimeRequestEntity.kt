package com.tudominio.parentalcontrol.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One `time_requests` row.
 *
 * `request_id` is the client-generated id and remains the primary key —
 * it's the stable identity the UI keys off (Solicitudes tabs, debug
 * logs, throttle bookkeeping). `server_id` is the id Supabase assigned
 * to the row on insert, populated by [com.tudominio.parentalcontrol.sync.SyncManager.sendOutboxItem]
 * when the `return=representation` response carries a different id.
 *
 * `pullApprovedRequests` looks up by `server_id` first, then by
 * `request_id` (for rows the server identified with the client's id).
 * Splitting the two columns is non-destructive: the existing
 * reconciliation that renamed `request_id` in place broke
 * `GrantEntity.request_id` soft-FK consistency and raced with the
 * parent's approval arriving before the rename completed. New column
 * in v9.
 */
@Entity(tableName = "time_requests")
data class TimeRequestEntity(
    @PrimaryKey val request_id: String,
    val device_id: String,
    val package_name: String?,
    val minutes_requested: Int,
    val reason: String?,
    val status: String,
    val created_at: String,
    val responded_at: String?,
    val parent_response: String?,
    @ColumnInfo(defaultValue = "NULL")
    val server_id: String? = null
)
