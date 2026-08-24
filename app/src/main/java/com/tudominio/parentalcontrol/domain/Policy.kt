package com.tudominio.parentalcontrol.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.Instant

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
        return canonicalInstant(granted_at).atZone(ZoneOffset.UTC).toLocalDateTime()
    }

    fun expiresAt(): java.time.LocalDateTime {
        return canonicalInstant(expires_at).atZone(ZoneOffset.UTC).toLocalDateTime()
    }

    fun canonicalGrantedAt(): String = canonicalTimestamp(granted_at)

    fun canonicalExpiresAt(): String = canonicalTimestamp(expires_at)

    fun verify() {}
}

@Serializable
enum class DeviceState {
    @SerialName("active")
    ACTIVE,
    @SerialName("locked")
    LOCKED,
    @SerialName("downtime")
    DOWNTIME
}

@Serializable
enum class AppPolicyState {
    @SerialName("allowed")
    ALLOWED,
    @SerialName("blocked")
    BLOCKED,
    @SerialName("limited")
    LIMITED,
    @SerialName("always_allowed")
    ALWAYS_ALLOWED
}

@Serializable
enum class ScheduleAction {
    @SerialName("lock")
    LOCK,
    @SerialName("allow_only")
    ALLOW_ONLY
}

@Serializable
enum class GrantSource {
    @SerialName("extra_time")
    EXTRA_TIME,
    @SerialName("reward")
    REWARD,
    @SerialName("manual")
    MANUAL
}

@Serializable
enum class DayOfWeek {
    @SerialName("MON")
    MONDAY,
    @SerialName("TUE")
    TUESDAY,
    @SerialName("WED")
    WEDNESDAY,
    @SerialName("THU")
    THURSDAY,
    @SerialName("FRI")
    FRIDAY,
    @SerialName("SAT")
    SATURDAY,
    @SerialName("SUN")
    SUNDAY
}

private fun verifyTimeFormat(time: String, fieldName: String) {
    require(time.matches(Regex("^([01]\\d|2[0-3]):([0-5]\\d)$"))) {
        "$fieldName must be in HH:mm format, got: $time"
    }
}

private fun verifyIsoTimestamp(timestamp: String, fieldName: String) {
    require(timestamp.isNotBlank()) { "$fieldName must not be blank" }
    require(timestamp.matches(GRANT_TIMESTAMP_PATTERN)) {
        "$fieldName must be ISO 8601 timestamp with an offset and at most nanoseconds, got: $timestamp"
    }
    runCatching { OffsetDateTime.parse(timestamp, DateTimeFormatter.ISO_OFFSET_DATE_TIME) }
        .getOrElse { throw IllegalArgumentException("$fieldName must be a valid timestamp, got: $timestamp") }
}

private val GRANT_TIMESTAMP_PATTERN = Regex(
    "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?:Z|[+-]\\d{2}:\\d{2})$"
)

private fun canonicalInstant(timestamp: String): java.time.Instant =
    OffsetDateTime.parse(timestamp, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()

fun canonicalGrantTimestamp(timestamp: String): String {
    verifyIsoTimestamp(timestamp, "timestamp")
    return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(ZoneOffset.UTC)
        .format(canonicalInstant(timestamp))
}

fun Instant.toCanonicalGrantTimestamp(): String = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    .withZone(ZoneOffset.UTC)
    .format(truncatedTo(ChronoUnit.MILLIS))

private fun canonicalTimestamp(timestamp: String): String = canonicalGrantTimestamp(timestamp)
