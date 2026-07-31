package com.tudominio.parentalcontrol.sync

import com.tudominio.parentalcontrol.time.FakeTimeProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SyncManagerTrustedTimeTest {
    @Test
    fun `successful authenticated policy confirms server time`() = runBlocking {
        val provider = FakeTimeProvider()
        val client = mockClient("{\"version\":1,\"server_time\":100}")
        val confirmed = fetchAndConfirm(client, provider)
        assertTrue(confirmed)
        assertEquals(Instant.ofEpochSecond(100), provider.trustedNow())
    }

    @Test
    fun `missing server time remains unavailable`() = runBlocking {
        val provider = FakeTimeProvider()
        val client = mockClient("{\"version\":1}")
        val confirmed = fetchAndConfirm(client, provider)
        assertTrue(!confirmed)
        assertNull(provider.trustedNow())
    }

    @Test
    fun `malformed policy remains fail closed`() = runBlocking {
        val provider = FakeTimeProvider()
        val client = mockClient("not-json")
        val confirmed = fetchAndConfirm(client, provider)
        assertTrue(!confirmed)
        assertNull(provider.trustedNow())
    }

    private suspend fun fetchAndConfirm(
        client: HttpClient,
        provider: FakeTimeProvider
    ): Boolean {
        val response = client.get("https://example.test/functions/v1/get-policy") {
            header(HttpHeaders.Authorization, "Bearer authenticated-token")
        }
        if (!response.status.isSuccess()) return false
        return try {
            val policy = Json.decodeFromString<PolicyPullResponse>(response.bodyAsText())
            confirmTrustedTime(policy, provider)
        } catch (_: Exception) {
            false
        }
    }

    private fun mockClient(body: String): HttpClient = HttpClient(
        MockEngine { request ->
            assertEquals("Bearer authenticated-token", request.headers[HttpHeaders.Authorization])
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
    )
}
