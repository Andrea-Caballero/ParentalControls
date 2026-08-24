package com.tudominio.parentalcontrol.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.tudominio.parentalcontrol.domain.canonicalGrantTimestamp

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
) {
    init {
        canonicalGrantTimestamp(granted_at)
        canonicalGrantTimestamp(expires_at)
    }

    fun canonicalized(): GrantEntity = copy(
        granted_at = canonicalGrantTimestamp(granted_at),
        expires_at = canonicalGrantTimestamp(expires_at),
    )
}
