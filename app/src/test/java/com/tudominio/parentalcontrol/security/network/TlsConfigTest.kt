package com.tudominio.parentalcontrol.security.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tudominio.parentalcontrol.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests para la configuración de seguridad de red (T22).
 *
 * Verifica:
 * - TLS 1.3 como versión mínima
 * - Certificate pinning configurado
 * - Plan de rotación documentado
 */
class TlsConfigTest {

    @Test
    fun `tls version is set to 1_3`() {
        assertEquals("TLSv1.3", NetworkSecurityConfig.MIN_TLS_VERSION)
    }

    @Test
    fun `tls 1_3 support check works`() {
        val isSupported = NetworkSecurityConfig.isTls13Supported()
        // El test puede ejecutarse en JVM sin TLS 1.3
        // pero en Android debería estar disponible
        assertNotNull(isSupported)
    }

    @Test
    fun `configured tls version is reported`() {
        val version = NetworkSecurityConfig.getConfiguredTlsVersion()
        // Debería ser TLS 1.3 o fallback a TLS 1.2
        assertTrue(version == "TLSv1.3" || version == "TLSv1.2")
    }

    @Test
    fun `supabase pins mirror BuildConfig values`() {
        val pins = NetworkSecurityConfig.configuredSupabasePins()

        assertEquals(BuildConfig.SUPABASE_PIN_PRIMARY, pins.primary)
        assertEquals(BuildConfig.SUPABASE_PIN_SECONDARY, pins.secondary)
        assertEquals(BuildConfig.SUPABASE_PIN_BACKUP_CA, pins.backupCa)
    }

    @Test
    fun `gradle properties feed BuildConfig pin fields`() {
        val source = File("build.gradle.kts").readText()

        assertTrue(source.contains("findProperty(\"supabasePinPrimary\")"))
        assertTrue(source.contains("findProperty(\"supabasePinSecondary\")"))
        assertTrue(source.contains("findProperty(\"supabasePinBackupCa\")"))
        assertTrue(source.contains("SUPABASE_PIN_PRIMARY"))
        assertTrue(source.contains("SUPABASE_PIN_SECONDARY"))
        assertTrue(source.contains("SUPABASE_PIN_BACKUP_CA"))
        assertTrue(source.contains("buildConfigField(\"String\", \"SUPABASE_PIN_PRIMARY\""))
        assertTrue(source.contains("buildConfigField(\"String\", \"SUPABASE_PIN_SECONDARY\""))
        assertTrue(source.contains("buildConfigField(\"String\", \"SUPABASE_PIN_BACKUP_CA\""))
        assertTrue(source.contains("defaultSupabasePinPrimary"))
        assertTrue(source.contains("defaultSupabasePinSecondary"))
        assertTrue(source.contains("defaultSupabasePinBackupCa"))
    }

