package com.tudominio.parentalcontrol.pairing

import android.content.Context
import androidx.lifecycle.SavedStateHandle
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * RED→GREEN — selected behavior (Option B of the manual pairing-code
 * validation decision): preserve what the user typed, show a truthful
 * inline format error, block the UI button, and enforce the same
 * contract in `pairWithManualCode` so IME / programmatic / restored-state
 * submission cannot bypass it.
 *
 * Server contract: `^[A-HJ-NP-Z2-9]{8}$` (uppercase; excludes I, O, 0, 1).
 * The shared [PairingManager.isValidManualCode] helper is the single
 * source of truth for that boundary on the client side.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PairingViewModelManualCodeFormatTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var mockClient: HttpClient
    private val networkCalls = AtomicInteger(0)

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        resetPairingManagerSingleton()

        // Mock the singletons PairingManager.getInstance() consults so
        // we can count (and assert against) the network-call surface.
        networkCalls.set(0)
        mockClient = HttpClient(
            MockEngine { _ ->
                networkCalls.incrementAndGet()
                respond(
                    content = ByteReadChannel("""{"device_id":"x","parent_id":"y"}"""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        val mockAuthManager = mockk<DeviceAuthManager>()
        every { mockAuthManager.getAccessToken() } returns "test-jwt-token"
        coEvery { mockAuthManager.savePairedSession(any(), any()) } returns Unit

        val mockClientProvider = mockk<SupabaseClientProvider>()
        every { mockClientProvider.httpClient } returns mockClient

        mockkObject(DeviceAuthManager.Companion)
        every { DeviceAuthManager.getInstance(any()) } returns mockAuthManager
        mockkObject(SupabaseClientProvider.Companion)
        every { SupabaseClientProvider.getInstance(any()) } returns mockClientProvider
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        resetPairingManagerSingleton()
        mockClient.close()
        unmockkObject(DeviceAuthManager.Companion)
        unmockkObject(SupabaseClientProvider.Companion)
    }

    /**
     * Defensive isolation: [PairingViewModel]'s `init {}` writes
     * `childFirstNameProvider` onto the process-wide [PairingManager]
     * singleton. Without teardown, a leak from this class could
     * contaminate [PairingManagerTest]. Mirrors the strategy used in
     * [PairingViewModelChildNameLengthTest].
     */
    private fun resetPairingManagerSingleton() {
        PairingManager.getInstance(context).childFirstNameProvider = { null }
        val instanceField = PairingManager::class.java.getDeclaredField("instance")
        instanceField.isAccessible = true
        instanceField.set(null, null)
    }

    private fun newVm(
        savedState: SavedStateHandle = SavedStateHandle()
    ): PairingViewModel = PairingViewModel(context, savedState)

    // -------------------- Validator truth table --------------------

    @Test
    fun `validator matches the can create pairing code alphabet exactly`() {
        // Valid: each letter range (A-H, J-N, P-Z) and the digit range 2-9.
        assertTrue(PairingManager.isValidManualCode("ABCDEFGH"))
        assertTrue(PairingManager.isValidManualCode("JKLMNPQR"))
        assertTrue(PairingManager.isValidManualCode("ZYXWVUTS"))
        assertTrue(PairingManager.isValidManualCode("23456789"))
        assertTrue(PairingManager.isValidManualCode("ABCD2345"))

        // Invalid: each excluded character must be rejected at full length.
        assertFalse(PairingManager.isValidManualCode("ABCD1234")) // '1'
        assertFalse(PairingManager.isValidManualCode("ABCDEFG0")) // '0'
        assertFalse(PairingManager.isValidManualCode("IBCDEFGH")) // 'I'
        assertFalse(PairingManager.isValidManualCode("OBCDEFGH")) // 'O'

        // Invalid: wrong length and Unicode.
        assertFalse(PairingManager.isValidManualCode(""))
        assertFalse(PairingManager.isValidManualCode("ABCD234"))
        assertFalse(PairingManager.isValidManualCode("ABCD23456"))
        assertFalse(PairingManager.isValidManualCode("ÁBCD2345")) // 'Á' is not ASCII
    }

    // -------------------- ViewModel-level tests --------------------

    @Test
    fun `valid lowercase input is normalized and accepted by shared validator`() {
        val vm = newVm()
        vm.updateChildFirstName("Lucía")

        vm.updateManualCode("abcd2345")

        assertEquals("ABCD2345", vm.manualCode.value)
        assertTrue(PairingManager.isValidManualCode(vm.manualCode.value))
    }

    @Test
    fun `pairWithManualCode rejects full-length codes containing excluded character or Unicode`() {
        val vm = newVm()
        vm.updateChildFirstName("Lucía")

        // Full-length, but contains '1' → invalid by server contract.
        vm.updateManualCode("ABCD1234")
        vm.pairWithManualCode()
        val stateAfterDigit = vm.uiState.value
        assertTrue("Expected Error, got $stateAfterDigit", stateAfterDigit is PairingUiState.Error)
        val errorDigit = stateAfterDigit as PairingUiState.Error
        assertTrue(
            "Error message must name the excluded characters, got='${errorDigit.message}'",
            "I, O, 0, 1" in errorDigit.message
        )

        // 8 chars but contains 'Á' (Unicode) — rejected by the ASCII-only regex.
        vm.updateManualCode("ÁBCD2345")
        vm.pairWithManualCode()
        val stateAfterUnicode = vm.uiState.value
        assertTrue("Expected Error, got $stateAfterUnicode", stateAfterUnicode is PairingUiState.Error)
        assertTrue(
            "Unicode code must be rejected with the format error, got='${(stateAfterUnicode as PairingUiState.Error).message}'",
            "Código no válido" in stateAfterUnicode.message
        )
    }

    @Test
    fun `preloaded invalid code restored from SavedStateHandle is rejected by pairWithManualCode`() {
        // Mirrors the `PairingViewModelChildNameLengthTest` pre-fix
        // restored-state bypass pattern: a value persisted by a
        // pre-fix (or hostile) source can be rehydrated on cold start.
        // The ViewModel must still reject the invalid code via the
        // shared validator — the IME/deeplink/restored state paths
        // must not bypass the format gate.
        val restoredHandle = SavedStateHandle(
            mapOf(PairingViewModel.KEY_MANUAL_CODE to "ABCD1234")
        )
        val vm = newVm(savedState = restoredHandle)
        vm.updateChildFirstName("Lucía")

        vm.pairWithManualCode()

        val state = vm.uiState.value
        assertTrue("Expected Error, got $state", state is PairingUiState.Error)
        assertTrue(
            "Restored invalid code must be rejected with the format error, got='${(state as PairingUiState.Error).message}'",
            "Código no válido" in state.message
        )
    }

    @Test
    fun `pairWithManualCode does not call network when format is invalid`() {
        val vm = newVm()
        vm.updateChildFirstName("Lucía")
        vm.updateManualCode("ABCD1234") // invalid

        vm.pairWithManualCode()

        // The format check is synchronous and runs BEFORE the launch,
        // so the network call counter must be exactly 0. If the
        // format check were bypassed, the coroutine would dispatch
        // through `withContext(Dispatchers.IO)` and the MockEngine
        // would increment the counter.
        assertEquals(
            "Network must not be called when format is invalid — the regex gate must run before the launch.",
            0, networkCalls.get()
        )

        // Belt-and-suspenders: also assert the UI state matches the
        // format error so a regression that drops the uiState update
        // is caught too.
        val state = vm.uiState.value
        assertTrue("Expected Error, got $state", state is PairingUiState.Error)
    }

    @Test
    fun `pairWithManualCode short code error is unchanged by the format fix`() {
        // Regression check: the existing length-specific error
        // (preserved verbatim from the previous PR) must still fire
        // for short input. The format check runs AFTER the length
        // check, so a 4-char code never reaches the regex.
        val vm = newVm()
        vm.updateChildFirstName("Lucía")
        vm.updateManualCode("ABCD") // 4 chars, short

        vm.pairWithManualCode()

        val state = vm.uiState.value
        assertTrue("Expected Error, got $state", state is PairingUiState.Error)
        val error = state as PairingUiState.Error
        assertTrue(
            "Short code must still produce the length-specific error, got='${error.message}'",
            "debe tener" in error.message
        )
        assertFalse(
            "Short code must not produce the format error (length check runs first), got='${error.message}'",
            "Código no válido" in error.message
        )
    }
}
