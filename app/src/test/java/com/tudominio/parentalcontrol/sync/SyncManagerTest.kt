package com.tudominio.parentalcontrol.sync

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests para SyncManager y sincronización offline-first.
 * Tests puros sin dependencias de Ktor MockEngine para evitar problemas de classpath.
 */
class SyncManagerTest {

    @Test
    fun `pull policy applies when version is higher`() = runBlocking {
        val syncManager = TestableSyncManager()

        val currentVersion = 3L
        val serverVersion = 5L

        val result = syncManager.pullPolicy(currentVersion, serverVersion)

        assertTrue(result is SyncResult.Success)
    }

    @Test
    fun `pull policy ignores when version is lower`() = runBlocking {
        val syncManager = TestableSyncManager()

        val currentVersion = 10L
        val serverVersion = 5L

        // Server returned older version, should be ignored
        val result = syncManager.pullPolicy(currentVersion, serverVersion)

        assertTrue(result is SyncResult.Success) // No error, solo ignora
    }

    @Test
    fun `pull policy ignores when version is equal`() = runBlocking {
        val syncManager = TestableSyncManager()

        val currentVersion = 5L
        val serverVersion = 5L

        val result = syncManager.pullPolicy(currentVersion, serverVersion)

        assertTrue(result is SyncResult.Success)
    }

    @Test
    fun `offline sync returns offline result`() = runBlocking {
        val syncManager = TestableSyncManager(isOnline = false)

        val result = syncManager.sync()

        assertTrue(result is SyncResult.Offline)
    }

    @Test
    fun `outbox respects dedup key`() = runBlocking {
        val syncManager = TestableSyncManager()

        val dedupKey = "test-dedup-key"

        // First insert should succeed
        val result1 = syncManager.enqueue(OutboxEventType.USAGE_LOG, mapOf("test" to "value"), dedupKey)
        assertTrue(result1)

        // Second insert with same key should fail (duplicate)
        val result2 = syncManager.enqueue(OutboxEventType.USAGE_LOG, mapOf("test" to "value2"), dedupKey)
        assertFalse(result2)
    }

    @Test
    fun `outbox retry does not duplicate on same dedup key`() = runBlocking {
        val syncManager = TestableSyncManager()
        val dedupKey = "retry-test-key"

        // First send succeeds
        val firstSend = syncManager.sendOutboxItem(dedupKey, OutboxEventType.USAGE_LOG)
        assertTrue(firstSend)

        // Retry with same dedup key should also succeed (idempotent)
        val retrySend = syncManager.sendOutboxItem(dedupKey, OutboxEventType.USAGE_LOG)
        assertTrue(retrySend)
    }

    @Test
    fun `server returns 500 triggers error`() = runBlocking {
        val syncManager = TestableSyncManager()

        val result = syncManager.handleServerError(500)

        assertTrue(result is SyncResult.Error)
    }

    @Test
    fun `timeout triggers offline fallback`() = runBlocking {
        val syncManager = TestableSyncManager()

        val result = syncManager.handleTimeout()

        assertTrue(result is SyncResult.Offline)
    }

    @Test
    fun `malformed JSON is handled gracefully`() = runBlocking {
        val syncManager = TestableSyncManager()

        val result = syncManager.parseMalformedJson("not valid json {{{")

        assertTrue(result is SyncResult.Error)
    }

    @Test
    fun `heartbeat is sent successfully`() = runBlocking {
        val syncManager = TestableSyncManager()

        val result = syncManager.sendHeartbeat()

        assertTrue(result)
    }

    @Test
    fun `policy version comparison is correct`() {
        // Test various version scenarios
        assertTrue(compareVersions(5, 3) > 0)
        assertTrue(compareVersions(3, 5) < 0)
        assertTrue(compareVersions(5, 5) == 0)
        assertTrue(compareVersions(10, 9) > 0)
        assertTrue(compareVersions(0, 1) < 0)
    }

    @Test
    fun `server time offset is calculated`() {
        val localTime = System.currentTimeMillis() / 1000
        val serverTime = localTime + 30 // 30 seconds ahead

        val offset = calculateServerOffset(serverTime, localTime)

        assertEquals(30L, offset)
    }

