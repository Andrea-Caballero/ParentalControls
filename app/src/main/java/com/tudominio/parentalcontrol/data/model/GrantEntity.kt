package com.tudominio.parentalcontrol.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "grants")
data class GrantEntity(
    @PrimaryKey val id: String,
    val device_id: String,
    val request_id: String?,
    val scope: String,
    val minutes: Int,
    val source: String,
    val granted_at: String,
    val expires_at: String
)
