package com.tudominio.parentalcontrol.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PolicyTest {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    @Test
    fun `round-trip Policy from §0 3 example`() {
        val policyJson = """
        {
            "device_id": "550e8400-e29b-41d4-a716-446655440000",
            "version": 42,
            "device_state": "ACTIVE",
            "daily_screen_time_minutes": 120,
            "schedules": [
                {
                    "id": "sched-1",
                    "days": ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"],
                    "from": "08:00",
                    "to": "15:00",
                    "action": "LOCK",
                    "allow_list": null
                },
                {
                    "id": "sched-2",
                    "days": ["SATURDAY", "SUNDAY"],
                    "from": "10:00",
                    "to": "20:00",
                    "action": "ALLOW_ONLY",
                    "allow_list": ["com.whatsapp", "com.instagram"]
                }
            ],
            "category_limits": [
                {"category": "games", "minutes": 60}
            ],
            "app_policies": [
                {
                    "package_name": "com.example.game",
                    "state": "LIMITED",
                    "daily_limit_minutes": 30,
                    "allowed_windows": [
                        {"days": ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"], "from": "16:00", "to": "18:00"},
                        {"days": ["SATURDAY", "SUNDAY"], "from": "10:00", "to": "20:00"}
                    ],
                    "category": "games"
                },
                {
                    "package_name": "com.example.blocked",
                    "state": "BLOCKED",
                    "daily_limit_minutes": null,
                    "allowed_windows": [],
                    "category": null
                }
            ],
            "category_assignments": {
                "com.example.game": "games"
            },
            "grants": [
                {
                    "id": "grant-1",
                    "request_id": "req-1",
                    "scope": "device",
                    "minutes": 30,
                    "source": "EXTRA_TIME",
                    "granted_at": "2026-06-13T10:00:00Z",
                    "expires_at": "2026-06-13T10:30:00Z"
                }
            ]
        }
        """.trimIndent()

        val policy = json.decodeFromString<Policy>(policyJson)

        assertEquals("550e8400-e29b-41d4-a716-446655440000", policy.device_id)
        assertEquals(42, policy.version)
        assertEquals(DeviceState.ACTIVE, policy.device_state)
        assertEquals(120, policy.daily_screen_time_minutes)
        assertEquals(2, policy.schedules.size)
        assertEquals(1, policy.category_limits.size)
        assertEquals(2, policy.app_policies.size)
        assertEquals(1, policy.grants.size)

        val reEncoded = json.encodeToString(policy)
        val reDecoded = json.decodeFromString<Policy>(reEncoded)

        assertEquals(policy.device_id, reDecoded.device_id)
        assertEquals(policy.version, reDecoded.version)
        assertEquals(policy.device_state, reDecoded.device_state)
        assertEquals(policy.daily_screen_time_minutes, reDecoded.daily_screen_time_minutes)
        assertEquals(policy.schedules.size, reDecoded.schedules.size)
        assertEquals(policy.category_limits.size, reDecoded.category_limits.size)
        assertEquals(policy.app_policies.size, reDecoded.app_policies.size)
        assertEquals(policy.grants.size, reDecoded.grants.size)
    }

    @Test
    fun `Policy rejects negative version`() {
        try {
            Policy(
                device_id = "test",
                version = -1,
                device_state = DeviceState.ACTIVE,
                daily_screen_time_minutes = 120,
                schedules = emptyList(),
                category_limits = emptyList(),
                app_policies = emptyList(),
                category_assignments = emptyMap(),
                grants = emptyList()
            )
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("version"))
        }
    }

    @Test
    fun `AppPolicy with LIMITED state requires daily_limit_minutes`() {
        try {
            AppPolicy(
                package_name = "com.example.app",
                state = AppPolicyState.LIMITED,
                daily_limit_minutes = null,
                allowed_windows = emptyList(),
                category = null
            )
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("daily_limit_minutes"))
        }
    }

    @Test
    fun `AppPolicy with LIMITED state accepts valid daily_limit_minutes`() {
        val appPolicy = AppPolicy(
            package_name = "com.example.app",
            state = AppPolicyState.LIMITED,
            daily_limit_minutes = 30,
            allowed_windows = emptyList(),
            category = null
        )
        assertEquals(30, appPolicy.daily_limit_minutes)
    }

    @Test
    fun `Schedule with ALLOW_ONLY requires non-empty allow_list`() {
        try {
            Schedule(
                id = "sched-1",
                days = listOf(DayOfWeek.MONDAY),
                from = "08:00",
                to = "15:00",
                action = ScheduleAction.ALLOW_ONLY,
                allow_list = emptyList()
            )
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("allow_list"))
        }
    }

    @Test
    fun `Schedule with ALLOW_ONLY accepts non-empty allow_list`() {
        val schedule = Schedule(
            id = "sched-1",
            days = listOf(DayOfWeek.MONDAY),
            from = "08:00",
            to = "15:00",
            action = ScheduleAction.ALLOW_ONLY,
            allow_list = listOf("com.whatsapp")
        )
        assertEquals("sched-1", schedule.id)
    }

    @Test
    fun `Schedule rejects invalid time format`() {
        try {
            Schedule(
                id = "sched-1",
                days = listOf(DayOfWeek.MONDAY),
                from = "25:00",
                to = "15:00",
                action = ScheduleAction.LOCK,
                allow_list = null
            )
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("HH:mm"))
        }
    }

    @Test
    fun `Grant rejects expires_at before granted_at`() {
        try {
            Grant(
                id = "grant-1",
                request_id = null,
                scope = "device",
                minutes = 30,
                source = GrantSource.EXTRA_TIME,
                granted_at = "2026-06-13T11:00:00Z",
                expires_at = "2026-06-13T10:00:00Z"
            )
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("expires_at"))
        }
    }

    @Test
    fun `Grant accepts valid timestamps`() {
        val grant = Grant(
            id = "grant-1",
            request_id = null,
            scope = "device",
            minutes = 30,
            source = GrantSource.EXTRA_TIME,
            granted_at = "2026-06-13T10:00:00Z",
            expires_at = "2026-06-13T10:30:00Z"
        )
        assertEquals("grant-1", grant.id)
    }

    @Test
    fun `Window rejects invalid time format`() {
        try {
            Window(
                days = listOf(DayOfWeek.MONDAY),
                from = "invalid",
                to = "15:00"
            )
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("HH:mm"))
        }
    }

    @Test
    fun `Window accepts midnight crossing from greater than to`() {
        val window = Window(
            days = listOf(DayOfWeek.MONDAY),
            from = "22:00",
            to = "06:00"
        )
        assertEquals("22:00", window.from)
    }

    @Test
    fun `CategoryLimit rejects negative minutes`() {
        try {
            CategoryLimit(
                category = "games",
                minutes = -1
            )
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("minutes"))
        }
    }

    @Test
    fun `Grant accepts LocalDateTime format timestamps`() {
        val grant = Grant(
            id = "grant-1",
            request_id = null,
            scope = "device",
            minutes = 30,
            source = GrantSource.MANUAL,
            granted_at = "2026-06-13T10:00:00",
            expires_at = "2026-06-13T10:30:00"
        )
        assertEquals("grant-1", grant.id)
    }
}
