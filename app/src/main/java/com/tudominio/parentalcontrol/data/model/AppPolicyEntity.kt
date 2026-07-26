package com.tudominio.parentalcontrol.data.model

import androidx.room.Entity

@Entity(tableName = "app_policies", primaryKeys = ["device_id", "package_name"])
data class AppPolicyEntity(
    val device_id: String,
    val package_name: String,
    val state: String,
    val daily_limit_minutes: Int?,
    val allowed_windows: List<WindowEntity>,
    val category: String?
)
