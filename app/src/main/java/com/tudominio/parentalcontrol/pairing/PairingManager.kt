package com.tudominio.parentalcontrol.pairing

import android.content.Context
import android.os.Build
import android.util.Log
import com.tudominio.parentalcontrol.auth.AuthResult
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.network.SupabaseClientProvider
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Manager para el emparejamiento de dispositivos.
 * 
 * §0.5: Emparejamiento vía tabla pairing_codes
 * §0.9: La sesión se guarda tras emparejar exitosamente
 */
class PairingManager private constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "PairingManager"
        
        // Longitud del código de emparejamiento
        const val CODE_LENGTH = 8
        
        // Timeout del código en minutos
        const val CODE_TTL_MINUTES = 10

        @Volatile
        private var instance: PairingManager? = null

        fun getInstance(context: Context): PairingManager {
            return instance ?: synchronized(this) {
                instance ?: PairingManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val authManager = DeviceAuthManager.getInstance(context)
    private val clientProvider = SupabaseClientProvider.getInstance(context)

    /**
     * Visible for testing: lets the test inject a stub [DeviceInfo] provider
     * to avoid the JVM `Build.MODEL` null issue without touching production
     * behavior. Default uses [getDeviceInfo].
     */
    @JvmField
    var deviceInfoProvider: () -> DeviceInfo = ::getDeviceInfo

    /**
     * Empareja el dispositivo con el código proporcionado.
     *
     * Posts to `${SUPABASE_URL}/functions/v1/pairing` with the bearer token +
     * apikey header and the JSON body built by [buildPairingRequest]. The
     * server returns `{ device_id, parent_id }` on success; the existing
     * [parsePairingResponse] handles 200/404/409/410/etc.
     *
     * @param code Código de emparejamiento (QR o manual)
     * @return Resultado del emparejamiento
     */
    suspend fun pairWithCode(code: String): PairingResult {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Intentando emparejar con código: ${code.take(4)}...")

            var token = authManager.getAccessToken()
            if (token == null) {
                token = when (val authResult = authManager.authenticateOrCreate()) {
                    is AuthResult.Success -> authResult.accessToken
                    is AuthResult.Error,
                    is AuthResult.NeedsPairing -> return@withContext PairingResult.Error(
                        PairingErrorType.NETWORK_ERROR,
                        "Error de conexión. Verifica tu conexión a internet."
                    )
                }
            }

            try {
                val deviceInfo = deviceInfoProvider()
                val requestBody = buildPairingRequest(code, deviceInfo)
                val response = clientProvider.httpClient.post(
                    "${SupabaseClientProvider.SUPABASE_URL}/functions/v1/pairing"
                ) {
                    header("Authorization", "Bearer $token")
                    header("apikey", SupabaseClientProvider.SUPABASE_ANON_KEY)
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }
                parsePairingResponse(response.status.value, response.bodyAsText())
            } catch (e: Exception) {
                Log.e(TAG, "Error en emparejamiento: ${e.message}")
                PairingResult.Error(
                    PairingErrorType.NETWORK_ERROR,
                    e.message ?: "Error de red"
                )
            }
        }
    }

    /**
     * Empareja el dispositivo usando el contenido de un QR.
     * Extrae el código del payload del QR.
     * 
     * @param qrContent Contenido del código QR
     * @return Resultado del emparejamiento
     */
    suspend fun pairWithQr(qrContent: String): PairingResult {
        Log.d(TAG, "Procesando QR content")
        
        // El QR puede contener:
        // 1. Solo el código (ej: "PC-ABCD1234")
        // 2. URL con código (ej: "https://app.com/pair/ABCD1234")
        // 3. JSON con código
        
        val code = extractCodeFromQr(qrContent)
        
        if (code == null) {
            return PairingResult.Error(
                PairingErrorType.INVALID_QR,
                "Código QR no reconocido"
            )
        }
        
        return pairWithCode(code)
    }

    /**
     * Extrae el código del contenido del QR.
     */
    private fun extractCodeFromQr(content: String): String? {
        return when {
            // URL: extraer de path
            content.startsWith("http://") || content.startsWith("https://") -> {
                val path = content.substringAfter("/pair/").substringBefore("?")
                if (path.length >= CODE_LENGTH) path else null
            }
            
            // Código directo (puede tener prefijo como "PC-")
            content.contains("-") -> {
                content.substringAfterLast("-")
            }
            
            // Código directo de longitud correcta
            content.length >= CODE_LENGTH -> {
                content.takeLast(CODE_LENGTH)
            }
            
            else -> null
        }
    }

    /**
     * Obtiene información del dispositivo para el emparejamiento.
     */
    private fun getDeviceInfo(): DeviceInfo {
        return DeviceInfo(
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
            deviceModel = Build.MODEL,
            osVersion = Build.VERSION.SDK_INT.toString(),
            appVersion = getAppVersion(),
            ageBand = null // El padre selecciona la banda de edad desde el panel
        )
    }

    /**
     * Obtiene la versión de la app.
     */
    private fun getAppVersion(): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    /**
     * Construye el body del request de emparejamiento.
     */
    private fun buildPairingRequest(code: String, deviceInfo: DeviceInfo): String {
        return buildString {
            append("{")
            append("\"code\":\"$code\",")
            append("\"device_name\":\"${deviceInfo.deviceName}\",")
            append("\"device_model\":\"${deviceInfo.deviceModel}\",")
            append("\"os_version\":\"${deviceInfo.osVersion}\",")
            append("\"app_version\":\"${deviceInfo.appVersion}\"")
            deviceInfo.ageBand?.let {
                append(",\"age_band\":\"$it\"")
            }
            append("}")
        }
    }

    /**
     * Parsea la respuesta del servidor de emparejamiento.
     */
    private suspend fun parsePairingResponse(statusCode: Int, responseBody: String): PairingResult {
        return when (statusCode) {
            200, 201 -> {
                // Éxito
                val resp = PairingResponseParser.parseSuccess(responseBody)

                if (resp != null) {
                    // Guardar la sesión emparejada
                    authManager.savePairedSession(resp.device_id, resp.parent_id)

                    Log.d(TAG, "Emparejamiento exitoso: deviceId=${resp.device_id}")
                    PairingResult.Success(resp.device_id, resp.parent_id)
                } else {
                    PairingResult.Error(
                        PairingErrorType.INVALID_RESPONSE,
                        "Respuesta del servidor inválida"
                    )
                }
            }

            404 -> {
                Log.w(TAG, "Código inválido o no encontrado")
                PairingResult.Error(
                    PairingErrorType.INVALID_CODE,
                    "El código no es válido"
                )
            }

            410 -> {
                Log.w(TAG, "Código expirado")
                PairingResult.Error(
                    PairingErrorType.EXPIRED_CODE,
                    "El código ha expirado. Solicita uno nuevo desde el panel parental."
                )
            }

            409 -> {
                Log.w(TAG, "Código ya usado")
                PairingResult.Error(
                    PairingErrorType.ALREADY_USED,
                    "Este código ya fue utilizado"
                )
            }

            else -> {
                val errorMessage = PairingResponseParser.parseError(responseBody)
                Log.e(TAG, "Error de emparejamiento: $statusCode - $errorMessage")
                PairingResult.Error(
                    PairingErrorType.SERVER_ERROR,
                    errorMessage ?: "Error del servidor"
                )
            }
        }
    }
}

