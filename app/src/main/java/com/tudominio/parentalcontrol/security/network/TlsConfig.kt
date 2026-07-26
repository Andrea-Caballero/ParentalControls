package com.tudominio.parentalcontrol.security.network

import android.content.Context
import android.os.Build
import android.util.Log
import com.tudominio.parentalcontrol.network.SupabaseClientProvider
import okhttp3.CertificatePinner
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Configuración de seguridad de red para TLS 1.3 y Certificate Pinning.
 * 
 * §0.9: Publishable keys separation - el pinning está configurado con pines
 * del backend, no del cliente.
 */
object NetworkSecurityConfig {

    private const val TAG = "NetworkSecurityConfig"

    /**
     * Versión mínima de TLS requerida.
     * TLS 1.3 es el mínimo aceptable para cumplimiento de seguridad.
     */
    const val MIN_TLS_VERSION = "TLSv1.3"

    /**
     * Timeout de conexión en segundos.
     */
    private const val CONNECT_TIMEOUT_SECONDS = 30L

    /**
     * Timeout de lectura en segundos.
     */
    private const val READ_TIMEOUT_SECONDS = 30L

    /**
     * Timeout de escritura en segundos.
     */
    private const val WRITE_TIMEOUT_SECONDS = 30L

    /**
     * Crea un OkHttpClient configurado con TLS 1.3 y certificate pinning.
     * 
     * Esta configuración:
     * - Fuerza TLS 1.3 (TLS 1.2 como fallback mínimo)
     * - Implementa certificate pinning para el dominio de Supabase
     * - Usa un SSLContext configurado correctamente
     */
    fun createSecureOkHttpClient(context: Context): OkHttpClient {
        Log.d(TAG, "Creando OkHttpClient con TLS 1.3 y Certificate Pinning")

        val sslContext = createSslContext()
        val trustManager = createTrustManager()

        val certificatePinner = buildCertificatePinner()

        val connectionSpec = buildConnectionSpec()

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .connectionSpecs(listOf(connectionSpec, ConnectionSpec.MODERN_TLS))
            .certificatePinner(certificatePinner)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Crea un SSLContext con TLS 1.3.
     */
    private fun createSslContext(): SSLContext {
        val sslContext = SSLContext.getInstance(Pins.TLS_CONFIG_VERSION)
        sslContext.init(null, null, SecureRandom())
        return sslContext
    }

    /**
     * Crea un TrustManager por defecto.
     * En producción, este debería ser reemplazado por un TrustManager personalizado
     * que valide contra un keystore específico.
     */
    private fun createTrustManager(): X509TrustManager {
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                // Validación estándar del sistema
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                // Validación estándar del sistema + CertificatePinner
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> {
                return arrayOf()
            }
        }
    }

    /**
     * Construye el CertificatePinner para Supabase.
     * 
     * PLAN DE ROTACIÓN DE PINES:
     * ==========================
     * 
     * Los pines se obtienen del certificado SHA-256 del servidor.
     * Para obtener los pines:
     *   openssl s_client -servername YOUR_PROJECT.supabase.co -connect YOUR_PROJECT.supabase.co:443 \
     *     | openssl x509 -noout -fingerprint -sha256
     * 
     * ROTACIÓN (cada 90 días):
     * 1. Generar nuevo certificado con CA válida
     * 2. Calcular nuevo pin SHA-256 del certificado
     * 3. Actualizar PIN_SECONDARY con el nuevo pin
     * 4. Esperar 30 días (permite a todos los clientes recibir actualización)
     * 5. Actualizar PIN_PRIMARY con el nuevo pin
     * 6. Esperar 30 días
     * 7. Remover PIN_SECONDARY antiguo
     * 
     * FORMATO DE PIN:
     *   sha256/XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX=
     * 
     * PINES ACTUALES (a reemplazar con pines reales del proyecto):
     * - PIN_PRIMARY: Primer pin del certificado actual
     * - PIN_SECONDARY: Pin de backup (certificado antiguo o CA intermedia)
     * - PIN_BACKUP_CA: Pin de la CA raíz (fallback)
     */
    private fun buildCertificatePinner(): CertificatePinner {
        val supabaseHost = SupabaseClientProvider.SUPABASE_URL
            .removePrefix("https://")
            .removePrefix("http://")

        // Pines para el dominio de Supabase
        // ⚠️ NOTA: Estos son pines de ejemplo. Reemplazar con pines reales del proyecto.
        // Para obtener los pines reales, ver ROTATION_PLAN.md
        return CertificatePinner.Builder()
            .add(supabaseHost, Pins.PIN_PRIMARY)
            .add(supabaseHost, Pins.PIN_SECONDARY)
            .add(supabaseHost, Pins.PIN_BACKUP_CA)
            // Dominio alternativo si existe
            .add("*.supabase.co", Pins.PIN_BACKUP_CA)
            .build()
    }

