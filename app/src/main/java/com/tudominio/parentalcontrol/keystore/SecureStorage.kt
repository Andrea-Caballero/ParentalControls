package com.tudominio.parentalcontrol.keystore

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureStorage(private val context: Context) {

    companion object {
        private const val KEYSTORE_ALIAS = "parental_control_key"
        private const val PREFS_NAME = "secure_storage"
        private const val KEY_PREFIX = "enc_"

        @Volatile
        private var instance: SecureStorage? = null

        fun getInstance(context: Context): SecureStorage {
            return instance ?: synchronized(this) {
                instance ?: SecureStorage(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val sharedPreferences: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        
        return keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey ?: createSecretKey()
    }

    private fun createSecretKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        
        val keySpec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(false)
            .build()

        keyGenerator.init(keySpec)
        return keyGenerator.generateKey()
    }

    private fun encrypt(plaintext: String): String {
        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val combined = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)

        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encrypted: String): String {
        val combined = Base64.decode(encrypted, Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, 12)
        val encryptedBytes = combined.copyOfRange(12, combined.size)

        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        return String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)
    }

    suspend fun saveString(key: String, value: String): Unit = withContext(Dispatchers.IO) {
        val encrypted = encrypt(value)
        sharedPreferences.edit().putString(KEY_PREFIX + key, encrypted).apply()
    }

    suspend fun getString(key: String): String? = withContext(Dispatchers.IO) {
        val encrypted = sharedPreferences.getString(KEY_PREFIX + key, null) ?: return@withContext null
        return@withContext try {
            decrypt(encrypted)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun saveDeviceToken(token: String): Unit = saveString("device_token", token)

    suspend fun getDeviceToken(): String? = getString("device_token")

    suspend fun savePairingSecret(secret: String): Unit = saveString("pairing_secret", secret)

    suspend fun getPairingSecret(): String? = getString("pairing_secret")

    suspend fun saveDeviceId(deviceId: String): Unit = saveString("device_id", deviceId)

    suspend fun getDeviceId(): String? = getString("device_id")

    suspend fun savePaired(paired: Boolean): Unit = saveString("paired", paired.toString())

    suspend fun isPaired(): Boolean = getString("paired")?.toBoolean() ?: false

    suspend fun saveParentId(parentId: String): Unit = saveString("parent_id", parentId)

    suspend fun getParentId(): String? = getString("parent_id")

    suspend fun clearAll(): Unit = withContext(Dispatchers.IO) {
        sharedPreferences.edit().clear().apply()
    }

    suspend fun hasDeviceToken(): Boolean = getString("device_token") != null

    suspend fun hasPairingSecret(): Boolean = getString("pairing_secret") != null
}
