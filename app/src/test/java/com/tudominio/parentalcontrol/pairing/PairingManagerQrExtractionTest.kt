package com.tudominio.parentalcontrol.pairing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.network.SupabaseClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * Focused extraction tests for [PairingManager.pairWithQr] (slice B of
 * the bounded QR pairing fix). Required proofs (per fix brief):
 *   a) real deeplink succeeds and reaches the mocked network once
 *   b) trailing query param still extracts the valid code and reaches
 *      network once
 *   c) invalid alphabet, short/long, missing/empty param, Unicode,
 *      and garbage return INVALID_QR with zero network calls
 *   d) one valid HTTP legacy (with and without trailing query) and
 *      one valid PC-/bare format remain accepted
 *
 * Table-driven for (c) — keeps the file compact vs. 12 duplicated
 * cases. Uses Robolectric to share the application-context singleton
 * pattern with `PairingViewModelManualCodeFormatTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PairingManagerQrExtractionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val networkCalls = AtomicInteger(0)
    private lateinit var mockClient: HttpClient

    @Before fun setUp() {
        resetSingleton(); networkCalls.set(0)
        mockClient = HttpClient(MockEngine { _ ->
            networkCalls.incrementAndGet()
            respond(
                ByteReadChannel("""{"device_id":"dev-1","parent_id":"par-1"}"""),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

        val mockAuth = mockk<DeviceAuthManager>()
        every { mockAuth.getAccessToken() } returns "test-jwt-token"
        coEvery { mockAuth.savePairedSession(any(), any()) } returns Unit
        val mockProvider = mockk<SupabaseClientProvider>()
        every { mockProvider.httpClient } returns mockClient
        mockkObject(DeviceAuthManager.Companion)
        every { DeviceAuthManager.getInstance(any()) } returns mockAuth
        mockkObject(SupabaseClientProvider.Companion)
        every { SupabaseClientProvider.getInstance(any()) } returns mockProvider
    }

    @After fun tearDown() {
        resetSingleton(); mockClient.close()
        unmockkObject(DeviceAuthManager.Companion)
        unmockkObject(SupabaseClientProvider.Companion)
    }

    private fun resetSingleton() {
        PairingManager.getInstance(context).childFirstNameProvider = { null }
        PairingManager::class.java.getDeclaredField("instance").apply {
            isAccessible = true; set(null, null)
        }
    }

    private fun newManager() = PairingManager.getInstance(context).also {
        it.deviceInfoProvider = { DeviceInfo("TestDevice", "TestModel", "33", "1.0.0", null) }
    }

    // ---------- Accepted paths ----------

    /** (a) Production wire format hits the network exactly once. */
    @Test fun `real deeplink with valid code reaches network once`() = runTest {
        val r = newManager().pairWithQr("parentalcontrol://pair?code=ABCDEFGH")
        assertTrue(r is PairingResult.Success); assertEquals(1, networkCalls.get())
    }

    /** (b) Trailing query params must not corrupt the extraction. */
    @Test fun `deeplink with trailing query param still extracts the valid code`() = runTest {
        val r = newManager().pairWithQr("parentalcontrol://pair?code=ABCDEFGH&ref=foo")
        assertTrue(r is PairingResult.Success); assertEquals(1, networkCalls.get())
    }

    /** (d-1) Legacy HTTP(S) `/pair/` URL remains accepted. */
    @Test fun `valid HTTP legacy url is still accepted`() = runTest {
        val r = newManager().pairWithQr("https://app.parentalcontrol.com/pair/ABCDEFGH")
        assertTrue(r is PairingResult.Success); assertEquals(1, networkCalls.get())
    }

    /**
     * (d-1b) Legacy HTTP(S) `/pair/` URL with a trailing query string
     * still extracts the valid code and reaches the network once.
     * Mirrors the production behavior of
     * `PairingManager.extractCodeFromQr`'s HTTP branch, which uses
     * `substringBefore("?")` to drop anything after the path.
     */
    @Test fun `valid HTTP legacy url with trailing query is still accepted`() = runTest {
        val r = newManager().pairWithQr("https://app.parentalcontrol.com/pair/ABCDEFGH?ref=foo")
        assertTrue(r is PairingResult.Success); assertEquals(1, networkCalls.get())
    }

    /** (d-2) Legacy bare code and `PC-` prefix remain accepted. */
    @Test fun `valid bare code and PC prefix are still accepted`() = runTest {
        val m = newManager()
        assertTrue(m.pairWithQr("ABCDEFGH") is PairingResult.Success)
        assertTrue(m.pairWithQr("PC-ABCDEFGH") is PairingResult.Success)
        assertEquals(2, networkCalls.get())
    }

    /**
     * (c) Out-of-format payloads return INVALID_QR with a safe
     * Spanish fallback AND do NOT advance the network counter.
     * Covers: invalid alphabet (1/0/I/O), short/long length,
     * missing/empty/no-`=` `code` query param, Unicode, garbage,
     * junk hyphen prefixes, raw JSON (`qr_data` base64/JSON is
     * intentionally NOT supported — production QR is rendered
     * from `deeplink`).
     */
    @Test fun `invalid QR values return INVALID_QR and never reach the network`() = runTest {
        val manager = newManager()
        val rejected = listOf(
            // Invalid alphabet (full length, excluded char):
            "parentalcontrol://pair?code=ABCD1234",  // '1'
            "parentalcontrol://pair?code=ABCDEFG0",  // '0'
            "parentalcontrol://pair?code=IBCDEFGH",  // 'I'
            "parentalcontrol://pair?code=OBCDEFGH",  // 'O'
            // Short / long:
            "parentalcontrol://pair?code=ABC234",    // 6
            "parentalcontrol://pair?code=ABCDEFG",   // 7
            "parentalcontrol://pair?code=ABCDEFGHABCDEFGH", // 16
            // Missing / empty / malformed `code` query param:
            "parentalcontrol://pair",
            "parentalcontrol://pair?",
            "parentalcontrol://pair?code=",
            "parentalcontrol://pair?code",
            "parentalcontrol://pair?ref=ABCDEFGH",
            // Unicode (rejected by ASCII-only server regex):
            "parentalcontrol://pair?code=ÁBCD2345",
            // Garbage / non-deeplink:
            "not-a-deeplink", "",
            // Raw JSON — production QR does NOT encode this field:
            """{"code":"ABCDEFGH"}""",
            // Pre-fix `takeLast(8)` failure mode (trailing-only garbage):
            "parentalcontrol://pair?code=&ref=ABCDEFGH",
            // Pre-fix bare-fallback failure mode: arbitrary leading
            // junk ending in a valid code. Pre-fix, `length >= 8` +
            // `takeLast(8)` returned `ABCDEFGH`; post-fix the bare
            // branch requires `length == CODE_LENGTH` and falls
            // through to INVALID_QR with zero network calls.
            "ZZABCDEFGH",
            // Pre-fix hyphen-branch failure mode: the old
            // `content.contains("-")` + `substringAfterLast("-")`
            // accepted any hyphen payload. Post-fix the branch is
            // restricted to an exact case-sensitive `PC-` prefix, so
            // arbitrary, nested, lowercase, or junk prefixes must
            // all fall through to INVALID_QR with zero network calls.
            "invoice-ABCDEFGH",
            "XX-ABCDEFGH",
            "EXTRA-PC-ABCDEFGH",
            "pc-ABCDEFGH",
        )
        for (payload in rejected) {
            val before = networkCalls.get()
            val r = manager.pairWithQr(payload)
            assertTrue("Expected Error for '$payload', got $r", r is PairingResult.Error)
            assertEquals(
                "Expected INVALID_QR for '$payload'",
                PairingErrorType.INVALID_QR, (r as PairingResult.Error).type,
            )
            assertEquals("Network must not advance for '$payload'", before, networkCalls.get())
        }
        assertEquals("Total network calls must be zero", 0, networkCalls.get())
    }
}