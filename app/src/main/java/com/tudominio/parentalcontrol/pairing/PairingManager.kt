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
import kotlinx.serialization.encodeToString
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

        // Mirrors the server regex in `supabase/functions/pairing/index.ts`
        // (A-H, J-N, P-Z, 2-9 — excludes I, O, 0, 1). Private — callers go
        // through [isValidManualCode] so the contract has a single entry
        // point and the regex itself is not leaked across the public API.
        private val CODE_FORMAT_REGEX = Regex("^[A-HJ-NP-Z2-9]{8}$")

        /**
         * Single source of truth for the 8-char alphanumeric pairing-code
         * format. Mirrors the server regex in
         * `supabase/functions/pairing/index.ts` (A-H, J-N, P-Z, 2-9 —
         * excludes I, O, 0, 1). The ViewModel and the UI both call this
         * so the gate on the manual "Pair" button and the gate inside
         * `pairWithManualCode` cannot diverge from the server contract.
         *
         * Pre-fix bug: the UI enabled the "Pair" button on `length >= 8`
         * and `pairWithManualCode` only checked length. A typed
         * `ABCD1234` (contains `1`) reached the wire and failed
         * server-side with HTTP 400 `INVALID_CODE_FORMAT`, leaking the
         * raw server token to the user. Post-fix: the same helper gates
         * both the UI button and the ViewModel entry point, so a
         * full-length invalid code never reaches the network.
         */
        fun isValidManualCode(code: String): Boolean = CODE_FORMAT_REGEX.matches(code)

        // Token emitted by `supabase/functions/pairing/index.ts` when the
        // user-submitted code does not match the `create-pairing-code`
        // generator alphabet. It is the ONLY 400 machine token that may
        // map to `PairingErrorType.INVALID_CODE`; any other 400 must not
        // blame the pairing code.
        private const val INVALID_CODE_FORMAT_TOKEN = "INVALID_CODE_FORMAT"

        // Safe, localized fallback message used when the 400 carries the
        // `INVALID_CODE_FORMAT` token. The raw server token is never
        // surfaced to the user.
        private const val INVALID_CODE_FORMAT_MESSAGE = "El código no es válido"

        // Safe, localized fallback message used when a 400 is NOT a code
        // format failure (e.g. `child_first_name` length, missing fields).
        // The message must NOT blame the pairing code and must NOT expose
        // raw server tokens.
        private const val NON_CODE_400_MESSAGE =
            "No se pudo emparejar el dispositivo. Verifica la información e intenta de nuevo."

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
     * Visible for testing: lets the test inject a stub child-name source so
     * the request body can carry `child_first_name` without depending on a
     * UX collection point that the pairing screen does not yet have.
     *
     * The pairing edge function (`supabase/functions/pairing/index.ts`)
     * now returns HTTP 400 when this is missing or empty. The default
     * returns `null` to match the current production screen (no input
     * field for the child's name) — this preserves the wire-shape
     * contract while the device-side capture flow lands in a follow-up.
     * Returning `null` here keeps the existing call sites and
     * `PairingManager.pairWithCode` signature unchanged.
     */
    @JvmField
    var childFirstNameProvider: () -> String? = { null }

    /**
     * Serializes the pairing request body.
     *
     * `encodeDefaults = true` is required so the `age_band` and
     * `child_first_name` properties — both declared with `= null`
     * defaults so the constructor can omit them — still appear in the
     * JSON when their value is `null`. kotlinx-serialization's default
     * `encodeDefaults = false` would drop those keys, leaving the
     * payload missing `child_first_name` when the device side has no
     * name to send. Keeping the key present (as `null`) preserves the
     * wire-shape contract with `supabase/functions/pairing/index.ts`,
     * which then performs the non-empty validation server-side.
     */
    private val requestJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

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
     *
     * Supported QR formats (priority order, see [extractCodeFromQr] for
     * the extraction rules):
     *   1. `parentalcontrol://pair?code=ABCDEFGH` — emitted by the
     *      parent-side QR generator
     *      (`supabase/functions/create-pairing-code`, `deeplink`
     *      field). The `code` query parameter is parsed explicitly
     *      so trailing query parameters cannot corrupt the
     *      extracted value (the pre-fix `takeLast(8)` returned the
     *      garbage suffix whenever the QR payload appended any
     *      extra params).
     *   2. Legacy `https://app.com/pair/ABCDEFGH` URLs.
     *   3. Legacy bare codes `ABCDEFGH` and `PC-ABCDEFGH` (exact
     *      length, no leading junk).
     *
     * Every extracted value is gated through [isValidManualCode]
     * BEFORE any auth or network work so a malformed QR (null,
     * missing/empty `code`, short/long length, excluded character,
     * Unicode, garbage) never reaches the wire. The user-facing
     * message is a localized Spanish fallback — raw server tokens
     * are never surfaced.
     *
     * @param qrContent Contenido del código QR
     * @return Resultado del emparejamiento
     */
    suspend fun pairWithQr(qrContent: String): PairingResult {
        Log.d(TAG, "Procesando QR content")

        val extracted = extractCodeFromQr(qrContent)

        // Gate every extracted QR code through the shared validator
        // so out-of-format input (null, empty, short, long, excluded
        // character, Unicode, garbage) returns INVALID_QR
        // synchronously, before `pairWithCode` would issue an auth
        // preflight or HTTP request. Mirrors the contract enforced
        // by the manual-code path
        // (`PairingViewModel.rejectInvalidFormat`).
        if (extracted == null || !isValidManualCode(extracted)) {
            return PairingResult.Error(
                PairingErrorType.INVALID_QR,
                "Código QR no reconocido"
            )
        }

        return pairWithCode(extracted)
    }

    /**
     * Extracts the 8-char pairing code from a QR payload, returning
     * the raw extracted string or `null` when no branch matched.
     *
     * Validation is NOT done here — every extracted value is routed
     * through [isValidManualCode] by [pairWithQr] so the manual and
     * QR paths share a single gate.
     *
     * Supported branches (priority order):
     *   1. `parentalcontrol://pair?code=XXXXXXXX` — emitted by the
     *      parent-side QR generator. The `code` query parameter is
     *      parsed explicitly via [extractQueryParameter] so a
     *      trailing `&ref=...` / `&state=...` cannot corrupt the
     *      extraction (the pre-fix `takeLast(8)` returned the
     *      garbage suffix in that case).
     *   2. Legacy `http(s)://...` URLs with a `/pair/XXXXXXXX`
     *      path. Query parameters are stripped by
     *      `substringBefore("?")` so the legacy extractor keeps
     *      working.
     *   3. Legacy `PC-XXXXXXXX` prefix.
     *   4. Legacy bare `XXXXXXXX` (length == [CODE_LENGTH]).
     *
     * Production wire format is documented in
     * `supabase/functions/create-pairing-code/index.ts` (the
     * `deeplink` field). The `qr_data` base64/JSON field on the
     * same response is intentionally NOT supported here — the
     * production QR is rendered from `deeplink`, not from
     * `qr_data`.
     */
    private fun extractCodeFromQr(content: String): String? {
        return when {
            // Production deeplink — see
            // `parentalcontrol://pair?code=` wire format in
            // `supabase/functions/create-pairing-code`. Parses the
            // `code` query parameter explicitly so a trailing
            // `&ref=...` cannot corrupt the extraction (pre-fix
            // `takeLast(8)` returned garbage whenever the QR payload
            // appended any extra params).
            content.startsWith("parentalcontrol://") -> {
                extractQueryParameter(content, "code")
            }

            // Legacy HTTP(S) URL with `/pair/XXXXXXXX` path.
            content.startsWith("http://") || content.startsWith("https://") -> {
                val path = content.substringAfter("/pair/").substringBefore("?")
                if (path.length >= CODE_LENGTH) path else null
            }

            // Legacy `PC-XXXXXXXX` prefix — exact, case-sensitive. The pre-fix
            // `content.contains("-")` + `substringAfterLast("-")` accepted
            // any hyphen payload (e.g. "invoice-ABCDEFGH", "XX-ABCDEFGH",
            // "pc-ABCDEFGH"), re-opening the junk-prefix hole.
            content.startsWith("PC-") -> content.substringAfter("PC-")

            // Bare code: only accept when the entire payload is exactly
            // [CODE_LENGTH] chars. Pre-fix `length >= CODE_LENGTH` +
            // `takeLast(CODE_LENGTH)` silently swallowed arbitrary
            // leading text (e.g. "ZZABCDEFGH"), which could let a
            // junk-prefixed QR reach `pairWithCode` and the wire. Any
            // longer/arbitrary text now falls through to `else -> null`
            // and is short-circuited to INVALID_QR by [pairWithQr]
            // before any network call.
            content.length == CODE_LENGTH -> content

            else -> null
        }
    }

    /**
     * Manual `name=value` query-parameter extractor for
     * `parentalcontrol://pair?code=...` URIs. Kept private so
     * callers always go through [extractCodeFromQr] +
     * [isValidManualCode].
     *
     * Returns `null` when the URL has no query string or when the
     * named parameter is absent; returns the empty string when the
     * parameter is present but empty (`?code=` or `?code`). The
     * production wire format only emits non-empty `code` values so
     * empty/null are short-circuited to INVALID_QR by
     * [pairWithQr].
     *
     * Avoids `android.net.Uri.parse` so this runs on a plain JVM
     * unit test (the QR extraction path is otherwise
     * framework-free).
     */
    private fun extractQueryParameter(url: String, name: String): String? {
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return null
        val query = url.substring(queryStart + 1)
        if (query.isEmpty()) return null
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            val key = if (eq >= 0) pair.substring(0, eq) else pair
            if (key == name) {
                return if (eq >= 0) pair.substring(eq + 1) else ""
            }
        }
        return null
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
     *
     * Mirrors the field set validated by
     * `supabase/functions/pairing/index.ts`:
     *   - `code`, `device_name`, `device_model`, `os_version`, `app_version`
     *     are required by the edge function (omission → HTTP 400).
     *   - `age_band` and `child_first_name` are optional in the schema,
     *     but the edge function now rejects `child_first_name` whose
     *     trimmed length is `0` or `> 32` (also HTTP 400). We send the
     *     field unconditionally and let [childFirstNameProvider] decide
     *     the value; a `null` round-trips through the server's
     *     `(child_first_name ?? "").trim()` handling, which matches the
     *     current pairing screen that has no input for the child's name.
     */
    private fun buildPairingRequest(code: String, deviceInfo: DeviceInfo): String {
        return requestJson.encodeToString(
            PairingRequestBody(
                code = code,
                device_name = deviceInfo.deviceName,
                device_model = deviceInfo.deviceModel,
                os_version = deviceInfo.osVersion,
                app_version = deviceInfo.appVersion,
                age_band = deviceInfo.ageBand,
                child_first_name = childFirstNameProvider(),
            )
        )
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

            400 -> {
                // Supabase edge function returns HTTP 400 for several distinct
                // validation failures (`supabase/functions/pairing/index.ts`):
                //   - `INVALID_CODE_FORMAT`: the code itself is malformed
                //     (malformed manual entry, wrong alphabet). The code is
                //     bad → route to INVALID_CODE.
                //   - `child_first_name` length / missing-fields: the code
                //     is fine but the request body is invalid. The pairing
                //     code is NOT the cause → must NOT route to INVALID_CODE.
                //
                // We inspect the parsed `code` machine token to route
                // accurately. Only the explicit `INVALID_CODE_FORMAT` token
                // may map to INVALID_CODE; any other 400 (including
                // malformed bodies, missing `code` field, or unrecognized
                // tokens) is treated as a generic server-side validation
                // failure. The raw server `error` string is discarded so it
                // cannot leak into the UI in either branch.
                val machineToken = PairingResponseParser.parseErrorCode(responseBody)
                if (machineToken == INVALID_CODE_FORMAT_TOKEN) {
                    Log.w(TAG, "Código con formato no válido")
                    PairingResult.Error(
                        PairingErrorType.INVALID_CODE,
                        INVALID_CODE_FORMAT_MESSAGE
                    )
                } else {
                    Log.w(TAG, "Emparejamiento rechazado por validación del servidor (400)")
                    PairingResult.Error(
                        PairingErrorType.SERVER_ERROR,
                        NON_CODE_400_MESSAGE
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

@Serializable
private data class PairingRequestBody(
    val code: String,
    val device_name: String,
    val device_model: String,
    val os_version: String,
    val app_version: String,
    val age_band: String? = null,
    /**
     * Child's first name (1..32 chars, non-blank). Mirrors
     * `supabase/functions/pairing/index.ts` validation. Nullable so the
     * device side can serialize `null` while the device-side capture UX
     * is still pending — the server treats `null`/empty as HTTP 400 and
     * the production capture flow is tracked as a follow-up.
     */
    val child_first_name: String? = null,
)

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
 * Wire shape for the machine-token field returned alongside some
 * 4xx responses (e.g. `INVALID_CODE_FORMAT`, `INVALID_CODE`,
 * `EXPIRED_CODE`, `ALREADY_USED`). The Supabase edge function returns
 * `{ "error": "<token-or-message>", "code": "<token>" }` when the
 * failure is classifiable. The `code` field is the canonical machine
 * token used by the 400 routing logic in `parsePairingResponse`; older
 * responses (e.g. `child_first_name` length, missing-fields) omit it.
 */
@Serializable
data class PairingErrorCodeBody(
    val code: String? = null
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

    /**
     * Extracts the machine-token `code` field from a 4xx response body.
     * Returns `null` when the field is absent or the body is malformed.
     * Used by the 400 routing branch in `parsePairingResponse` to
     * distinguish `INVALID_CODE_FORMAT` (the only 400 that maps to
     * `INVALID_CODE`) from generic validation failures such as
     * `child_first_name` length or missing-fields, which must NOT blame
     * the pairing code.
     */
    fun parseErrorCode(body: String): String? = try {
        json.decodeFromString<PairingErrorCodeBody>(body).code
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
