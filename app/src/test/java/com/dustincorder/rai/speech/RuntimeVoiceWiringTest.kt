package com.dustincorder.rai.speech

import com.dustincorder.rai.data.secrets.ApiKeyStore
import com.dustincorder.rai.data.settings.AppSettings
import com.dustincorder.rai.data.settings.LlmProviderPreset
import com.dustincorder.rai.data.settings.SettingsRepository
import com.dustincorder.rai.data.settings.SttEngine
import com.dustincorder.rai.domain.AudioCapture
import com.dustincorder.rai.domain.AudioUtterance
import com.dustincorder.rai.domain.ConversationLanguage
import com.dustincorder.rai.domain.LocalNeuralTtsEngine
import com.dustincorder.rai.domain.SpeechRecognitionEvent
import com.dustincorder.rai.domain.SpeechRecognitionProvider
import com.dustincorder.rai.domain.SpeechSynthesisProvider
import com.dustincorder.rai.domain.SpeechTranscriptionProvider
import com.dustincorder.rai.domain.TtsEngine
import com.dustincorder.rai.domain.TtsModelPack
import com.dustincorder.rai.domain.TtsModelPackStore
import com.dustincorder.rai.domain.TranscriptionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeVoiceWiringTest {
    @Test
    fun `groq adapter captures endpoint transcribes and emits final`() = runBlocking {
        val capture = FakeAudioCapture()
        val transcription = FakeTranscriptionProvider()
        val providerScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        val provider = WhisperSpeechRecognitionProvider(
            scope = providerScope,
            audioCapture = capture,
            transcription = transcription,
            model = { "whisper-large-v3-turbo" },
            languageHint = { null },
        )

        val event = async(Dispatchers.Default) { provider.events.first() }
        provider.startListening(com.dustincorder.rai.domain.RecognitionRequest(
            com.dustincorder.rai.domain.ConversationLanguage.Auto,
            "ru-RU",
        ))

        assertEquals(
            SpeechRecognitionEvent.Final("Привет, Райя?", "ru-RU"),
            withTimeout(5_000) { event.await() },
        )
        assertEquals("whisper-large-v3-turbo", transcription.lastModel)
        assertTrue(capture.called)
        provider.release()
        providerScope.cancel()
    }

    @Test
    fun `runtime selector uses system provider when settings select system`() = runTest {
        val settings = FakeSettingsRepository(AppSettings(sttEngine = SttEngine.System))
        val system = FakeRecognitionProvider(SpeechRecognitionEvent.Final("system", "en-US"))
        val groq = FakeRecognitionProvider(SpeechRecognitionEvent.Final("groq", "en-US"))
        val providerScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        val runtime = RuntimeSpeechRecognitionProvider(
            scope = providerScope,
            settings = settings,
            keys = FakeApiKeyStore("key"),
            groq = groq,
            system = system,
        )
        val event = async { runtime.events.first() }
        runCurrent()

        runtime.startListening(com.dustincorder.rai.domain.RecognitionRequest(
            com.dustincorder.rai.domain.ConversationLanguage.Auto,
            "en-US",
        ))

        assertEquals("system", (event.await() as SpeechRecognitionEvent.Final).text)
        assertEquals(1, system.startCount)
        assertEquals(0, groq.startCount)
        runtime.release()
        providerScope.cancel()
    }

    @Test
    fun `runtime selector uses groq when key exists`() = runTest {
        val settings = FakeSettingsRepository(AppSettings(sttEngine = SttEngine.GroqWhisper))
        val groq = FakeRecognitionProvider(SpeechRecognitionEvent.Final("groq", "ru-RU"))
        val system = FakeRecognitionProvider(SpeechRecognitionEvent.Final("system", "ru-RU"))
        val providerScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        val runtime = RuntimeSpeechRecognitionProvider(
            scope = providerScope,
            settings = settings,
            keys = FakeApiKeyStore("key"),
            groq = groq,
            system = system,
        )
        val event = async { runtime.events.first() }
        runCurrent()

        runtime.startListening(com.dustincorder.rai.domain.RecognitionRequest(
            com.dustincorder.rai.domain.ConversationLanguage.Auto,
            "ru-RU",
        ))

        assertEquals("groq", (event.await() as SpeechRecognitionEvent.Final).text)
        assertEquals(1, groq.startCount)
        assertEquals(0, system.startCount)
        runtime.cancel()
        providerScope.cancel()
    }

    @Test
    fun `cancel stops whisper capture before transcription`() = runTest {
        val capture = BlockingAudioCapture()
        val transcription = FakeTranscriptionProvider()
        val providerScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        val provider = WhisperSpeechRecognitionProvider(
            providerScope,
            capture,
            transcription,
            { "whisper-large-v3" },
            { null },
        )
        provider.startListening(com.dustincorder.rai.domain.RecognitionRequest(
            com.dustincorder.rai.domain.ConversationLanguage.Auto,
            "ru-RU",
        ))
        withTimeout(2_000) { while (!capture.started) kotlinx.coroutines.delay(10) }
        provider.cancel()
        withTimeout(2_000) { while (!capture.cancelled) kotlinx.coroutines.delay(10) }

        assertTrue(capture.cancelled)
        assertFalse(transcription.called)
        providerScope.cancel()
    }

    @Test
    fun `installed local neural engine is invoked`() = runTest {
        val engine = FakeLocalEngine()
        val local = LocalNeuralSpeechSynthesisProvider(FakePackStore(), engine)
        local.speak("Встреча в 12:30", Locale("ru", "RU"))

        assertEquals("Встреча в 12 часов 30 минут", engine.lastText)
        assertEquals("ru-RU", engine.lastLocale?.toLanguageTag())
    }

    @Test
    fun `local unavailable falls back to system and exposes fallback`() = runTest {
        val system = FakeSynthesisProvider()
        val local = LocalNeuralSpeechSynthesisProvider(FakePackStore(), null)
        val fallback = RuntimeSpeechSynthesisProvider(
            FakeSettingsRepository(AppSettings(ttsEngine = TtsEngine.LocalNeural)),
            local,
            system,
        )
        fallback.speak("Hello", Locale.US)

        assertEquals(1, system.speakCount)
    }
}