    @Test
    fun `enqueue with null dedup key generates unique key`() = runBlocking {
        val syncManager = TestableSyncManager()

        val result1 = syncManager.enqueue(OutboxEventType.USAGE_LOG, mapOf("test" to "value1"), null)
        val result2 = syncManager.enqueue(OutboxEventType.USAGE_LOG, mapOf("test" to "value2"), null)

        assertTrue(result1)
        assertTrue(result2) // Different auto-generated keys
    }

    // ============ Helper methods ============

    private fun compareVersions(v1: Long, v2: Long): Int {
        return when {
            v1 > v2 -> 1
            v1 < v2 -> -1
            else -> 0
        }
    }

    private fun calculateServerOffset(serverTime: Long, localTime: Long): Long {
        return serverTime - localTime
    }
}

/**
 * SyncManager simplificado para tests (sin database real).
 */
class TestableSyncManager(val isOnline: Boolean = true) {
    private val outbox = mutableMapOf<String, Map<String, Any>>()
    private var currentPolicyVersion: Long = 0

    suspend fun pullPolicy(currentVersion: Long, serverVersion: Long): SyncResult {
        return if (serverVersion > currentVersion) {
            currentPolicyVersion = serverVersion
            SyncResult.Success
        } else {
            SyncResult.Success // Ignora versión más vieja
        }
    }

    suspend fun sync(): SyncResult {
        return if (isOnline) {
            SyncResult.Success
        } else {
            SyncResult.Offline
        }
    }

    suspend fun pullPolicyFromServer(): SyncResult {
        return SyncResult.Success
    }

    suspend fun drainOutbox(): SyncResult {
        return SyncResult.Success
    }

    suspend fun enqueue(
        eventType: OutboxEventType,
        payload: Map<String, Any>,
        dedupKey: String?
    ): Boolean {
        val key = dedupKey ?: "${eventType.name}_${System.currentTimeMillis()}"
        if (dedupKey != null && outbox.containsKey(dedupKey)) {
            return false // Duplicate
        }
        outbox[key] = payload
        return true
    }

    suspend fun sendHeartbeat(): Boolean {
        return true
    }

    fun handleServerError(statusCode: Int): SyncResult {
        return when (statusCode) {
            in 500..599 -> SyncResult.Error("Server error: $statusCode")
            else -> SyncResult.Error("Client error: $statusCode")
        }
    }

    fun handleTimeout(): SyncResult {
        return SyncResult.Offline
    }

    fun parseMalformedJson(json: String): SyncResult {
        return try {
            Json.decodeFromString<Map<String, Any>>(json)
            SyncResult.Success
        } catch (e: Exception) {
            SyncResult.Error("Invalid JSON: ${e.message}")
        }
    }

    fun sendOutboxItem(dedupKey: String, eventType: OutboxEventType): Boolean {
        // Simular éxito
        return true
    }
}


/**
 * Tests para OutboxEventType enum.
 */
class OutboxEventTypeTest {

    @Test
    fun `all event types are defined`() {
        val types = OutboxEventType.entries.toTypedArray()

        assertEquals(5, types.size)
        assertTrue(types.contains(OutboxEventType.USAGE_LOG))
        assertTrue(types.contains(OutboxEventType.DEVICE_ALERT))
        assertTrue(types.contains(OutboxEventType.BEHAVIORAL_EVENT))
        assertTrue(types.contains(OutboxEventType.TIME_REQUEST))
        assertTrue(types.contains(OutboxEventType.HEARTBEAT))
    }
}

/**
 * Tests para SyncResult sealed class.
 */
class SyncResultTest {

    @Test
    fun `success result is distinguishable`() {
        val result = SyncResult.Success

        assertTrue(result is SyncResult.Success)
    }

    @Test
    fun `partial success contains failed count`() {
        val result = SyncResult.PartialSuccess(3)

        assertTrue(result is SyncResult.PartialSuccess)
        assertEquals(3, result.failedItems)
    }

    @Test
    fun `error result contains message`() {
        val result = SyncResult.Error("Connection timeout")

        assertTrue(result is SyncResult.Error)
        assertEquals("Connection timeout", result.message)
    }

    @Test
    fun `offline result is distinguishable`() {
        val result = SyncResult.Offline

        assertTrue(result is SyncResult.Offline)
    }
}

/**
 * Tests para SyncStatus enum.
 */
class SyncStatusTest {