/**
 * Wire-shape DTOs for the `POST /functions/v1/pairing` endpoint.
 *
 * Mirrors the production Supabase edge-function response. Lives here
 * (not in `data.repository`) because the pairing flow has its own
 * transport-shape contract distinct from the parent-side DTOs.
 *
 * SUGGESTION #2 of `verify-report.md`: replaces the three hand-rolled
 * regex extractors that used to live in `PairingManager.parsePairingResponse`.
 * kotlinx-serialization gives us typed field access and free pretty-vs-compact
 * tolerance — no more `\s*:\s*` in regex strings, no more silent misses on
 * unexpected whitespace.
 */
@Serializable
data class PairingResponse(
    val device_id: String,
    val parent_id: String? = null
)

/**
 * Wire shape for the error body returned alongside non-2xx responses
 * (the `else` branch in `parsePairingResponse`). The Supabase edge
 * function returns `{ "error": "<human-readable message>" }`.
 */
@Serializable
data class PairingErrorBody(
    val error: String
)

/**
 * Pure-function parser that turns the raw response body string into
 * a typed DTO. Kept as an internal `object` so unit tests can exercise
 * it without spinning up the full `PairingManager` (which needs Hilt,
 * `Context`, `DeviceAuthManager`, and `SupabaseClientProvider`).
 *
 * Returns `null` instead of throwing on malformed input so callers can
 * distinguish "valid JSON but wrong shape" (e.g. a 200 with a body the
 * function doesn't recognize → `INVALID_RESPONSE`) from "valid shape"
 * (the happy path).
 *
 * The lenient config (`ignoreUnknownKeys`, `isLenient`) means a real
 * Supabase response with extra fields (e.g. `request_id`, `created_at`)
 * won't break the parser, and the parser tolerates JSON that doesn't
 * strictly conform to RFC 8259 (e.g. unquoted control chars).
 */
internal object PairingResponseParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parseSuccess(body: String): PairingResponse? = try {
        json.decodeFromString<PairingResponse>(body)
    } catch (e: Exception) {
        null
    }

    fun parseError(body: String): String? = try {
        json.decodeFromString<PairingErrorBody>(body).error
    } catch (e: Exception) {
        null
    }
}

/**
 * Información del dispositivo para emparejamiento.
 */
data class DeviceInfo(
    val deviceName: String,
    val deviceModel: String,
    val osVersion: String,
    val appVersion: String,
    val ageBand: String?
)

/**
 * Resultado del emparejamiento.
 */
sealed class PairingResult {
    data class Success(
        val deviceId: String,
        val parentId: String?
    ) : PairingResult()
    
    data class Error(
        val type: PairingErrorType,
        val message: String
    ) : PairingResult()
}

/**
 * Tipos de error de emparejamiento.
 */
enum class PairingErrorType {
    /** El código no es válido */
    INVALID_CODE,
    
    /** El código ha expirado */
    EXPIRED_CODE,
    
    /** El código ya fue usado */
    ALREADY_USED,
    
    /** El QR no contiene un código reconocido */
    INVALID_QR,
    
    /** Error de sesión */
    SESSION_ERROR,
    
    /** Error de red */
    NETWORK_ERROR,
    
    /** Respuesta del servidor inválida */
    INVALID_RESPONSE,
    
    /** Error genérico del servidor */
    SERVER_ERROR
}