private class FakeAudioCapture : AudioCapture {
    var called = false
    override suspend fun recordUtterance(
        endpointDetector: com.dustincorder.rai.domain.VoiceActivityDetector,
        sampleRateHz: Int,
        channels: Int,
    ): AudioUtterance {
        called = true
        return AudioUtterance(ByteArray(32), sampleRateHz, channels, confirmedSpeechMs = 250, voicedRatio = 0.5)
    }
}

private class BlockingAudioCapture : AudioCapture {
    @Volatile var started = false
    var cancelled = false
    override suspend fun recordUtterance(
        endpointDetector: com.dustincorder.rai.domain.VoiceActivityDetector,
        sampleRateHz: Int,
        channels: Int,
    ): AudioUtterance {
        started = true
        try {
            kotlinx.coroutines.awaitCancellation()
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            cancelled = true
            throw cancel
        }
    }
}

private class FakeTranscriptionProvider : SpeechTranscriptionProvider {
    var called = false
    var lastModel: String? = null
    override suspend fun transcribe(audio: AudioUtterance, model: String, languageHint: String?): TranscriptionResult {
        called = true
        lastModel = model
        return TranscriptionResult("Привет, Райя?", "ru-RU")
    }
}

private class FakeRecognitionProvider(
    private val event: SpeechRecognitionEvent,
) : SpeechRecognitionProvider {
    private val source = MutableSharedFlow<SpeechRecognitionEvent>(replay = 1, extraBufferCapacity = 1)
    override val events: SharedFlow<SpeechRecognitionEvent> = source.asSharedFlow()
    var startCount = 0
    override suspend fun startListening(request: com.dustincorder.rai.domain.RecognitionRequest) {
        startCount++
        source.emit(event)
    }
    override fun cancel() = Unit
    override fun release() = Unit
}

private class FakeApiKeyStore(private val value: String?) : ApiKeyStore {
    override suspend fun read(provider: com.dustincorder.rai.data.settings.LlmProviderPreset): String? = value
    override suspend fun write(provider: com.dustincorder.rai.data.settings.LlmProviderPreset, value: String) = Unit
    override suspend fun delete(provider: com.dustincorder.rai.data.settings.LlmProviderPreset) = Unit
}

private class FakeSettingsRepository(initial: AppSettings) : SettingsRepository {
    private val source = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = source
    override val modelCache: Flow<Map<String, List<String>>> = MutableStateFlow(emptyMap())
    override suspend fun save(settings: AppSettings) { source.value = settings }
    override suspend fun saveModelCache(providerName: String, modelIds: List<String>) = Unit
    override suspend fun currentLanguage(): ConversationLanguage = source.value.conversationLanguage
}

private class FakePackStore : TtsModelPackStore {
    private val pack = TtsModelPack("ru-test", "1", setOf("ru-RU"), "", 0)
    override suspend fun installed(languageTag: String): TtsModelPack? = pack
    override suspend fun install(pack: TtsModelPack, source: java.io.InputStream) = Unit
    override suspend fun delete(packId: String) = Unit
}

private class FakeLocalEngine : LocalNeuralTtsEngine {
    var lastText: String? = null
    var lastLocale: Locale? = null
    override suspend fun speak(text: String, locale: Locale, pack: TtsModelPack) {
        lastText = text
        lastLocale = locale
    }
    override fun stop() = Unit
    override fun shutdown() = Unit
}

private class FakeSynthesisProvider : SpeechSynthesisProvider {
    var speakCount = 0
    override suspend fun speak(text: String, locale: Locale) { speakCount++ }
    override fun stop() = Unit
    override fun shutdown() = Unit
}
