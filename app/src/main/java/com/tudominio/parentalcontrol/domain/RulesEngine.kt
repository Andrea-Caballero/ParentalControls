package com.tudominio.parentalcontrol.domain

import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

// UsageContext: uso por paquete, por categoría y global — calculado por T03
data class UsageContext(
    val usagePorApp: Map<String, Int> = emptyMap(),
    val usagePorCategoria: Map<String, Int> = emptyMap(),
    val tiempoGlobal: Int = 0
) {
    companion object {
        fun empty() = UsageContext()
    }
}

sealed class Decision {
    object Permitir : Decision()
    data class Bloquear(val motivo: String) : Decision()
}

// §0.4 — Algoritmo de precedencia del motor (núcleo)
// 12 pasos en orden exacto; primera coincidencia decide
fun evaluar(
    policy: Policy,
    packageName: String,
    usage: UsageContext,
    now: LocalDateTime,
    zonaHoraria: ZoneId
): Decision {

    val motivos = MotivoTabla()

    // Paso 1 (§0.4): ¿App del agente o app de sistema crítica?
    // → PERMITIR (nunca bloquear marcador/emergencias/ajustes del agente)
    if (isCriticalApp(packageName)) {
        return Decision.Permitir
    }

    // Paso 2 (§0.4): ¿device_state == locked?
    // → BLOQUEAR (total; ni always_allowed ni grants lo levantan)
    if (policy.device_state == DeviceState.LOCKED) {
        return Decision.Bloquear(motivos.deviceLocked)
    }

    // Paso 3 (§0.4): ¿app_policy.state == blocked?
    // → BLOQUEAR (dura; ningún grant la levanta)
    val appPolicy = policy.app_policies.find {
        it.package_name == packageName
    }

    if (appPolicy?.state == AppPolicyState.BLOCKED) {
        return Decision.Bloquear(motivos.appBlocked)
    }

    // Paso 4 (§0.4): ¿Schedule 'allow_only' activo y la app NO está en allow_list?
    // → BLOQUEAR (dura)
    val activeAllowOnly = activeAllowOnlySchedule(policy.schedules, now, zonaHoraria)
    if (activeAllowOnly != null && packageName !in activeAllowOnly.allow_list.orEmpty()) {
        return Decision.Bloquear(motivos.allowOnly)
    }

    // Paso 5 (§0.4): ¿La app define allowed_windows y AHORA está fuera de todas?
    // → BLOQUEAR (dura)
    if (appPolicy?.allowed_windows?.isNotEmpty() == true &&
        appPolicy.allowed_windows.none { window ->
            isNowInWindow(window.days, window.from, window.to, now, zonaHoraria)
        }
    ) {
        return Decision.Bloquear(motivos.outsideAllowedWindows)
    }

    // Paso 6 (§0.4): ¿Grant vigente que cubre el scope (device | <pkg> | <cat>)?
    // → PERMITIR (levanta los pasos 7–11; nunca 2–5)
    if (isGrantActive(policy.grants, packageName, appPolicy?.category, now)) {
        return Decision.Permitir
    }

    // Paso 7 (§0.4): ¿device_state == downtime y la app NO es always_allowed?
    // → BLOQUEAR
    if (policy.device_state == DeviceState.DOWNTIME &&
        appPolicy?.state != AppPolicyState.ALWAYS_ALLOWED
    ) {
        return Decision.Bloquear(motivos.downtime)
    }

    // Paso 8 (§0.4): ¿Dentro de un schedule action 'lock' y NO always_allowed?
    // → BLOQUEAR
    val activeLock = activeLockSchedule(policy.schedules, now, zonaHoraria)
    if (activeLock != null && appPolicy?.state != AppPolicyState.ALWAYS_ALLOWED) {
        return Decision.Bloquear(motivos.scheduleLock)
    }

    // Paso 9 (§0.4): ¿Excedió daily_limit_minutes de la app y NO always_allowed?
    // → BLOQUEAR
    if (appPolicy?.daily_limit_minutes != null &&
        (usage.usagePorApp[packageName] ?: 0) >= appPolicy.daily_limit_minutes &&
        appPolicy.state != AppPolicyState.ALWAYS_ALLOWED
    ) {
        return Decision.Bloquear(motivos.appTimeLimit)
    }

    // Paso 10 (§0.4): ¿Excedió el límite de su categoría y NO always_allowed?
    // → BLOQUEAR
    val category = appPolicy?.category
    if (category != null) {
        val categoryLimit = policy.category_limits.find { it.category == category }
        if (categoryLimit != null &&
            (usage.usagePorCategoria[category] ?: 0) >= categoryLimit.minutes &&
            appPolicy.state != AppPolicyState.ALWAYS_ALLOWED
        ) {
            return Decision.Bloquear(motivos.categoryTimeLimit)
        }
    }

    // Paso 11 (§0.4): ¿Excedió daily_screen_time global (app no exenta)?
    // → BLOQUEAR
    if (usage.tiempoGlobal >= policy.daily_screen_time_minutes &&
        appPolicy?.state != AppPolicyState.ALWAYS_ALLOWED
    ) {
        return Decision.Bloquear(motivos.globalTimeLimit)
    }

    // Paso 12 (§0.4): En cualquier otro caso → PERMITIR
    return Decision.Permitir
}

