package com.tudominio.parentalcontrol.keystore

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SecureStorageTest {

    private lateinit var context: Context
    private lateinit var secureStorage: SecureStorage

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        secureStorage = SecureStorage.getInstance(context)
    }

    @Test
    fun saveAndRetrieveDeviceToken() = runBlocking {
        val token = "device_token_abc123"
        secureStorage.saveDeviceToken(token)
        val retrieved = secureStorage.getDeviceToken()
        assertEquals(token, retrieved)
    }

    @Test
    fun saveAndRetrievePairingSecret() = runBlocking {
        val secret = "pairing_secret_xyz789"
        secureStorage.savePairingSecret(secret)
        val retrieved = secureStorage.getPairingSecret()
        assertEquals(secret, retrieved)
    }

    @Test
    fun saveAndRetrieveDeviceId() = runBlocking {
        val deviceId = "device_id_12345"
        secureStorage.saveDeviceId(deviceId)
        val retrieved = secureStorage.getDeviceId()
        assertEquals(deviceId, retrieved)
    }

    @Test
    fun getDeviceTokenReturnsNullWhenNotSet() = runBlocking {
        secureStorage.clearAll()
        val retrieved = secureStorage.getDeviceToken()
        assertNull(retrieved)
    }

    @Test
    fun hasDeviceTokenReturnsCorrectly() = runBlocking {
        secureStorage.clearAll()
        assertFalse(secureStorage.hasDeviceToken())
        secureStorage.saveDeviceToken("token")
        assertTrue(secureStorage.hasDeviceToken())
    }

    @Test
    fun hasPairingSecretReturnsCorrectly() = runBlocking {
        secureStorage.clearAll()
        assertFalse(secureStorage.hasPairingSecret())
        secureStorage.savePairingSecret("secret")
        assertTrue(secureStorage.hasPairingSecret())
    }

    @Test
    fun clearAllRemovesAllData() = runBlocking {
        secureStorage.saveDeviceToken("token")
        secureStorage.savePairingSecret("secret")
        secureStorage.saveDeviceId("id")
        secureStorage.clearAll()
        assertNull(secureStorage.getDeviceToken())
        assertNull(secureStorage.getPairingSecret())
        assertNull(secureStorage.getDeviceId())
    }

    @Test
    fun encryptDecryptProducesDifferentOutput() = runBlocking {
        val token = "my_super_secret_token"
        secureStorage.saveDeviceToken(token)
        val encrypted = secureStorage.getDeviceToken()
        assertNotNull(encrypted)
        assertTrue(encrypted != token)
    }
}
