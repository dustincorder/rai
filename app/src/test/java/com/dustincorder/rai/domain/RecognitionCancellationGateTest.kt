package com.dustincorder.rai.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionCancellationGateTest {
    private val staleError = SpeechRecognitionEvent.Error(
        SpeechRecognitionErrorReason.Other,
        "Ошибка распознавания речи (5).",
    )

    @Test
    fun `terminal after plain start is forwarded`() {
        val gate = RecognitionCancellationGate()
        gate.markStarted()
        assertTrue(gate.shouldForward(SpeechRecognitionEvent.Final("повтор", "ru-RU")))
        assertTrue(gate.shouldForward(SpeechRecognitionEvent.Error(SpeechRecognitionErrorReason.NoMatch, "не услышал")))
    }

    @Test
    fun `cancel consequence before a new attempt arrives is harmless at the gate`() {
        val gate = RecognitionCancellationGate()
        gate.markStarted()
        gate.onCancel()
        assertTrue(gate.shouldForward(staleError))
    }

    @Test
    fun `stale cancel error after new attempt started is suppressed`() {
        val gate = RecognitionCancellationGate()
        gate.markStarted()
        gate.onCancel()
        gate.markStarted()
        assertFalse("delayed cancellation error must not leak into the new attempt", gate.shouldForward(staleError))
    }

    @Test
    fun `suppression is one-shot and next terminal belongs to the new attempt`() {
        val gate = RecognitionCancellationGate()
        gate.markStarted()
        gate.onCancel()
        gate.markStarted()
        assertFalse(gate.shouldForward(staleError))
        assertTrue(gate.shouldForward(staleError))
        assertTrue(gate.shouldForward(SpeechRecognitionEvent.Final("новый", null)))
    }

    @Test
    fun `cancel with no attempt started does not arm suppression`() {
        val gate = RecognitionCancellationGate()
        gate.onCancel()
        assertTrue(gate.shouldForward(staleError))
    }

    @Test
    fun `restart over a live attempt arms suppression for a possible stray error`() {
        val gate = RecognitionCancellationGate()
        gate.markStarted()
        gate.markStarted()
        assertFalse(gate.shouldForward(staleError))
    }

    @Test
    fun `recoverable and final events are never suppressed`() {
        val gate = RecognitionCancellationGate()
        gate.markStarted()
        gate.onCancel()
        gate.markStarted()
        assertTrue(
            gate.shouldForward(SpeechRecognitionEvent.Error(SpeechRecognitionErrorReason.NoMatch, "не услышал")),
        )
        assertTrue(gate.shouldForward(SpeechRecognitionEvent.Error(SpeechRecognitionErrorReason.NoSpeech, "нет речи")))
        assertTrue(gate.shouldForward(SpeechRecognitionEvent.Final("рабочий", "ru-RU")))
    }
}