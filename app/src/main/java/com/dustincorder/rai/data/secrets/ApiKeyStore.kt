package com.dustincorder.rai.data.secrets

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.dustincorder.rai.data.settings.LlmProviderPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ApiKeyStorageException(message: String) : Exception(message)

interface ApiKeyStore {
    suspend fun read(provider: LlmProviderPreset): String?
    suspend fun write(provider: LlmProviderPreset, value: String)
    suspend fun delete(provider: LlmProviderPreset)
    suspend fun isConfigured(provider: LlmProviderPreset): Boolean = !read(provider).isNullOrBlank()
}

class AndroidApiKeyStore(context: Context) : ApiKeyStore {
    private val preferences = context.applicationContext.getSharedPreferences("raya_encrypted_keys", Context.MODE_PRIVATE)
    private val keyLock = Any()

    override suspend fun read(provider: LlmProviderPreset): String? = withContext(Dispatchers.IO) {
        val encoded = preferences.getString(provider.name, null) ?: return@withContext null
        try {
            decrypt(encoded)
        } catch (failure: Throwable) {
            throw ApiKeyStorageException("Не удалось прочитать сохранённый API key. Замените или удалите его.")
        }
    }

    override suspend fun write(provider: LlmProviderPreset, value: String) = withContext(Dispatchers.IO) {
        if (value.isBlank()) {
            remove(provider.name)
            return@withContext
        }
        val payload = encrypt(value)
        val committed = preferences.edit()
            .putString(provider.name, Base64.encodeToString(payload, Base64.NO_WRAP))
            .commit()
        if (!committed) {
            throw ApiKeyStorageException("Не удалось сохранить API key.")
        }
        val written = preferences.getString(provider.name, null)
        if (written == null || decryptVerified(written, value).not()) {
            throw ApiKeyStorageException("Не удалось сохранить API key.")
        }
    }

    override suspend fun delete(provider: LlmProviderPreset) = withContext(Dispatchers.IO) {
        remove(provider.name)
    }

    private fun decryptVerified(encoded: String, expected: String): Boolean = try {
        decrypt(encoded) == expected
    } catch (_: Throwable) {
        false
    }

    private fun remove(name: String) {
        val committed = preferences.edit().remove(name).commit()
        if (!committed) {
            throw ApiKeyStorageException("Не удалось удалить API key.")
        }
    }

    private fun encrypt(value: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(value.encodeToByteArray())
    }

    private fun decrypt(encoded: String): String {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = payload.copyOfRange(0, IV_SIZE)
        val ciphertext = payload.copyOfRange(IV_SIZE, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext).decodeToString()
    }

    private fun key(): SecretKey = synchronized(keyLock) {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey) ?: run {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
                generateKey()
            }
        }
    }

    private companion object {
        const val KEY_ALIAS = "raya_api_keys"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}