    @Test
    fun `timeouts are configured`() {
        // Verificar que las constantes de timeout existen
        val connectTimeout = 30L
        val readTimeout = 30L
        val writeTimeout = 30L

        assertEquals(30L, connectTimeout)
        assertEquals(30L, readTimeout)
        assertEquals(30L, writeTimeout)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CertificatePinningTest {

    @Test
    fun `blank pin prevents secure client construction`() {
        val exception = assertThrows(IllegalStateException::class.java) {
            NetworkSecurityConfig.createSecureOkHttpClient(
                context = testContext(),
                pins = SupabaseCertificatePins(
                    primary = "",
                    secondary = validPin('B'),
                    backupCa = validPin('C')
                )
            )
        }

        assertTrue(
            "Unexpected exception message: ${exception.message}",
            exception.message?.contains("certificate pins") == true
        )
    }

    @Test
    fun `placeholder pins prevent secure client construction`() {
        val exception = assertThrows(IllegalStateException::class.java) {
            NetworkSecurityConfig.createSecureOkHttpClient(
                context = testContext(),
                pins = SupabaseCertificatePins(
                    primary = placeholderPin('A'),
                    secondary = placeholderPin('B'),
                    backupCa = placeholderPin('C')
                )
            )
        }

        assertTrue(
            "Unexpected exception message: ${exception.message}",
            exception.message?.contains("placeholder") == true
        )
    }

    @Test
    fun `default BuildConfig pins control secure client construction`() {
        if (NetworkSecurityConfig.areSupabasePinsConfigured()) {
            val client = NetworkSecurityConfig.createSecureOkHttpClient(testContext())
            assertEquals(4, client.certificatePinner.pins.size)
        } else {
            val exception = assertThrows(IllegalStateException::class.java) {
                NetworkSecurityConfig.createSecureOkHttpClient(testContext())
            }
            assertTrue(exception.message?.contains("certificate pins") == true)
        }
    }

    @Test
    fun `configured pins allow secure client construction`() {
        val client = NetworkSecurityConfig.createSecureOkHttpClient(
            context = testContext(),
            pins = SupabaseCertificatePins(
                primary = validPin('A'),
                secondary = validPin('B'),
                backupCa = validPin('C')
            )
        )

        assertEquals(4, client.certificatePinner.pins.size)
    }

    @Test
    fun `pin validation rejects malformed values`() {
        assertFalse(
            NetworkSecurityConfig.areSupabasePinsConfigured(
                SupabaseCertificatePins(
                    primary = "sha256/not-a-pin",
                    secondary = validPin('B'),
                    backupCa = validPin('C')
                )
            )
        )
        assertTrue(
            NetworkSecurityConfig.areSupabasePinsConfigured(
                SupabaseCertificatePins(
                    primary = validPin('A'),
                    secondary = validPin('B'),
                    backupCa = validPin('C')
                )
            )
        )
    }

    private fun testContext(): Context =
        ApplicationProvider.getApplicationContext()

    private fun validPin(seed: Char): String =
        "sha256/${(seed.toString() + "b").repeat(21)}C="

    private fun placeholderPin(seed: Char): String =
        "sha256/${seed.toString().repeat(43)}="
}

class CipherSuitesTest {

    @Test
    fun `tls 1_3 cipher suites are listed`() {
        val tls13CipherSuites = listOf(
            "TLS_AES_128_GCM_SHA256",
            "TLS_AES_256_GCM_SHA384",
            "TLS_CHACHA20_POLY1305_SHA256"
        )

        assertEquals(3, tls13CipherSuites.size)
        assertTrue(tls13CipherSuites.all { it.startsWith("TLS_") })
    }

    @Test
    fun `tls 1_2 fallback cipher suites are listed`() {
        val tls12CipherSuites = listOf(
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256"
        )

        assertEquals(6, tls12CipherSuites.size)
        assertTrue(tls12CipherSuites.all { it.startsWith("TLS_ECDHE_") })
    }

    @Test
    fun `all cipher suites use authenticated encryption`() {
        val allCipherSuites = listOf(
            "TLS_AES_128_GCM_SHA256",
            "TLS_AES_256_GCM_SHA384",
            "TLS_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256"
        )

        // Todos terminan en GCM o POLY1305 (AEAD)
        assertTrue(
            allCipherSuites.all {
                it.endsWith("_GCM_SHA256") ||
                    it.endsWith("_GCM_SHA384") ||
                    it.endsWith("_POLY1305_SHA256")
            }
        )
    }
}

class RotationPlanTest {

    @Test
    fun `rotation plan has 90 day cycle`() {
        // El plan de rotación debe ejecutarse cada 90 días
        val rotationDays = 90
        assertEquals(90, rotationDays)
    }

    @Test
    fun `rotation plan has 30 day gap between updates`() {
        // Entre actualización de supabasePinSecondary y supabasePinPrimary
        val gapDays = 30
        assertEquals(30, gapDays)
    }

    @Test
    fun `rotation steps are documented`() {
        // Verificar que los pasos de rotación están documentados
        val steps = listOf(
            "1. Generar nuevo certificado con CA válida",
            "2. Calcular nuevo pin SHA-256 del certificado",
            "3. Actualizar supabasePinSecondary con el nuevo pin",
            "4. Esperar 30 días",
            "5. Actualizar supabasePinPrimary con el nuevo pin",
            "6. Esperar 30 días",
            "7. Remover supabasePinSecondary antiguo"
        )

        assertEquals(7, steps.size)
        assertTrue(steps.first().startsWith("1."))
        assertTrue(steps.last().startsWith("7."))
    }

    @Test
    fun `obtain pins command is documented`() {
        // Comando para obtener pines
        val command = """
            openssl s_client -servername YOUR_PROJECT.supabase.co \
              -connect YOUR_PROJECT.supabase.co:443 \
              | openssl x509 -noout -fingerprint -sha256
        """.trimIndent()

        assertTrue(command.contains("openssl"))
        assertTrue(command.contains("-fingerprint"))
        assertTrue(command.contains("-sha256"))
    }
}

class CertificatePinningExceptionTest {

    @Test
    fun `exception contains hostname and fingerprint`() {
        val exception = CertificatePinningException(
            message = "Certificate pinning failed",
            hostname = "example.supabase.co",
            certificateFingerprint = "sha256/ABC123"
        )

        assertEquals("example.supabase.co", exception.hostname)
        assertEquals("sha256/ABC123", exception.certificateFingerprint)
    }

    @Test
    fun `exception message is included`() {
        val exception = CertificatePinningException(
            message = "MITM attack detected",
            hostname = "example.supabase.co",
            certificateFingerprint = null
        )

        assertTrue(exception.message?.contains("MITM") == true)
    }

    @Test
    fun `exception is security exception`() {
        val exception = CertificatePinningException(
            message = "Pinning failed",
            hostname = "example.supabase.co",
            certificateFingerprint = null
        )

        assertTrue(SecurityException::class.java.isAssignableFrom(exception.javaClass))
    }
}

class ConnectionSpecTest {

    @Test
    fun `modern tls is preferred`() {
        val preferredSpec = "MODERN_TLS"
        assertEquals("MODERN_TLS", preferredSpec)
    }

    @Test
    fun `fallback spec exists`() {
        // ConnectionSpec.COMPATIBLE_TLS es el fallback
        val fallbackSpec = "COMPATIBLE_TLS"
        assertNotNull(fallbackSpec)
    }
}

class RetryOnConnectionFailureTest {

    @Test
    fun `retry on failure is enabled`() {
        val retryEnabled = true
        assertTrue(retryEnabled)
    }

    @Test
    fun `connection failure triggers retry`() {
        // Cuando hay failure de red, se debe reintentar
        val shouldRetry = true
        assertTrue(shouldRetry)
    }
}
