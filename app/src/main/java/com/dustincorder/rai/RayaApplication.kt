package com.dustincorder.rai

import android.app.Application
import com.dustincorder.rai.data.llm.AnthropicCompatibleReplyProvider
import com.dustincorder.rai.data.llm.ConfigurableReplyProvider
import com.dustincorder.rai.data.llm.DefaultLlmModelDiscovery
import com.dustincorder.rai.data.llm.GeminiReplyProvider
import com.dustincorder.rai.data.llm.OpenAiCompatibleReplyProvider
import com.dustincorder.rai.data.llm.rayaSystemPrompt
import com.dustincorder.rai.data.chat.FileChatSessionRepository
import com.dustincorder.rai.data.secrets.AndroidApiKeyStore
import com.dustincorder.rai.data.settings.DataStoreSettingsRepository
import com.dustincorder.rai.data.settings.OnboardingStore
import com.dustincorder.rai.data.stt.GroqWhisperTranscriptionProvider
import com.dustincorder.rai.speech.AndroidAudioCapture
import com.dustincorder.rai.speech.AndroidBargeInMonitor
import com.dustincorder.rai.speech.AndroidSpeechRecognitionProvider
import com.dustincorder.rai.speech.AndroidSpeechSynthesisProvider
import com.dustincorder.rai.speech.AndroidTtsModelPackStore
import com.dustincorder.rai.speech.LocalNeuralSpeechSynthesisProvider
import com.dustincorder.rai.speech.RuntimeSpeechRecognitionProvider
import com.dustincorder.rai.speech.RuntimeSpeechSynthesisProvider
import com.dustincorder.rai.speech.SherpaOnnxLocalNeuralTtsEngine
import com.dustincorder.rai.speech.SherpaSileroSpeechFrameClassifier
import com.dustincorder.rai.speech.WhisperSpeechRecognitionProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class RayaApplication : Application() {
    val chatRepository by lazy { FileChatSessionRepository(java.io.File(filesDir, "chats")) }
    val onboardingStore by lazy { OnboardingStore(this) }
    val bargeInMonitor by lazy { AndroidBargeInMonitor(this, ::debugVoice) }
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
            gemini = GeminiReplyProvider(httpClient, json),
            systemPrompt = ::rayaSystemPrompt,
        )
    }
    val modelDiscovery by lazy {
        DefaultLlmModelDiscovery(
            client = httpClient,
            json = json,
            gemini = GeminiReplyProvider(httpClient, json),
        )
    }
    val toolRegistry: com.dustincorder.rai.domain.tools.ToolRegistry by lazy {
        com.dustincorder.rai.domain.tools.InMemoryToolRegistry()
    }
    val jsonSchemaValidator: com.dustincorder.rai.domain.tools.JsonSchemaValidator by lazy {
        com.dustincorder.rai.data.tools.HarrelJsonSchemaValidator()
    }
    val toolTurnRunner: com.dustincorder.rai.domain.tools.ToolTurnRunner by lazy {
        com.dustincorder.rai.domain.tools.ToolTurnRunner(
            registry = toolRegistry,
            schemaValidator = jsonSchemaValidator,
        )
    }

    private val runtimeScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    fun runtimeSpeechRecognitionProvider() = RuntimeSpeechRecognitionProvider(
        scope = runtimeScope,
        settings = settingsRepository,
        keys = apiKeyStore,
        groq = WhisperSpeechRecognitionProvider(
            scope = runtimeScope,
            audioCapture = AndroidAudioCapture(),
            transcription = GroqWhisperTranscriptionProvider(
                client = httpClient,
                json = json,
                apiKey = { apiKeyStore.read(com.dustincorder.rai.data.settings.LlmProviderPreset.Groq) },
            ),
            model = { runBlocking { settingsRepository.settings.first().sttModelId } },
            languageHint = { null },
            speechClassifier = runCatching { SherpaSileroSpeechFrameClassifier(assets) }.getOrNull(),
            diagnostics = { event -> debugVoice(event) },
        ),
        system = AndroidSpeechRecognitionProvider(this),
        diagnostics = { event -> debugVoice(event) },
    )

    fun runtimeSpeechSynthesisProvider(): RuntimeSpeechSynthesisProvider {
        val system = AndroidSpeechSynthesisProvider(this)
        val local = LocalNeuralSpeechSynthesisProvider(
            packs = AndroidTtsModelPackStore(this),
            engine = SherpaOnnxLocalNeuralTtsEngine(this),
        )
        return RuntimeSpeechSynthesisProvider(
            settings = settingsRepository,
            local = local,
            system = system,
            diagnostics = { event -> debugVoice(event) },
        )
    }

    private fun debugVoice(event: String) {
        if (com.dustincorder.rai.BuildConfig.DEBUG) android.util.Log.d("Raya-Voice", event)
    }
}
