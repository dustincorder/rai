package com.dustincorder.rai.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RayaOrchestratorTest {
    @Test
    fun `A submitText appends user and assistant without TTS`() = runTest {
        val recognition = FakeRecognitionProvider()
        val synthesis = FakeSynthesisProvider()
        val reply = FakeReplyProvider(waitForReply = true, responses = mutableListOf("Привет, Алекс!"))
        val orchestrator = RayaOrchestrator(this, recognition, synthesis, reply)

        orchestrator.submitText("Меня зовут Алекс")
        runCurrent()

        assertEquals(RayaState.Thinking, orchestrator.state.value)
        assertEquals(listOf(ConversationMessage(ConversationRole.User, "Меня зовут Алекс")), reply.lastMessages)
        assertEquals(1, reply.callCount)

        reply.complete()
        runCurrent()

        assertEquals(RayaState.Idle, orchestrator.state.value)
        assertEquals(0, synthesis.speakCount)
        assertEquals(
            listOf(
                ConversationMessage(ConversationRole.User, "Меня зовут Алекс"),
                ConversationMessage(ConversationRole.Assistant, "Привет, Алекс!"),
            ),
            orchestrator.conversation.value,
        )
    }

    @Test
    fun `A2 blank text is not submitted`() = runTest {
        val recognition = FakeRecognitionProvider()
        val reply = FakeReplyProvider()
        val orchestrator = RayaOrchestrator(this, recognition, FakeSynthesisProvider(), reply)

        orchestrator.submitText("   ")
        runCurrent()

        assertEquals(RayaState.Idle, orchestrator.state.value)
        assertEquals(0, reply.callCount)
        assertEquals(emptyList<ConversationMessage>(), orchestrator.conversation.value)
    }

    @Test
    fun `B startVoiceSession activates session mic and listening`() = runTest {
        val recognition = FakeRecognitionProvider()
        val orchestrator = RayaOrchestrator(this, recognition, FakeSynthesisProvider(), FakeReplyProvider())

        orchestrator.startVoiceSession()
        runCurrent()

        assertTrue(orchestrator.voiceSessionActive.value)
        assertTrue(orchestrator.microphoneEnabled.value)
        assertEquals(InteractionMode.Voice, orchestrator.interactionMode.value)
        assertEquals(RayaState.Listening, orchestrator.state.value)
        orchestrator.endVoiceSession()
        runCurrent()
    }

    @Test
    fun `B2 session parent stays active across scheduler advancement and mic cycle restarts listening`() = runTest {
        val recognition = FakeRecognitionProvider()
        val orchestrator = RayaOrchestrator(this, recognition, FakeSynthesisProvider(), FakeReplyProvider())

        orchestrator.startVoiceSession()
        runCurrent()

        assertTrue(orchestrator.voiceSessionActive.value)
        assertEquals(1, recognition.startCount)
        assertEquals(RayaState.Listening, orchestrator.state.value)

        orchestrator.toggleMicrophone()
        runCurrent()
        assertFalse(orchestrator.microphoneEnabled.value)
        assertEquals(1, recognition.startCount)

        orchestrator.toggleMicrophone()
        runCurrent()

        assertTrue("mic on must restart listening even after scheduler advancement", orchestrator.microphoneEnabled.value)
        assertEquals(2, recognition.startCount)
        assertEquals(RayaState.Listening, orchestrator.state.value)

        orchestrator.endVoiceSession()
        runCurrent()
    }

    @Test
    fun `B3 session parent survives full turn loop until explicit end`() = runTest {
        val recognition = FakeRecognitionProvider()
        val synthesis = FakeSynthesisProvider()
        val reply = FakeReplyProvider(waitForReply = true, responses = mutableListOf("B", "C"))
        val orchestrator = RayaOrchestrator(this, recognition, synthesis, reply)

        orchestrator.startVoiceSession()
        runCurrent()
        recognition.emit(SpeechRecognitionEvent.Final("Рая, вопрос один", "ru-RU"))
        runCurrent()
        reply.complete()
        runCurrent()
        synthesis.complete()
        runCurrent()

        assertTrue(orchestrator.voiceSessionActive.value)
        assertEquals("auto-loop must open a second turn", 2, recognition.startCount)
        assertEquals(RayaState.Listening, orchestrator.state.value)

        advanceTimeBy(10_000)
        runCurrent()
        assertTrue("session must not end on its own", orchestrator.voiceSessionActive.value)
        assertEquals(RayaState.Listening, orchestrator.state.value)

        orchestrator.endVoiceSession()
        runCurrent()
        assertFalse(orchestrator.voiceSessionActive.value)
        assertEquals(InteractionMode.Text, orchestrator.interactionMode.value)
    }

    @Test
    fun `C voice response automatically returns to listening`() = runTest {
        val recognition = FakeRecognitionProvider()
        val synthesis = FakeSynthesisProvider()
        val reply = FakeReplyProvider(waitForReply = true, responses = mutableListOf("B"))
        val orchestrator = RayaOrchestrator(this, recognition, synthesis, reply)

        orchestrator.startVoiceSession()
        runCurrent()
        recognition.emit(SpeechRecognitionEvent.Final("Рая, A", "ru-RU"))
        runCurrent()
        assertEquals(RayaState.Thinking, orchestrator.state.value)
        reply.complete()
        runCurrent()
        assertEquals(RayaState.Speaking("B"), orchestrator.state.value)
        synthesis.complete()
        runCurrent()

        assertEquals(RayaState.Listening, orchestrator.state.value)
        assertEquals(2, recognition.startCount)
        orchestrator.endVoiceSession()
        runCurrent()
    }

    @Test
    fun `D interrupt starts listening immediately and stops tts after delay`() = runTest {
        val recognition = FakeRecognitionProvider()
        val synthesis = FakeSynthesisProvider()
        val reply = FakeReplyProvider(waitForReply = true, responses = mutableListOf("B"))
        val orchestrator = RayaOrchestrator(this, recognition, synthesis, reply)

        orchestrator.startVoiceSession()
        runCurrent()
        recognition.emit(SpeechRecognitionEvent.Final("Рая, A", "ru-RU"))
        runCurrent()
        reply.complete()
        runCurrent()
        assertEquals(RayaState.Speaking("B"), orchestrator.state.value)
        val startsBefore = recognition.startCount

        orchestrator.interruptSpeech()
        runCurrent()

        assertEquals(RayaState.Listening, orchestrator.state.value)
        assertEquals(startsBefore + 1, recognition.startCount)
        assertEquals(0, synthesis.stopCount)

        advanceTimeBy(RayaOrchestrator.INTERRUPT_TTS_STOP_DELAY_MS)
        runCurrent()
        assertTrue("TTS stop must be scheduled ~300ms after interrupt", synthesis.stopCount >= 1)

        orchestrator.endVoiceSession()
        runCurrent()
    }

    @Test
    fun `D2 stale tts completion cannot corrupt the new listening state`() = runTest {
        val recognition = FakeRecognitionProvider()
        val synthesis = FakeSynthesisProvider()
        val reply = FakeReplyProvider(waitForReply = true, responses = mutableListOf("B", "C"))
        val orchestrator = RayaOrchestrator(this, recognition, synthesis, reply)

        orchestrator.startVoiceSession()
        runCurrent()
        recognition.emit(SpeechRecognitionEvent.Final("Рая, A", "ru-RU"))
        runCurrent()
        reply.complete()
        runCurrent()
        assertEquals(RayaState.Speaking("B"), orchestrator.state.value)

        orchestrator.interruptSpeech()
        runCurrent()
        assertEquals(RayaState.Listening, orchestrator.state.value)

        synthesis.complete()
        runCurrent()
        assertEquals(RayaState.Listening, orchestrator.state.value)
        assertTrue(orchestrator.voiceSessionActive.value)

        recognition.emit(SpeechRecognitionEvent.Final("Рая, новая реплика", "ru-RU"))
        runCurrent()
        assertEquals(RayaState.Thinking, orchestrator.state.value)
        reply.complete()
        runCurrent()
        assertEquals(RayaState.Speaking("C"), orchestrator.state.value)
        assertEquals(2, reply.callCount)

        orchestrator.endVoiceSession()
        runCurrent()
    }

    @Test
    fun `D3 interrupt is idempotent`() = runTest {
        val recognition = FakeRecognitionProvider()
        val synthesis = FakeSynthesisProvider()
        val reply = FakeReplyProvider(waitForReply = true, responses = mutableListOf("B"))
        val orchestrator = RayaOrchestrator(this, recognition, synthesis, reply)

        orchestrator.startVoiceSession()
        runCurrent()
        recognition.emit(SpeechRecognitionEvent.Final("Рая, A", "ru-RU"))
        runCurrent()
        reply.complete()
        runCurrent()
        assertEquals(RayaState.Speaking("B"), orchestrator.state.value)

        orchestrator.interruptSpeech()
        runCurrent()
        assertEquals(RayaState.Listening, orchestrator.state.value)
        val startsAfterFirst = recognition.startCount

        orchestrator.interruptSpeech()
        runCurrent()
        assertEquals(startsAfterFirst, recognition.startCount)

        orchestrator.endVoiceSession()
        runCurrent()
    }

    @Test
    fun `D4 interrupt with mic disabled stops speech without error`() = runTest {
        val recognition = FakeRecognitionProvider()
        val synthesis = FakeSynthesisProvider()
        val reply = FakeReplyProvider(waitForReply = true, responses = mutableListOf("B"))
        val orchestrator = RayaOrchestrator(this, recognition, synthesis, reply)

        orchestrator.startVoiceSession()
        runCurrent()
        recognition.emit(SpeechRecognitionEvent.Final("Рая, A", "ru-RU"))
        runCurrent()
        reply.complete()
        runCurrent()
        assertEquals(RayaState.Speaking("B"), orchestrator.state.value)

        orchestrator.toggleMicrophone()
        runCurrent()
        assertFalse(orchestrator.microphoneEnabled.value)
        orchestrator.interruptSpeech()
        runCurrent()

        advanceTimeBy(RayaOrchestrator.INTERRUPT_TTS_STOP_DELAY_MS)
        runCurrent()

        assertTrue("speech must be stopped", synthesis.stopCount >= 1)
        assertTrue(orchestrator.voiceSessionActive.value)
        assertFalse("mic-off interrupt must not surface an error", orchestrator.state.value is RayaState.Error)
        orchestrator.endVoiceSession()
        runCurrent()
    }

    @Test
    fun `E endVoiceSession cancels recognition and preserves history`() = runTest {
        val recognition = FakeRecognitionProvider()
        val synthesis = FakeSynthesisProvider()
        val reply = FakeReplyProvider(waitForReply = true, responses = mutableListOf("B"))
        val orchestrator = RayaOrchestrator(this, recognition, synthesis, reply)

        orchestrator.startVoiceSession()
        runCurrent()
        recognition.emit(SpeechRecognitionEvent.Final("A", "ru-RU"))
        runCurrent()
        reply.complete()
        runCurrent()
        synthesis.complete()
        runCurrent()
        assertEquals(RayaState.Listening, orchestrator.state.value)
        assertEquals(
            listOf(
                ConversationMessage(ConversationRole.User, "A"),
                ConversationMessage(ConversationRole.Assistant, "B"),
            ),
            orchestrator.conversation.value,
        )
        val cancelBefore = recognition.cancelCount

        orchestrator.endVoiceSession()
        runCurrent()

        assertFalse(orchestrator.voiceSessionActive.value)
        assertEquals(InteractionMode.Text, orchestrator.interactionMode.value)
        assertEquals(RayaState.Idle, orchestrator.state.value)
        assertTrue(recognition.cancelCount > cancelBefore)
        assertTrue(synthesis.stopCount >= 1)
        assertEquals(
            listOf(
                ConversationMessage(ConversationRole.User, "A"),
                ConversationMessage(ConversationRole.Assistant, "B"),
            ),
            orchestrator.conversation.value,
        )
    }

    @Test
    fun `F mic off cancels recognition and does not auto restart`() = runTest {
        val recognition = FakeRecognitionProvider()
        val orchestrator = RayaOrchestrator(this, recognition, FakeSynthesisProvider(), FakeReplyProvider())

        orchestrator.startVoiceSession()
        runCurrent()
        assertEquals(RayaState.Listening, orchestrator.state.value)
        val cancelBefore = recognition.cancelCount
        val startsBefore = recognition.startCount

        orchestrator.toggleMicrophone()
        runCurrent()

        assertFalse(orchestrator.microphoneEnabled.value)
        assertTrue(recognition.cancelCount > cancelBefore)
        assertEquals(RayaState.Idle, orchestrator.state.value)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals("no new listening attempts while mic disabled", startsBefore, recognition.startCount)
        orchestrator.endVoiceSession()
        runCurrent()
    }

    @Test
    fun `M mic off cancel error from old turn is stale and not fatal`() = runTest {
        val recognition = FakeRecognitionProvider()
        val orchestrator = RayaOrchestrator(this, recognition, FakeSynthesisProvider(), FakeReplyProvider())

        orchestrator.startVoiceSession()
        runCurrent()
        assertEquals(RayaState.Listening, orchestrator.state.value)

        orchestrator.toggleMicrophone()
        runCurrent()
        assertFalse(orchestrator.microphoneEnabled.value)
        assertEquals(RayaState.Idle, orchestrator.state.value)

        recognition.emit(SpeechRecognitionEvent.Error(SpeechRecognitionErrorReason.Other, "Ошибка распознавания речи (5)."))
        runCurrent()

        assertTrue("voice chat must survive intentional mic-off cancel error", orchestrator.voiceSessionActive.value)
        assertFalse(orchestrator.microphoneEnabled.value)
        assertEquals(RayaState.Idle, orchestrator.state.value)
        assertFalse("no error banner for intentional cancellation", orchestrator.state.value is RayaState.Error)

        val startsBefore = recognition.startCount
        orchestrator.toggleMicrophone()
        runCurrent()
        assertTrue(orchestrator.microphoneEnabled.value)
        assertEquals(RayaState.Listening, orchestrator.state.value)
        assertEquals("mic on must start a fresh recognition turn", startsBefore + 1, recognition.startCount)

        orchestrator.endVoiceSession()
        runCurrent()
    }

    @Test
    fun `G mic on resumes listening`() = runTest {
        val recognition = FakeRecognitionProvider()
        val orchestrator = RayaOrchestrator(this, recognition, FakeSynthesisProvider(), FakeReplyProvider())

        orchestrator.startVoiceSession()
        runCurrent()
        orchestrator.toggleMicrophone()
        runCurrent()
        assertEquals(RayaState.Idle, orchestrator.state.value)
        val startsBefore = recognition.startCount

        orchestrator.toggleMicrophone()
        runCurrent()

        assertTrue(orchestrator.microphoneEnabled.value)
        assertEquals(RayaState.Listening, orchestrator.state.value)
        assertEquals(startsBefore + 1, recognition.startCount)
        orchestrator.endVoiceSession()
        runCurrent()
    }

    @Test
    fun `H recoverable no speech keeps session active and restarts listening`() = runTest {
        val recognition = FakeRecognitionProvider()
        val orchestrator = RayaOrchestrator(this, recognition, FakeSynthesisProvider(), FakeReplyProvider())

        orchestrator.startVoiceSession()
        runCurrent()
        val startsBefore = recognition.startCount

        recognition.emit(SpeechRecognitionEvent.Error(SpeechRecognitionErrorReason.NoSpeech, "Нет речи"))
        runCurrent()

        assertTrue(orchestrator.voiceSessionActive.value)
        assertEquals(RayaState.Listening, orchestrator.state.value)
        assertEquals(startsBefore + 1, recognition.startCount)
        assertTrue("recoverable error must not produce Error state", orchestrator.state.value !is RayaState.Error)
        orchestrator.endVoiceSession()
        runCurrent()
    }

    @Test
    fun `H2 recoverable no match keeps session active`() = runTest {
        val recognition = FakeRecognitionProvider()
        val orchestrator = RayaOrchestrator(this, recognition, FakeSynthesisProvider(), FakeReplyProvider())

        orchestrator.startVoiceSession()
        runCurrent()
        recognition.emit(
            SpeechRecognitionEvent.Error(SpeechRecognitionErrorReason.NoMatch, "Не удалось распознать речь."),
        )
        runCurrent()

        assertTrue(orchestrator.voiceSessionActive.value)
        assertEquals(RayaState.Listening, orchestrator.state.value)
        orchestrator.endVoiceSession()
        runCurrent()
    }

    @Test
    fun `I inactivity timeout ends session and appends notice`() = runTest {
        val recognition = FakeRecognitionProvider()
        val orchestrator = RayaOrchestrator(
            scope = this,
            speechRecognition = recognition,
            speechSynthesis = FakeSynthesisProvider(),
            replyProvider = FakeReplyProvider(),
            now = { testScheduler.currentTime },
            inactivityTimeoutMs = { 3_000L },
        )

        orchestrator.startVoiceSession()
        runCurrent()
        assertEquals(RayaState.Listening, orchestrator.state.value)

        advanceTimeBy(4_000L)
        runCurrent()

        assertFalse(orchestrator.voiceSessionActive.value)
        assertEquals(InteractionMode.Text, orchestrator.interactionMode.value)
        assertTrue(
            orchestrator.conversation.value.any {
                it.role == ConversationRole.Notice &&
                    it.text == RayaOrchestrator.INACTIVITY_NOTICE_MESSAGE
            },
        )
    }

    @Test
    fun `J notice is visible in timeline but excluded from LLM context`() = runTest {
        val recognition = FakeRecognitionProvider()
        val synthesis = FakeSynthesisProvider()
        val reply = FakeReplyProvider(waitForReply = true, responses = mutableListOf("B", "D", "E"))
        val orchestrator = RayaOrchestrator(
            scope = this,
            speechRecognition = recognition,
            speechSynthesis = synthesis,
            replyProvider = reply,
            now = { testScheduler.currentTime },
            inactivityTimeoutMs = { 3_000L },
        )

        orchestrator.startVoiceSession()
        runCurrent()
        recognition.emit(SpeechRecognitionEvent.Final("A", "ru-RU"))
        runCurrent()
        reply.complete()
        runCurrent()
        assertEquals(RayaState.Speaking("B"), orchestrator.state.value)
        synthesis.complete()
        runCurrent()
        recognition.emit(SpeechRecognitionEvent.Final("C", "ru-RU"))
        runCurrent()
        reply.complete()
        runCurrent()
        assertEquals(RayaState.Speaking("D"), orchestrator.state.value)

        advanceTimeBy(4_000L)
        runCurrent()
        assertFalse(orchestrator.voiceSessionActive.value)

        orchestrator.submitText("проверка истории")
        runCurrent()

        assertTrue(
            orchestrator.conversation.value.any { it.role == ConversationRole.Notice },
        )
        assertTrue(
            "Notice must never reach ReplyProvider",
            reply.lastMessages.orEmpty().none { it.role == ConversationRole.Notice },
        )
        assertEquals(
            listOf(
                ConversationMessage(ConversationRole.User, "A"),
                ConversationMessage(ConversationRole.Assistant, "B"),
                ConversationMessage(ConversationRole.User, "C"),
                ConversationMessage(ConversationRole.Assistant, "D"),
                ConversationMessage(ConversationRole.User, "проверка истории"),
            ),
            reply.lastMessages,
        )
        reply.complete()
        runCurrent()
    }

    @Test
    fun `K text turn after voice turn reuses the same LLM context`() = runTest {
        val recognition = FakeRecognitionProvider()
        val reply = FakeReplyProvider(waitForReply = true, responses = mutableListOf("B", "D"))
        val orchestrator = RayaOrchestrator(this, recognition, FakeSynthesisProvider(), reply)

        orchestrator.startVoiceSession()
        runCurrent()
        recognition.emit(SpeechRecognitionEvent.Final("A", "ru-RU"))
        runCurrent()
        reply.complete()
        runCurrent()

        orchestrator.endVoiceSession()
        runCurrent()
        orchestrator.submitText("Как меня зовут?")
        runCurrent()

        assertEquals(
            listOf(
                ConversationMessage(ConversationRole.User, "A"),
                ConversationMessage(ConversationRole.Assistant, "B"),
                ConversationMessage(ConversationRole.User, "Как меня зовут?"),
            ),
            reply.lastMessages,
        )
        reply.complete()
        runCurrent()
    }

    @Test
    fun `L voice turn after text turn reuses the same LLM context`() = runTest {
        val recognition = FakeRecognitionProvider()
        val reply = FakeReplyProvider(waitForReply = true, responses = mutableListOf("B", "D"))
        val orchestrator = RayaOrchestrator(this, recognition, FakeSynthesisProvider(), reply)

        orchestrator.submitText("текстовый вопрос")
        runCurrent()
        reply.complete()
        runCurrent()

        orchestrator.startVoiceSession()
        runCurrent()
        recognition.emit(SpeechRecognitionEvent.Final("Рая, голосовой вопрос", "ru-RU"))
        runCurrent()

        assertEquals(
            listOf(
                ConversationMessage(ConversationRole.User, "текстовый вопрос"),
                ConversationMessage(ConversationRole.Assistant, "B"),
                ConversationMessage(ConversationRole.User, "голосовой вопрос"),
            ),
            reply.lastMessages,
        )
        reply.complete()
        runCurrent()
        orchestrator.endVoiceSession()
        runCurrent()
    }

    @Test
    fun `N automatic language is always used regardless of legacy preference`() = runTest {
        val recognition = FakeRecognitionProvider()
        val orchestrator = RayaOrchestrator(
            this,
            recognition,
            FakeSynthesisProvider(),
            FakeReplyProvider(),
            systemLanguageTag = { "ru-RU" },
        )

        orchestrator.startVoiceSession()
        runCurrent()

        assertEquals(ConversationLanguage.Auto, recognition.lastRequest?.language)
        assertEquals("ru-RU", recognition.lastRequest?.systemLanguageTag)
        orchestrator.endVoiceSession()
        runCurrent()
    }

    @Test
    fun `O fatal recognition error ends session without restart loop`() = runTest {
        val recognition = FakeRecognitionProvider()
        val orchestrator = RayaOrchestrator(this, recognition, FakeSynthesisProvider(), FakeReplyProvider())

        orchestrator.startVoiceSession()
        runCurrent()
        val startsBefore = recognition.startCount
        recognition.emit(
            SpeechRecognitionEvent.Error(SpeechRecognitionErrorReason.Network, "Сервис распознавания недоступен."),
        )
        runCurrent()

        assertTrue(orchestrator.state.value is RayaState.Error)
        assertFalse(orchestrator.voiceSessionActive.value)
        assertEquals(InteractionMode.Text, orchestrator.interactionMode.value)

        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals("no infinite recognition restart loop", startsBefore, recognition.startCount)
    }

    @Test
    fun `O2 llm failure keeps user message adds no assistant and ends session`() = runTest {
        val recognition = FakeRecognitionProvider()
        val orchestrator = RayaOrchestrator(
            this,
            recognition,
            FakeSynthesisProvider(),
            FakeReplyProvider(fail = true),
        )

        orchestrator.startVoiceSession()
        runCurrent()
        recognition.emit(SpeechRecognitionEvent.Final("Рая, что такое Марс?", "ru-RU"))
        runCurrent()

        assertTrue(orchestrator.state.value is RayaState.Error)
        assertFalse(orchestrator.voiceSessionActive.value)
        assertEquals(
            listOf(ConversationMessage(ConversationRole.User, "что такое Марс?")),
            orchestrator.conversation.value,
        )
        assertTrue(
            orchestrator.conversation.value.none { it.role == ConversationRole.Assistant },
        )
    }

    @Test
    fun `name only uses local response without LLM`() = runTest {
        val recognition = FakeRecognitionProvider()
        val synthesis = FakeSynthesisProvider()
        val reply = FakeReplyProvider()
        val orchestrator = RayaOrchestrator(this, recognition, synthesis, reply)

        orchestrator.startVoiceSession()
        runCurrent()
        recognition.emit(SpeechRecognitionEvent.Final("Райя", "ru-RU"))
        runCurrent()

        assertEquals(RayaState.Speaking("Я здесь."), orchestrator.state.value)
        assertEquals(0, reply.callCount)
        assertEquals(
            listOf(
                ConversationMessage(ConversationRole.User, "Райя"),
                ConversationMessage(ConversationRole.Assistant, "Я здесь."),
            ),
            orchestrator.conversation.value,
        )
        synthesis.complete()
        runCurrent()
        orchestrator.endVoiceSession()
        runCurrent()
    }

    @Test
    fun `repeated real stt names before a request still reach the LLM`() = runTest {
        val recognition = FakeRecognitionProvider()
        val synthesis = FakeSynthesisProvider()
        val reply = FakeReplyProvider()
        val orchestrator = RayaOrchestrator(this, recognition, synthesis, reply)

        orchestrator.startVoiceSession()
        runCurrent()
        recognition.emit(SpeechRecognitionEvent.Final("Рая, Райя, расскажи про Марс", "ru-RU"))
        runCurrent()

        assertEquals(listOf(ConversationMessage(ConversationRole.User, "расскажи про Марс")), reply.lastMessages)
        assertEquals(1, reply.callCount)
        synthesis.complete()
        runCurrent()
        orchestrator.endVoiceSession()
        runCurrent()
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
            systemLanguageTag = { "ru-RU" },
        )

        orchestrator.startVoiceSession()
        runCurrent()
        recognition.emit(SpeechRecognitionEvent.Final("Raya, hello", "en-US"))
        runCurrent()

        assertEquals(listOf(ConversationMessage(ConversationRole.User, "hello")), reply.lastMessages)
        assertEquals("en-US", reply.lastLanguageTag)
        assertEquals("en-US", synthesis.lastLocale?.toLanguageTag())
        synthesis.complete()
        runCurrent()
        orchestrator.endVoiceSession()
        runCurrent()
    }

    @Test
    fun `second voice turn sends full history`() = runTest {
        val recognition = FakeRecognitionProvider()
        val synthesis = FakeSynthesisProvider()
        val reply = FakeReplyProvider(waitForReply = true, responses = mutableListOf("B", "D"))
        val orchestrator = RayaOrchestrator(this, recognition, synthesis, reply)

        orchestrator.startVoiceSession()
        runCurrent()
        recognition.emit(SpeechRecognitionEvent.Final("A", "ru-RU"))
        runCurrent()
        reply.complete()
        runCurrent()
        synthesis.complete()
        runCurrent()
        assertEquals(RayaState.Listening, orchestrator.state.value)
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
        orchestrator.endVoiceSession()
        runCurrent()
    }

    @Test
    fun `clear conversation empties history and next turn sends only current user`() = runTest {
        val recognition = FakeRecognitionProvider()
        val synthesis = FakeSynthesisProvider()
        val reply = FakeReplyProvider(waitForReply = true, responses = mutableListOf("B", "D"))
        val orchestrator = RayaOrchestrator(this, recognition, synthesis, reply)

        orchestrator.startVoiceSession()
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

        recognition.emit(SpeechRecognitionEvent.Final("C", "ru-RU"))
        runCurrent()
        assertEquals(listOf(ConversationMessage(ConversationRole.User, "C")), reply.lastMessages)
        reply.complete()
        runCurrent()
        orchestrator.endVoiceSession()
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

        orchestrator.startVoiceSession()
        runCurrent()
        for (i in 1..13) {
            recognition.emit(SpeechRecognitionEvent.Final("Рая, Q$i", "ru-RU"))
            runCurrent()
            reply.complete()
            runCurrent()
            assertEquals(RayaState.Speaking("R$i"), orchestrator.state.value)
            synthesis.complete()
            runCurrent()
        }

        assertEquals(RayaOrchestrator.MAX_LLM_CONTEXT_MESSAGES, reply.lastMessages?.size)
        assertEquals(ConversationMessage(ConversationRole.Assistant, "R3"), reply.lastMessages?.first())
        assertEquals(ConversationMessage(ConversationRole.User, "Q13"), reply.lastMessages?.last())
        orchestrator.endVoiceSession()
        runCurrent()
    }
}

private class FakeRecognitionProvider : SpeechRecognitionProvider {
    private val _events = MutableSharedFlow<SpeechRecognitionEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<SpeechRecognitionEvent> = _events
    var cancelCount = 0
    var startCount = 0
    var lastRequest: RecognitionRequest? = null

    override suspend fun startListening(request: RecognitionRequest) {
        lastRequest = request
        startCount++
    }

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
    var speakCount = 0
    var stopCount = 0
    var lastLocale: Locale? = null
    private var pendingCompletion: CompletableDeferred<Unit>? = null

    override suspend fun speak(text: String, locale: Locale) {
        speakCount++
        lastLocale = locale
        if (fail) error("TTS failure")
        val deferred = CompletableDeferred<Unit>()
        pendingCompletion = deferred
        try {
            deferred.await()
        } finally {
            if (pendingCompletion === deferred) pendingCompletion = null
        }
    }

    override fun stop() {
        stopCount++
        pendingCompletion?.cancel()
    }

    override fun shutdown() = Unit

    fun complete() {
        pendingCompletion?.complete(Unit)
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