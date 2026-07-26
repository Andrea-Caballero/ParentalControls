package com.tudominio.parentalcontrol.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class RulesEngineTest {

    private val zonaHoraria = ZoneId.of("UTC")

    // =============================================================================
    // PASO 1: App del agente o sistema crítica → PERMITIR
    // =============================================================================
    @Test
    fun `paso 1 - app del agente es permitida`() {
        val policy = newPolicy()
        val decision = evaluar(policy, "com.agent.app", UsageContext.empty(), now(10, 0), zonaHoraria)
        assertEquals(Decision.Permitir, decision)
    }

    @Test
    fun `paso 1 - dialer de emergencia es permitido`() {
        val policy = newPolicy()
        val decision = evaluar(policy, "com.android.dialer", UsageContext.empty(), now(10, 0), zonaHoraria)
        assertEquals(Decision.Permitir, decision)
    }

    @Test
    fun `paso 1 - app normal no es permitida por paso 1`() {
        val policy = newPolicy()
        val decision = evaluar(policy, "com.example.app", UsageContext.empty(), now(10, 0), zonaHoraria)
        assertEquals(Decision.Permitir, decision)
    }

    // =============================================================================
    // PASO 2: device_state == locked → BLOQUEAR (total)
    // =============================================================================
    @Test
    fun `paso 2 - locked bloquea todo incluyendo grants`() {
        val policy = newPolicy(
            deviceState = DeviceState.LOCKED,
            grants = listOf(validGrant("device"))
        )
        val decision = evaluar(policy, "com.example.app", UsageContext.empty(), now(10, 0), zonaHoraria)
        assertEquals(Decision.Bloquear("El dispositivo está bloqueado."), decision)
    }

    @Test
    fun `paso 2 - locked ignora always_allowed`() {
        val policy = newPolicy(
            deviceState = DeviceState.LOCKED,
            appPolicies = listOf(
                AppPolicy("com.always.app", AppPolicyState.ALWAYS_ALLOWED, null, emptyList(), null)
            )
        )
        val decision = evaluar(policy, "com.always.app", UsageContext.empty(), now(10, 0), zonaHoraria)
        assertEquals(Decision.Bloquear("El dispositivo está bloqueado."), decision)
    }

    // =============================================================================
    // PASO 3: app_policy.state == blocked → BLOQUEAR (dura)
    // =============================================================================
    @Test
    fun `paso 3 - blocked bloquea aunque haya grant device`() {
        val policy = newPolicy(
            appPolicies = listOf(
                AppPolicy("com.blocked.app", AppPolicyState.BLOCKED, null, emptyList(), null)
            ),
            grants = listOf(validGrant("device"))
        )
        val decision = evaluar(policy, "com.blocked.app", UsageContext.empty(), now(10, 0), zonaHoraria)
        assertEquals(Decision.Bloquear("Esta app está bloqueada."), decision)
    }

    @Test
    fun `paso 3 - blocked no se levanta con grant package`() {
        val policy = newPolicy(
            appPolicies = listOf(
                AppPolicy("com.blocked.app", AppPolicyState.BLOCKED, null, emptyList(), null)
            ),
            grants = listOf(validGrant("com.blocked.app"))
        )
        val decision = evaluar(policy, "com.blocked.app", UsageContext.empty(), now(10, 0), zonaHoraria)
        assertEquals(Decision.Bloquear("Esta app está bloqueada."), decision)
    }

    // =============================================================================
    // PASO 4: Schedule allow_only activo → BLOQUEAR (dura)
    // =============================================================================
    @Test
    fun `paso 4 - allow_only bloquea app no en allow_list`() {
        val policy = newPolicy(
            schedules = listOf(
                Schedule("s1", listOf(DayOfWeek.MONDAY), "08:00", "15:00", ScheduleAction.ALLOW_ONLY, listOf("com.whatsapp"))
            )
        )
        val decision = evaluar(policy, "com.instagram", now(10, 0), zonaHoraria)
        assertEquals(Decision.Bloquear("Esta app no está permitida en este horario."), decision)
    }

    @Test
    fun `paso 4 - allow_only permite app en allow_list`() {
        val policy = newPolicy(
            schedules = listOf(
                Schedule("s1", listOf(DayOfWeek.MONDAY), "08:00", "15:00", ScheduleAction.ALLOW_ONLY, listOf("com.whatsapp"))
            )
        )
        val decision = evaluar(policy, "com.whatsapp", now(10, 0), zonaHoraria)
        assertEquals(Decision.Permitir, decision)
    }

    @Test
    fun `paso 4 - allow_only no se levanta con grant device`() {
        val policy = newPolicy(
            schedules = listOf(
                Schedule("s1", listOf(DayOfWeek.MONDAY), "08:00", "15:00", ScheduleAction.ALLOW_ONLY, listOf("com.whatsapp"))
            ),
            grants = listOf(validGrant("device"))
        )
        val decision = evaluar(policy, "com.instagram", now(10, 0), zonaHoraria)
        assertEquals(Decision.Bloquear("Esta app no está permitida en este horario."), decision)
    }

    // =============================================================================
    // PASO 5: allowed_windows fuera de ventana → BLOQUEAR (dura)
    // =============================================================================
    @Test
    fun `paso 5 - fuera de allowed_windows bloquea`() {
        val policy = newPolicy(
            appPolicies = listOf(
                AppPolicy(
                    "com.game.app", AppPolicyState.LIMITED, 60,
                    listOf(Window(listOf(DayOfWeek.MONDAY), "16:00", "18:00")),
                    "games"
                )
            )
        )
        val decision = evaluar(policy, "com.game.app", now(10, 0), zonaHoraria)
        assertEquals(Decision.Bloquear("Esta app no está dentro de ninguna ventana permitida."), decision)
    }

    @Test
    fun `paso 5 - dentro de allowed_windows permite`() {
        val policy = newPolicy(
            appPolicies = listOf(
                AppPolicy(
                    "com.game.app", AppPolicyState.LIMITED, 60,
                    listOf(Window(listOf(DayOfWeek.MONDAY), "16:00", "18:00")),
                    "games"
                )
            )
        )
        val decision = evaluar(policy, "com.game.app", now(17, 0), zonaHoraria)
        assertEquals(Decision.Permitir, decision)
    }

    // =============================================================================
    // PASO 6: Grant vigente → PERMITIR (levanta 7–11)
    // =============================================================================
    @Test
    fun `paso 6 - grant device levanta downtime`() {
        val policy = newPolicy(
            deviceState = DeviceState.DOWNTIME,
            grants = listOf(validGrant("device"))
        )
        val decision = evaluar(policy, "com.example.app", UsageContext.empty(), now(10, 0), zonaHoraria)
        assertEquals(Decision.Permitir, decision)
    }

    @Test
    fun `paso 6 - grant package levanta limite global`() {
        val policy = newPolicy(
            grants = listOf(validGrant("com.example.app")),
            dailyScreenTimeMinutes = 60
        )
        val usage = UsageContext(tiempoGlobal = 120)
        val decision = evaluar(policy, "com.example.app", usage, now(10, 0), zonaHoraria)
        assertEquals(Decision.Permitir, decision)
    }

    @Test
    fun `paso 6 - grant category levanta limite de categoria`() {
        val policy = newPolicy(
            categoryLimits = listOf(CategoryLimit("games", 60)),
            grants = listOf(validGrant("games")),
            appPolicies = listOf(
                AppPolicy("com.game.app", AppPolicyState.LIMITED, 30, emptyList(), "games")
            )
        )
        val usage = UsageContext(usagePorCategoria = mapOf("games" to 120))
        val decision = evaluar(policy, "com.game.app", usage, now(10, 0), zonaHoraria)
        assertEquals(Decision.Permitir, decision)
    }

    @Test
    fun `paso 6 - grant vencido no permite`() {
        val policy = newPolicy(
            grants = listOf(expiredGrant("device")),
            deviceState = DeviceState.DOWNTIME
        )
        val decision = evaluar(policy, "com.example.app", UsageContext.empty(), now(10, 0), zonaHoraria)
        assertEquals(Decision.Bloquear("Es hora de dormir."), decision)
    }

    // =============================================================================
    // PASO 7: downtime → BLOQUEAR
    // =============================================================================
    @Test
    fun `paso 7 - downtime bloquea app no always_allowed`() {
        val policy = newPolicy(deviceState = DeviceState.DOWNTIME)
        val decision = evaluar(policy, "com.example.app", UsageContext.empty(), now(23, 0), zonaHoraria)
        assertEquals(Decision.Bloquear("Es hora de dormir."), decision)
    }

    @Test
    fun `paso 7 - downtime permite always_allowed`() {
        val policy = newPolicy(
            deviceState = DeviceState.DOWNTIME,
            appPolicies = listOf(
                AppPolicy("com.always.app", AppPolicyState.ALWAYS_ALLOWED, null, emptyList(), null)
            )
        )
        val decision = evaluar(policy, "com.always.app", UsageContext.empty(), now(23, 0), zonaHoraria)
        assertEquals(Decision.Permitir, decision)
    }

    // =============================================================================
    // PASO 8: Schedule LOCK activo → BLOQUEAR
    // =============================================================================
    @Test
    fun `paso 8 - schedule lock bloquea`() {
        val policy = newPolicy(
            schedules = listOf(
                Schedule("s1", listOf(DayOfWeek.MONDAY), "08:00", "15:00", ScheduleAction.LOCK, null)
            )
        )
        val decision = evaluar(policy, "com.example.app", now(10, 0), zonaHoraria)
        assertEquals(Decision.Bloquear("El acceso está restringido en este horario."), decision)
    }

    @Test
    fun `paso 8 - schedule lock permite always_allowed`() {
        val policy = newPolicy(
            schedules = listOf(
                Schedule("s1", listOf(DayOfWeek.MONDAY), "08:00", "15:00", ScheduleAction.LOCK, null)
            ),
            appPolicies = listOf(
                AppPolicy("com.always.app", AppPolicyState.ALWAYS_ALLOWED, null, emptyList(), null)
            )
        )
        val decision = evaluar(policy, "com.always.app", now(10, 0), zonaHoraria)
        assertEquals(Decision.Permitir, decision)
    }

    // =============================================================================
    // PASO 9: Límite diario por app → BLOQUEAR
    // =============================================================================
    @Test
    fun `paso 9 - limite de app alcanzado bloquea`() {
        val policy = newPolicy(
            appPolicies = listOf(
                AppPolicy("com.game.app", AppPolicyState.LIMITED, 30, emptyList(), null)
            )
        )
        val usage = UsageContext(usagePorApp = mapOf("com.game.app" to 30))
        val decision = evaluar(policy, "com.game.app", usage, now(10, 0), zonaHoraria)
        assertEquals(Decision.Bloquear("Has alcanzado el límite diario de esta app."), decision)
    }

    @Test
    fun `paso 9 - limite de app no alcanzado permite`() {
        val policy = newPolicy(
            appPolicies = listOf(
                AppPolicy("com.game.app", AppPolicyState.LIMITED, 30, emptyList(), null)
            )
        )
        val usage = UsageContext(usagePorApp = mapOf("com.game.app" to 15))
        val decision = evaluar(policy, "com.game.app", usage, now(10, 0), zonaHoraria)
        assertEquals(Decision.Permitir, decision)
    }

    @Test
    fun `paso 9 - always_allowed ignora limite de app`() {
        val policy = newPolicy(
            appPolicies = listOf(
                AppPolicy("com.game.app", AppPolicyState.ALWAYS_ALLOWED, 30, emptyList(), null)
            )
        )
        val usage = UsageContext(usagePorApp = mapOf("com.game.app" to 120))
        val decision = evaluar(policy, "com.game.app", usage, now(10, 0), zonaHoraria)
        assertEquals(Decision.Permitir, decision)
    }

    // =============================================================================
    // PASO 10: Límite por categoría → BLOQUEAR
    // =============================================================================
    @Test
    fun `paso 10 - limite de categoria alcanzado bloquea`() {
        val policy = newPolicy(
            categoryLimits = listOf(CategoryLimit("games", 60)),
            appPolicies = listOf(
                AppPolicy("com.game.app", AppPolicyState.LIMITED, 30, emptyList(), "games")
            )
        )
        val usage = UsageContext(usagePorCategoria = mapOf("games" to 60))
        val decision = evaluar(policy, "com.game.app", usage, now(10, 0), zonaHoraria)
        assertEquals(Decision.Bloquear("Has alcanzado el límite diario de la categoría."), decision)
    }

    @Test
    fun `paso 10 - always_allowed ignora limite de categoria`() {
        val policy = newPolicy(
            categoryLimits = listOf(CategoryLimit("games", 60)),
            appPolicies = listOf(
                AppPolicy("com.game.app", AppPolicyState.ALWAYS_ALLOWED, 30, emptyList(), "games")
            )
        )
        val usage = UsageContext(usagePorCategoria = mapOf("games" to 120))
        val decision = evaluar(policy, "com.game.app", usage, now(10, 0), zonaHoraria)
        assertEquals(Decision.Permitir, decision)
    }

    // =============================================================================
    // PASO 11: Límite global → BLOQUEAR
    // =============================================================================
    @Test
    fun `paso 11 - limite global alcanzado bloquea`() {
        val policy = newPolicy(dailyScreenTimeMinutes = 120)
        val usage = UsageContext(tiempoGlobal = 120)
        val decision = evaluar(policy, "com.example.app", usage, now(10, 0), zonaHoraria)
        assertEquals(Decision.Bloquear("Has alcanzado el tiempo de pantalla permitido."), decision)
    }

    @Test
    fun `paso 11 - always_allowed ignora limite global`() {
        val policy = newPolicy(
            dailyScreenTimeMinutes = 120,
            appPolicies = listOf(
                AppPolicy("com.always.app", AppPolicyState.ALWAYS_ALLOWED, null, emptyList(), null)
            )
        )
        val usage = UsageContext(tiempoGlobal = 300)
        val decision = evaluar(policy, "com.always.app", usage, now(10, 0), zonaHoraria)
        assertEquals(Decision.Permitir, decision)
    }

    // =============================================================================
    // COMBINACIONES COMPLEJAS
    // =============================================================================
    @Test
    fun `combo - solo Classroom durante allow_only de tareas`() {
        val policy = newPolicy(
            schedules = listOf(
                Schedule("s1", listOf(DayOfWeek.MONDAY), "08:00", "15:00", ScheduleAction.ALLOW_ONLY, listOf("com.google.android.apps.classroom"))
            )
        )
        val decision = evaluar(policy, "com.instagram", now(10, 0), zonaHoraria)
        assertEquals(Decision.Bloquear("Esta app no está permitida en este horario."), decision)
    }

    @Test
    fun `combo - 2h de juegos pero downtime 22 00 a 07 00`() {
        val policy = newPolicy(
            deviceState = DeviceState.DOWNTIME,
            categoryLimits = listOf(CategoryLimit("games", 120)),
            appPolicies = listOf(
                AppPolicy("com.game.app", AppPolicyState.LIMITED, 60, emptyList(), "games")
            )
        )
        val usage = UsageContext(usagePorCategoria = mapOf("games" to 60))
        val decision = evaluar(policy, "com.game.app", usage, now(23, 0), zonaHoraria)
        assertEquals(Decision.Bloquear("Es hora de dormir."), decision)
    }

    @Test
    fun `combo - grant device levanta global pero NO desbloquea blocked`() {
        val policy = newPolicy(
            appPolicies = listOf(
                AppPolicy("com.blocked.app", AppPolicyState.BLOCKED, null, emptyList(), null)
            ),
            grants = listOf(validGrant("device")),
            dailyScreenTimeMinutes = 60
        )
        val usage = UsageContext(tiempoGlobal = 120)
        val decision = evaluar(policy, "com.blocked.app", usage, now(10, 0), zonaHoraria)
        assertEquals(Decision.Bloquear("Esta app está bloqueada."), decision)
    }

    @Test
    fun `combo - grant device levanta global pero NO desbloquea allow_only`() {
        val policy = newPolicy(
            schedules = listOf(
                Schedule("s1", listOf(DayOfWeek.MONDAY), "08:00", "15:00", ScheduleAction.ALLOW_ONLY, listOf("com.whatsapp"))
            ),
            grants = listOf(validGrant("device")),
            dailyScreenTimeMinutes = 60
        )
        val usage = UsageContext(tiempoGlobal = 120)
        val decision = evaluar(policy, "com.instagram", usage, now(10, 0), zonaHoraria)
        assertEquals(Decision.Bloquear("Esta app no está permitida en este horario."), decision)
    }

    @Test
    fun `combo - always_allowed ignora downtime y limites pero no locked`() {
        val policyLocked = newPolicy(
            deviceState = DeviceState.LOCKED,
            appPolicies = listOf(
                AppPolicy("com.always.app", AppPolicyState.ALWAYS_ALLOWED, null, emptyList(), null)
            )
        )
        val decisionLocked = evaluar(policyLocked, "com.always.app", UsageContext.empty(), now(23, 0), zonaHoraria)
        assertEquals(Decision.Bloquear("El dispositivo está bloqueado."), decisionLocked)

        val policyDowntime = newPolicy(
            deviceState = DeviceState.DOWNTIME,
            appPolicies = listOf(
                AppPolicy("com.always.app", AppPolicyState.ALWAYS_ALLOWED, null, emptyList(), null)
            )
        )
        val decisionDowntime = evaluar(policyDowntime, "com.always.app", UsageContext.empty(), now(23, 0), zonaHoraria)
        assertEquals(Decision.Permitir, decisionDowntime)
    }

    // =============================================================================
    // BORDES: medianoche, zona horaria, grant vencido
    // =============================================================================
    @Test
    fun `borde - schedule cruza medianoche`() {
        val policy = newPolicy(
            schedules = listOf(
                Schedule("s1", listOf(DayOfWeek.MONDAY), "22:00", "06:00", ScheduleAction.LOCK, null)
            )
        )
        val decisionNoche = evaluar(policy, "com.example.app", now(23, 0), zonaHoraria)
        assertEquals(Decision.Bloquear("El acceso está restringido en este horario."), decisionNoche)

        val decisionManana = evaluar(policy, "com.example.app", now(5, 0), zonaHoraria)
        assertEquals(Decision.Bloquear("El acceso está restringido en este horario."), decisionManana)

        val decisionMediodia = evaluar(policy, "com.example.app", now(12, 0), zonaHoraria)
        assertEquals(Decision.Permitir, decisionMediodia)
    }

    @Test
    fun `borde - allowed_windows cruza medianoche`() {
        val policy = newPolicy(
            appPolicies = listOf(
                AppPolicy(
                    "com.game.app", AppPolicyState.LIMITED, 60,
                    listOf(Window(listOf(DayOfWeek.MONDAY), "22:00", "06:00")),
                    "games"
                )
            )
        )
        val decisionNoche = evaluar(policy, "com.game.app", now(23, 0), zonaHoraria)
        assertEquals(Decision.Permitir, decisionNoche)

        val decisionManana = evaluar(policy, "com.game.app", now(5, 0), zonaHoraria)
        assertEquals(Decision.Permitir, decisionManana)
    }

    @Test
    fun `borde - grant al filo de expirar`() {
        val almostExpired = Grant(
            id = "g1",
            request_id = null,
            scope = "device",
            minutes = 30,
            source = GrantSource.EXTRA_TIME,
            granted_at = "2026-06-13T09:00:00Z",
            expires_at = "2026-06-13T09:00:01Z"
        )
        val policy = newPolicy(
            grants = listOf(almostExpired),
            deviceState = DeviceState.DOWNTIME
        )
        val decisionAntes = evaluar(policy, "com.example.app", LocalDateTime.parse("2026-06-13T09:00:00"), zonaHoraria)
        assertEquals(Decision.Permitir, decisionAntes)

        val decisionDespues = evaluar(policy, "com.example.app", LocalDateTime.parse("2026-06-13T09:00:02"), zonaHoraria)
        assertEquals(Decision.Bloquear("Es hora de dormir."), decisionDespues)
    }

    @Test
    fun `borde - zona horaria diferente`() {
        val zonaNy = ZoneId.of("America/New_York")
        val policy = newPolicy(
            schedules = listOf(
                Schedule("s1", listOf(DayOfWeek.MONDAY), "08:00", "15:00", ScheduleAction.LOCK, null)
            )
        )
        val decisionUtc = evaluar(policy, "com.example.app", LocalDateTime.parse("2026-06-15T12:00:00"), ZoneId.of("UTC"))
        val decisionNy = evaluar(policy, "com.example.app", LocalDateTime.parse("2026-06-15T08:00:00"), zonaNy)
        assertEquals(decisionUtc.toString(), decisionNy.toString())
    }

    @Test
    fun `borde - cambio de dia reset de uso`() {
        val policy = newPolicy(
            appPolicies = listOf(
                AppPolicy("com.game.app", AppPolicyState.LIMITED, 30, emptyList(), null)
            )
        )
        val usageAntesMedianoche = UsageContext(usagePorApp = mapOf("com.game.app" to 30))
        val decisionAntes = evaluar(policy, "com.game.app", usageAntesMedianoche, LocalDateTime.parse("2026-06-14T23:59:00"), zonaHoraria)
        assertEquals(Decision.Bloquear("Has alcanzado el límite diario de esta app."), decisionAntes)

        val usageNuevoDia = UsageContext(usagePorApp = mapOf("com.game.app" to 0))
        val decisionNuevoDia = evaluar(policy, "com.game.app", usageNuevoDia, LocalDateTime.parse("2026-06-15T00:01:00"), zonaHoraria)
        assertEquals(Decision.Permitir, decisionNuevoDia)
    }

    // =============================================================================
    // HELPERS
    // =============================================================================
    private fun now(hour: Int, minute: Int): LocalDateTime {
        return LocalDateTime.of(2026, 6, 15, hour, minute)
    }

    private fun evaluar(
        policy: Policy,
        packageName: String,
        now: LocalDateTime,
        zonaHoraria: ZoneId
    ): Decision {
        return evaluar(policy, packageName, UsageContext.empty(), now, zonaHoraria)
    }

    private fun evaluar(
        policy: Policy,
        packageName: String,
        usage: UsageContext,
        now: LocalDateTime,
        zonaHoraria: ZoneId
    ): Decision {
        return com.tudominio.parentalcontrol.domain.evaluar(policy, packageName, usage, now, zonaHoraria)
    }

    private fun newPolicy(
        deviceState: DeviceState = DeviceState.ACTIVE,
        dailyScreenTimeMinutes: Int = 120,
        schedules: List<Schedule> = emptyList(),
        categoryLimits: List<CategoryLimit> = emptyList(),
        appPolicies: List<AppPolicy> = emptyList(),
        grants: List<Grant> = emptyList()
    ): Policy {
        return Policy(
            device_id = "test-device",
            version = 1,
            device_state = deviceState,
            daily_screen_time_minutes = dailyScreenTimeMinutes,
            schedules = schedules,
            category_limits = categoryLimits,
            app_policies = appPolicies,
            category_assignments = appPolicies.filter { it.category != null }.associate { it.package_name to it.category!! },
            grants = grants
        )
    }

    private fun validGrant(scope: String): Grant {
        return Grant(
            id = "grant-1",
            request_id = null,
            scope = scope,
            minutes = 30,
            source = GrantSource.EXTRA_TIME,
            granted_at = "2026-06-15T08:00:00Z",
            expires_at = "2026-06-15T23:00:00Z"
        )
    }

    private fun expiredGrant(scope: String): Grant {
        return Grant(
            id = "grant-1",
            request_id = null,
            scope = scope,
            minutes = 30,
            source = GrantSource.EXTRA_TIME,
            granted_at = "2026-06-15T08:00:00Z",
            expires_at = "2026-06-15T09:00:00Z"
        )
    }
}
