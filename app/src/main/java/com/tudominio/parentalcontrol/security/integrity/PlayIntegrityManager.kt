package com.tudominio.parentalcontrol.security.integrity

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.integrity.IntegrityManager
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.google.android.play.core.integrity.IntegrityTokenResponse
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.network.SupabaseClientProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manager para Play Integrity API.
 * 
 * §0.9: El veredicto se verifica server-side, no solo en cliente.
 * El cliente solo solicita el token; el servidor decide la acción.
 * 
 * @param context Contexto de la aplicación
 */
class PlayIntegrityManager private constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "PlayIntegrityManager"
        
        // Nonce para evitar replay attacks
        private const val NONCE_PREFIX = "parental_control_integrity_v1_"
        
        @Volatile
        private var instance: PlayIntegrityManager? = null

        fun getInstance(context: Context): PlayIntegrityManager {
            return instance ?: synchronized(this) {
                instance ?: PlayIntegrityManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val authManager = DeviceAuthManager.getInstance(context)
    private val clientProvider = SupabaseClientProvider.getInstance(context)
    
    private var integrityManager: IntegrityManager? = null

    init {
        try {
            integrityManager = IntegrityManagerFactory.create(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error inicializando IntegrityManager: ${e.message}")
        }
    }

    /**
     * Solicita un veredicto de integridad.
     * 
     * @param customNonce Nonce adicional para evitar replay attacks
     * @return Token de integridad o null si falla
     */
    suspend fun requestIntegrityToken(customNonce: String? = null): IntegrityResult {
        return withContext(Dispatchers.IO) {
            try {
                val manager = integrityManager
                    ?: return@withContext IntegrityResult.Error("IntegrityManager no disponible")
                
                // Generar nonce único
                val nonce = generateNonce(customNonce)
                
                // Solicitar token
                val tokenResponse = requestToken(manager, nonce)
                
                // Decodificar token
                val token = decodeToken(tokenResponse)
                
                if (token != null) {
                    Log.d(TAG, "Token de integridad obtenido exitosamente")
                    IntegrityResult.Success(token, nonce)
                } else {
                    IntegrityResult.Error("Token vacío")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error solicitando integridad: ${e.message}")
                IntegrityResult.Error(e.message ?: "Error desconocido")
            }
        }
    }

    /**
     * Genera un nonce único para la solicitud.
     * Incluye timestamp para evitar replay attacks.
     */
    private fun generateNonce(customNonce: String?): String {
        val timestamp = System.currentTimeMillis()
        val deviceId = authManager.deviceId.value ?: "unknown"
        
        return buildString {
            append(NONCE_PREFIX)
            append(timestamp)
            append("_")
            append(deviceId)
            customNonce?.let {
                append("_")
                append(it)
            }
        }.take(500) // Límite de nonce en Play Integrity
    }

    /**
     * Solicita el token de integridad de forma asíncrona.
     */
    private suspend fun requestToken(
        manager: IntegrityManager,
        nonce: String
    ): IntegrityTokenResponse {
        return withContext(Dispatchers.IO) {
            val request = IntegrityTokenRequest.builder()
                .setNonce(nonce)
                .build()
            
            val task: Task<IntegrityTokenResponse> = manager.requestIntegrityToken(request)
            Tasks.await(task)
        }
    }

    /**
     * Decodifica el token de integridad.
     */
    private fun decodeToken(response: IntegrityTokenResponse): String? {
        return try {
            response.token()
        } catch (e: Exception) {
            Log.e(TAG, "Error decodificando token: ${e.message}")
            null
        }
    }

    /**
     * Verifica el token en el servidor.
     * 
     * §0.9: La verificación se hace server-side.
     * El cliente solo transmite el token.
     */
    suspend fun verifyOnServer(token: String): IntegrityVerificationResult {
        return withContext(Dispatchers.IO) {
            try {
                val accessToken = authManager.getAccessToken()
                    ?: return@withContext IntegrityVerificationResult.Error("No access token")
                
                val deviceId = authManager.deviceId.value ?: ""
                
                val response = clientProvider.httpClient.post(
                    "${SupabaseClientProvider.SUPABASE_URL}/functions/v1/verify-integrity"
                ) {
                    header("Authorization", "Bearer $accessToken")
                    header("apikey", SupabaseClientProvider.SUPABASE_ANON_KEY)
                    header("Content-Type", "application/json")
                    setBody("""
                        {
                            "integrity_token": "$token",
                            "device_id": "$deviceId"
                        }
                    """.trimIndent())
                }
                
                val responseBody = response.bodyAsText()
                
                // Parsear respuesta del servidor
                parseServerResponse(responseBody)
            } catch (e: Exception) {
                Log.e(TAG, "Error verificando en servidor: ${e.message}")
                IntegrityVerificationResult.Error(e.message ?: "Error desconocido")
            }
        }
    }

    /**
     * Parsea la respuesta del servidor de verificación.
     */
    private fun parseServerResponse(responseBody: String): IntegrityVerificationResult {
        return try {
            // Respuesta simple de ejemplo
            // El servidor real debería retornar JSON estructurado
            when {
                responseBody.contains("\"passed\":true") || 
                responseBody.contains("\"verdict\":\"passed\"") ||
                responseBody.contains("\"device_integrity\":\"passed\"") -> {
                    IntegrityVerificationResult.Passed
                }
                responseBody.contains("\"passed\":false") ||
                responseBody.contains("\"verdict\":\"failed\"") -> {
                    IntegrityVerificationResult.Failed(
                        reason = extractReason(responseBody)
                    )
                }
                else -> {
                    // Respuesta no reconocida, asumir passed para evitar falsos positivos
                    Log.w(TAG, "Respuesta de verificación no reconocida: $responseBody")
                    IntegrityVerificationResult.Uncertain
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parseando respuesta: ${e.message}")
            IntegrityVerificationResult.Uncertain
        }
    }

    /**
     * Extrae la razón del fallo de la respuesta.
     */
    private fun extractReason(responseBody: String): String {
        return try {
            val reasonRegex = "\"reason\":\"([^\"]+)\"".toRegex()
            reasonRegex.find(responseBody)?.groupValues?.get(1) 
                ?: "Razón no especificada"
        } catch (e: Exception) {
            "Error extrayendo razón"
        }
    }

    /**
     * Realiza verificación completa: solicita token y verifica en servidor.
     */
    suspend fun performFullVerification(): IntegrityVerificationResult {
        val tokenResult = requestIntegrityToken()
        
        return when (tokenResult) {
            is IntegrityResult.Success -> {
                Log.d(TAG, "Token obtenido, verificando en servidor...")
                verifyOnServer(tokenResult.token)
            }
            is IntegrityResult.Error -> {
                Log.w(TAG, "Error obteniendo token: ${tokenResult.message}")
                // En caso de error, no bloqueamos pero alertamos
                IntegrityVerificationResult.Uncertain
            }
        }
    }
}

/**
 * Resultado de obtener el token de integridad.
 */
sealed class IntegrityResult {
    data class Success(
        val token: String,
        val nonce: String
    ) : IntegrityResult()
    
    data class Error(
        val message: String
    ) : IntegrityResult()
}

/**
 * Resultado de la verificación server-side.
 */
sealed class IntegrityVerificationResult {
    /** El dispositivo pasó la verificación de integridad */
    data object Passed : IntegrityVerificationResult()
    
    /** El dispositivo falló la verificación */
    data class Failed(
        val reason: String
    ) : IntegrityVerificationResult()
    
    /** No se pudo determinar el resultado (error, etc.) */
    data object Uncertain : IntegrityVerificationResult()
    
    /** Error de comunicación o parsing */
    data class Error(
        val message: String
    ) : IntegrityVerificationResult()
}
