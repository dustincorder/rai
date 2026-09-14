package com.dustincorder.rai.data.secrets

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dustincorder.rai.data.settings.LlmProviderPreset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidApiKeyStoreTest {

    @Test
    fun newInstanceReadsCiphertextWrittenByPreviousInstance() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = AndroidApiKeyStore(context)
        first.write(LlmProviderPreset.OpenAI, "secret")

        val second = AndroidApiKeyStore(context)
        assertEquals("secret", second.read(LlmProviderPreset.OpenAI))
        assertTrue(second.isConfigured(LlmProviderPreset.OpenAI))

        second.delete(LlmProviderPreset.OpenAI)
        assertNull(second.read(LlmProviderPreset.OpenAI))
        assertFalse(second.isConfigured(LlmProviderPreset.OpenAI))
    }

    @Test
    fun corruptCiphertextThrowsWithoutDeletingCiphertext() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("raya_encrypted_keys", Context.MODE_PRIVATE)
        prefs.edit().putString(LlmProviderPreset.Anthropic.name, "garbage-not-base64").commit()

        val store = AndroidApiKeyStore(context)
        val failure = runCatching { store.read(LlmProviderPreset.Anthropic) }.exceptionOrNull()
        assertTrue(failure is ApiKeyStorageException)
        assertNotNull(prefs.getString(LlmProviderPreset.Anthropic.name, null))

        store.delete(LlmProviderPreset.Anthropic)
    }
}