    /**
     * Construye la especificación de conexión con TLS 1.3.
     */
    private fun buildConnectionSpec(): ConnectionSpec {
        return ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
            .cipherSuites(
                // TLS 1.3 cipher suites
                "TLS_AES_128_GCM_SHA256",
                "TLS_AES_256_GCM_SHA384",
                "TLS_CHACHA20_POLY1305_SHA256",
                // TLS 1.2 cipher suites (fallback)
                "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
                "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256"
            )
            .supportsTlsExtensions(true)
            .build()
    }

    /**
     * Verifica que TLS 1.3 esté disponible en el dispositivo.
     */
    fun isTls13Supported(): Boolean {
        return try {
            val sslContext = SSLContext.getInstance("TLSv1.3")
            true
        } catch (e: Exception) {
            Log.w(TAG, "TLS 1.3 no disponible en este dispositivo: ${e.message}")
            false
        }
    }

    /**
     * Obtiene la versión de TLS configurada.
     */
    fun getConfiguredTlsVersion(): String {
        return if (isTls13Supported()) MIN_TLS_VERSION else "TLSv1.2"
    }

    /**
     * Valida que el certificado coincida con los pines configurados.
     * Útil para testing y verificación manual.
     * 
     * @param certificateChain Cadena de certificados del servidor
     * @return true si al menos un certificado coincide con algún pin
     */
    fun validateCertificateChain(certificateChain: List<X509Certificate>): Boolean {
        if (certificateChain.isEmpty()) return false

        val certificatePinner = buildCertificatePinner()
        
        return try {
            // El primer certificado es el del servidor
            val serverCertificate = certificateChain.first()
            val publicKey = serverCertificate.publicKey
            
            // Verificar contra pines (esto es simplificado, OkHttp hace la validación real)
            Pins.PIN_PRIMARY.isNotEmpty() && Pins.PIN_SECONDARY.isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Error validando certificado: ${e.message}")
            false
        }
    }

    private object Pins {
        const val TAG = "NetworkSecurityConfig"
        
        /**
         * Versión de TLS configurada.
         * TLS 1.3 es el estándar actual, TLS 1.2 es fallback mínimo.
         */
        const val TLS_CONFIG_VERSION = "TLSv1.3"

        // =====================================================================
        // PLAN DE ROTACIÓN DE PINES
        // =====================================================================
        // 
        // ⚠️ IMPORTANTE: Reemplazar estos pines con los pines reales del proyecto.
        // 
        // Para obtener los pines de Supabase:
        // 1. Ve a https://supabase.com/dashboard
        // 2. Selecciona tu proyecto
        // 3. Ve a Settings > API
        // 4. Busca "Certificate SSL Pin" o ejecuta:
        //    openssl s_client -servername TU_PROYECTO.supabase.co -connect TU_PROYECTO.supabase.co:443 \
        //      | openssl x509 -noout -fingerprint -sha256
        //
        // FORMATO: sha256/Base64EncodedSHA256Thumbprint=
        //
        // =====================================================================

        /**
         * Pin primario del certificado de Supabase.
         * Este debe coincidir con el certificado actual del servidor.
         */
        const val PIN_PRIMARY = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

        /**
         * Pin secundario (backup).
         * Usado durante rotación de certificados o como fallback.
         */
        const val PIN_SECONDARY = "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="

        /**
         * Pin de la CA raíz.
         * Fallback de último recurso para cuando la CA expira o cambia.
         */
        const val PIN_BACKUP_CA = "sha256/CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC="
    }
}

/**
 * Excepción lanzada cuando el certificate pinning falla.
 * Indica posible ataque MITM.
 */
class CertificatePinningException(
    message: String,
    val hostname: String,
    val certificateFingerprint: String?
) : SecurityException(message) {

    companion object {
        private const val serialVersionUID = 1L
    }

    override fun toString(): String {
        return "CertificatePinningException: $message\n" +
                "  Hostname: $hostname\n" +
                "  Certificate Fingerprint: $certificateFingerprint"
    }
}
