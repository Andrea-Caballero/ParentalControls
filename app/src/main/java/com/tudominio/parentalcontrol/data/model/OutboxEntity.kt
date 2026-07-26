package com.tudominio.parentalcontrol.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val id: UUID = UUID.randomUUID(),
    val tipo: String,
    val payload_json: String,
    val dedup_key: String?,
    val retries: Int = 0,
    val created_at: String,
    val server_date: String,
    val processed: Boolean = false,
    val processed_at: String? = null
)
