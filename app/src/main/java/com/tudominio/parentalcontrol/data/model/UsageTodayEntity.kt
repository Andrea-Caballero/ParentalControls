package com.tudominio.parentalcontrol.data.model

import androidx.room.Entity

@Entity(tableName = "usage_today", primaryKeys = ["package_name", "server_date"])
data class UsageTodayEntity(
    val package_name: String,
    val server_date: String,
    val usage_minutes: Int
)