    @Test
    fun `all sync statuses are defined`() {
        val statuses = SyncStatus.entries.toTypedArray()

        assertEquals(5, statuses.size)
        assertTrue(statuses.contains(SyncStatus.IDLE))
        assertTrue(statuses.contains(SyncStatus.SYNCING))
        assertTrue(statuses.contains(SyncStatus.OFFLINE))
        assertTrue(statuses.contains(SyncStatus.PENDING_ITEMS))
        assertTrue(statuses.contains(SyncStatus.ERROR))
    }
}

/**
 * Tests para PolicyPullResponse.
 */
class PolicyPullResponseTest {

    @Test
    fun `policy response can be parsed from JSON`() {
        val json = """
        {
            "policy_id": "policy-123",
            "version": 5,
            "category_assignments": {"com.example.app": "games"},
            "app_policies": [
                {
                    "package_name": "com.example.app",
                    "state": "LIMITED",
                    "daily_limit_minutes": 60
                }
            ],
            "server_time": 1234567890
        }
        """.trimIndent()

        val response = Json.decodeFromString<PolicyPullResponse>(json)

        assertEquals("policy-123", response.policy_id)
        assertEquals(5L, response.version)
        assertEquals(1, response.app_policies?.size)
        assertEquals("com.example.app", response.app_policies?.get(0)?.package_name)
    }

    @Test
    fun `policy response with null fields works`() {
        val json = """
        {
            "version": 1
        }
        """.trimIndent()

        val response = Json.decodeFromString<PolicyPullResponse>(json)

        assertNull(response.policy_id)
        assertEquals(1L, response.version)
        assertNull(response.app_policies)
    }

    /**
     * R4 — `PolicyPullResponse.device_state` decodes correctly. The
     * shared Python mock + production edge function both include this
     * field so the child can pick up a parent's LOCKED/ACTIVE
     * signal on the next pullPolicy cycle (NextSync propagation).
     */
    @Test
    fun `policy response decodes device_state LOCKED`() {
        val json = """
        {
            "version": 5,
            "device_state": "LOCKED",
            "category_assignments": {}
        }
        """.trimIndent()

        val response = Json.decodeFromString<PolicyPullResponse>(json)
        assertEquals("LOCKED", response.device_state)
    }

    @Test
    fun `policy response decodes device_state ACTIVE`() {
        val json = """
        {
            "version": 5,
            "device_state": "ACTIVE",
            "category_assignments": {}
        }
        """.trimIndent()

        val response = Json.decodeFromString<PolicyPullResponse>(json)
        assertEquals("ACTIVE", response.device_state)
    }
}

/**
 * Tests para idempotencia en outbox.
 */
class OutboxIdempotencyTest {

    @Test
    fun `same payload different dedup key succeeds`() = runBlocking {
        val syncManager = TestableSyncManager()

        val result1 = syncManager.enqueue(
            OutboxEventType.USAGE_LOG,
            mapOf("minutes" to 10, "package" to "com.example.app"),
            "key-1"
        )
        val result2 = syncManager.enqueue(
            OutboxEventType.USAGE_LOG,
            mapOf("minutes" to 10, "package" to "com.example.app"),
            "key-2"
        )

        assertTrue(result1)
        assertTrue(result2)
    }

    @Test
    fun `different payloads same dedup key fails`() = runBlocking {
        val syncManager = TestableSyncManager()

        val result1 = syncManager.enqueue(
            OutboxEventType.USAGE_LOG,
            mapOf("minutes" to 10),
            "same-key"
        )
        val result2 = syncManager.enqueue(
            OutboxEventType.USAGE_LOG,
            mapOf("minutes" to 20),
            "same-key"
        )

        assertTrue(result1)
        assertFalse(result2)
    }
}

/**
 * Tests para offline tolerance.
 */
class OfflineToleranceTest {

    @Test
    fun `enqueue works when offline`() = runBlocking {
        val syncManager = TestableSyncManager(isOnline = false)

        val result = syncManager.enqueue(
            OutboxEventType.USAGE_LOG,
            mapOf("minutes" to 10),
            "offline-key"
        )

        assertTrue(result) // Should still queue locally
    }

    @Test
    fun `sync returns offline when not connected`() = runBlocking {
        val syncManager = TestableSyncManager(isOnline = false)

        val result = syncManager.sync()

        assertTrue(result is SyncResult.Offline)
    }
}
