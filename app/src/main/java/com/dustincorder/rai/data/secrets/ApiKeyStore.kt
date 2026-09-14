package com.dustincorder.rai.data.secrets

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.dustincorder.rai.data.settings.LlmProviderPreset
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface ApiKeyStore {
    suspend fun read(provider: LlmProviderPreset): String?
    suspend fun write(provider: LlmProviderPreset, value: String)
    suspend fun delete(provider: LlmProviderPreset)
    suspend fun isConfigured(provider: LlmProviderPreset): Boolean = !read(provider).isNullOrBlank()
}

class AndroidApiKeyStore(context: Context) : ApiKeyStore {
    private val preferences = context.applicationContext.getSharedPreferences("raya_encrypted_keys", Context.MODE_PRIVATE)
    private val keyLock = Any()

    override suspend fun read(provider: LlmProviderPreset): String? {
        val encoded = preferences.getString(provider.name, null) ?: return null
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = payload.copyOfRange(0, IV_SIZE)
            val ciphertext = payload.copyOfRange(IV_SIZE, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            cipher.doFinal(ciphertext).decodeToString()
        }.getOrElse {
            preferences.edit().remove(provider.name).apply()
            null
        }
    }

    override suspend fun write(provider: LlmProviderPreset, value: String) {
        if (value.isBlank()) {
            delete(provider)
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val payload = cipher.iv + cipher.doFinal(value.encodeToByteArray())
        preferences.edit().putString(provider.name, Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
    }

    override suspend fun delete(provider: LlmProviderPreset) {
        preferences.edit().remove(provider.name).apply()
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
