package com.dustincorder.rai.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RayaOrchestratorTest {
    @Test
    fun `voice flow reaches speaking after final recognition and idle after tts`() = runTest {
        val recognition = FakeRecognitionProvider()
        val synthesis = FakeSynthesisProvider()
        val reply = FakeReplyProvider(waitForReply = true)
        val orchestrator = RayaOrchestrator(this, recognition, synthesis, reply)

        orchestrator.startVoiceFlow()
        runCurrent()
        assertEquals(RayaState.Listening, orchestrator.state.value)

        recognition.emit(SpeechRecognitionEvent.Partial("Прив"))
        runCurrent()
        assertEquals("Прив", orchestrator.userText.value)

        recognition.emit(SpeechRecognitionEvent.Final("Привет"))
        runCurrent()
        assertEquals(RayaState.Thinking, orchestrator.state.value)

        reply.complete()
        runCurrent()
        assertEquals(RayaState.Speaking("Я тебя слышу."), orchestrator.state.value)
        assertEquals("Привет", orchestrator.userText.value)

        synthesis.complete()
        runCurrent()
        assertEquals(RayaState.Idle, orchestrator.state.value)
    }

    @Test
    fun `speech recognition error reaches error`() = runTest {
        val recognition = FakeRecognitionProvider()
        val orchestrator = RayaOrchestrator(this, recognition, FakeSynthesisProvider(), FakeReplyProvider())

        orchestrator.startVoiceFlow()
        runCurrent()
        recognition.emit(SpeechRecognitionEvent.Error("Нет речи"))
        runCurrent()

        assertTrue(orchestrator.state.value is RayaState.Error)
    }

    @Test
    fun `tts error reaches error`() = runTest {
        val recognition = FakeRecognitionProvider()
        val synthesis = FakeSynthesisProvider(fail = true)
        val orchestrator = RayaOrchestrator(this, recognition, synthesis, FakeReplyProvider())

        orchestrator.startVoiceFlow()
        runCurrent()
        recognition.emit(SpeechRecognitionEvent.Final("Привет"))
        runCurrent()

        assertTrue(orchestrator.state.value is RayaState.Error)
    }

    @Test
    fun `cancel listening returns idle`() = runTest {
        val recognition = FakeRecognitionProvider()
        val orchestrator = RayaOrchestrator(this, recognition, FakeSynthesisProvider(), FakeReplyProvider())

        orchestrator.startVoiceFlow()
        runCurrent()
        orchestrator.cancelListening()

        assertEquals(RayaState.Idle, orchestrator.state.value)
        assertEquals(1, recognition.cancelCount)
    }

    @Test
    fun `only Raya name uses local response without LLM`() = runTest {
        val recognition = FakeRecognitionProvider()
        val synthesis = FakeSynthesisProvider()
        val reply = FakeReplyProvider()
        val orchestrator = RayaOrchestrator(this, recognition, synthesis, reply)

        orchestrator.startVoiceFlow()
        runCurrent()
        recognition.emit(SpeechRecognitionEvent.Final("Райя", "ru-RU"))
        runCurrent()

        assertEquals(RayaState.Speaking("Я здесь."), orchestrator.state.value)
        assertEquals(0, reply.callCount)
        synthesis.complete()
        runCurrent()
    }

    @Test
    fun `real stt spelling ray a uses local response without LLM`() = runTest {
        val recognition = FakeRecognitionProvider()
        val synthesis = FakeSynthesisProvider()
        val reply = FakeReplyProvider()
        val orchestrator = RayaOrchestrator(this, recognition, synthesis, reply)

        orchestrator.startVoiceFlow()
        runCurrent()
        recognition.emit(SpeechRecognitionEvent.Final("Рая", "ru-RU"))
        runCurrent()

        assertEquals(RayaState.Speaking("Я здесь."), orchestrator.state.value)
        assertEquals(0, reply.callCount)
        synthesis.complete()
        runCurrent()
    }

    @Test
    fun `repeated real stt names before a request still reach the LLM`() = runTest {
        val recognition = FakeRecognitionProvider()
        val synthesis = FakeSynthesisProvider()
        val reply = FakeReplyProvider()
        val orchestrator = RayaOrchestrator(this, recognition, synthesis, reply)

        orchestrator.startVoiceFlow()
        runCurrent()
        recognition.emit(SpeechRecognitionEvent.Final("Рая, Райя, расскажи про Марс", "ru-RU"))
        runCurrent()

        assertEquals("расскажи про Марс", reply.lastInput)
        assertEquals(1, reply.callCount)
        synthesis.complete()
        runCurrent()
    }

    @Test
    fun `local name response uses resolved language`() = runTest {
        val cases = listOf(
            "ru-RU" to "Я здесь.",
            "uk-UA" to "Я тут.",
            "en-US" to "I'm here.",
        )
        cases.forEach { (tag, expected) ->
            val recognition = FakeRecognitionProvider()
            val synthesis = FakeSynthesisProvider()
            val orchestrator = RayaOrchestrator(
                this,
                recognition,
                synthesis,
                FakeReplyProvider(),
                languageProvider = object : ConversationLanguageProvider {
                    override suspend fun currentLanguage(): ConversationLanguage = ConversationLanguage.Auto
                },
                systemLanguageTag = { "ru-RU" },
            )

            orchestrator.startVoiceFlow()
            runCurrent()
            recognition.emit(SpeechRecognitionEvent.Final("Райя", tag))
            runCurrent()

            assertEquals(RayaState.Speaking(expected), orchestrator.state.value)
            synthesis.complete()
            runCurrent()
        }
    }

    @Test
    fun `detected language reaches reply and TTS`() = runTest {
        val recognition = FakeRecognitionProvider()
        val synthesis = FakeSynthesisProvider()
        val reply = FakeReplyProvider()
        val orchestrator = RayaOrchestrator(
            this,
            recognition,
            synthesis,
            reply,
            languageProvider = object : ConversationLanguageProvider {
                override suspend fun currentLanguage(): ConversationLanguage = ConversationLanguage.Auto
            },
            systemLanguageTag = { "ru-RU" },
        )

        orchestrator.startVoiceFlow()
        runCurrent()
        recognition.emit(SpeechRecognitionEvent.Final("Raya, hello", "en-US"))
        runCurrent()

        assertEquals("hello", reply.lastInput)
        assertEquals("en-US", reply.lastLanguageTag)
        assertEquals("en-US", synthesis.lastLocale?.toLanguageTag())
        synthesis.complete()
        runCurrent()
    }
}

