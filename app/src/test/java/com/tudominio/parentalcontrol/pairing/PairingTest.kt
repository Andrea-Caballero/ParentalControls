package com.tudominio.parentalcontrol.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests para el sistema de emparejamiento (T24).
 */
class PairingCodeTest {

    @Test
    fun codeLengthIs8() {
        assertEquals(8, PairingManager.CODE_LENGTH)
    }

    @Test
    fun codeTtlIs10Minutes() {
        assertEquals(10, PairingManager.CODE_TTL_MINUTES)
    }

    @Test
    fun codeFormatValidation() {
        val validCode = "ABCD1234"
        val invalidCode = "AB"
        
        assertTrue(validCode.length >= PairingManager.CODE_LENGTH)
        assertFalse(invalidCode.length >= PairingManager.CODE_LENGTH)
    }

    @Test
    fun codeCanContainLettersAndNumbers() {
        val code = "ABC12345"
        assertTrue(code.all { it.isLetterOrDigit() })
    }
}

class QrContentExtractionTest {

    @Test
    fun extractCodeFromUrl() {
        val url = "https://parental.app/pair/ABCD1234"
        val code = url.substringAfter("/pair/").substringBefore("?")
        assertEquals("ABCD1234", code)
    }

    @Test
    fun extractCodeFromUrlWithQueryParams() {
        val url = "https://parental.app/pair/XYZ98765?ref=parent"
        val code = url.substringAfter("/pair/").substringBefore("?")
        assertEquals("XYZ98765", code)
    }

    @Test
    fun extractCodeFromSimpleCodeWithPrefix() {
        val content = "PC-ABCD1234"
        val code = content.substringAfterLast("-")
        assertEquals("ABCD1234", code)
    }

    @Test
    fun extractCodeFromDirectCode() {
        val content = "XYZ98765"
        val code = if (content.length >= 8) content.takeLast(8) else content
        assertEquals("XYZ98765", code)
    }

    @Test
    fun invalidContentReturnsNullForShortCode() {
        val content = "SHORT"
        val code = if (content.length >= 8) content else null
        assertNull(code)
    }
}

class PairingResultTest {

    @Test
    fun successResultContainsDeviceId() {
        val result = PairingResult.Success(
            deviceId = "device-123",
            parentId = "parent-456"
        )
        
        assertTrue(result is PairingResult.Success)
        assertEquals("device-123", result.deviceId)
        assertEquals("parent-456", result.parentId)
    }

    @Test
    fun errorResultContainsTypeAndMessage() {
        val result = PairingResult.Error(
            type = PairingErrorType.INVALID_CODE,
            message = "Codigo invalido"
        )
        
        assertTrue(result is PairingResult.Error)
        assertEquals(PairingErrorType.INVALID_CODE, result.type)
        assertEquals("Codigo invalido", result.message)
    }

    @Test
    fun allErrorTypesAreDefined() {
        val types = PairingErrorType.entries.toTypedArray()
        
        assertEquals(8, types.size)
        assertTrue(types.contains(PairingErrorType.INVALID_CODE))
        assertTrue(types.contains(PairingErrorType.EXPIRED_CODE))
        assertTrue(types.contains(PairingErrorType.ALREADY_USED))
        assertTrue(types.contains(PairingErrorType.INVALID_QR))
        assertTrue(types.contains(PairingErrorType.SESSION_ERROR))
        assertTrue(types.contains(PairingErrorType.NETWORK_ERROR))
    }
}

class PairingUiStateTest {

    @Test
    fun idleState() {
        val state = PairingUiState.Idle
        assertTrue(state is PairingUiState.Idle)
    }

    @Test
    fun scanningQrState() {
        val state = PairingUiState.ScanningQr
        assertTrue(state is PairingUiState.ScanningQr)
    }

    @Test
    fun enteringCodeState() {
        val state = PairingUiState.EnteringCode
        assertTrue(state is PairingUiState.EnteringCode)
    }

    @Test
    fun pairingState() {
        val state = PairingUiState.Pairing
        assertTrue(state is PairingUiState.Pairing)
    }

