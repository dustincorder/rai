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
        val orchestrator = RayaOrchestrator(this, recognition, synthesis, FakeReplyProvider())

        orchestrator.startVoiceFlow()
        runCurrent()
        assertEquals(RayaState.Listening, orchestrator.state.value)

        recognition.emit(SpeechRecognitionEvent.Partial("Прив"))
        runCurrent()
        assertEquals("Прив", orchestrator.userText.value)

        recognition.emit(SpeechRecognitionEvent.Final("Привет"))
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
}

private class FakeRecognitionProvider : SpeechRecognitionProvider {
    private val _events = MutableSharedFlow<SpeechRecognitionEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<SpeechRecognitionEvent> = _events
    var cancelCount = 0

    override fun startListening(locale: Locale) = Unit

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

    override suspend fun speak(text: String, locale: Locale) {
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

private class FakeReplyProvider : ReplyProvider {
    override suspend fun reply(input: String): String = "Я тебя слышу."
}
