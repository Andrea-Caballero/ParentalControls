package com.tudominio.parentalcontrol.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.data.model.TimeRequestEntity
import com.tudominio.parentalcontrol.data.repository.TimeExtraRepository
import com.tudominio.parentalcontrol.outbox.OutboxManager
import com.tudominio.parentalcontrol.time.FakeTimeProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
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
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import javax.inject.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SyncManagerTrustedTimeTest {
    @Test
    fun `sync approval persists one readable grant through Room`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, ParentalDatabase::class.java)
            .allowMainThreadQueries().build()
        val provider = FakeTimeProvider(fakeWallMillis = 1_000_000L)
        val auth = mockk<DeviceAuthManager>()
        every { auth.isPaired() } returns true
        every { auth.getAccessToken() } returns "token"
        every { auth.deviceId } returns kotlinx.coroutines.flow.MutableStateFlow("device-1")
        mockkObject(DeviceAuthManager.Companion)
        every { DeviceAuthManager.getInstance(any()) } returns auth
        var client: HttpClient? = null
        try {
            database.timeRequestDao().insertRequest(
                TimeRequestEntity(
                    request_id = "request-1", device_id = "device-1", package_name = "app",
                    minutes_requested = 15, reason = null, status = "PENDING",
                    created_at = "2026-07-29T12:00:00Z", responded_at = null,
                    parent_response = null, server_id = "server-1"
                )
            )
            client = HttpClient(MockEngine { request ->
                if (request.url.encodedPath.endsWith("get-policy")) {
                    respond("{\"version\":1,\"server_time\":100}", HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"))
                } else {
                    respond("[{\"id\":\"server-1\",\"minutes_approved\":15,\"resolved_at\":\"2026-07-29T12:00:00Z\"}]", HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"))
                }
            })
            val repository = TimeExtraRepository(context, database, mockk<OutboxManager>(), provider)
            val manager = SyncManager(context, client, database, provider, Provider { repository })
            manager.sync()
            val firstGrant = database.grantDao()
                .getGrantsForDeviceFlow("device-1").first().single()
            assertEquals(15, firstGrant.minutes)
            assertEquals("extra_time", firstGrant.source)
            assertEquals("request-1", firstGrant.request_id)
            assertEquals("2026-07-29T12:15:00.000Z", firstGrant.expires_at)
            manager.sync()
            assertEquals(1, database.grantDao().getGrantsForDeviceFlow("device-1").first().size)
        } finally {
            client?.close()
            unmockkObject(DeviceAuthManager.Companion)
            database.close()
        }
    }

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