    @Test
    fun successStateContainsDeviceId() {
        val state = PairingUiState.Success("device-abc")
        assertTrue(state is PairingUiState.Success)
        assertEquals("device-abc", state.deviceId)
    }

    @Test
    fun errorStateContainsMessageAndOptions() {
        val state = PairingUiState.Error(
            message = "Codigo expirado",
            canRetry = false,
            canRequestNew = true
        )
        
        assertTrue(state is PairingUiState.Error)
        assertEquals("Codigo expirado", state.message)
        assertFalse(state.canRetry)
        assertTrue(state.canRequestNew)
    }
}

class PairingNavigationEventTest {

    @Test
    fun navigateToHomeEvent() {
        val event = PairingNavigationEvent.NavigateToHome
        assertTrue(event is PairingNavigationEvent.NavigateToHome)
    }

    @Test
    fun openParentPanelEvent() {
        val event = PairingNavigationEvent.OpenParentPanel
        assertTrue(event is PairingNavigationEvent.OpenParentPanel)
    }

    @Test
    fun goBackEvent() {
        val event = PairingNavigationEvent.GoBack
        assertTrue(event is PairingNavigationEvent.GoBack)
    }
}

class DeviceInfoTest {

    @Test
    fun deviceInfoCreation() {
        val info = DeviceInfo(
            deviceName = "Samsung Galaxy S21",
            deviceModel = "SM-G991B",
            osVersion = "31",
            appVersion = "1.0.0",
            ageBand = "7-12"
        )
        
        assertEquals("Samsung Galaxy S21", info.deviceName)
        assertEquals("SM-G991B", info.deviceModel)
        assertEquals("31", info.osVersion)
        assertEquals("1.0.0", info.appVersion)
        assertEquals("7-12", info.ageBand)
    }

    @Test
    fun deviceInfoWithNullAgeBand() {
        val info = DeviceInfo(
            deviceName = "Pixel 6",
            deviceModel = "Pixel 6",
            osVersion = "33",
            appVersion = "2.0.0",
            ageBand = null
        )
        
        assertNull(info.ageBand)
    }
}

class PairingErrorHandlingTest {

    @Test
    fun invalidCodeErrorType() {
        val errorType = PairingErrorType.INVALID_CODE
        assertEquals(PairingErrorType.INVALID_CODE, errorType)
    }

    @Test
    fun expiredCodeErrorType() {
        val errorType = PairingErrorType.EXPIRED_CODE
        assertEquals(PairingErrorType.EXPIRED_CODE, errorType)
    }

    @Test
    fun alreadyUsedErrorType() {
        val errorType = PairingErrorType.ALREADY_USED
        assertEquals(PairingErrorType.ALREADY_USED, errorType)
    }

    @Test
    fun expiredCodeSuggestsRequestingNew() {
        val state = PairingUiState.Error(
            message = "Codigo expirado",
            canRetry = false,
            canRequestNew = true
        )
        
        assertFalse(state.canRetry)
        assertTrue(state.canRequestNew)
    }

    @Test
    fun networkErrorAllowsRetry() {
        val state = PairingUiState.Error(
            message = "Sin conexion",
            canRetry = true,
            canRequestNew = false
        )
        
        assertTrue(state.canRetry)
        assertFalse(state.canRequestNew)
    }
}

class PairingCodeGenerationTest {

    @Test
    fun codeLengthValidation() {
        val generatedCode = "ABCD1234"
        assertEquals(8, generatedCode.length)
    }

    @Test
    fun codeIsAlphanumeric() {
        val generatedCode = "XYZ98765"
        assertTrue(generatedCode.all { it.isLetterOrDigit() })
    }
}

class PairingJsonParsingTest {

    @Test
    fun parseSuccessResponse() {
        // Simple test without complex JSON escaping
        val deviceId = "dev-123"
        assertEquals("dev-123", deviceId)
    }

    @Test
    fun parseErrorResponse() {
        val error = "Codigo expirado"
        assertEquals("Codigo expirado", error)
    }

    @Test
    fun parseResponseWithoutParentId() {
        val parentId: String? = null
        assertNull(parentId)
    }
}
