package com.tudominio.parentalcontrol.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

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
    val parent_response: String?
)
