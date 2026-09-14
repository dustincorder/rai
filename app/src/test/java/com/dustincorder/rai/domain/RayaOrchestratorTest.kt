package com.dustincorder.rai.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
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
    fun `ellipsis separated names still bypass the LLM`() = runTest {
        val recognition = FakeRecognitionProvider()
        val synthesis = FakeSynthesisProvider()
        val reply = FakeReplyProvider()
        val orchestrator = RayaOrchestrator(this, recognition, synthesis, reply)

        orchestrator.startVoiceFlow()
        runCurrent()
        recognition.emit(SpeechRecognitionEvent.Final("Рая… Райя", "ru-RU"))
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

        assertEquals(listOf(ConversationMessage(ConversationRole.User, "расскажи про Марс")), reply.lastMessages)
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

        assertEquals(listOf(ConversationMessage(ConversationRole.User, "hello")), reply.lastMessages)
        assertEquals("en-US", reply.lastLanguageTag)
        assertEquals("en-US", synthesis.lastLocale?.toLanguageTag())
        synthesis.complete()
        runCurrent()
    }

    @Test
    fun `first turn sends only current user and appends both messages`() = runTest {
        val recognition = FakeRecognitionProvider()
        val synthesis = FakeSynthesisProvider()
        val reply = FakeReplyProvider(waitForReply = true, responses = mutableListOf("B"))
        val orchestrator = RayaOrchestrator(this, recognition, synthesis, reply)

        orchestrator.startVoiceFlow()
        runCurrent()
        recognition.emit(SpeechRecognitionEvent.Final("Рая, A", "ru-RU"))
        runCurrent()

        assertEquals(listOf(ConversationMessage(ConversationRole.User, "A")), reply.lastMessages)
        reply.complete()
        runCurrent()
        assertEquals(
            listOf(
                ConversationMessage(ConversationRole.User, "A"),
                ConversationMessage(ConversationRole.Assistant, "B"),
            ),
            orchestrator.conversation.value,
        )
        synthesis.complete()
        runCurrent()
    }

    @Test
    fun `second turn sends full history and appends current turn`() = runTest {
        val recognition = FakeRecognitionProvider()
        val synthesis = FakeSynthesisProvider()
        val reply = FakeReplyProvider(waitForReply = true, responses = mutableListOf("B", "D"))
        val orchestrator = RayaOrchestrator(this, recognition, synthesis, reply)

        orchestrator.startVoiceFlow()
        runCurrent()
        recognition.emit(SpeechRecognitionEvent.Final("Рая, A", "ru-RU"))
        runCurrent()
        reply.complete()
        runCurrent()
        synthesis.complete()
        runCurrent()

        orchestrator.startVoiceFlow()
        runCurrent()
        recognition.emit(SpeechRecognitionEvent.Final("Рая, C", "ru-RU"))
        runCurrent()

        assertEquals(
            listOf(
                ConversationMessage(ConversationRole.User, "A"),
                ConversationMessage(ConversationRole.Assistant, "B"),
                ConversationMessage(ConversationRole.User, "C"),
            ),
            reply.lastMessages,
        )
        reply.complete()
        runCurrent()
        assertEquals(
            listOf(
                ConversationMessage(ConversationRole.User, "A"),
                ConversationMessage(ConversationRole.Assistant, "B"),
                ConversationMessage(ConversationRole.User, "C"),
                ConversationMessage(ConversationRole.Assistant, "D"),
            ),
            orchestrator.conversation.value,
        )
        synthesis.complete()
        runCurrent()
    }

    @Test
    fun `name only appends name and local response without LLM`() = runTest {
        val recognition = FakeRecognitionProvider()
        val synthesis = FakeSynthesisProvider()
        val reply = FakeReplyProvider()
        val orchestrator = RayaOrchestrator(this, recognition, synthesis, reply)

        orchestrator.startVoiceFlow()
        runCurrent()
        recognition.emit(SpeechRecognitionEvent.Final("Рая", "ru-RU"))
        runCurrent()

        assertEquals(0, reply.callCount)
        assertEquals(RayaState.Speaking("Я здесь."), orchestrator.state.value)
        assertEquals(
            listOf(
                ConversationMessage(ConversationRole.User, "Рая"),
                ConversationMessage(ConversationRole.Assistant, "Я здесь."),
            ),
            orchestrator.conversation.value,
        )
        synthesis.complete()
        runCurrent()
    }

    @Test
    fun `failed llm keeps user message and adds no assistant message`() = runTest {
        val recognition = FakeRecognitionProvider()
        val orchestrator = RayaOrchestrator(this, recognition, FakeSynthesisProvider(), FakeReplyProvider(fail = true))

        orchestrator.startVoiceFlow()
        runCurrent()
        recognition.emit(SpeechRecognitionEvent.Final("Рая, что такое Марс?", "ru-RU"))
        runCurrent()

        assertTrue(orchestrator.state.value is RayaState.Error)
        assertEquals(
            listOf(ConversationMessage(ConversationRole.User, "что такое Марс?")),
            orchestrator.conversation.value,
        )
    }

    @Test
    fun `clear conversation empties history and next turn sends only current user`() = runTest {
        val recognition = FakeRecognitionProvider()
        val synthesis = FakeSynthesisProvider()
        val reply = FakeReplyProvider(waitForReply = true, responses = mutableListOf("B", "D"))
        val orchestrator = RayaOrchestrator(this, recognition, synthesis, reply)

        orchestrator.startVoiceFlow()
        runCurrent()
        recognition.emit(SpeechRecognitionEvent.Final("Рая, A", "ru-RU"))
        runCurrent()
        reply.complete()
        runCurrent()
        synthesis.complete()
        runCurrent()
        assertEquals(2, orchestrator.conversation.value.size)

        orchestrator.clearConversation()
        assertEquals(emptyList<ConversationMessage>(), orchestrator.conversation.value)

        orchestrator.startVoiceFlow()
        runCurrent()
        recognition.emit(SpeechRecognitionEvent.Final("Рая, C", "ru-RU"))
        runCurrent()
        assertEquals(listOf(ConversationMessage(ConversationRole.User, "C")), reply.lastMessages)
        reply.complete()
        runCurrent()
        synthesis.complete()
        runCurrent()
    }

    @Test
    fun `llm context is truncated to last 20 messages including current user`() = runTest {
        val recognition = FakeRecognitionProvider()
        val synthesis = FakeSynthesisProvider()
        val reply = FakeReplyProvider(
            waitForReply = true,
            responses = (1..13).map { "R$it" }.toMutableList(),
        )
        val orchestrator = RayaOrchestrator(this, recognition, synthesis, reply)

        for (i in 1..13) {
            orchestrator.startVoiceFlow()
            runCurrent()
            recognition.emit(SpeechRecognitionEvent.Final("Рая, Q$i", "ru-RU"))
            runCurrent()
            reply.complete()
            runCurrent()
            synthesis.complete()
            runCurrent()
        }

        assertEquals(RayaOrchestrator.MAX_LLM_CONTEXT_MESSAGES, reply.lastMessages?.size)
        assertEquals(ConversationMessage(ConversationRole.Assistant, "R3"), reply.lastMessages?.first())
        assertEquals(ConversationMessage(ConversationRole.User, "Q13"), reply.lastMessages?.last())
    }

    @Test
    fun `history survives speaking to idle transition`() = runTest {
        val recognition = FakeRecognitionProvider()
        val synthesis = FakeSynthesisProvider()
        val reply = FakeReplyProvider(waitForReply = true, responses = mutableListOf("B"))
        val orchestrator = RayaOrchestrator(this, recognition, synthesis, reply)

        orchestrator.startVoiceFlow()
        runCurrent()
        recognition.emit(SpeechRecognitionEvent.Final("Рая, A", "ru-RU"))
        runCurrent()
        reply.complete()
        runCurrent()
        assertEquals(RayaState.Speaking("B"), orchestrator.state.value)

        synthesis.complete()
        runCurrent()
        assertEquals(RayaState.Idle, orchestrator.state.value)
        assertEquals(
            listOf(
                ConversationMessage(ConversationRole.User, "A"),
                ConversationMessage(ConversationRole.Assistant, "B"),
            ),
            orchestrator.conversation.value,
        )
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
    private val fail: Boolean = false,
    private val responses: MutableList<String> = mutableListOf("Я тебя слышу."),
) : ReplyProvider {
    private val pending = Channel<String>(Channel.UNLIMITED)
    var callCount = 0
    var lastMessages: List<ConversationMessage>? = null
    var lastLanguageTag: String? = null

    override suspend fun reply(messages: List<ConversationMessage>, languageTag: String?): String {
        callCount++
        lastMessages = messages
        lastLanguageTag = languageTag
        if (fail) throw RuntimeException("LLM failure")
        if (waitForReply) return pending.receive()
        return responses.removeFirst()
    }

    fun complete() {
        pending.trySend(responses.removeFirst())
    }
}
