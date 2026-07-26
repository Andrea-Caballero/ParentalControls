package com.tudominio.parentalcontrol.domain.model

import kotlinx.serialization.Serializable

/**
 * Dispositivo hijo asociado a un padre.
 *
 * Change A of `feat-multi-child-picker` (design §A.5): the `child` field
 * is nullable so devices paired BEFORE the `children` table migration
 * keep a `child == null` value and render as "Sin asignar" on the
 * dashboard. Default-null means every existing call site keeps compiling
 * without source changes.
 *
 * The `parentId`/timestamps on [child] are empty on the device payload
 * (the dashboard only renders `firstName`); a future `getChildren()` call
 * hydrates the full row.
 */
@Serializable
data class ChildDevice(
    val id: String,
    val name: String,
    val model: String? = null,
    val appVersion: String,
    val policyVersion: Int,
    val state: DeviceState,
    val lastSeenAt: String,
    val isOnline: Boolean = false,
    val child: Child? = null
)

/**
 * Niño asociado a una cuenta de padre. Nuevo con Change A de
 * `feat-multi-child-picker` (spec: child-entity).
 *
 * Serializado desde el payload de `get-devices-for-parent` cuando el
 * `.select` incluye el resource embedding `child:children(id, first_name)`
 * (design §A.8), y desde la futura `GET /rest/v1/children` que el
 * dialogo de RenameChildDialog (Change B §B.6) llama después del rename.
 */
@Serializable
data class Child(
    val id: String,
    val parentId: String,
    val firstName: String,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
enum class DeviceState {
    ACTIVE,
    LOCKED,
    DOWNTIME
}

/**
 * Solicitud de tiempo del niño.
 */
@Serializable
data class TimeRequest(
    val id: String,
    val deviceId: String,
    val deviceName: String? = null,
    val packageName: String? = null,
    val appName: String? = null,
    val minutesRequested: Int,
    val reason: String? = null,
    val status: RequestStatus,
    val createdAt: String,
    val respondedAt: String? = null,
    val parentResponse: String? = null
)

@Serializable
enum class RequestStatus {
    PENDING,
    APPROVED,
    DENIED
}

// Alias para uso interno
typealias TimeRequestStatus = RequestStatus

/**
 * Estado de bloqueo para el niño.
 */
enum class BlockedState {
    NOT_BLOCKED,
    APP_BLOCKED,
    SCHEDULE_BLOCK,
    TIME_EXCEEDED,
    LIMIT_EXCEEDED
}

/**
 * Plantilla de política.
 */
@Serializable
data class PolicyTemplate(
    val id: String,
    val name: String,
    val description: String,
    val ageBand: String,
    val isDefault: Boolean
)

/**
 * Respuesta de emparejamiento.
 */
@Serializable
data class PairingResponse(
    val code: String,
    val expiresAt: String,
    val qrData: String? = null,
    val deeplink: String
)

/**
 * Resultado de aprobar solicitud.
 */
@Serializable
data class ApprovalResult(
    val success: Boolean,
    val grantId: String? = null,
    val minutes: Int? = null,
    val expiresAt: String? = null,
    val idempotent: Boolean = false,
    val message: String? = null
)

/**
 * Health status de un dispositivo.
 */
@Serializable
data class DeviceHealth(
    val enforcementLevel: String,
    val suspicionLevel: String,
    val lastHeartbeat: String?,
    val batteryLevel: Int?,
    val isCharging: Boolean,
    val alerts: List<String> = emptyList()
)

/**
 * Estadísticas de uso.
 */
@Serializable
data class UsageStats(
    val deviceId: String,
    val packageName: String,
    val appName: String? = null,
    val date: String,
    val minutesUsed: Int,
    val limitMinutes: Int? = null,
    val remainingMinutes: Int? = null
)

/**
 * Política completa del dispositivo (según §0.3).
 */
@Serializable
data class Policy(
    val deviceId: String,
    val version: Int,
    val deviceState: String,
    val dailyScreenTimeMinutes: Int,
    val schedules: List<Schedule>,
    val categoryLimits: List<CategoryLimit>,
    val appPolicies: List<AppPolicy>,
    val categoryAssignments: Map<String, String>,
    val grants: List<Grant> = emptyList()
)

@Serializable
data class Schedule(
    val id: String,
    val days: List<String>,
    val from: String,
    val to: String,
    val action: String,
    val allowList: List<String>? = null
)

@Serializable
data class CategoryLimit(
    val category: String,
    val minutes: Int
)

@Serializable
data class AppPolicy(
    val packageName: String,
    val state: String,
    val dailyLimitMinutes: Int?,
    val allowedWindows: List<AllowedWindow>,
    val category: String?
)

@Serializable
data class AllowedWindow(
    val from: String,
    val to: String
)

@Serializable
data class Grant(
    val id: String,
    val requestId: String?,
    val scope: String,
    val minutes: Int,
    val source: String,
    val grantedAt: String,
    val expiresAt: String
)
