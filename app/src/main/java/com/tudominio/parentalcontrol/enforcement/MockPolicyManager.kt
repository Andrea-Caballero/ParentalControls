package com.tudominio.parentalcontrol.enforcement

import com.tudominio.parentalcontrol.domain.*

/**
 * Generador de políticas mock para testing.
 * Cubre todos los escenarios de §0.3.
 */
object MockPolicyManager {

    /**
     * Política mock que ejercita todas las reglas de §0.3.
     */
    fun createFullTestPolicy(): Policy {
        return Policy(
            device_id = "test-device",
            version = 1,
            device_state = DeviceState.ACTIVE,
            daily_screen_time_minutes = 120,
            schedules = listOf(
                Schedule(
                    id = "downtime-evening",
                    days = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, 
                                  DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
                    from = "21:00",
                    to = "07:00",
                    action = ScheduleAction.LOCK
                ),
                Schedule(
                    id = "weekend-loose",
                    days = listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
                    from = "08:00",
                    to = "22:00",
                    action = ScheduleAction.ALLOW_ONLY
                )
            ),
            category_limits = listOf(
                CategoryLimit(category = "games", minutes = 60),
                CategoryLimit(category = "social", minutes = 30)
            ),
            app_policies = listOf(
                // App bloqueada
                AppPolicy(
                    package_name = "com.example.blocked",
                    state = AppPolicyState.BLOCKED
                ),
                // App con límite diario
                AppPolicy(
                    package_name = "com.example.limited",
                    state = AppPolicyState.LIMITED,
                    daily_limit_minutes = 30
                ),
                // App con ventanas
                AppPolicy(
                    package_name = "com.example.windowed",
                    state = AppPolicyState.ALLOWED,
                    allowed_windows = listOf(
                        Window(
                            days = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                                          DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
                            from = "15:00",
                            to = "20:00"
                        )
                    )
                ),
                // App de sistema (crítica)
                AppPolicy(
                    package_name = "com.example.system",
                    state = AppPolicyState.ALLOWED
                ),
                // App con categoría limitada
                AppPolicy(
                    package_name = "com.example.game",
                    state = AppPolicyState.LIMITED,
                    category = "games"
                )
            ),
            category_assignments = mapOf(
                "com.example.game" to "games",
                "com.example.social" to "social"
            ),
            grants = listOf(
                Grant(
                    id = "extra-time-1",
                    scope = "device",
                    minutes = 30,
                    source = GrantSource.EXTRA_TIME,
                    granted_at = "2026-06-04T14:00:00Z",
                    expires_at = "2026-06-04T23:59:59Z"
                )
            )
        )
    }

    /**
     * Política simple para test rápido.
     */
    fun createSimplePolicy(): Policy {
        return Policy(
            device_id = "simple-device",
            version = 1,
            device_state = DeviceState.ACTIVE,
            daily_screen_time_minutes = 60,
            schedules = emptyList(),
            category_limits = emptyList(),
            app_policies = listOf(
                AppPolicy(
                    package_name = "com.test.blocked",
                    state = AppPolicyState.BLOCKED
                ),
                AppPolicy(
                    package_name = "com.test.allowed",
                    state = AppPolicyState.ALLOWED
                )
            ),
            category_assignments = emptyMap(),
            grants = emptyList()
        )
    }

    /**
     * Política con downtime activo.
     */
    fun createDowntimePolicy(): Policy {
        return Policy(
            device_id = "downtime-device",
            version = 1,
            device_state = DeviceState.DOWNTIME,
            daily_screen_time_minutes = 60,
            schedules = emptyList(),
            category_limits = emptyList(),
            app_policies = listOf(
                AppPolicy(
                    package_name = "com.test.any",
                    state = AppPolicyState.ALLOWED
                ),
                AppPolicy(
                    package_name = "com.test.always",
                    state = AppPolicyState.ALWAYS_ALLOWED
                )
            ),
            category_assignments = emptyMap(),
            grants = emptyList()
        )
    }

    /**
     * Política con device locked.
     */
    fun createLockedPolicy(): Policy {
        return Policy(
            device_id = "locked-device",
            version = 1,
            device_state = DeviceState.LOCKED,
            daily_screen_time_minutes = 60,
            schedules = emptyList(),
            category_limits = emptyList(),
            app_policies = listOf(
                AppPolicy(
                    package_name = "com.test.any",
                    state = AppPolicyState.ALLOWED
                )
            ),
            category_assignments = emptyMap(),
            grants = emptyList()
        )
    }

    /**
     * Política con grants activos.
     */
    fun createGrantPolicy(): Policy {
        return Policy(
            device_id = "grant-device",
            version = 1,
            device_state = DeviceState.DOWNTIME,
            daily_screen_time_minutes = 60,
            schedules = emptyList(),
            category_limits = emptyList(),
            app_policies = listOf(
                AppPolicy(
                    package_name = "com.test.app",
                    state = AppPolicyState.ALLOWED
                )
            ),
            category_assignments = emptyMap(),
            grants = listOf(
                Grant(
                    id = "active-grant",
                    scope = "com.test.app",
                    minutes = 30,
                    source = GrantSource.MANUAL,
                    granted_at = "2026-06-04T00:00:00Z",
                    expires_at = "2026-06-04T23:59:59Z"
                )
            )
        )
    }
}
