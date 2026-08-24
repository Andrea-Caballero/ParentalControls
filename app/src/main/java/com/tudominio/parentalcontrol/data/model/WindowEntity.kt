package com.tudominio.parentalcontrol.data.model

import kotlinx.serialization.Serializable

/**
 * Represents a recurring time window (days + time range) when an app is allowed.
 * Persisted as JSON by [com.tudominio.parentalcontrol.data.db.Converters].
 */
@Serializable
data class WindowEntity(
    val days: List<String>,
    val from: String,
    val to: String
)
