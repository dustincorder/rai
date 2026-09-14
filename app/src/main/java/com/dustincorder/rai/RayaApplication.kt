package com.dustincorder.rai

import android.app.Application
import com.dustincorder.rai.data.llm.AnthropicCompatibleReplyProvider
import com.dustincorder.rai.data.llm.ConfigurableReplyProvider
import com.dustincorder.rai.data.llm.OpenAiCompatibleReplyProvider
import com.dustincorder.rai.data.llm.rayaSystemPrompt
import com.dustincorder.rai.data.secrets.AndroidApiKeyStore
import com.dustincorder.rai.data.settings.DataStoreSettingsRepository
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class RayaApplication : Application() {
    val settingsRepository by lazy { DataStoreSettingsRepository(this) }
    val apiKeyStore by lazy { AndroidApiKeyStore(this) }
    private val json by lazy { Json { ignoreUnknownKeys = true } }
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }
    val replyProvider by lazy {
        ConfigurableReplyProvider(
            settingsRepository = settingsRepository,
            apiKeyStore = apiKeyStore,
            openAi = OpenAiCompatibleReplyProvider(httpClient, json),
            anthropic = AnthropicCompatibleReplyProvider(httpClient, json),
            systemPrompt = ::rayaSystemPrompt,
        )
    }
}
