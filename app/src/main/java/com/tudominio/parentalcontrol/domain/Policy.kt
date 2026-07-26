package com.tudominio.parentalcontrol.domain

import kotlinx.serialization.Serializable
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@Serializable
data class Policy(
    val device_id: String,
    val version: Int,
    val device_state: DeviceState,
    val daily_screen_time_minutes: Int,
    val schedules: List<Schedule>,
    val category_limits: List<CategoryLimit>,
    val app_policies: List<AppPolicy>,
    val category_assignments: Map<String, String>,
    val grants: List<Grant>
) {
    init {
        require(version >= 0) { "version must be non-negative" }
        require(daily_screen_time_minutes >= 0) { "daily_screen_time_minutes must be non-negative" }
        schedules.forEach { it.verify() }
        app_policies.forEach { it.verify() }
        grants.forEach { it.verify() }
    }
}

@Serializable
data class Schedule(
    val id: String,
    val days: List<DayOfWeek>,
    val from: String,
    val to: String,
    val action: ScheduleAction,
    val allow_list: List<String>? = null
) {
    init {
        verifyTimeFormat(from, "from")
        verifyTimeFormat(to, "to")
        require(days.isNotEmpty()) { "days must not be empty" }
        when (action) {
            ScheduleAction.ALLOW_ONLY -> require(!allow_list.isNullOrEmpty()) {
                "allow_list must not be empty when action is ALLOW_ONLY"
            }
            ScheduleAction.LOCK -> { }
        }
    }

    fun verify() {}
}

@Serializable
data class Window(
    val days: List<DayOfWeek>,
    val from: String,
    val to: String
) {
    init {
        verifyTimeFormat(from, "from")
        verifyTimeFormat(to, "to")
        require(days.isNotEmpty()) { "days must not be empty" }
    }

    fun verify() {}
}

@Serializable
data class CategoryLimit(
    val category: String,
    val minutes: Int
) {
    init {
        require(category.isNotBlank()) { "category must not be blank" }
        require(minutes >= 0) { "minutes must be non-negative" }
    }

    fun verify() {}
}

@Serializable
data class AppPolicy(
    val package_name: String,
    val state: AppPolicyState,
    val daily_limit_minutes: Int? = null,
    val allowed_windows: List<Window> = emptyList(),
    val category: String? = null
) {
    init {
        require(package_name.isNotBlank()) { "package_name must not be blank" }
        when (state) {
            AppPolicyState.LIMITED -> require(daily_limit_minutes != null && daily_limit_minutes > 0) {
                "daily_limit_minutes must be non-null and positive when state is LIMITED"
            }
            else -> { }
        }
        allowed_windows.forEach { it.verify() }
    }

    fun verify() {}
}

@Serializable
data class Grant(
    val id: String,
    val request_id: String? = null,
    val scope: String,
    val minutes: Int,
    val source: GrantSource,
    val granted_at: String,
    val expires_at: String
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(scope.isNotBlank()) { "scope must not be blank" }
        require(minutes > 0) { "minutes must be positive" }
        verifyIsoTimestamp(granted_at, "granted_at")
        verifyIsoTimestamp(expires_at, "expires_at")
        require(expiresAt().isAfter(grantedAt())) {
            "expires_at must be after granted_at"
        }
    }

    fun grantedAt(): java.time.LocalDateTime {
        return try {
            java.time.LocalDateTime.parse(granted_at, java.time.format.DateTimeFormatter.ISO_DATE_TIME)
        } catch (e: Exception) {
            java.time.Instant.parse(granted_at)
                .atZone(java.time.ZoneOffset.UTC)
                .toLocalDateTime()
        }
    }

    fun expiresAt(): java.time.LocalDateTime {
        return try {
            java.time.LocalDateTime.parse(expires_at, java.time.format.DateTimeFormatter.ISO_DATE_TIME)
        } catch (e: Exception) {
            java.time.Instant.parse(expires_at)
                .atZone(java.time.ZoneOffset.UTC)
                .toLocalDateTime()
        }
    }

    fun verify() {}
}

@Serializable
enum class DeviceState {
    ACTIVE, LOCKED, DOWNTIME
}

@Serializable
enum class AppPolicyState {
    ALLOWED, BLOCKED, LIMITED, ALWAYS_ALLOWED
}

@Serializable
enum class ScheduleAction {
    LOCK, ALLOW_ONLY
}

@Serializable
enum class GrantSource {
    EXTRA_TIME, REWARD, MANUAL
}

@Serializable
enum class DayOfWeek {
    MONDAY,
    TUESDAY,
    WEDNESDAY,
    THURSDAY,
    FRIDAY,
    SATURDAY,
    SUNDAY
}

private fun verifyTimeFormat(time: String, fieldName: String) {
    require(time.matches(Regex("^([01]\\d|2[0-3]):([0-5]\\d)$"))) {
        "$fieldName must be in HH:mm format, got: $time"
    }
}

private fun verifyIsoTimestamp(timestamp: String, fieldName: String) {
    require(timestamp.isNotBlank()) { "$fieldName must not be blank" }
    try {
        java.time.Instant.parse(timestamp)
    } catch (e: Exception) {
        try {
            java.time.LocalDateTime.parse(timestamp, java.time.format.DateTimeFormatter.ISO_DATE_TIME)
        } catch (e2: Exception) {
            throw IllegalArgumentException("$fieldName must be ISO 8601 format, got: $timestamp")
        }
    }
}