// §0.4 Paso 6: Grant vigente — cubre si scope == device | packageName | category
private fun isGrantActive(
    grants: List<Grant>,
    packageName: String,
    category: String?,
    now: LocalDateTime
): Boolean {
    return grants.any { grant ->
        grant.isValid(now) && grant.covers(packageName, category)
    }
}

// §0.4: Grant vigente ⇔ granted_at ≤ now_servidor < expires_at
private fun Grant.isValid(now: LocalDateTime): Boolean {
    val grantedAt = parseTimestamp(granted_at)
    val expiresAt = parseTimestamp(expires_at)
    return now in grantedAt..expiresAt
}

// §0.4: scope device | packageName | category_assignments[pkg]
private fun Grant.covers(packageName: String, category: String?): Boolean {
    return scope == "device" || scope == packageName || scope == category
}

private fun parseTimestamp(ts: String): LocalDateTime {
    return try {
        Instant.parse(ts).atZone(ZoneOffset.UTC).toLocalDateTime()
    } catch (e: Exception) {
        LocalDateTime.parse(ts)
    }
}

// §0.4 Paso 1: Apps críticas del agente y sistema
private fun isCriticalApp(packageName: String): Boolean {
    return packageName in listOf(
        "com.agent.app",
        "com.android.dialer"
    )
}

// §0.4 Paso 4: Schedule ALLOW_ONLY activo
private fun activeAllowOnlySchedule(
    schedules: List<Schedule>,
    now: LocalDateTime,
    zonaHoraria: ZoneId
): Schedule? {
    return schedules.find { schedule ->
        schedule.action == ScheduleAction.ALLOW_ONLY &&
            isNowInWindow(schedule.days, schedule.from, schedule.to, now, zonaHoraria)
    }
}

// §0.4 Paso 8: Schedule LOCK activo
private fun activeLockSchedule(
    schedules: List<Schedule>,
    now: LocalDateTime,
    zonaHoraria: ZoneId
): Schedule? {
    return schedules.find { schedule ->
        schedule.action == ScheduleAction.LOCK &&
            isNowInWindow(schedule.days, schedule.from, schedule.to, now, zonaHoraria)
    }
}

// §0.4: Determina si now está dentro de la ventana.
// Cruce de medianoche permitido (from > to).
private fun isNowInWindow(
    days: List<DayOfWeek>,
    from: String,
    to: String,
    now: LocalDateTime,
    zonaHoraria: ZoneId
): Boolean {
    val currentDay = DayOfWeek.valueOf(now.dayOfWeek.name)
    if (currentDay !in days) return false

    val localNow = now.atZone(zonaHoraria).toLocalTime()
    val fromTime = LocalTime.parse(from)
    val toTime = LocalTime.parse(to)

    return if (fromTime < toTime) {
        // Normal: 08:00–15:00
        localNow >= fromTime && localNow <= toTime
    } else {
        // Cruce de medianoche: 22:00–06:00
        localNow >= fromTime || localNow <= toTime
    }
}

// §0.4: Motivos legibles para el overlay
class MotivoTabla {
    val deviceLocked = "El dispositivo está bloqueado."
    val appBlocked = "Esta app está bloqueada."
    val allowOnly = "Esta app no está permitida en este horario."
    val outsideAllowedWindows = "Esta app no está dentro de ninguna ventana permitida."
    val downtime = "Es hora de dormir."
    val scheduleLock = "El acceso está restringido en este horario."
    val appTimeLimit = "Has alcanzado el límite diario de esta app."
    val categoryTimeLimit = "Has alcanzado el límite diario de la categoría."
    val globalTimeLimit = "Has alcanzado el tiempo de pantalla permitido."
}