private class FakeRecognitionProvider : SpeechRecognitionProvider {
    private val _events = MutableSharedFlow<SpeechRecognitionEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<SpeechRecognitionEvent> = _events
    var cancelCount = 0

    override suspend fun startListening(request: RecognitionRequest) = Unit

    override fun cancel() {
        cancelCount++
    }

    override fun release() = Unit

    suspend fun emit(event: SpeechRecognitionEvent) {
        _events.emit(event)
    }
}

private class FakeSynthesisProvider(
    private val fail: Boolean = false,
) : SpeechSynthesisProvider {
    private var completion = CompletableDeferred<Unit>()
    var lastLocale: Locale? = null

    override suspend fun speak(text: String, locale: Locale) {
        lastLocale = locale
        if (fail) error("TTS failure")
        completion.await()
    }

    override fun stop() {
        completion.cancel()
    }

    override fun shutdown() = Unit

    fun complete() {
        completion.complete(Unit)
    }
}

private class FakeReplyProvider(
    private val waitForReply: Boolean = false,
) : ReplyProvider {
    private val response = CompletableDeferred<String>()
    var callCount = 0
    var lastInput: String? = null
    var lastLanguageTag: String? = null

    override suspend fun reply(input: String, languageTag: String?): String {
        callCount++
        lastInput = input
        lastLanguageTag = languageTag
        return if (waitForReply) response.await() else "Я тебя слышу."
    }

    fun complete() {
        response.complete("Я тебя слышу.")
    }
}
